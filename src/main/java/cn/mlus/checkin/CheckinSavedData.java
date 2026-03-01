package cn.mlus.checkin;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * 签到数据持久化存储，使用 Minecraft 的 SavedData 机制。
 * 存储所有玩家的积分、签到日期、连续签到天数等信息。
 */
public class CheckinSavedData extends SavedData {

    private static final String DATA_NAME = "checkin_points";

    private final Map<UUID, PlayerCheckinInfo> playerData = new HashMap<>();

    public CheckinSavedData() {
    }

    /**
     * 获取当前服务器的签到数据实例（自动创建或加载）。
     */
    public static CheckinSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(CheckinSavedData::new, CheckinSavedData::load),
                DATA_NAME
        );
    }

    /**
     * 从 NBT 数据加载签到数据。
     */
    public static CheckinSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        CheckinSavedData data = new CheckinSavedData();
        ListTag playerList = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < playerList.size(); i++) {
            CompoundTag playerTag = playerList.getCompound(i);
            UUID uuid = playerTag.getUUID("uuid");
            int points = playerTag.getInt("points");
            String lastCheckin = playerTag.getString("lastCheckin");
            int consecutiveDays = playerTag.getInt("consecutiveDays");
            int totalCheckins = playerTag.getInt("totalCheckins");

            PlayerCheckinInfo info = new PlayerCheckinInfo(points, lastCheckin, consecutiveDays, totalCheckins);
            data.playerData.put(uuid, info);
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider provider) {
        ListTag playerList = new ListTag();
        for (Map.Entry<UUID, PlayerCheckinInfo> entry : playerData.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("uuid", entry.getKey());
            PlayerCheckinInfo info = entry.getValue();
            playerTag.putInt("points", info.getPoints());
            playerTag.putString("lastCheckin", info.getLastCheckinDate());
            playerTag.putInt("consecutiveDays", info.getConsecutiveDays());
            playerTag.putInt("totalCheckins", info.getTotalCheckins());
            playerList.add(playerTag);
        }
        tag.put("players", playerList);
        return tag;
    }

    /**
     * 获取指定玩家的签到信息，若不存在则自动创建。
     */
    public PlayerCheckinInfo getOrCreate(UUID uuid) {
        return playerData.computeIfAbsent(uuid, k -> new PlayerCheckinInfo());
    }

    /**
     * 获取所有玩家数据的不可变视图。
     */
    public Map<UUID, PlayerCheckinInfo> getAllPlayerData() {
        return Collections.unmodifiableMap(playerData);
    }

    /**
     * 玩家签到信息数据类。
     */
    public static class PlayerCheckinInfo {
        private int points;
        private String lastCheckinDate;
        private int consecutiveDays;
        private int totalCheckins;

        public PlayerCheckinInfo() {
            this(0, "", 0, 0);
        }

        public PlayerCheckinInfo(int points, String lastCheckinDate, int consecutiveDays, int totalCheckins) {
            this.points = points;
            this.lastCheckinDate = lastCheckinDate;
            this.consecutiveDays = consecutiveDays;
            this.totalCheckins = totalCheckins;
        }

        public int getPoints() {
            return points;
        }

        public void setPoints(int points) {
            this.points = points;
        }

        public String getLastCheckinDate() {
            return lastCheckinDate;
        }

        public void setLastCheckinDate(String date) {
            this.lastCheckinDate = date;
        }

        public int getConsecutiveDays() {
            return consecutiveDays;
        }

        public void setConsecutiveDays(int days) {
            this.consecutiveDays = days;
        }

        public int getTotalCheckins() {
            return totalCheckins;
        }

        public void setTotalCheckins(int total) {
            this.totalCheckins = total;
        }
    }
}
