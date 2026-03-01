package cn.mlus.checkin;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 签到系统配置。
 * <p>
 * 配置项包括：
 * <ul>
 *   <li>minPoints / maxPoints — 每日签到随机积分范围</li>
 *   <li>autoCheckin — 是否在玩家登录时自动签到</li>
 *   <li>consecutiveBonus — 每连续签到一天的额外奖励</li>
 *   <li>maxConsecutiveBonus — 连续签到奖励的上限</li>
 * </ul>
 */
@EventBusSubscriber(modid = Checkin.MODID)
public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.IntValue MIN_POINTS = BUILDER
            .comment("Minimum points awarded for daily check-in")
            .defineInRange("minPoints", 10, 0, 10000);

    private static final ModConfigSpec.IntValue MAX_POINTS = BUILDER
            .comment("Maximum points awarded for daily check-in")
            .defineInRange("maxPoints", 50, 0, 10000);

    private static final ModConfigSpec.BooleanValue AUTO_CHECKIN = BUILDER
            .comment("Automatically check in when player logs in")
            .define("autoCheckin", true);

    private static final ModConfigSpec.IntValue CONSECUTIVE_BONUS = BUILDER
            .comment("Bonus points per consecutive check-in day (added to random amount)")
            .defineInRange("consecutiveBonus", 1, 0, 1000);

    private static final ModConfigSpec.IntValue MAX_CONSECUTIVE_BONUS = BUILDER
            .comment("Maximum bonus from consecutive check-in days")
            .defineInRange("maxConsecutiveBonus", 10, 0, 10000);

    private static final ModConfigSpec.BooleanValue WEB_ENABLED = BUILDER
            .comment("Enable the built-in web shop server")
            .define("webEnabled", true);

    private static final ModConfigSpec.IntValue WEB_PORT = BUILDER
            .comment("Port for the built-in web server")
            .defineInRange("webPort", 25580, 1024, 65535);

    private static final ModConfigSpec.ConfigValue<String> WEB_HOST = BUILDER
            .comment("Web server host address (use IP or domain name)")
            .define("webHost", "localhost");

    static final ModConfigSpec SPEC = BUILDER.build();

    public static int minPoints;
    public static int maxPoints;
    public static boolean autoCheckin;
    public static int consecutiveBonus;
    public static int maxConsecutiveBonus;
    public static boolean webEnabled;
    public static int webPort;
    public static String webHost;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        minPoints = MIN_POINTS.get();
        maxPoints = MAX_POINTS.get();
        autoCheckin = AUTO_CHECKIN.get();
        consecutiveBonus = CONSECUTIVE_BONUS.get();
        maxConsecutiveBonus = MAX_CONSECUTIVE_BONUS.get();
        webEnabled = WEB_ENABLED.get();
        webPort = WEB_PORT.get();
        webHost = WEB_HOST.get();
    }
}
