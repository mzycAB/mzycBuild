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
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 灯光方块。
 *
 * <p>整体照抄原版「光源方块」（minecraft:light）的可见性思路：
 * 方块本身不参与区块网格渲染（{@link BlockRenderType#INVISIBLE}），
 * 是否可被选中完全由当前玩家的手持物品决定。
 *
 * <p>区别是原版光源方块自己什么也画不出来，这里由 {@code LightBlockEntityRenderer}
 * 画出一个<b>整方块黄色线框</b>（铺满整格的立方体，12 条棱、中间全透明），
 * 所以拿着它的人能清楚看到每一块灯光方块在哪。
 */
public class LightBlock extends Block implements BlockEntityProvider {
    /** 是否点亮。亮度由它决定，所以必须是方块状态而不是方块实体字段。 */
    public static final BooleanProperty LIT = BooleanProperty.of("lit");

    /**
     * 点亮时的亮度（0~15，和原版光源方块 `minecraft:light` 的 `level` 属性同一套语义）。
     *
     * <p>为什么必须进方块状态：亮度是**光照引擎**要读的（而且客户端也要读，才能正确画光照）。
     * `Settings.luminance(...)` 只拿得到 {@link BlockState}，拿不到世界，所以设置里的值
     * 只能先写进方块状态再被读到。用 `/light light X` 改设置后，各方块会在自己的下一次 tick 里把
     * 这个属性同步过去。
     */
    public static final IntProperty LEVEL = IntProperty.of("level",
            LightConfig.MIN_LIGHT_LEVEL, LightConfig.MAX_LIGHT_LEVEL);

    public LightBlock(AbstractBlock.Settings settings) {
        super(settings);
        setDefaultState(getDefaultState()
                .with(LIT, false)
                .with(LEVEL, LightConfig.DEFAULT_LIGHT_LEVEL));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LIT, LEVEL);
    }

    public static int getLuminance(BlockState state) {
        return state.get(LIT) ? state.get(LEVEL) : 0;
    }

    /**
     * 方块被放进世界时立刻写入当前设置里的亮度。
     *
     * <p>为什么不只用 {@code onPlaced}：{@code onPlaced} <b>只有玩家用物品放置</b>才会触发。
     * 而这条路径对所有来源都成立 —— 玩家放置、`/setblock`、结构生成、
     * 以及建筑模组（Axiom 之类）的批量写入，全都会走 {@code onBlockAdded}。
     * 少了它，非玩家来源放下的方块会一直挂着默认亮度，直到下一次 tick 才被纠正。
     *
     * <p>自己调 {@code setBlockState} 会再触发一次本方法，所以用
     * {@code state.get(LEVEL) != level} 做闸门切断递归。
     */
    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        if (state.isOf(oldState.getBlock())) {
            return; // 只是同一个方块换了状态，不是「新放下的」，别掺和
        }
        int level = LightConfig.get(serverWorld).getLightLevel();
        if (state.get(LEVEL) != level) {
            world.setBlockState(pos, state.with(LEVEL, level), Block.NOTIFY_ALL);
        }
    }

    /**
     * 放下时也补一次（冗余保险：两种钩子各覆盖一部分调用路径，重复写无副作用，
     * 因为下面有 {@code state.get(LEVEL) != level} 的闸门）。
     */
    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (world instanceof ServerWorld serverWorld) {
            int level = LightConfig.get(serverWorld).getLightLevel();
            if (state.get(LEVEL) != level) {
                world.setBlockState(pos, state.with(LEVEL, level), Block.NOTIFY_ALL);
            }
        }
    }

    // ---------------------------------------------------------------- 穿行 / 选中

    /** 没有碰撞箱：玩家、实体都能直接穿过。 */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    /**
     * 只有手持灯光方块的玩家才拿得到「外框」。
     * 客户端准星射线用的就是这个形状（RaycastContext.ShapeType.OUTLINE），
     * 返回空就意味着既看不见也点不中、更拆不掉。
     */
    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return isHoldingLightBlock(context) ? VoxelShapes.fullCube() : VoxelShapes.empty();
    }

    /** 形状上下文版判定：主手由 {@link ShapeContext#isHolding} 覆盖，副手自己补。 */
    public static boolean isHoldingLightBlock(ShapeContext context) {
        if (context.isHolding(MzycBuild.LIGHT_BLOCK_ITEM)) {
            return true;
        }
        if (context instanceof EntityShapeContext entityContext) {
            Entity entity = entityContext.getEntity();
            if (entity instanceof PlayerEntity player) {
                return isHoldingLightBlock(player);
            }
        }
        return false;
    }

    /** 主手或副手拿着灯光方块。 */
    public static boolean isHoldingLightBlock(PlayerEntity player) {
        return player.getMainHandStack().isOf(MzycBuild.LIGHT_BLOCK_ITEM)
                || player.getOffHandStack().isOf(MzycBuild.LIGHT_BLOCK_ITEM);
    }

    // ---------------------------------------------------------------- 渲染 / 光照

    /** 不进区块网格，交给方块实体渲染器逐玩家绘制。 */
    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    /** 不挡光、不挡视线（原版光源方块也是这么写的）。 */
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

    /** 该位置已被灯光方块占用，任何方块都放不进来。 */
    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext context) {
        return false;
    }

    // ---------------------------------------------------------------- 方块实体

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new LightBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient) {
            return null;
        }
        return (w, p, s, be) -> {
            if (be instanceof LightBlockEntity light) {
                light.tick(w, p, s);
            }
        };
    }
}
