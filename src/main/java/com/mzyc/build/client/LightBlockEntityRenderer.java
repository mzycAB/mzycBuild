package com.mzyc.build.client;

import com.mzyc.build.block.LightBlock;
import com.mzyc.build.block.LightBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import org.joml.Matrix4f;

/**
 * 把黄色灯光方块画成「整方块黄色线框」——只对「手上拿着灯光方块」的玩家画。
 *
 * <p>为什么必须走方块实体渲染器：区块网格是构建一次就缓存、所有玩家共用的，
 * 在网格构建阶段按手持物品决定画不画，会把结果写死进缓存里。
 * 方块实体渲染器是每帧、每个客户端各跑一次的，才能做到「同一块方块，
 * 拿着的玩家看得见、没拿的玩家完全看不见」。
 *
 * <p>线框几何在 {@link Wireframe}（和红色灯光方块共用同一份）。
 */
public class LightBlockEntityRenderer implements BlockEntityRenderer<LightBlockEntity> {
    /** 线框颜色：高饱和亮黄。 */
    private static final float LINE_RED = 1.0F;
    private static final float LINE_GREEN = 0.84F;
    private static final float LINE_BLUE = 0.0F;
    private static final float LINE_ALPHA = 1.0F;

    public LightBlockEntityRenderer(BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(LightBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || !LightBlock.isHoldingLightBlock(player)) {
            return;
        }
        VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getLines());
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        Wireframe.drawCube(buffer, matrix, LINE_RED, LINE_GREEN, LINE_BLUE, LINE_ALPHA);
    }
}
