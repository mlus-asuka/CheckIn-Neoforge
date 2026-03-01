package cn.mlus.checkin;

import cn.mlus.checkin.web.CheckinWebServer;
import cn.mlus.checkin.web.RedemptionLog;
import cn.mlus.checkin.web.ShopManager;
import cn.mlus.checkin.web.WebAuthManager;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * CheckIn 模组主类 —— 每日签到 & 积分管理系统。
 * <p>
 * 功能概述：
 * <ul>
 *   <li>玩家每天首次登录自动签到，获得随机积分</li>
 *   <li>连续签到额外奖励</li>
 *   <li>完善的积分管理 API（查询、增删改、转账、排行榜）</li>
 *   <li>指令系统：/checkin 及其子命令</li>
 * </ul>
 */
@Mod(Checkin.MODID)
public class Checkin {

    public static final String MODID = "checkin";
    private static final Logger LOGGER = LogUtils.getLogger();

    private final WebAuthManager webAuthManager = new WebAuthManager();
    private final ShopManager shopManager = new ShopManager(FMLPaths.CONFIGDIR.get());
    private final RedemptionLog redemptionLog = new RedemptionLog(FMLPaths.CONFIGDIR.get());
    private CheckinWebServer webServer;

    public Checkin(IEventBus modEventBus, ModContainer modContainer) {
        // 注册游戏事件总线
        NeoForge.EVENT_BUS.register(this);

        // 注册配置文件
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 将 WebAuthManager 传递给命令系统
        CheckinCommands.setWebAuthManager(webAuthManager);

        LOGGER.info("CheckIn mod initialized");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("CheckIn mod: server starting");

        // 加载商店配置
        shopManager.load();
        shopManager.setServer(event.getServer());

        // 加载兑换记录
        redemptionLog.load();
        shopManager.setRedemptionLog(redemptionLog);

        // 启动 Web 服务器
        if (Config.webEnabled) {
            webServer = new CheckinWebServer(Config.webPort, webAuthManager, shopManager);
            webServer.start(event.getServer());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CheckinCommands.register(event.getDispatcher());
        LOGGER.info("CheckIn commands registered");
    }
}
