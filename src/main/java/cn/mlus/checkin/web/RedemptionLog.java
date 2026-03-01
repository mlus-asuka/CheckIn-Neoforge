package cn.mlus.checkin.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 兑换记录管理器 —— 记录所有商品兑换操作并持久化到 JSON 文件。
 */
public class RedemptionLog {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<RedemptionRecord> records = new CopyOnWriteArrayList<>();
    private final Path logPath;

    public RedemptionLog(Path configDir) {
        this.logPath = configDir.resolve("checkin_redemptions.json");
    }

    public void load() {
        if (!Files.exists(logPath)) return;
        try (Reader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
            List<RedemptionRecord> loaded = GSON.fromJson(reader,
                    new TypeToken<List<RedemptionRecord>>() {}.getType());
            records.clear();
            if (loaded != null) records.addAll(loaded);
            LOGGER.info("Loaded {} redemption records", records.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load redemption log", e);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(logPath.getParent());
            try (Writer writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8)) {
                GSON.toJson(records, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save redemption log", e);
        }
    }

    /**
     * 添加一条兑换记录。
     */
    public void addRecord(UUID playerUUID, String playerName, int itemId,
                          String itemDisplayName, String itemType, int price) {
        RedemptionRecord record = new RedemptionRecord(
                playerUUID.toString(), playerName, itemId,
                itemDisplayName, itemType, price, Instant.now().toEpochMilli()
        );
        records.addFirst(record);
        save();
    }

    /**
     * 获取指定玩家的兑换记录（分页）。
     */
    public List<RedemptionRecord> getPlayerRecords(UUID playerUUID, int offset, int limit) {
        String uuid = playerUUID.toString();
        return records.stream()
                .filter(r -> r.playerUUID.equals(uuid))
                .skip(offset).limit(limit)
                .toList();
    }

    /**
     * 获取指定玩家的兑换记录总数。
     */
    public int getPlayerRecordCount(UUID playerUUID) {
        String uuid = playerUUID.toString();
        return (int) records.stream().filter(r -> r.playerUUID.equals(uuid)).count();
    }

    /**
     * 获取所有兑换记录（分页）。
     */
    public List<RedemptionRecord> getAllRecords(int offset, int limit) {
        return records.stream().skip(offset).limit(limit).toList();
    }

    /**
     * 获取所有兑换记录总数。
     */
    public int getTotalRecordCount() {
        return records.size();
    }

    /**
     * 获取指定玩家今日对某商品的购买次数（用于每日限购检查）。
     */
    public int getPlayerItemCountToday(UUID playerUUID, int itemId) {
        String uuid = playerUUID.toString();
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        return (int) records.stream()
                .filter(r -> r.playerUUID.equals(uuid) && r.itemId == itemId)
                .filter(r -> {
                    LocalDate recordDate = Instant.ofEpochMilli(r.timestamp)
                            .atZone(ZoneId.systemDefault()).toLocalDate();
                    return recordDate.equals(today);
                })
                .count();
    }

    public static class RedemptionRecord {
        public String playerUUID;
        public String playerName;
        public int itemId;
        public String itemDisplayName;
        public String itemType;
        public int price;
        public long timestamp;

        public RedemptionRecord() {}

        public RedemptionRecord(String playerUUID, String playerName, int itemId,
                                String itemDisplayName, String itemType, int price, long timestamp) {
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.itemId = itemId;
            this.itemDisplayName = itemDisplayName;
            this.itemType = itemType;
            this.price = price;
            this.timestamp = timestamp;
        }
    }
}
