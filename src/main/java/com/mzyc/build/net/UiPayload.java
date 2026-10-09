package com.mzyc.build.net;

import com.mzyc.build.MzycBuild;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.PacketByteBuf;

/**
 * 客户端 → 服务端：UI 上按了按钮 / 点了「设置」。
 *
 * <p>{@code command} 是**不带斜杠**的指令正文（如 {@code light percent 30}、{@code flex off}），
 * 空串 = 只要一份当前设置的快照（UI 的「刷新」）。
 *
 * <p>为什么不让 UI 直接往聊天框里打指令：
 * <ol>
 *   <li>每条指令都会回一句「指令执行成功」，点十下就刷十行；</li>
 *   <li>UI 需要知道成功还是失败，好在退出时汇总成一条 —— 聊天框那条回执拿不到。</li>
 * </ol>
 * 所以走自定义包，指令在服务端**静音执行**（见 {@code UiServer}），
 * 反馈只在退出 UI 时汇总一条「UI执行成功 / UI执行失败」。
 */
public record UiPayload(String command) implements FabricPacket {
    /** 指令最长就这么长（正常指令远短于此）。 */
    private static final int MAX_LENGTH = 64;

    public static final PacketType<UiPayload> TYPE =
            PacketType.create(MzycBuild.id("ui_action"), UiPayload::new);

    public UiPayload(PacketByteBuf buf) {
        this(buf.readString(MAX_LENGTH));
    }

    @Override
    public void write(PacketByteBuf buf) {
        buf.writeString(command == null ? "" : command, MAX_LENGTH);
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
