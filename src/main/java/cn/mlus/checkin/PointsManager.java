package cn.mlus.checkin;

import net.minecraft.server.MinecraftServer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 积分管理 API —— 提供完整的签到积分管理功能。
 * <p>
 * 功能包括：
 * <ul>
 *   <li>积分 CRUD：获取、设置、增加、扣除</li>
 *   <li>积分转账：玩家间转移积分</li>
 *   <li>每日签到：自动判断连续签到并计算奖励</li>
 *   <li>数据查询：排行榜、签到信息、批量查询</li>
 *   <li>数据管理：重置玩家数据</li>
 * </ul>
 * <p>
 * 使用方式：{@code PointsManager manager = PointsManager.of(server);}
 */
public class PointsManager {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CheckinSavedData data;
    private final Random random = new Random();

    private PointsManager(CheckinSavedData data) {
        this.data = data;
    }

    /**
     * 获取与指定服务器关联的 PointsManager 实例。
     *
     * @param server Minecraft 服务器实例
     * @return PointsManager 实例
     */
    public static PointsManager of(MinecraftServer server) {
        return new PointsManager(CheckinSavedData.get(server));
    }

    // ==================== 积分 CRUD ====================

    /**
     * 获取玩家当前积分。
     *
     * @param playerUUID 玩家 UUID
     * @return 当前积分
     */
    public int getPoints(UUID playerUUID) {
        return data.getOrCreate(playerUUID).getPoints();
    }

    /**
     * 设置玩家积分为指定值（不低于 0）。
     *
     * @param playerUUID 玩家 UUID
     * @param points     目标积分值
     */
    public void setPoints(UUID playerUUID, int points) {
        data.getOrCreate(playerUUID).setPoints(Math.max(0, points));
        data.setDirty();
    }

    /**
     * 增加玩家积分。
     *
     * @param playerUUID 玩家 UUID
     * @param amount     增加数量（必须 >= 0）
     * @return 操作后的总积分
     * @throws IllegalArgumentException 如果 amount 为负数
     */
    public int addPoints(UUID playerUUID, int amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must be non-negative");
        CheckinSavedData.PlayerCheckinInfo info = data.getOrCreate(playerUUID);
        int newPoints = info.getPoints() + amount;
        info.setPoints(newPoints);
        data.setDirty();
        return newPoints;
    }

    /**
     * 扣除玩家积分（不会扣至负数）。
     *
     * @param playerUUID 玩家 UUID
     * @param amount     期望扣除的数量（必须 >= 0）
     * @return 实际扣除的数量
     * @throws IllegalArgumentException 如果 amount 为负数
     */
    public int removePoints(UUID playerUUID, int amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must be non-negative");
        CheckinSavedData.PlayerCheckinInfo info = data.getOrCreate(playerUUID);
        int current = info.getPoints();
        int removed = Math.min(current, amount);
        info.setPoints(current - removed);
        data.setDirty();
        return removed;
    }

    /**
     * 检查玩家是否拥有至少指定数量的积分。
     *
     * @param playerUUID 玩家 UUID
     * @param amount     需要的积分数量
     * @return 是否足够
     */
    public boolean hasEnoughPoints(UUID playerUUID, int amount) {
        return getPoints(playerUUID) >= amount;
    }

    /**
     * 在两个玩家之间转移积分。
     *
     * @param from   转出方 UUID
     * @param to     转入方 UUID
     * @param amount 转移数量（必须 > 0）
     * @return 转移是否成功（积分不足时返回 false）
     */
    public boolean transferPoints(UUID from, UUID to, int amount) {
        if (amount <= 0) return false;
        if (!hasEnoughPoints(from, amount)) return false;
        removePoints(from, amount);
        addPoints(to, amount);
        return true;
    }

    // ==================== 每日签到 ====================

    /**
     * 判断玩家今天是否已签到。
     *
     * @param playerUUID 玩家 UUID
     * @return 今天是否已签到
     */
    public boolean hasCheckedInToday(UUID playerUUID) {
        String today = LocalDate.now().format(DATE_FORMAT);
        return today.equals(data.getOrCreate(playerUUID).getLastCheckinDate());
    }

    /**
     * 执行每日签到。
     * <p>
     * 自动判断是否连续签到，计算基础随机积分 + 连续签到奖励。
     *
     * @param playerUUID 玩家 UUID
     * @return 本次签到获得的积分，若今天已签到则返回 -1
     */
    public int checkIn(UUID playerUUID) {
        if (hasCheckedInToday(playerUUID)) return -1;

        CheckinSavedData.PlayerCheckinInfo info = data.getOrCreate(playerUUID);
        String today = LocalDate.now().format(DATE_FORMAT);
        String lastDate = info.getLastCheckinDate();

        // 判断是否连续签到
        boolean isConsecutive = false;
        if (!lastDate.isEmpty()) {
            try {
                LocalDate last = LocalDate.parse(lastDate, DATE_FORMAT);
                isConsecutive = last.plusDays(1).equals(LocalDate.now());
            } catch (Exception e) {
                // 日期解析错误时视为非连续
            }
        }

        // 更新连续天数
        if (isConsecutive) {
            info.setConsecutiveDays(info.getConsecutiveDays() + 1);
        } else {
            info.setConsecutiveDays(1);
        }

        // 计算基础随机积分
        int min = Config.minPoints;
        int max = Config.maxPoints;
        int basePoints = min + (max > min ? random.nextInt(max - min + 1) : 0);

        // 连续签到奖励
        int bonus = Math.min(
                (info.getConsecutiveDays() - 1) * Config.consecutiveBonus,
                Config.maxConsecutiveBonus
        );
        int totalEarned = basePoints + bonus;

        // 写入数据
        info.setPoints(info.getPoints() + totalEarned);
        info.setLastCheckinDate(today);
        info.setTotalCheckins(info.getTotalCheckins() + 1);
        data.setDirty();

        return totalEarned;
    }

    // ==================== 数据查询 ====================

    /**
     * 获取玩家连续签到天数。
     *
     * @param playerUUID 玩家 UUID
     * @return 连续签到天数
     */
    public int getConsecutiveDays(UUID playerUUID) {
        return data.getOrCreate(playerUUID).getConsecutiveDays();
    }

    /**
     * 获取玩家总签到次数。
     *
     * @param playerUUID 玩家 UUID
     * @return 总签到次数
     */
    public int getTotalCheckins(UUID playerUUID) {
        return data.getOrCreate(playerUUID).getTotalCheckins();
    }

    /**
     * 获取玩家最后一次签到日期。
     *
     * @param playerUUID 玩家 UUID
     * @return 日期字符串（ISO 格式），从未签到返回空字符串
     */
    public String getLastCheckinDate(UUID playerUUID) {
        return data.getOrCreate(playerUUID).getLastCheckinDate();
    }

    /**
     * 获取积分排行榜（按积分降序）。
     *
     * @param limit 返回的最大条目数
     * @return 排行榜条目列表，每项为 UUID → 积分
     */
    public List<Map.Entry<UUID, Integer>> getTopPlayers(int limit) {
        return getTopPlayers(0, limit);
    }

    /**
     * 获取积分排行榜（按积分降序，支持分页）。
     *
     * @param offset 跳过的条目数
     * @param limit  返回的最大条目数
     * @return 排行榜条目列表，每项为 UUID → 积分
     */
    public List<Map.Entry<UUID, Integer>> getTopPlayers(int offset, int limit) {
        return data.getAllPlayerData().entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().getPoints()))
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取有积分数据的玩家总数。
     *
     * @return 玩家总数
     */
    public int getTotalPlayerCount() {
        return data.getAllPlayerData().size();
    }

    /**
     * 获取所有玩家的积分（不可变视图）。
     *
     * @return UUID → 积分映射
     */
    public Map<UUID, Integer> getAllPoints() {
        Map<UUID, Integer> result = new HashMap<>();
        data.getAllPlayerData().forEach((uuid, info) -> result.put(uuid, info.getPoints()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * 重置指定玩家的所有签到数据（积分、签到记录、连续天数等）。
     *
     * @param playerUUID 玩家 UUID
     */
    public void resetPlayer(UUID playerUUID) {
        CheckinSavedData.PlayerCheckinInfo info = data.getOrCreate(playerUUID);
        info.setPoints(0);
        info.setLastCheckinDate("");
        info.setConsecutiveDays(0);
        info.setTotalCheckins(0);
        data.setDirty();
    }
}
