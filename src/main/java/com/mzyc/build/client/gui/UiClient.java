package com.mzyc.build.client.gui;

import com.mzyc.build.LightConfig;
import com.mzyc.build.net.UiPayload;
import com.mzyc.build.net.UiStatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

/**
 * UI 的客户端一半：收服务端快照、把 UI 上的动作发回服务端、退出时汇总一条反馈。
 *
 * <h2>反馈规矩（用户定的铁规矩）</h2>
 * <ul>
 *   <li>界面里<b>不放任何操作反馈小字</b>（类似「操作完成」），小字只允许是标题/行名；</li>
 *   <li>点按钮、点「设置」时**不刷聊天框**——指令在服务端静音执行；</li>
 *   <li>退出整个 UI 时，才在聊天框回**一条**汇总：{@code UI执行成功} 或 {@code UI执行失败}。</li>
 * </ul>
 *
 * <p>快照只有一份（{@link #snapshot}），所有子界面都从它取值 ——
 * 这样改完设置再退到上一层，看到的也是最新的数，不会拿着旧值显示。
 */
public final class UiClient {
    /** 正在子界面之间跳转；这段时间内的 {@code removed()} 不算「退出 UI」。 */
    static boolean navigating;

    private static LightConfig snapshot = new LightConfig();
    private static boolean sessionOpen;
    private static boolean sessionFailure;

    private UiClient() {
    }

    /** 在客户端初始化时挂上 S2C 接收器。 */
    public static void register() {
        // 收包线程不一定是客户端主线程，开界面 / 重建控件都得排回主线程做
        ClientPlayNetworking.registerGlobalReceiver(UiStatePayload.TYPE, (payload, player, sender) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> onState(client, payload));
        });
    }

    /** 当前设置快照（客户端只读）。 */
    static LightConfig snapshot() {
        return snapshot;
    }

    private static void onState(MinecraftClient client, UiStatePayload payload) {
        NbtCompound nbt = payload.snapshot() == null ? new NbtCompound() : payload.snapshot();
        snapshot = LightConfig.fromNbt(nbt);
        if (payload.open()) {
            sessionOpen = true;
            sessionFailure = false;
            client.setScreen(new UiMainScreen());
            return;
        }
        if (!payload.ok()) {
            sessionFailure = true;
        }
        if (client.currentScreen instanceof UiScreen screen) {
            screen.applySnapshot();
        }
    }

    /** UI 上按了一下：把指令正文发给服务端（不走聊天框，所以不会刷回执）。 */
    static void send(String command) {
        ClientPlayNetworking.send(new UiPayload(command == null ? "" : command));
    }

    /** 退出整个 UI：按规矩只回一条汇总。 */
    static void finishSession(MinecraftClient client) {
        if (!sessionOpen) {
            return;
        }
        sessionOpen = false;
        String feedback = sessionFailure ? "UI执行失败" : "UI执行成功";
        sessionFailure = false;
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal(feedback), false);
        }
    }
}
