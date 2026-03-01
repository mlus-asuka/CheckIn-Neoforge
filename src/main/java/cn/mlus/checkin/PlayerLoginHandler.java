package cn.mlus.checkin;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/**
 * 监听玩家登录事件，实现自动签到。
 */
@EventBusSubscriber(modid = Checkin.MODID)
public class PlayerLoginHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Config.autoCheckin) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.getServer() == null) return;

        PointsManager manager = PointsManager.of(player.getServer());
        int earned = manager.checkIn(player.getUUID());

        if (earned >= 0) {
            int total = manager.getPoints(player.getUUID());
            int consecutive = manager.getConsecutiveDays(player.getUUID());
            player.sendSystemMessage(Component.translatable("checkin.success", earned, total, consecutive));
            LOGGER.info("Player {} checked in and earned {} points (total: {}, consecutive: {} days)",
                    player.getGameProfile().getName(), earned, total, consecutive);
        }
    }
}
