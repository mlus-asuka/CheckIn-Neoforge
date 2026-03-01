package cn.mlus.checkin.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 积分商店管理器 —— 管理可兑换的商品列表及兑换逻辑。
 * <p>
 * 支持两种商品类型：
 * <ul>
 *   <li><b>item</b> — 给予玩家实际物品</li>
 *   <li><b>command</b> — 服务器执行预设指令，支持 {player} 占位符</li>
 * </ul>
 * 商品配置从 JSON 文件加载，支持 CRUD 与热重载。
 */
public class ShopManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<ShopItem> shopItems = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Path configPath;
    private MinecraftServer server;
    private RedemptionLog redemptionLog;

    public ShopManager(Path configDir) {
        this.configPath = configDir.resolve("checkin_shop.json");
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public void setRedemptionLog(RedemptionLog redemptionLog) {
        this.redemptionLog = redemptionLog;
    }

    public RedemptionLog getRedemptionLog() {
        return redemptionLog;
    }

    // ==================== 加载 / 保存 ====================

    /**
     * 加载商品配置文件，若不存在则生成默认配置。
     */
    public void load() {
        if (!Files.exists(configPath)) {
            createDefaultConfig();
        }
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            List<ShopItemConfig> configs = GSON.fromJson(reader, new TypeToken<List<ShopItemConfig>>() {}.getType());
            shopItems.clear();
            if (configs != null) {
                int maxId = 0;
                for (ShopItemConfig cfg : configs) {
                    int id = cfg.id > 0 ? cfg.id : nextId.getAndIncrement();
                    if (id > maxId) maxId = id;
                    shopItems.add(new ShopItem(
                            id, cfg.type == null ? "item" : cfg.type,
                            cfg.itemId == null ? "" : cfg.itemId,
                            cfg.command == null ? "" : cfg.command,
                            cfg.displayName, cfg.description,
                            cfg.price, cfg.count,
                            cfg.category, cfg.iconUrl == null ? "" : cfg.iconUrl,
                            cfg.dailyLimit
                    ));
                }
                nextId.set(maxId + 1);
            }
            LOGGER.info("Loaded {} shop items from config", shopItems.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load shop config", e);
        }
    }

    /**
     * 将当前商品列表保存到配置文件。
     */
    public synchronized void save() {
        try {
            Files.createDirectories(configPath.getParent());
            List<ShopItemConfig> configs = new ArrayList<>();
            for (ShopItem item : shopItems) {
                ShopItemConfig cfg = new ShopItemConfig();
                cfg.id = item.id();
                cfg.type = item.type();
                cfg.itemId = item.itemId();
                cfg.command = item.command();
                cfg.displayName = item.displayName();
                cfg.description = item.description();
                cfg.price = item.price();
                cfg.count = item.count();
                cfg.category = item.category();
                cfg.iconUrl = item.iconUrl();
                cfg.dailyLimit = item.dailyLimit();
                configs.add(cfg);
            }
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(configs, writer);
            }
            LOGGER.info("Saved {} shop items to config", configs.size());
        } catch (IOException e) {
            LOGGER.error("Failed to save shop config", e);
        }
    }

    // ==================== CRUD ====================

    public List<ShopItem> getShopItems() {
        return Collections.unmodifiableList(shopItems);
    }

    public ShopItem getItem(int id) {
        return shopItems.stream().filter(i -> i.id() == id).findFirst().orElse(null);
    }

    /**
     * 添加商品，返回新商品（含分配的 ID）。
     */
    public synchronized ShopItem addItem(String type, String itemId, String command,
                                         String displayName, String description,
                                         int price, int count, String category, String iconUrl,
                                         int dailyLimit) {
        ShopItem item = new ShopItem(
                nextId.getAndIncrement(), type, itemId, command,
                displayName, description, price, count, category, iconUrl,
                dailyLimit
        );
        shopItems.add(item);
        save();
        return item;
    }

    /**
     * 更新商品，返回是否成功。
     */
    public synchronized boolean updateItem(int id, String type, String itemId, String command,
                                           String displayName, String description,
                                           int price, int count, String category, String iconUrl,
                                           int dailyLimit) {
        for (int i = 0; i < shopItems.size(); i++) {
            if (shopItems.get(i).id() == id) {
                shopItems.set(i, new ShopItem(id, type, itemId, command,
                        displayName, description, price, count, category, iconUrl,
                        dailyLimit));
                save();
                return true;
            }
        }
        return false;
    }

    /**
     * 删除商品，返回是否成功。
     */
    public synchronized boolean deleteItem(int id) {
        boolean removed = shopItems.removeIf(i -> i.id() == id);
        if (removed) save();
        return removed;
    }

    /**
     * 重新排列商品顺序（传入 ID 列表）。
     */
    public synchronized boolean reorderItems(List<Integer> idOrder) {
        Map<Integer, ShopItem> map = new HashMap<>();
        for (ShopItem item : shopItems) map.put(item.id(), item);
        List<ShopItem> reordered = new ArrayList<>();
        for (int id : idOrder) {
            ShopItem item = map.remove(id);
            if (item != null) reordered.add(item);
        }
        // 追加未包含的
        reordered.addAll(map.values());
        shopItems.clear();
        shopItems.addAll(reordered);
        save();
        return true;
    }

    // ==================== 兑换 ====================

    /**
     * 执行兑换操作，支持物品类型和指令类型。
     */
    public BuyResult buyItem(UUID playerUUID, int itemId) {
        if (server == null) return new BuyResult(false, "Server not available");

        ShopItem shopItem = getItem(itemId);
        if (shopItem == null) return new BuyResult(false, "商品不存在");

        cn.mlus.checkin.PointsManager manager = cn.mlus.checkin.PointsManager.of(server);
        int currentPoints = manager.getPoints(playerUUID);
        if (currentPoints < shopItem.price()) {
            return new BuyResult(false, "积分不足，需要 " + shopItem.price() + " 积分，当前 " + currentPoints + " 积分");
        }

        // 每日限购检查
        if (shopItem.dailyLimit() > 0 && redemptionLog != null) {
            int todayCount = redemptionLog.getPlayerItemCountToday(playerUUID, itemId);
            if (todayCount >= shopItem.dailyLimit()) {
                return new BuyResult(false, "今日该商品已达限购上限（每日限购 " + shopItem.dailyLimit() + " 次）");
            }
        }

        // 查找在线玩家
        ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
        if (player == null) {
            return new BuyResult(false, "你需要在线才能兑换物品");
        }

        String playerName = player.getGameProfile().getName();

        if ("command".equals(shopItem.type())) {
            // ===== 指令类型 =====
            String cmd = shopItem.command();
            if (cmd == null || cmd.isBlank()) {
                return new BuyResult(false, "商品指令配置为空");
            }
            // 替换占位符
            cmd = cmd.replace("{player}", playerName);

            // 扣除积分
            manager.removePoints(playerUUID, shopItem.price());

            // 以服务器身份执行指令
            CommandSourceStack source = server.createCommandSourceStack();
            server.getCommands().performPrefixedCommand(source, cmd);

            int remaining = manager.getPoints(playerUUID);
            LOGGER.info("Player {} redeemed command '{}' for {} points (remaining: {})",
                    playerName, cmd, shopItem.price(), remaining);
            if (redemptionLog != null) {
                redemptionLog.addRecord(playerUUID, playerName, itemId,
                        shopItem.displayName(), shopItem.type(), shopItem.price());
            }
            return new BuyResult(true, "兑换成功！" + shopItem.displayName() + "，剩余积分: " + remaining);
        } else {
            // ===== 物品类型 =====
            ResourceLocation itemRL = ResourceLocation.parse(shopItem.itemId());
            if (!BuiltInRegistries.ITEM.containsKey(itemRL)) {
                return new BuyResult(false, "物品 ID 无效: " + shopItem.itemId());
            }

            // 扣除积分
            manager.removePoints(playerUUID, shopItem.price());

            // 给予物品
            Item item = BuiltInRegistries.ITEM.get(itemRL);
            ItemStack stack = new ItemStack(item, shopItem.count());

            boolean added = player.getInventory().add(stack);
            if (!added) {
                player.drop(stack, false);
            }

            int remaining = manager.getPoints(playerUUID);
            LOGGER.info("Player {} purchased {} x{} for {} points (remaining: {})",
                    playerName, shopItem.itemId(), shopItem.count(), shopItem.price(), remaining);
            if (redemptionLog != null) {
                redemptionLog.addRecord(playerUUID, playerName, itemId,
                        shopItem.displayName(), shopItem.type(), shopItem.price());
            }
            return new BuyResult(true, "兑换成功！获得 " + shopItem.displayName() + " x" + shopItem.count() + "，剩余积分: " + remaining);
        }
    }

    // ==================== 默认配置 ====================

    private void createDefaultConfig() {
        List<ShopItemConfig> defaults = new ArrayList<>();
        int id = 1;
        defaults.add(cfg(id++, "item", "minecraft:diamond", "", "钻石", "闪闪发光的钻石", 100, 1, "矿物"));
        defaults.add(cfg(id++, "item", "minecraft:iron_ingot", "", "铁锭", "实用的铁锭", 20, 16, "矿物"));
        defaults.add(cfg(id++, "item", "minecraft:gold_ingot", "", "金锭", "珍贵的金锭", 40, 8, "矿物"));
        defaults.add(cfg(id++, "item", "minecraft:emerald", "", "绿宝石", "与村民交易的货币", 80, 4, "矿物"));
        defaults.add(cfg(id++, "item", "minecraft:netherite_ingot", "", "下界合金锭", "最顶级的材料", 500, 1, "矿物"));
        defaults.add(cfg(id++, "item", "minecraft:enchanted_golden_apple", "", "附魔金苹果", "传说中的果实", 300, 1, "食物"));
        defaults.add(cfg(id++, "item", "minecraft:golden_apple", "", "金苹果", "恢复效果极佳", 60, 4, "食物"));
        defaults.add(cfg(id++, "item", "minecraft:cooked_beef", "", "牛排", "美味的牛排", 5, 64, "食物"));
        defaults.add(cfg(id++, "item", "minecraft:experience_bottle", "", "附魔之瓶", "满满的经验", 30, 16, "工具"));
        defaults.add(cfg(id++, "item", "minecraft:elytra", "", "鞘翅", "在天空翱翔", 1000, 1, "装备"));
        defaults.add(cfg(id++, "item", "minecraft:totem_of_undying", "", "不死图腾", "免死金牌", 400, 1, "装备"));
        defaults.add(cfg(id++, "item", "minecraft:name_tag", "", "命名牌", "给宠物起名字", 15, 4, "工具"));
        defaults.add(cfg(id++, "command", "", "effect give {player} minecraft:regeneration 60 2", "生命恢复 III", "获得 60 秒的生命恢复 III 效果", 50, 1, "指令"));
        defaults.add(cfg(id++, "command", "", "give {player} minecraft:diamond_sword{Enchantments:[{id:sharpness,lvl:5}]} 1", "锋利 V 钻石剑", "一把强力附魔钻石剑", 800, 1, "指令"));
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(defaults, writer);
            }
            LOGGER.info("Created default shop config at {}", configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to create default shop config", e);
        }
    }

    private static ShopItemConfig cfg(int id, String type, String itemId, String command,
                                       String displayName, String desc, int price, int count, String category) {
        ShopItemConfig c = new ShopItemConfig();
        c.id = id; c.type = type; c.itemId = itemId; c.command = command;
        c.displayName = displayName; c.description = desc;
        c.price = price; c.count = count; c.category = category; c.iconUrl = "";
        c.dailyLimit = 0;
        return c;
    }

    // ===== 内部数据类 =====

    private static class ShopItemConfig {
        int id;
        String type;        // "item" 或 "command"
        String itemId;      // 物品类型使用
        String command;     // 指令类型使用，支持 {player} 占位符
        String displayName;
        String description;
        int price;
        int count;
        String category;
        String iconUrl;
        int dailyLimit;     // 每人每日限购，0 = 不限购
    }

    /**
     * @param type       "item" = 物品类型, "command" = 指令类型
     * @param itemId     物品 ID（仅 type=item 时使用）
     * @param command    指令（仅 type=command 时使用，{player} 会替换为玩家名）
     * @param dailyLimit 每人每日限购数量，0 表示不限购
     */
    public record ShopItem(int id, String type, String itemId, String command,
                           String displayName, String description,
                           int price, int count, String category, String iconUrl,
                           int dailyLimit) {}

    public record BuyResult(boolean success, String message) {}
}
