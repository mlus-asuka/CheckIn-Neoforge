package cn.mlus.checkin.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import cn.mlus.checkin.PointsManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.locating.IModFile;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;

/**
 * 内嵌 HTTP 服务器 —— 随 MC 服务器启动，提供前端页面和 REST API。
 */
public class CheckinWebServer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private HttpServer httpServer;
    private MinecraftServer mcServer;
    private final WebAuthManager authManager;
    private final ShopManager shopManager;
    private final int port;

    public CheckinWebServer(int port, WebAuthManager authManager, ShopManager shopManager) {
        this.port = port;
        this.authManager = authManager;
        this.shopManager = shopManager;
    }

    public void start(MinecraftServer mcServer) {
        this.mcServer = mcServer;
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.setExecutor(Executors.newFixedThreadPool(4));

            // 前端页面
            httpServer.createContext("/", this::handleStaticFiles);

            // REST API
            httpServer.createContext("/api/login", this::handleLogin);
            httpServer.createContext("/api/user", this::handleUser);
            httpServer.createContext("/api/shop", this::handleShop);
            httpServer.createContext("/api/shop/buy", this::handleBuy);
            httpServer.createContext("/api/leaderboard", this::handleLeaderboard);
            httpServer.createContext("/api/logout", this::handleLogout);
            httpServer.createContext("/api/redemptions", this::handleRedemptions);
            httpServer.createContext("/api/item-icon/", this::handleItemIcon);

            // Admin API
            httpServer.createContext("/api/admin/shop/add", this::handleAdminShopAdd);
            httpServer.createContext("/api/admin/shop/update", this::handleAdminShopUpdate);
            httpServer.createContext("/api/admin/shop/delete", this::handleAdminShopDelete);
            httpServer.createContext("/api/admin/shop/reload", this::handleAdminShopReload);

            httpServer.start();
            LOGGER.info("CheckIn web server started on port {}", port);
        } catch (IOException e) {
            LOGGER.error("Failed to start web server on port {}", port, e);
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            LOGGER.info("CheckIn web server stopped");
        }
    }

    // ==================== 静态文件服务 ====================

    private void handleStaticFiles(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        // 从 classpath 加载前端文件
        String resourcePath = "/assets/checkin/web" + path;
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                // 对 SPA 路由，始终返回 index.html
                try (InputStream idx = getClass().getResourceAsStream("/assets/checkin/web/index.html")) {
                    if (idx != null) {
                        byte[] data = idx.readAllBytes();
                        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                        exchange.sendResponseHeaders(200, data.length);
                        exchange.getResponseBody().write(data);
                        exchange.getResponseBody().close();
                        return;
                    }
                }
                sendJson(exchange, 404, Map.of("error", "Not Found"));
                return;
            }
            byte[] data = is.readAllBytes();
            String contentType = getContentType(path);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.getResponseBody().close();
        }
    }

    // ==================== API 处理 ====================

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "POST")) return;
        addCorsHeaders(exchange);
        try {
            JsonObject body = readJsonBody(exchange);
            String code = body.get("code").getAsString();
            WebAuthManager.SessionInfo session = authManager.redeemCode(code);
            if (session == null) {
                sendJson(exchange, 401, Map.of("success", false, "message", "登录码无效或已过期"));
                return;
            }
            sendJson(exchange, 200, Map.of(
                    "success", true,
                    "token", session.token(),
                    "playerName", session.playerName(),
                    "uuid", session.uuid().toString(),
                    "isOp", session.isOp()
            ));
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of("success", false, "message", "请求格式错误"));
        }
    }

    private void handleUser(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        addCorsHeaders(exchange);
        String token = getToken(exchange);
        UUID uuid = authManager.validateToken(token);
        if (uuid == null) {
            sendJson(exchange, 401, Map.of("success", false, "message", "未登录或登录已过期"));
            return;
        }

        PointsManager pm = PointsManager.of(mcServer);
        String playerName = authManager.getPlayerName(uuid);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("playerName", playerName);
        data.put("uuid", uuid.toString());
        data.put("points", pm.getPoints(uuid));
        data.put("consecutiveDays", pm.getConsecutiveDays(uuid));
        data.put("totalCheckins", pm.getTotalCheckins(uuid));
        data.put("lastCheckinDate", pm.getLastCheckinDate(uuid));
        data.put("checkedInToday", pm.hasCheckedInToday(uuid));
        data.put("isAdmin", authManager.isAdmin(token));
        sendJson(exchange, 200, data);
    }

    private void handleShop(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        addCorsHeaders(exchange);

        // 尝试获取当前登录用户（用于计算每日已购买次数）
        String token = getToken(exchange);
        UUID currentUuid = token != null ? authManager.validateToken(token) : null;
        RedemptionLog log = shopManager.getRedemptionLog();

        List<Map<String, Object>> items = shopManager.getShopItems().stream().map(item -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", item.id());
            map.put("type", item.type());
            map.put("itemId", item.itemId());
            map.put("command", item.command());
            map.put("displayName", item.displayName());
            map.put("description", item.description());
            map.put("price", item.price());
            map.put("count", item.count());
            map.put("category", item.category());
            map.put("iconUrl", item.iconUrl());
            map.put("dailyLimit", item.dailyLimit());
            if (item.dailyLimit() > 0 && currentUuid != null && log != null) {
                map.put("todayPurchased", log.getPlayerItemCountToday(currentUuid, item.id()));
            } else {
                map.put("todayPurchased", 0);
            }
            return map;
        }).toList();

        sendJson(exchange, 200, Map.of("success", true, "items", items));
    }

    private void handleBuy(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "POST")) return;
        addCorsHeaders(exchange);
        UUID uuid = authenticate(exchange);
        if (uuid == null) return;

        try {
            JsonObject body = readJsonBody(exchange);
            int itemId = body.get("itemId").getAsInt();

            // 使用 CompletableFuture 在服务器线程上执行兑换
            CompletableFuture<ShopManager.BuyResult> future = new CompletableFuture<>();
            mcServer.execute(() -> {
                ShopManager.BuyResult result = shopManager.buyItem(uuid, itemId);
                future.complete(result);
            });

            // 等待结果（最多 5 秒），不阻塞线程
            ShopManager.BuyResult result = future.get(5, TimeUnit.SECONDS);

            if (result == null) {
                sendJson(exchange, 500, Map.of("success", false, "message", "服务器处理超时"));
                return;
            }

            int statusCode = result.success() ? 200 : 400;
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", result.success());
            response.put("message", result.message());
            if (result.success()) {
                PointsManager pm = PointsManager.of(mcServer);
                response.put("remainingPoints", pm.getPoints(uuid));
            }
            sendJson(exchange, statusCode, response);
        } catch (TimeoutException e) {
            sendJson(exchange, 500, Map.of("success", false, "message", "服务器处理超时"));
        } catch (ExecutionException e) {
            sendJson(exchange, 500, Map.of("success", false, "message", "服务器处理错误"));
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of("success", false, "message", "请求格式错误"));
        }
    }

    private void handleLeaderboard(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        addCorsHeaders(exchange);

        // 解析分页参数
        int page = getQueryInt(exchange, "page", 1);
        int size = Math.min(getQueryInt(exchange, "size", 20), 100);
        if (page < 1) page = 1;
        int offset = (page - 1) * size;

        PointsManager pm = PointsManager.of(mcServer);
        int totalPlayers = pm.getTotalPlayerCount();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalPlayers / size));

        List<Map.Entry<UUID, Integer>> top = pm.getTopPlayers(offset, size);
        List<Map<String, Object>> board = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            Map.Entry<UUID, Integer> entry = top.get(i);
            String name = mcServer.getProfileCache() != null
                    ? mcServer.getProfileCache().get(entry.getKey()).map(GameProfile::getName).orElse("Unknown")
                    : "Unknown";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", offset + i + 1);
            row.put("playerName", name);
            row.put("points", entry.getValue());
            board.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("leaderboard", board);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPlayers", totalPlayers);
        result.put("totalPages", totalPages);
        sendJson(exchange, 200, result);
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "POST")) return;
        addCorsHeaders(exchange);
        String token = getToken(exchange);
        if (token != null) {
            authManager.invalidateSession(token);
        }
        sendJson(exchange, 200, Map.of("success", true, "message", "已退出登录"));
    }

    private void handleRedemptions(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        addCorsHeaders(exchange);
        UUID uuid = authenticate(exchange);
        if (uuid == null) return;

        RedemptionLog log = shopManager.getRedemptionLog();
        if (log == null) {
            sendJson(exchange, 200, Map.of("success", true, "records", List.of(),
                    "page", 1, "totalPages", 1, "totalRecords", 0));
            return;
        }

        String scope = getQueryString(exchange, "scope", "mine");
        int page = getQueryInt(exchange, "page", 1);
        int size = Math.min(getQueryInt(exchange, "size", 20), 100);
        if (page < 1) page = 1;
        int offset = (page - 1) * size;

        List<RedemptionLog.RedemptionRecord> records;
        int totalRecords;

        if ("all".equals(scope) && authManager.isAdmin(getToken(exchange))) {
            records = log.getAllRecords(offset, size);
            totalRecords = log.getTotalRecordCount();
        } else {
            records = log.getPlayerRecords(uuid, offset, size);
            totalRecords = log.getPlayerRecordCount(uuid);
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) totalRecords / size));

        List<Map<String, Object>> list = new ArrayList<>();
        for (RedemptionLog.RedemptionRecord r : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("playerName", r.playerName);
            row.put("itemDisplayName", r.itemDisplayName);
            row.put("itemType", r.itemType);
            row.put("price", r.price);
            row.put("timestamp", r.timestamp);
            list.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("records", list);
        result.put("page", page);
        result.put("size", size);
        result.put("totalRecords", totalRecords);
        result.put("totalPages", totalPages);
        sendJson(exchange, 200, result);
    }

    private void handleItemIcon(HttpExchange exchange) throws IOException {
        if (!checkMethod(exchange, "GET")) return;
        addCorsHeaders(exchange);

        // 路径格式: /api/item-icon/minecraft:diamond
        String path = exchange.getRequestURI().getPath();
        String itemId = path.substring("/api/item-icon/".length());
        if (itemId.isEmpty()) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        // 安全校验：只允许合法的 ResourceLocation 字符
        if (!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        String namespace = "minecraft";
        String name = itemId;
        if (itemId.contains(":")) {
            String[] parts = itemId.split(":", 2);
            namespace = parts[0];
            name = parts[1];
        }

        byte[] data = resolveItemTexture(namespace, name);
        if (data != null) {
            // 动态贴图（animated texture）的 PNG 高度 > 宽度，包含多帧纵向堆叠
            // 只取第一帧返回，避免显示异常
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                if (img != null && img.getHeight() > img.getWidth()) {
                    int frameSize = img.getWidth();
                    BufferedImage firstFrame = img.getSubimage(0, 0, frameSize, frameSize);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(firstFrame, "png", baos);
                    data = baos.toByteArray();
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to crop animated texture for {}: {}", itemId, e.getMessage());
            }

            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.getResponseBody().close();
            return;
        }

        // 未找到贴图
        exchange.sendResponseHeaders(404, -1);
    }

    /**
     * 解析物品贴图：先尝试直接路径，失败则解析物品模型 JSON（含 parent 链）获取实际贴图引用。
     */
    private byte[] resolveItemTexture(String namespace, String name) {
        var modFileInfo = ModList.get().getModFileById(namespace);
        if (modFileInfo == null) return null;
        IModFile modFile = modFileInfo.getFile();

        // 1. 尝试直接路径
        String[][] directPaths = {
                {"assets", namespace, "textures", "item", name + ".png"},
                {"assets", namespace, "textures", "block", name + ".png"}
        };
        for (String[] pathParts : directPaths) {
            try {
                Path filePath = modFile.findResource(pathParts);
                if (Files.exists(filePath)) {
                    return Files.readAllBytes(filePath);
                }
            } catch (Exception ignored) {}
        }

        // 2. 解析模型 JSON 链（跟随 parent 引用），获取实际贴图路径
        String texRef = resolveTextureFromModel(namespace, name, 0);
        if (texRef != null) {
            return loadTextureByRef(texRef);
        }

        return null;
    }

    /**
     * 从模型 JSON 中解析 layer0 贴图引用，支持递归跟随 parent。
     * maxDepth 防止无限循环。
     */
    private String resolveTextureFromModel(String namespace, String name, int depth) {
        if (depth > 5) return null;
        try {
            var modFileInfo = ModList.get().getModFileById(namespace);
            if (modFileInfo == null) return null;

            Path modelPath = modFileInfo.getFile().findResource("assets", namespace, "models", "item", name + ".json");
            if (!Files.exists(modelPath)) return null;

            String json = Files.readString(modelPath, StandardCharsets.UTF_8);
            JsonObject model = JsonParser.parseString(json).getAsJsonObject();

            // 先检查 textures.layer0
            if (model.has("textures")) {
                JsonObject textures = model.getAsJsonObject("textures");
                if (textures.has("layer0")) {
                    return textures.get("layer0").getAsString();
                }
                if (!textures.isEmpty()) {
                    return textures.entrySet().iterator().next().getValue().getAsString();
                }
            }

            // 没有 textures，跟随 parent 链
            if (model.has("parent")) {
                String parent = model.get("parent").getAsString();
                String parentNs = "minecraft";
                String parentPath = parent;
                if (parent.contains(":")) {
                    String[] parts = parent.split(":", 2);
                    parentNs = parts[0];
                    parentPath = parts[1];
                }
                // parent 格式如 "minecraft:item/golden_apple" → name = "golden_apple"
                int lastSlash = parentPath.lastIndexOf('/');
                if (lastSlash >= 0) {
                    String parentName = parentPath.substring(lastSlash + 1);
                    return resolveTextureFromModel(parentNs, parentName, depth + 1);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to parse item model for {}:{} - {}", namespace, name, e.getMessage());
        }
        return null;
    }

    /**
     * 根据贴图引用（如 "minecraft:item/golden_apple"）加载实际 PNG 文件。
     */
    private byte[] loadTextureByRef(String texRef) {
        String texNs = "minecraft";
        String texPath = texRef;
        if (texRef.contains(":")) {
            String[] parts = texRef.split(":", 2);
            texNs = parts[0];
            texPath = parts[1];
        }
        var texModInfo = ModList.get().getModFileById(texNs);
        if (texModInfo == null) return null;

        // texPath 格式如 "item/golden_apple"，对应 assets/<ns>/textures/<texPath>.png
        String[] segments = texPath.split("/");
        String[] fullPath = new String[segments.length + 3];
        fullPath[0] = "assets";
        fullPath[1] = texNs;
        fullPath[2] = "textures";
        System.arraycopy(segments, 0, fullPath, 3, segments.length);
        fullPath[fullPath.length - 1] = fullPath[fullPath.length - 1] + ".png";

        try {
            Path filePath = texModInfo.getFile().findResource(fullPath);
            if (Files.exists(filePath)) {
                return Files.readAllBytes(filePath);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================== Admin API ====================

    private boolean authenticateAdmin(HttpExchange exchange) throws IOException {
        String token = getToken(exchange);
        UUID uuid = authManager.validateToken(token);
        if (uuid == null) {
            sendJson(exchange, 401, Map.of("success", false, "message", "未登录或登录已过期"));
            return false;
        }
        if (!authManager.isAdmin(token)) {
            sendJson(exchange, 403, Map.of("success", false, "message", "权限不足，需要 OP 权限"));
            return false;
        }
        return true;
    }

    private void handleAdminShopAdd(HttpExchange exchange) throws IOException {
        if (!checkMethodAny(exchange, "POST")) return;
        addCorsHeaders(exchange);
        if (!authenticateAdmin(exchange)) return;

        try {
            JsonObject body = readJsonBody(exchange);
            String type = getStr(body, "type", "item");
            String itemId = getStr(body, "itemId", "");
            String command = getStr(body, "command", "");
            String displayName = body.get("displayName").getAsString();
            String description = getStr(body, "description", "");
            int price = body.get("price").getAsInt();
            int count = getInt(body, "count", 1);
            String category = getStr(body, "category", "");
            String iconUrl = getStr(body, "iconUrl", "");
            int dailyLimit = getInt(body, "dailyLimit", 0);

            ShopManager.ShopItem item = shopManager.addItem(type, itemId, command,
                    displayName, description, price, count, category, iconUrl, dailyLimit);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "商品已添加");
            resp.put("item", itemToMap(item));
            sendJson(exchange, 200, resp);
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of("success", false, "message", "请求格式错误: " + e.getMessage()));
        }
    }

    private void handleAdminShopUpdate(HttpExchange exchange) throws IOException {
        if (!checkMethodAny(exchange, "POST")) return;
        addCorsHeaders(exchange);
        if (!authenticateAdmin(exchange)) return;

        try {
            JsonObject body = readJsonBody(exchange);
            int id = body.get("id").getAsInt();
            String type = getStr(body, "type", "item");
            String itemId = getStr(body, "itemId", "");
            String command = getStr(body, "command", "");
            String displayName = body.get("displayName").getAsString();
            String description = getStr(body, "description", "");
            int price = body.get("price").getAsInt();
            int count = getInt(body, "count", 1);
            String category = getStr(body, "category", "");
            String iconUrl = getStr(body, "iconUrl", "");
            int dailyLimit = getInt(body, "dailyLimit", 0);

            boolean ok = shopManager.updateItem(id, type, itemId, command,
                    displayName, description, price, count, category, iconUrl, dailyLimit);

            if (ok) {
                sendJson(exchange, 200, Map.of("success", true, "message", "商品已更新"));
            } else {
                sendJson(exchange, 404, Map.of("success", false, "message", "商品不存在"));
            }
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of("success", false, "message", "请求格式错误: " + e.getMessage()));
        }
    }

    private void handleAdminShopDelete(HttpExchange exchange) throws IOException {
        if (!checkMethodAny(exchange, "POST")) return;
        addCorsHeaders(exchange);
        if (!authenticateAdmin(exchange)) return;

        try {
            JsonObject body = readJsonBody(exchange);
            int id = body.get("id").getAsInt();
            boolean ok = shopManager.deleteItem(id);
            if (ok) {
                sendJson(exchange, 200, Map.of("success", true, "message", "商品已删除"));
            } else {
                sendJson(exchange, 404, Map.of("success", false, "message", "商品不存在"));
            }
        } catch (Exception e) {
            sendJson(exchange, 400, Map.of("success", false, "message", "请求格式错误"));
        }
    }

    private void handleAdminShopReload(HttpExchange exchange) throws IOException {
        if (!checkMethodAny(exchange, "POST")) return;
        addCorsHeaders(exchange);
        if (!authenticateAdmin(exchange)) return;

        shopManager.load();
        sendJson(exchange, 200, Map.of("success", true, "message", "商品配置已重新加载",
                "count", shopManager.getShopItems().size()));
    }

    private Map<String, Object> itemToMap(ShopManager.ShopItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.id());
        map.put("type", item.type());
        map.put("itemId", item.itemId());
        map.put("command", item.command());
        map.put("displayName", item.displayName());
        map.put("description", item.description());
        map.put("price", item.price());
        map.put("count", item.count());
        map.put("category", item.category());
        map.put("iconUrl", item.iconUrl());
        map.put("dailyLimit", item.dailyLimit());
        return map;
    }

    private boolean checkMethodAny(HttpExchange exchange, String expected) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            addCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return false;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase(expected)) {
            sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
            return false;
        }
        return true;
    }

    private String getStr(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : def;
    }

    private int getInt(JsonObject obj, String key, int def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : def;
    }

    // ==================== 工具方法 ====================

    private UUID authenticate(HttpExchange exchange) throws IOException {
        String token = getToken(exchange);
        UUID uuid = authManager.validateToken(token);
        if (uuid == null) {
            sendJson(exchange, 401, Map.of("success", false, "message", "未登录或登录已过期"));
        }
        return uuid;
    }

    private String getToken(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        // 也支持查询参数
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && kv[0].equals("token")) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private boolean checkMethod(HttpExchange exchange, String expected) throws IOException {
        // 处理 CORS 预检请求
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            addCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return false;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase(expected)) {
            sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
            return false;
        }
        return true;
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private JsonObject readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = GSON.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        addCorsHeaders(exchange);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private int getQueryInt(HttpExchange exchange, String key, int defaultValue) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && kv[0].equals(key)) {
                    try { return Integer.parseInt(kv[1]); } catch (NumberFormatException e) { break; }
                }
            }
        }
        return defaultValue;
    }

    private String getQueryString(HttpExchange exchange, String key, String defaultValue) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && kv[0].equals(key)) {
                    return kv[1];
                }
            }
        }
        return defaultValue;
    }

    private String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }
}
