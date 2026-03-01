package cn.mlus.checkin.web;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web 登录认证管理器。
 * <p>
 * 流程：玩家在游戏内执行 /checkin code 获取 6 位登录码，
 * 在网页输入登录码后获得 session token，后续请求携带 token 鉴权。
 */
public class WebAuthManager {

    /** 登录码 → 玩家 UUID（等待兑换） */
    private final Map<String, LoginCode> pendingCodes = new ConcurrentHashMap<>();

    /** Session token → 玩家 UUID（已登录） */
    private final Map<String, UUID> sessions = new ConcurrentHashMap<>();

    /** Session token → 是否为 OP */
    private final Map<String, Boolean> sessionOp = new ConcurrentHashMap<>();

    /** 玩家 UUID → 玩家名称缓存 */
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final long CODE_EXPIRE_MS = 5 * 60 * 1000; // 5 分钟过期
    private static final long SESSION_EXPIRE_MS = 60 * 60 * 1000; // 1 小时过期

    /**
     * 为玩家生成一个一次性登录码。
     *
     * @param playerUUID 玩家 UUID
     * @param playerName 玩家名称
     * @param isOp       是否为 OP 玩家
     * @return 6 位登录码
     */
    public String generateCode(UUID playerUUID, String playerName, boolean isOp) {
        // 清除该玩家之前的未使用登录码
        pendingCodes.entrySet().removeIf(e -> e.getValue().uuid.equals(playerUUID));
        nameCache.put(playerUUID, playerName);

        String code = createRandomCode();
        pendingCodes.put(code, new LoginCode(playerUUID, playerName, isOp, System.currentTimeMillis()));
        return code;
    }

    /**
     * 使用登录码换取 session token。
     *
     * @param code 登录码
     * @return session token，如果登录码无效或过期则返回 null
     */
    public SessionInfo redeemCode(String code) {
        cleanExpiredCodes();
        LoginCode loginCode = pendingCodes.remove(code.toUpperCase());
        if (loginCode == null) return null;
        if (System.currentTimeMillis() - loginCode.createdAt > CODE_EXPIRE_MS) return null;

        // 移除该玩家之前的 session
        sessions.entrySet().removeIf(e -> e.getValue().equals(loginCode.uuid));
        sessionOp.entrySet().removeIf(e -> !sessions.containsKey(e.getKey()));

        String token = UUID.randomUUID().toString();
        sessions.put(token, loginCode.uuid);
        sessionOp.put(token, loginCode.isOp);
        nameCache.put(loginCode.uuid, loginCode.playerName);

        return new SessionInfo(token, loginCode.uuid, loginCode.playerName, loginCode.isOp);
    }

    /**
     * 验证 session token 并返回玩家 UUID。
     *
     * @param token session token
     * @return 玩家 UUID，无效则返回 null
     */
    public UUID validateToken(String token) {
        if (token == null || token.isEmpty()) return null;
        return sessions.get(token);
    }

    /**
     * 获取玩家名称。
     */
    public String getPlayerName(UUID uuid) {
        return nameCache.getOrDefault(uuid, uuid.toString());
    }

    /**
     * 检查 token 对应的用户是否为 OP。
     */
    public boolean isAdmin(String token) {
        if (token == null) return false;
        return sessionOp.getOrDefault(token, false);
    }

    /**
     * 使指定 token 的 session 失效。
     */
    public void invalidateSession(String token) {
        sessions.remove(token);
        sessionOp.remove(token);
    }

    private String createRandomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private void cleanExpiredCodes() {
        long now = System.currentTimeMillis();
        pendingCodes.entrySet().removeIf(e -> now - e.getValue().createdAt > CODE_EXPIRE_MS);
    }

    private record LoginCode(UUID uuid, String playerName, boolean isOp, long createdAt) {}

    public record SessionInfo(String token, UUID uuid, String playerName, boolean isOp) {}
}
