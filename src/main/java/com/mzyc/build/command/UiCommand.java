package com.mzyc.build.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mzyc.build.net.UiServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * UI 入口指令：{@code /mb help}（也认 {@code /MB help} 和光秃秃的 {@code /mb}）。
 *
 * <p>它做的事只有一件：请服务端给这位玩家发一个包，客户端收到就把设置界面打开
 * （见 {@code com.mzyc.build.client.gui}）。界面里全是指令做成的按钮和输入框，
 * 手打指令照样可用 —— 两条路走的是同一套指令。
 *
 * <p>反馈仍按铁规矩只有两句：{@code 指令执行成功} / {@code 指令执行失败}。
 * 客户端没装模组（包发不出去）时回失败。
 */
public final class UiCommand {
    private static final Text FEEDBACK_OK = Text.literal("指令执行成功");
    private static final Text FEEDBACK_FAIL = Text.literal("指令执行失败");

    private UiCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // 大小写都认：抄笔记时不会因为写成 MB 而找不到指令
        dispatcher.register(build("mb"));
        dispatcher.register(build("MB"));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> build(String name) {
        return CommandManager.literal(name)
                .executes(UiCommand::open)
                .then(CommandManager.literal("help").executes(UiCommand::open));
    }

    private static int open(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        boolean ok = player != null && UiServer.open(player);
        source.sendFeedback(() -> ok ? FEEDBACK_OK : FEEDBACK_FAIL, false);
        return ok ? 1 : 0;
    }
}
