package cn.mlus.checkin;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;

import cn.mlus.checkin.web.WebAuthManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 签到系统命令注册。
 * <p>
 * 命令列表：
 * <ul>
 *   <li>/checkin — 手动签到</li>
 *   <li>/checkin info [player] — 查看签到信息</li>
 *   <li>/checkin top [count] — 积分排行榜</li>
 *   <li>/checkin set &lt;player&gt; &lt;amount&gt; — 设置积分（OP）</li>
 *   <li>/checkin add &lt;player&gt; &lt;amount&gt; — 增加积分（OP）</li>
 *   <li>/checkin remove &lt;player&gt; &lt;amount&gt; — 扣除积分（OP）</li>
 *   <li>/checkin transfer &lt;player&gt; &lt;amount&gt; — 转账积分</li>
 *   <li>/checkin reset &lt;player&gt; — 重置玩家数据（OP）</li>
 *   <li>/checkin code — 获取网页登录码</li>
 * </ul>
 */
public class CheckinCommands {

    private static WebAuthManager webAuthManager;

    public static void setWebAuthManager(WebAuthManager manager) {
        webAuthManager = manager;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("checkin")
                // /checkin — 手动签到
                .executes(ctx -> performCheckin(ctx.getSource()))

                // /checkin code — 获取网页登录码
                .then(Commands.literal("code")
                        .executes(ctx -> generateWebCode(ctx.getSource()))
                )

                // /checkin info [player]
                .then(Commands.literal("info")
                        .executes(ctx -> showInfo(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> showInfo(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))
                        )
                )

                // /checkin top [count]
                .then(Commands.literal("top")
                        .executes(ctx -> showTop(ctx.getSource(), 10))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(ctx -> showTop(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))
                        )
                )

                // /checkin set <player> <amount>
                .then(Commands.literal("set")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(ctx -> setPoints(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "amount")))
                                )
                        )
                )

                // /checkin add <player> <amount>
                .then(Commands.literal("add")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> addPoints(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "amount")))
                                )
                        )
                )

                // /checkin remove <player> <amount>
                .then(Commands.literal("remove")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> removePoints(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "amount")))
                                )
                        )
                )

                // /checkin transfer <player> <amount>
                .then(Commands.literal("transfer")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> transferPoints(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "amount")))
                                )
                        )
                )

                // /checkin reset <player>
                .then(Commands.literal("reset")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> resetPlayer(ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "player")))
                        )
                )
        );
    }

    private static int performCheckin(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            PointsManager manager = PointsManager.of(source.getServer());
            int earned = manager.checkIn(player.getUUID());
            if (earned < 0) {
                source.sendSuccess(() -> Component.translatable("checkin.already_checked_in"), false);
            } else {
                int total = manager.getPoints(player.getUUID());
                int consecutive = manager.getConsecutiveDays(player.getUUID());
                source.sendSuccess(() -> Component.translatable("checkin.success", earned, total, consecutive), false);
            }
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.translatable("checkin.error"));
            return 0;
        }
    }

    private static int showInfo(CommandSourceStack source, ServerPlayer target) {
        PointsManager manager = PointsManager.of(source.getServer());
        UUID uuid = target.getUUID();
        int points = manager.getPoints(uuid);
        int consecutive = manager.getConsecutiveDays(uuid);
        int totalCheckins = manager.getTotalCheckins(uuid);
        String lastDate = manager.getLastCheckinDate(uuid);
        boolean checkedToday = manager.hasCheckedInToday(uuid);

        source.sendSuccess(() -> Component.translatable("checkin.info.header", target.getDisplayName()), false);
        source.sendSuccess(() -> Component.translatable("checkin.info.points", points), false);
        source.sendSuccess(() -> Component.translatable("checkin.info.consecutive", consecutive), false);
        source.sendSuccess(() -> Component.translatable("checkin.info.total_checkins", totalCheckins), false);
        source.sendSuccess(() -> Component.translatable("checkin.info.last_date", lastDate.isEmpty() ? "-" : lastDate), false);
        source.sendSuccess(() -> Component.translatable("checkin.info.today", checkedToday ? "✔" : "✘"), false);
        return 1;
    }

    private static int showTop(CommandSourceStack source, int count) {
        PointsManager manager = PointsManager.of(source.getServer());
        List<Map.Entry<UUID, Integer>> top = manager.getTopPlayers(count);

        source.sendSuccess(() -> Component.translatable("checkin.top.header", count), false);
        for (int i = 0; i < top.size(); i++) {
            Map.Entry<UUID, Integer> entry = top.get(i);
            int rank = i + 1;
            String playerName = source.getServer().getProfileCache() != null
                    ? source.getServer().getProfileCache().get(entry.getKey())
                    .map(GameProfile::getName).orElse(entry.getKey().toString())
                    : entry.getKey().toString();
            int pts = entry.getValue();
            source.sendSuccess(() -> Component.translatable("checkin.top.entry", rank, playerName, pts), false);
        }
        if (top.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("checkin.top.empty"), false);
        }
        return 1;
    }

    private static int setPoints(CommandSourceStack source, ServerPlayer target, int amount) {
        PointsManager manager = PointsManager.of(source.getServer());
        manager.setPoints(target.getUUID(), amount);
        source.sendSuccess(() -> Component.translatable("checkin.admin.set", target.getDisplayName(), amount), false);
        return 1;
    }

    private static int addPoints(CommandSourceStack source, ServerPlayer target, int amount) {
        PointsManager manager = PointsManager.of(source.getServer());
        int newTotal = manager.addPoints(target.getUUID(), amount);
        source.sendSuccess(() -> Component.translatable("checkin.admin.add", amount, target.getDisplayName(), newTotal), false);
        return 1;
    }

    private static int removePoints(CommandSourceStack source, ServerPlayer target, int amount) {
        PointsManager manager = PointsManager.of(source.getServer());
        int removed = manager.removePoints(target.getUUID(), amount);
        int remaining = manager.getPoints(target.getUUID());
        source.sendSuccess(() -> Component.translatable("checkin.admin.remove", removed, target.getDisplayName(), remaining), false);
        return 1;
    }

    private static int transferPoints(CommandSourceStack source, ServerPlayer target, int amount) {
        try {
            ServerPlayer sender = source.getPlayerOrException();
            if (sender.getUUID().equals(target.getUUID())) {
                source.sendFailure(Component.translatable("checkin.transfer.self"));
                return 0;
            }
            PointsManager manager = PointsManager.of(source.getServer());
            if (manager.transferPoints(sender.getUUID(), target.getUUID(), amount)) {
                source.sendSuccess(() -> Component.translatable("checkin.transfer.success", amount, target.getDisplayName()), false);
                target.sendSystemMessage(Component.translatable("checkin.transfer.received", amount, sender.getDisplayName()));
                return 1;
            } else {
                source.sendFailure(Component.translatable("checkin.transfer.insufficient"));
                return 0;
            }
        } catch (Exception e) {
            source.sendFailure(Component.translatable("checkin.error"));
            return 0;
        }
    }

    private static int resetPlayer(CommandSourceStack source, ServerPlayer target) {
        PointsManager manager = PointsManager.of(source.getServer());
        manager.resetPlayer(target.getUUID());
        source.sendSuccess(() -> Component.translatable("checkin.admin.reset", target.getDisplayName()), false);
        return 1;
    }

    private static int generateWebCode(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (webAuthManager == null) {
                source.sendFailure(Component.translatable("checkin.web.disabled"));
                return 0;
            }
            String code = webAuthManager.generateCode(player.getUUID(), player.getGameProfile().getName(),
                    source.hasPermission(2));

            // 构建可点击复制的登录码消息（不使用 § 格式码，避免干扰子组件样式）
            Component codeComponent = Component.literal(code)
                    .withStyle(Style.EMPTY
                            .withBold(true)
                            .withColor(ChatFormatting.GOLD)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("点击复制登录码")))
                    );

            Component msg = Component.empty()
                    .append(Component.literal("[签到] ").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("你的网页登录码：").withStyle(ChatFormatting.WHITE))
                    .append(codeComponent)
                    .append(Component.literal(" (点击复制)").withStyle(ChatFormatting.GRAY));

            source.sendSuccess(() -> msg, false);
            source.sendSuccess(() -> Component.translatable("checkin.web.code_hint", Config.webPort), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.translatable("checkin.error"));
            return 0;
        }
    }
}
