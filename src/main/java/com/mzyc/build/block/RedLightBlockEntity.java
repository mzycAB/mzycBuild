package com.mzyc.build.block;

import com.mzyc.build.LightConfig;
import com.mzyc.build.MzycBuild;
import com.mzyc.build.util.Hash;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 红色灯光方块的方块实体：夜里**固定闪烁**红光。
 *
 * <h2>闪烁节奏（全部由 /hlight 指令调，均支持 2 位小数）</h2>
 * <ul>
 *   <li>{@code /hlight time on X} —— 亮起时间：一盏灯亮多久（默认 2.00 秒）；</li>
 *   <li>{@code /hlight time off X} —— 熄灭时间：一盏灯灭多久（默认 2.00 秒）；</li>
 *   <li>{@code /hlight slow on X} —— 灭→亮渐变时间（默认 0.10 秒）；</li>
 *   <li>{@code /hlight slow off X} —— 亮→灭渐变时间（默认 0.10 秒）；</li>
 *   <li>{@code /hlight flex on|off [X]} —— 「错开」：每块红方块按坐标得到一个固定偏移，
 *       于是不再齐亮齐灭；X = 随机错开时长上限（默认关 / 4.00 秒）。</li>
 * </ul>
 *
 * <p><b>一个完整周期</b> = 亮起时间 + 熄灭时间（默认 2.00 + 2.00 = 4.00 秒）。
 * 渐变是**含在**亮/灭时长之内的：亮起阶段的头 {@code slow on} 秒由暗升到满，
 * 熄灭阶段的头 {@code slow off} 秒由满降到暗，其余维持满亮 / 全灭 ——
 * 所以波形首尾天然接得上，不会跳变。
 *
 * <h2>⚠️ 两个时间源必须分清（和 {@link LightBlockEntity} 同一个坑）</h2>
 * {@code doDaylightCycle=false}（建筑档常见的「永夜」）时：
 * <pre>
 *   world.getTime()       = gameTime，每 tick 无条件 +1，永远在走
 *   world.getTimeOfDay()  = dayTime，只有昼夜循环开着才 +1，永夜时冻在 13000
 * </pre>
 * 所以这里的分工是固定的：
 * <ul>
 *   <li><b>判昼夜</b>用 {@code getTimeOfDay()}（这是它唯一正确的用途）；</li>
 *   <li><b>算闪烁相位</b>用 {@code getTime()} —— 否则永夜里相位冻死，
 *       红方块永远不亮。</li>
 * </ul>
 *
 * <h2>相位为什么直接锁在世界刻数上</h2>
 * 波形由 {@code gameTime} 直接算出，不存任何状态：
 * 一是**全世界所有红方块自动同步闪烁**；二是**确定性** —— 存档重启、区块卸载重载，相位都不漂。
 *
 * <p>{@code /hlight flex on X} 时，每块按坐标叠加一个**固定**偏移（{@link Hash} 确定性散列，
 * 偏移 ∈ [0, X]），于是各闪各的；因为偏移也是确定性的，重进世界不会换一种闪法。
 *
 * <h2>0.1 秒渐变在 MC 里的极限</h2>
 * 方块**投射出去的光照**只有 0~15 的整数档，一秒 20 tick，所以 0.10 秒最多只能写出
 * 2 个中间档（0 → 6 → 12）。这是引擎限制、不是偷懒。
 * <p>方块本体线框的明暗由客户端渲染器画（见 {@code RedLightBlockEntityRenderer}）——
 * 它**直接读方块状态的 level**，所以和实际光照逐帧一致；代价是渐变也只能是那 2 个台阶
 * （和光照本身一模一样），这是为了让「线框看到的」和「实际发光的」永远不分家、
 * 并且让 {@code /hlight …} 立刻生效、不需要任何额外的网络同步。
 */
public class RedLightBlockEntity extends BlockEntity {
    /** 世界时间 13000~23000 即夜晚（和 MC 自带昼夜一致，/time set night = 13000）。 */
    public static final long NIGHT_START = 13000L;
    public static final long NIGHT_END = 23000L;

    /** 1 秒 = 20 tick。 */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * 计算「错开」用的散列盐值（"RED_PH_SR" 的 ASCII）。
     * 换个盐值就会换一整套乱序 —— 固定住是为了让同一存档的闪法永远一致。
     */
    private static final long RED_PHASE_SALT = 0x5245445F50485F52L;

    public RedLightBlockEntity(BlockPos pos, BlockState state) {
        super(MzycBuild.RED_LIGHT_BLOCK_ENTITY, pos, state);
    }

    public void tick(World world, BlockPos pos, BlockState state) {
        // getTicker 只在服务端返回非 null，这里再兜一次底
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        apply(serverWorld, pos);
    }

    /**
     * 立刻按当前设置重算并写状态 —— 给「指令改完立即生效」用，也覆盖那些
     * <b>已加载但不会 tick</b>（超出模拟距离）的方块。见 {@code LightIndex}。
     */
    public void refreshNow(ServerWorld world) {
        apply(world, pos);
    }

    /** 重算一次亮度，和当前方块状态不同才写盘。 */
    private static void apply(ServerWorld world, BlockPos pos) {
        int want = desiredLevel(world, pos);

        // 基准状态从世界里现取（不用 tick 传进来的缓存 state），再精确比较要不要写。
        // 稳态时（整段满亮或整段全灭）这里的判断恒为「相等」，一个 tick 就一次数组取值，很便宜。
        BlockState current = world.getBlockState(pos);
        if (!current.isOf(MzycBuild.RED_LIGHT_BLOCK) || current.get(RedLightBlock.LEVEL) == want) {
            return;
        }
        world.setBlockState(pos, current.with(RedLightBlock.LEVEL, want), Block.NOTIFY_ALL);
    }

    /** 这个时刻它应该发多亮的光（0 = 灭）。白天、{@code /light -f off}、{@code /light h off} 恒为 0。 */
    public static int desiredLevel(ServerWorld world, BlockPos pos) {
        LightConfig config = LightConfig.get(world);
        // 总开关：全灭优先，其次红灯分组开关
        if (config.isForceOff() || !config.isRedOn()) {
            return 0;
        }
        int max = config.getLightLevel();
        long on = config.getRedTimeOnTicks();
        long off = config.getRedTimeOffTicks();
        long slowOn = config.getRedSlowOnTicks();
        long slowOff = config.getRedSlowOffTicks();

        // 相位默认就是游戏刻数 —— 全世界所有红方块共用同一条相位 ⇒ 齐亮齐灭。
        long phase = world.getTime();
        if (config.isRedFlexOn()) {
            // 开了「错开」（/hlight flex on X）：按坐标给一个固定偏移，落在 [0, X]。
            // 偏移上限夹到一个完整周期内 —— 否则偏移绕圈之后又会和别的方块对齐，白错开。
            long bound = Math.min(config.getRedFlexTicks(), config.getRedPeriodTicks());
            phase += randomOffsetTicks(pos, bound);
        }

        double fraction = fractionAt(world.getTimeOfDay(), phase, on, off, slowOn, slowOff);
        return (int) Math.round(fraction * max);
    }

    /**
     * 这块方块的固定相位偏移（tick），落在 [0, bound] 闭区间。
     *
     * <p>用坐标的确定性散列（{@link Hash}）：**看起来随机，但存档重启、区块卸载重载后一模一样** ——
     * 不会出现「重进一次世界红方块就换一种闪法」。{@code bound <= 0} 时恒为 0（等同不偏移）。
     */
    public static long randomOffsetTicks(BlockPos pos, long bound) {
        return Hash.range(pos.asLong(), RED_PHASE_SALT, bound);
    }

    /**
     * 该时刻红方块的实际亮度系数（0~1）：<b>昼夜判定 + 闪烁相位</b>合在一起。
     *
     * <p>服务端用它算投射光照（{@code round(fraction * maxLevel)} 取整到 0~15）。
     * 客户端渲染器不再重算它 —— 渲染器直接读方块状态里的 level，保证「看得见的亮」
     * 和「实际发光的亮」永远一致（见类注释里关于渐变台阶的说明）。
     *
     * @param timeOfDay 世界时间（dayTime，用来判是不是夜晚；永夜档里冻在 13000）
     * @param gameTime  游戏总刻数（用来算闪烁相位；永远在走）
     */
    public static double fractionAt(long timeOfDay, long gameTime,
                                     long onTicks, long offTicks,
                                     long slowOnTicks, long slowOffTicks) {
        if (timeOfDay < NIGHT_START || timeOfDay >= NIGHT_END) {
            return 0.0;
        }
        return blinkFraction(gameTime, onTicks, offTicks, slowOnTicks, slowOffTicks);
    }

    /**
     * 闪烁波形：给一个游戏总刻数，返回 0~1 的亮度系数（不含昼夜判定）。
     *
     * <p>一个周期 = {@code on + off} tick，前 {@code on} tick 为「亮起阶段」、后 {@code off} tick
     * 为「熄灭阶段」。渐变各占该阶段的**头部**：
     * <pre>
     *   1.0        ╱────────────╲                 ╱────
     *             ╱              ╲              ╱
     *   0.0 ─────╱                ╲────────────╱
     *        0 slowOn   on     on+slowOff  on+off
     * </pre>
     * 于是 {@code p=0} 与 {@code p=on} 处波形连续，接得上下一个周期。
     *
     * <p><b>参数是 gameTime 而不是 timeOfDay</b>：{@code gameTime} 每 tick 无条件 +1，
     * 永夜档里也照走；拿 {@code timeOfDay} 当相位来源会在永夜里冻死（根因）。
     *
     * <p>{@code on <= 0} ⇒ 永不亮（恒 0）；{@code off <= 0} ⇒ 恒亮（没有熄灭阶段）。
     */
    public static double blinkFraction(long gameTime,
                                       long onTicks, long offTicks,
                                       long slowOnTicks, long slowOffTicks) {
        long on = Math.max(0L, onTicks);
        long off = Math.max(0L, offTicks);
        if (on <= 0L) {
            return 0.0;                       // 亮起时间 = 0 ⇒ 永远不亮
        }
        if (off <= 0L) {
            return 1.0;                       // 熄灭时间 = 0 ⇒ 没有熄灭阶段，恒亮
        }
        long period = on + off;
        long p = Math.floorMod(gameTime, period);

        if (p < on) {
            long fade = Math.min(Math.max(0L, slowOnTicks), on);   // 渐变不能超过整个亮起阶段
            if (fade <= 0L) {
                return 1.0;                   // 硬开
            }
            return p < fade ? (double) p / (double) fade : 1.0;
        }

        long q = p - on;
        long fade = Math.min(Math.max(0L, slowOffTicks), off);
        if (fade <= 0L) {
            return 0.0;                       // 硬关
        }
        return q < fade ? 1.0 - (double) q / (double) fade : 0.0;
    }
}
