package com.mzyc.build;

import com.mzyc.build.block.LightBlockEntity;
import com.mzyc.build.block.RedLightBlockEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 「当前已加载的灯光方块实体」索引 —— 让<b>一条指令能立刻作用到全场</b>。
 *
 * <h2>为什么非要有它</h2>
 * 黄灯亮度/亮灭本来只在自己的 {@code tick} 里刷新，而那有两个天生的盲区：
 * <ol>
 *   <li><b>节流</b>：黄灯每 5 tick 才判定一次，改完设置最多要等 0.25 秒才看到效果；</li>
 *   <li><b>不 tick 的区块</b>：超出模拟距离的「边界区块」是<b>已加载但不会 tick 方块实体</b>的，
 *       那些方块永远不会自己刷新 —— 于是 {@code /light -f off} 灭不了远处的灯，
 *       违背「无论什么情况，立即全部熄灭」。</li>
 * </ol>
 * 这里的做法：挂 Fabric 的方块实体加载 / 卸载事件，把已加载的灯光方块实体收进两张表；
 * 指令改完设置后调 {@link #refreshAll}，把所有在册方块<b>当场</b>重算一遍并写状态。
 *
 * <p>另一件顺带的事：{@code /flex … percent X} 需要一个「本晚被点亮了多少盏黄灯」的总数
 * 才能算出「灭到只剩几盏时一次全灭」的收尾轮次（见 {@link #nightLitCount}）。
 *
 * <p>两张表用 {@link HashMap} 而不是弱引用：键是 {@link ServerWorld}，生命周期由卸载事件管；
 * 值里存的是方块实体引用，卸载事件负责移除，迭代时再兜底剔除 {@code isRemoved()} 的。
 * 只在服务端主线程访问，不需要加锁。
 */
public final class LightIndex {
    private static final Map<ServerWorld, Set<LightBlockEntity>> YELLOW = new HashMap<>();
    private static final Map<ServerWorld, Set<RedLightBlockEntity>> RED = new HashMap<>();

    /** 「本晚点亮数」的缓存：（世界 → 夜晚编号 / 数量），避免每 tick 全表扫。 */
    private static final Map<ServerWorld, Long> COUNT_NIGHT = new HashMap<>();
    private static final Map<ServerWorld, Integer> COUNT_VALUE = new HashMap<>();

    private LightIndex() {
    }

    /** 在模组初始化时挂上加载 / 卸载事件。 */
    public static void register() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register(LightIndex::onLoad);
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register(LightIndex::onUnload);
    }

    private static void onLoad(BlockEntity blockEntity, ServerWorld world) {
        if (blockEntity instanceof LightBlockEntity yellow) {
            YELLOW.computeIfAbsent(world, w -> new HashSet<>()).add(yellow);
        } else if (blockEntity instanceof RedLightBlockEntity red) {
            RED.computeIfAbsent(world, w -> new HashSet<>()).add(red);
        }
    }

    private static void onUnload(BlockEntity blockEntity, ServerWorld world) {
        if (blockEntity instanceof LightBlockEntity yellow) {
            drop(YELLOW.get(world), yellow);
        } else if (blockEntity instanceof RedLightBlockEntity red) {
            drop(RED.get(world), red);
        }
    }

    private static <T> void drop(Set<T> set, T value) {
        if (set != null) {
            set.remove(value);
        }
    }

    /** 让<b>所有维度</b>的已加载灯光方块立刻按当前设置刷新一遍。 */
    public static void refreshAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerWorld world : server.getWorlds()) {
            refresh(world);
        }
    }

    /** 刷新单个维度的已加载灯光方块。 */
    public static void refresh(ServerWorld world) {
        Set<LightBlockEntity> yellows = YELLOW.get(world);
        if (yellows != null) {
            for (LightBlockEntity entity : yellows.toArray(new LightBlockEntity[0])) {
                if (entity.isRemoved()) {
                    yellows.remove(entity);
                    continue;
                }
                entity.refreshNow(world);
            }
        }
        Set<RedLightBlockEntity> reds = RED.get(world);
        if (reds != null) {
            for (RedLightBlockEntity entity : reds.toArray(new RedLightBlockEntity[0])) {
                if (entity.isRemoved()) {
                    reds.remove(entity);
                    continue;
                }
                entity.refreshNow(world);
            }
        }
    }

    /**
     * 本晚「第 0 轮会亮」的黄灯数量 —— 按 {@code rollsOn(pos, 夜晚编号, percent)} 直接判，
     * <b>不依赖各方块是否已经锁存</b>，所以入夜后第一个来问的方块就能拿到完整数字。
     *
     * <p>结果按「世界 + 夜晚编号」缓存一整晚：轮次计算必须是稳定的，
     * 中途变数会让已经灭掉的方块「复活」，正是之前费劲消除的抽搐。
     */
    public static int nightLitCount(ServerWorld world, long nightIndex, int percent) {
        Long cachedNight = COUNT_NIGHT.get(world);
        if (cachedNight != null && cachedNight == nightIndex) {
            return COUNT_VALUE.getOrDefault(world, 0);
        }
        Set<LightBlockEntity> yellows = YELLOW.get(world);
        int count = 0;
        if (yellows != null) {
            for (LightBlockEntity entity : yellows) {
                if (entity.isRemoved()) {
                    continue;
                }
                if (LightBlockEntity.rollsOn(entity.getPos(), nightIndex, percent)) {
                    count++;
                }
            }
        }
        COUNT_NIGHT.put(world, nightIndex);
        COUNT_VALUE.put(world, count);
        return count;
    }
}
