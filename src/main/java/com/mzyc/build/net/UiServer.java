package com.mzyc.build.net;

import com.mzyc.build.LightConfig;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * UI 的服务端一半：开界面 + 执行 UI 上发来的指令 + 回传最新快照。
 *
 * <h2>为什么指令要「静音执行」</h2>
 * 用户定的铁规矩是「UI 的反馈只在退出时汇总一条」。可指令自己的反馈（{@code 指令执行成功}）
 * 是写死在 {@code LightCommand} 里的，直接跑就会一条一条刷进聊天框。
 * 这里用 {@link ServerCommandSource#withSilent()} 拿一个静音的源：
 * 回执全被吞掉，而<b>返回值照样拿得到</b>（成功 = 1，失败 = 0），于是 UI 能攒出那条汇总。
 *
 * <p>指令仍然走**正牌 Brigadier 解析**（而不是在 UI 里重写一遍赋值逻辑），
 * 所以 UI 按钮和手打指令百分之百同一条代码路径、同一套夹取与校验。
 *
 * <h2>白名单</h2>
 * 通道只接受 {@code light / hlight / flex / flexable / lightall / alllight} 开头的正文 ——
 * 免得这个包变成「任意指令执行器」（原版指令有自己的权限判断，但没必要开这个口子）。
 */
public final class UiServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mzycBuild");

    /** UI 通道允许触发的指令根名（手打指令本来也都能用，这里只是不给通道开后门）。 */
    private static final Set<String> ALLOWED_ROOTS =
            Set.of("light", "hlight", "flex", "flexable", "lightall", "alllight");

    private UiServer() {
    }

    /** 在模组初始化时挂上 C2S 接收器。 */
    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(UiPayload.TYPE, (payload, player, sender) -> {
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }
            // 收发包裹、读写存档状态都必须在服务端主线程上做
            server.execute(() -> handle(server, player, payload.command()));
        });
    }

    /**
     * 请这位玩家打开 UI（{@code /mb help} 走到这里）。
     *
     * @return 客户端装着模组、包发得出去 ⇒ true；否则 false（指令那边据此回「指令执行失败」）
     */
    public static boolean open(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null || !ServerPlayNetworking.canSend(player, UiStatePayload.TYPE)) {
            return false;
        }
        ServerPlayNetworking.send(player, new UiStatePayload(true, true, snapshot(server)));
        return true;
    }

    /** UI 上按了一下：执行指令（可能为空 = 只刷新），然后回一份最新快照。 */
    private static void handle(MinecraftServer server, ServerPlayerEntity player, String raw) {
        String body = raw == null ? "" : raw.trim();
        while (body.startsWith("/")) {
            body = body.substring(1).trim();
        }
        boolean ok = true;
        if (!body.isEmpty()) {
            ok = run(server, player, body);
        }
        ServerPlayNetworking.send(player, new UiStatePayload(ok, false, snapshot(server)));
    }

    /** 静音执行一条指令正文，返回是否成功。 */
    private static boolean run(MinecraftServer server, ServerPlayerEntity player, String body) {
        int space = body.indexOf(' ');
        String root = space < 0 ? body : body.substring(0, space);
        if (!ALLOWED_ROOTS.contains(root)) {
            LOGGER.warn("[mzycBuild] UI 通道拒绝了白名单外的指令: {}", body);
            return false;
        }
        ServerCommandSource source = player.getCommandSource().withSilent();
        try {
            var dispatcher = server.getCommandManager().getDispatcher();
            return dispatcher.execute(dispatcher.parse(body, source)) > 0;
        } catch (Exception failed) {
            // 解析失败 / 参数不合法 / 指令自己抛异常：一律算这一次 UI 动作失败
            LOGGER.warn("[mzycBuild] UI 动作失败: {} ({})", body, failed.toString());
            return false;
        }
    }

    /** 主世界归档里的那一份设置，序列化成 NBT 交给客户端显示。 */
    private static NbtCompound snapshot(MinecraftServer server) {
        LightConfig config = LightConfig.get(server.getOverworld());
        return config.writeNbt(new NbtCompound());
    }
}
