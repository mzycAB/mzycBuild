package com.mzyc.build.client;

import com.mzyc.build.LightConfig;
import com.mzyc.build.MzycBuild;
import com.mzyc.build.block.RedLightBlock;
import com.mzyc.build.block.RedLightBlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;
import org.joml.Matrix4f;

/**
 * 红色灯光方块的「整方块红色线框」——只对「手上拿着红色灯光方块」的玩家画。
 *
 * <p><b>线框明暗直接读方块状态的 {@code level}</b>（0~15），不自己算相位：
 * <ul>
 *   <li>好处一：和**实际投射出去的光照**逐帧完全一致，不会出现「线框在亮、光却是灭的」；</li>
 *   <li>好处二：{@code /hlight …} 改完闪烁节奏，线框立刻跟着变，不需要任何额外的网络同步
 *       （方块状态本来就是服务端同步给客户端的）；</li>
 *   <li>代价：过渡也只能是方块状态那几个台阶（默认亮度 12 时是 0 → 6 → 12）。
 *       但实际光照本来就只有 0~15 整数档、0.1 秒最多写出 2 个中间档，
 *       所以这里本来就不可能比光照更细，让两者一致反而更对。</li>
 * </ul>
 *
 * <p>灭的时候线框不全黑，留 {@link #MIN_BRIGHTNESS} 一点亮度 ——
 * 否则一半时间整块看不见，建造时找不到方块在哪。
 */
public class RedLightBlockEntityRenderer implements BlockEntityRenderer<RedLightBlockEntity> {
    /** 线框颜色：高饱和红。 */
    private static final float LINE_RED = 1.0F;
    private static final float LINE_GREEN = 0.15F;
    private static final float LINE_BLUE = 0.12F;
    private static final float LINE_ALPHA = 1.0F;

    /** 全灭时保留的线框亮度（0~1），保证方块始终看得见。 */
    private static final float MIN_BRIGHTNESS = 0.35F;

    public RedLightBlockEntityRenderer(BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(RedLightBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerEntity player = client.player;
        World world = client.world;
        if (player == null || world == null || !RedLightBlock.isHoldingRedLightBlock(player)) {
            return;
        }

        // 从世界现取状态（比 entity.getCachedState() 更保险，永远是最新一份）。
        BlockState state = world.getBlockState(entity.getPos());
        if (!state.isOf(MzycBuild.RED_LIGHT_BLOCK)) {
            return;
        }
        int level = state.get(RedLightBlock.LEVEL);

        // level 就是发光强度（0~15）。按满档 15 归一，所以线框最亮 = MIN + (1-MIN) * (亮度/15)。
        float fraction = level / (float) LightConfig.MAX_LIGHT_LEVEL;
        float brightness = MIN_BRIGHTNESS + (1.0F - MIN_BRIGHTNESS) * fraction;

        VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getLines());
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        Wireframe.drawCube(buffer, matrix,
                LINE_RED * brightness, LINE_GREEN * brightness, LINE_BLUE * brightness, LINE_ALPHA);
    }
}
