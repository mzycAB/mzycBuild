package com.mzyc.build.block;

import com.mzyc.build.LightConfig;
import com.mzyc.build.MzycBuild;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 红色灯光方块：和黄色 {@link LightBlock} 是同一套「结构空位」行为 ——
 * 不进区块网格、只对持有者可见可破坏、没有碰撞箱可以穿过、所在位置放不下别的方块，
 * 线框换成红色。
 *
 * <p>差别在发光规律：它不在夜里掷骰，而是**固定闪烁** ——
 * 默认「亮 2 秒 / 灭 2 秒」循环（用 {@code /hlight time on|off X} 分别改亮起 / 熄灭时间，支持 2 位小数），
 * 两端各带可调的明暗渐变（{@code /hlight slow on|off X}，默认各 0.10 秒）。
 * 因为要表现 0.10 秒（= 2 tick）的渐变，它必须**每 tick**都判定，
 * 不能像黄方块那样每 5 tick 才判一次。
 *
 * <p>亮度属性只需要一个 {@code level}（0 = 灭、15 = 最亮）：
 * 黄方块要一个 {@code lit} 布尔是因为「亮/灭」之外还要记住亮度，
 * 红方块这里 {@code level=0} 天然就是灭，不需要额外的布尔。
 * 最大亮度仍然读 {@code /light light X} 那个设置（默认 12），
 * 但**不受** {@code /light percent}（比例）和 {@code /light delay}（随机延迟）影响 —— 它不掷骰也不延迟。
 */
public class RedLightBlock extends Block implements BlockEntityProvider {
    /** 0 = 完全熄灭，15 = 设置里的最大亮度。直接就是发光强度。 */
    public static final IntProperty LEVEL = IntProperty.of("level",
            LightConfig.MIN_LIGHT_LEVEL, LightConfig.MAX_LIGHT_LEVEL);

    public RedLightBlock(AbstractBlock.Settings settings) {
        super(settings);
        // 放下来先是灭的，下一个 tick 由方块实体按当前时刻接管
        setDefaultState(getDefaultState().with(LEVEL, 0));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LEVEL);
    }

    /** 发光强度就是 level 本身（0~15）。 */
    public static int getLuminance(BlockState state) {
        return state.get(LEVEL);
    }

    // ---------------------------------------------------------------- 穿行 / 选中

    /** 没有碰撞箱：玩家、实体都能直接穿过。 */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    /**
     * 只有手持红色灯光方块的玩家才拿得到「外框」。
     * 客户端准星射线用的就是这个形状（RaycastContext.ShapeType.OUTLINE），
     * 返回空就意味着既看不见也点不中、更拆不掉。
     */
    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return isHoldingRedLightBlock(context) ? VoxelShapes.fullCube() : VoxelShapes.empty();
    }

    /** 形状上下文版判定：主手由 {@link ShapeContext#isHolding} 覆盖，副手自己补。 */
    public static boolean isHoldingRedLightBlock(ShapeContext context) {
        if (context.isHolding(MzycBuild.RED_LIGHT_BLOCK_ITEM)) {
            return true;
        }
        if (context instanceof EntityShapeContext entityContext) {
            Entity entity = entityContext.getEntity();
            if (entity instanceof PlayerEntity player) {
                return isHoldingRedLightBlock(player);
            }
        }
        return false;
    }

    /** 主手或副手拿着红色灯光方块。 */
    public static boolean isHoldingRedLightBlock(PlayerEntity player) {
        return player.getMainHandStack().isOf(MzycBuild.RED_LIGHT_BLOCK_ITEM)
                || player.getOffHandStack().isOf(MzycBuild.RED_LIGHT_BLOCK_ITEM);
    }

    // ---------------------------------------------------------------- 渲染 / 光照

    /** 不进区块网格，交给方块实体渲染器逐玩家绘制。 */
    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    /** 不挡光、不挡视线（和原版光源方块一致）。 */
    @Override
    public boolean isTransparent(BlockState state, BlockView world, BlockPos pos) {
        return true;
    }

    /** 不影响相邻方块面的明暗，避免一整排灯光方块把墙压黑。 */
    @Override
    public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
        return 1.0F;
    }

    // ---------------------------------------------------------------- 占位

    /** 该位置已被红色灯光方块占用，任何方块都放不进来。 */
    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext context) {
        return false;
    }

    // ---------------------------------------------------------------- 方块实体

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new RedLightBlockEntity(pos, state);
    }

    /**
     * 闪烁要逐 tick 推进（0.1 秒 = 2 tick 的过渡靠每 tick 写状态实现），
     * 所以这里**不做 5 tick 节流**。好在离开夜晚 / 已完全熄灭时会提前返回，稳态开销只有一次比较。
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient) {
            return null;
        }
        return (w, p, s, be) -> {
            if (be instanceof RedLightBlockEntity red) {
                red.tick(w, p, s);
            }
        };
    }
}
