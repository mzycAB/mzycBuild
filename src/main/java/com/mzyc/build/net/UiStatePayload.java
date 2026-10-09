package com.mzyc.build.net;

import com.mzyc.build.MzycBuild;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;

/**
 * 服务端 → 客户端：请开界面 / 这是最新的设置快照。
 *
 * @param ok       这一次 UI 动作（执行指令）成功还是失败；开界面时无意义
 * @param open     {@code true} = 让客户端打开 UI（对应 {@code /mb help}）；{@code false} = 只是回传快照
 * @param snapshot 当前设置的完整快照（就是 {@code LightConfig} 存盘用的那份 NBT，
 *                 客户端直接 {@code LightConfig.fromNbt} 解出来显示，不额外维护一套字段）
 */
public record UiStatePayload(boolean ok, boolean open, NbtCompound snapshot) implements FabricPacket {
    public static final PacketType<UiStatePayload> TYPE =
            PacketType.create(MzycBuild.id("ui_state"), UiStatePayload::new);

    public UiStatePayload(PacketByteBuf buf) {
        this(buf.readBoolean(), buf.readBoolean(), buf.readNbt());
    }

    @Override
    public void write(PacketByteBuf buf) {
        buf.writeBoolean(ok);
        buf.writeBoolean(open);
        buf.writeNbt(snapshot == null ? new NbtCompound() : snapshot);
    }

    @Override
    public PacketType<?> getType() {
        return TYPE;
    }
}
