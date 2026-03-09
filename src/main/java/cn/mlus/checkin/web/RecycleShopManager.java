package cn.mlus.checkin.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 回收商店管理器 —— 管理可回收物品列表及回收逻辑。
 * <p>
 * 玩家可以将背包中的物品出售给系统，获得对应积分。
 * 管理员可以通过配置或命令开关回收商店。
 */
public class RecycleShopManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<RecycleItem> recycleItems = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Path configPath;
    private MinecraftServer server;
    private RedemptionLog redemptionLog;
    private volatile boolean enabled = true;

    public RecycleShopManager(Path configDir) {
        this.configPath = configDir.resolve("checkin_recycle_shop.json");
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public void setRedemptionLog(RedemptionLog redemptionLog) {
        this.redemptionLog = redemptionLog;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ==================== 加载 / 保存 ====================

    public void load() {
        if (!Files.exists(configPath)) {
            createDefaultConfig();
        }
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            List<RecycleItemConfig> configs = GSON.fromJson(reader, new TypeToken<List<RecycleItemConfig>>() {}.getType());
            recycleItems.clear();
            if (configs != null) {
                int maxId = 0;
                for (RecycleItemConfig cfg : configs) {
                    int id = cfg.id > 0 ? cfg.id : nextId.getAndIncrement();
                    if (id > maxId) maxId = id;
                    recycleItems.add(new RecycleItem(
                            id,
                            cfg.itemId == null ? "" : cfg.itemId,
                            cfg.displayName,
                            cfg.description == null ? "" : cfg.description,
                            cfg.price,
                            cfg.category == null ? "" : cfg.category,
                            cfg.dailyLimit
                    ));
                }
                nextId.set(maxId + 1);
            }
            LOGGER.info("Loaded {} recycle shop items from config", recycleItems.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load recycle shop config", e);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(configPath.getParent());
            List<RecycleItemConfig> configs = new ArrayList<>();
            for (RecycleItem item : recycleItems) {
                RecycleItemConfig cfg = new RecycleItemConfig();
                cfg.id = item.id();
                cfg.itemId = item.itemId();
                cfg.displayName = item.displayName();
                cfg.description = item.description();
                cfg.price = item.price();
                cfg.category = item.category();
                cfg.dailyLimit = item.dailyLimit();
                configs.add(cfg);
            }
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(configs, writer);
            }
            LOGGER.info("Saved {} recycle shop items to config", configs.size());
        } catch (IOException e) {
            LOGGER.error("Failed to save recycle shop config", e);
        }
    }

    // ==================== CRUD ====================

    public List<RecycleItem> getRecycleItems() {
        return Collections.unmodifiableList(recycleItems);
    }

    public RecycleItem getItem(int id) {
        return recycleItems.stream().filter(i -> i.id() == id).findFirst().orElse(null);
    }

    /**
     * 通过物品 ID 查找回收商品。
     */
    public RecycleItem getItemByItemId(String itemId) {
        return recycleItems.stream().filter(i -> i.itemId().equals(itemId)).findFirst().orElse(null);
    }

    public synchronized RecycleItem addItem(String itemId, String displayName, String description,
                                            int price, String category, int dailyLimit) {
        RecycleItem item = new RecycleItem(nextId.getAndIncrement(), itemId, displayName, description, price, category, dailyLimit);
        recycleItems.add(item);
        save();
        return item;
    }

    public synchronized boolean updateItem(int id, String itemId, String displayName, String description,
                                           int price, String category, int dailyLimit) {
        for (int i = 0; i < recycleItems.size(); i++) {
            if (recycleItems.get(i).id() == id) {
                recycleItems.set(i, new RecycleItem(id, itemId, displayName, description, price, category, dailyLimit));
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean deleteItem(int id) {
        boolean removed = recycleItems.removeIf(i -> i.id() == id);
        if (removed) save();
        return removed;
    }

    // ==================== 回收逻辑 ====================

    /**
     * 执行回收操作：从玩家背包扣除物品，给予积分。
     *
     * @param playerUUID 玩家 UUID
     * @param recycleItemId 回收商品 ID
     * @param amount 回收数量
     * @return 回收结果
     */
    public RecycleResult sellItem(UUID playerUUID, int recycleItemId, int amount) {
        if (!enabled) return new RecycleResult(false, "回收商店当前未开启");
        if (server == null) return new RecycleResult(false, "服务器不可用");
        if (amount <= 0) return new RecycleResult(false, "数量必须大于 0");

        RecycleItem recycleItem = getItem(recycleItemId);
        if (recycleItem == null) return new RecycleResult(false, "回收商品不存在");

        ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
        if (player == null) return new RecycleResult(false, "你需要在线才能回收物品");

        // 检查每日限量
        if (recycleItem.dailyLimit() > 0 && redemptionLog != null) {
            int todayCount = redemptionLog.getPlayerItemCountToday(playerUUID, recycleItemId);
            if (todayCount + amount > recycleItem.dailyLimit()) {
                int remaining = recycleItem.dailyLimit() - todayCount;
                return new RecycleResult(false, "今日回收已达上限（剩余可回收: " + Math.max(0, remaining) + " 个）");
            }
        }

        ResourceLocation itemRL = ResourceLocation.parse(recycleItem.itemId());
        if (!BuiltInRegistries.ITEM.containsKey(itemRL)) {
            return new RecycleResult(false, "物品 ID 无效: " + recycleItem.itemId());
        }

        // 检查玩家背包中是否有足够的物品
        int playerHas = countItemInInventory(player, itemRL);
        if (playerHas < amount) {
            return new RecycleResult(false, "背包中物品不足，需要 " + amount + " 个，当前拥有 " + playerHas + " 个");
        }

        // 从背包中移除物品
        removeItemFromInventory(player, itemRL, amount);

        // 给予积分
        int totalPoints = recycleItem.price() * amount;
        cn.mlus.checkin.PointsManager manager = cn.mlus.checkin.PointsManager.of(server);
        int newTotal = manager.addPoints(playerUUID, totalPoints);

        String playerName = player.getGameProfile().getName();
        LOGGER.info("Player {} recycled {} x{} for {} points (total: {})",
                playerName, recycleItem.itemId(), amount, totalPoints, newTotal);

        if (redemptionLog != null) {
            redemptionLog.addRecord(playerUUID, playerName, recycleItemId,
                    "[回收] " + recycleItem.displayName(), "recycle", -totalPoints);
        }

        return new RecycleResult(true, "回收成功！出售 " + recycleItem.displayName() + " x" + amount
                + "，获得 " + totalPoints + " 积分，当前积分: " + newTotal);
    }

    private int countItemInInventory(ServerPlayer player, ResourceLocation itemRL) {
        int count = 0;
        var item = BuiltInRegistries.ITEM.get(itemRL);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void removeItemFromInventory(ServerPlayer player, ResourceLocation itemRL, int amount) {
        var item = BuiltInRegistries.ITEM.get(itemRL);
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
                if (stack.isEmpty()) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    // ==================== 默认配置 ====================

    private void createDefaultConfig() {
        List<RecycleItemConfig> defaults = new ArrayList<>();
        int id = 1;
        defaults.add(cfg(id++, "minecraft:cobblestone", "圆石", "最基础的建筑材料", 1, "矿物"));
        defaults.add(cfg(id++, "minecraft:dirt", "泥土", "随处可见的泥土", 1, "矿物"));
        defaults.add(cfg(id++, "minecraft:iron_ingot", "铁锭", "实用的铁锭", 5, "矿物"));
        defaults.add(cfg(id++, "minecraft:gold_ingot", "金锭", "珍贵的金锭", 10, "矿物"));
        defaults.add(cfg(id++, "minecraft:diamond", "钻石", "闪闪发光的钻石", 25, "矿物"));
        defaults.add(cfg(id++, "minecraft:emerald", "绿宝石", "与村民交易的货币", 20, "矿物"));
        defaults.add(cfg(id++, "minecraft:netherite_ingot", "下界合金锭", "最顶级的材料", 100, "矿物"));
        defaults.add(cfg(id++, "minecraft:coal", "煤炭", "常见的燃料", 2, "矿物"));
        defaults.add(cfg(id++, "minecraft:lapis_lazuli", "青金石", "附魔必需品", 3, "矿物"));
        defaults.add(cfg(id++, "minecraft:redstone", "红石", "红石工程的基础", 3, "矿物"));
        defaults.add(cfg(id++, "minecraft:rotten_flesh", "腐肉", "僵尸掉落物", 1, "战利品"));
        defaults.add(cfg(id++, "minecraft:bone", "骨头", "骷髅掉落物", 2, "战利品"));
        defaults.add(cfg(id++, "minecraft:string", "线", "蜘蛛掉落物", 2, "战利品"));
        defaults.add(cfg(id++, "minecraft:ender_pearl", "末影珍珠", "末影人掉落物", 8, "战利品"));
        defaults.add(cfg(id++, "minecraft:blaze_rod", "烈焰棒", "烈焰人掉落物", 10, "战利品"));
        defaults.add(cfg(id++, "minecraft:gunpowder", "火药", "苦力怕掉落物", 3, "战利品"));
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(defaults, writer);
            }
            LOGGER.info("Created default recycle shop config at {}", configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to create default recycle shop config", e);
        }
    }

    private static RecycleItemConfig cfg(int id, String itemId, String displayName,
                                          String desc, int price, String category) {
        RecycleItemConfig c = new RecycleItemConfig();
        c.id = id;
        c.itemId = itemId;
        c.displayName = displayName;
        c.description = desc;
        c.price = price;
        c.category = category;
        c.dailyLimit = 0;
        return c;
    }

    // ===== 内部数据类 =====

    private static class RecycleItemConfig {
        int id;
        String itemId;
        String displayName;
        String description;
        int price;          // 每个物品可获得的积分
        String category;
        int dailyLimit;     // 每日限量回收，0 = 不限
    }

    /**
     * @param price 每个物品可获得的积分
     * @param dailyLimit 每日限量回收次数，0 = 不限
     */
    public record RecycleItem(int id, String itemId, String displayName, String description,
                              int price, String category, int dailyLimit) {}

    public record RecycleResult(boolean success, String message) {}
}
