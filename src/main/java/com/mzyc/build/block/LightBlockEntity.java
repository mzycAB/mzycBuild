package com.mzyc.build.block;

import com.mzyc.build.LightConfig;
import com.mzyc.build.LightIndex;
import com.mzyc.build.MzycBuild;
import com.mzyc.build.util.Hash;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 灯光方块的方块实体：负责「入夜掷骰 → 随机延迟后亮起 → 天亮熄灭」，
 * 以及黄灯专属的「夜间随机重分配」（{@code /flex …}）。
 *
 * <h2>⚠️ 两个时间源必须分清（这是之前「黄灯红灯都不亮」的根因）</h2>
 * MC 的 {@code World} 有两个时钟，{@code doDaylightCycle=false} 时只有一个还在走
 * （原版 {@code ClientWorld.tickTime()} / 服务端同款逻辑）：
 * <pre>
 *   world.getTime()       = gameTime，<b>每 tick 无条件 +1</b>，永远在走
 *   world.getTimeOfDay()  = dayTime，<b>只有 doDaylightCycle 开着才 +1</b>
 * </pre>
 * 建筑档常见的「永夜」（{@code /time set night} + 关掉昼夜循环）会把 dayTime 冻在 13000，
 * 于是 {@code timeOfDay - NIGHT_START} 恒等于 0 —— 延迟永远等不到、整片方块不亮。
 * 所以这里：
 * <ul>
 *   <li><b>判昼夜</b>用 {@code getTimeOfDay()}（这是它唯一的用途）；</li>
 *   <li><b>算「过了多久」一律用 {@code getTime()}</b>。</li>
 * </ul>
 *
 * <h2>「这一晚」怎么定义</h2>
 * 不用时间戳去猜，而是**本地观察**：方块实体从「不是夜晚」变到「是夜晚」的那一刻，
 * 就是这一晚的起点，把它锁存下来（{@code nightStartedAt}），骰子也在这时掷一次。
 * 天亮时解锁，下一晚重新掷。
 * <ul>
 *   <li>正常昼夜循环：入夜锁存 → 延迟从「天黑那一刻」开始算 ✓</li>
 *   <li>永夜：永远观察不到天亮 ⇒ 只锁存一次、永不重置 ⇒ 延迟走完就一直亮着，
 *       不会有「每 20 分钟集体重来一次」的假动作 ✓</li>
 * </ul>
 * 锁存值随方块实体存进 NBT，所以区块卸载重载、重启游戏都不会重新掷骰。
 *
 * <h2>黄灯的「夜间随机重分配」（{@code /flex on|off …}）</h2>
 * 默认关闭，一整夜只掷一次骰（老行为）。开启后：
 * <ol>
 *   <li><b>第 0 轮</b>＝入夜那次掷骰（{@code /light percent} 的比例）＋随机延迟 —— 也就是「第一次亮灯」；</li>
 *   <li>从「本块第一次亮灯」起算，每隔 {@code time Y} 秒进入下一轮：
 *       <ul>
 *         <li>{@code percent X} 指定时 —— 在「本晚被点亮」的灯里，每轮熄灭**当前还亮着**的 X%
 *             （只灭不亮：灭了的不会自己再亮，也绝不会点亮本晚原本没亮的灯；越到后半夜越暗）。
 *             X=0 不变、X=100 第 1 轮全灭。收尾时如果剩下的灯少到「X% 不足一盏」就**一次全灭**，
 *             不留零星鬼火（见 {@link #flexEndRound}）。</li>
 *         <li>未指定 percent —— 对全部黄灯按 {@code /light percent} **重新分配**一次点亮 / 熄灭
 *             （**有熄灭也有点亮**，本晚没亮的也可能被点亮）。</li>
 *       </ul></li>
 *   <li>{@code flex X} 给每块一个固定偏差 ±X 秒，避免所有方块同一瞬间一起变；</li>
 *   <li>{@code grow X} 让第 k 轮间隔 = {@code Y + (k-1)·X}（可为负 ⇒ 间隔逐轮变短，
 *       60 → 55 → 50 …）。间隔下限夹到 1 tick。</li>
 * </ol>
 * 轮次和结果都是**确定性纯函数**（坐标 + 夜晚编号 + 轮次 → 结果），
 * 不存任何「每块状态」，所以存档重启 / 区块卸载重载后表现完全一致。
 *
 * <p><b>总开关</b>（{@code /light …}）：{@code /light -f off} 时黄/红一律不亮（无视其它设置）；
 * {@code /light off} 只关黄灯；{@code /light h off} 只关红灯。黑灯状态下仍然照常锁存「今晚」状态，
 * 一旦重新打开就立刻按原有节奏恢复。
 * <b>开关是「立即」生效的</b>：指令写完会当场把全场已加载的方块刷新一遍（见 {@code LightIndex}），
 * 既不等黄灯那 5 tick 节流，也能灭掉超出模拟距离、根本不会 tick 的边界区块方块。
 *
 * <p>比例 / 亮度 / 延迟上限都由 {@link LightConfig} 提供（默认 30% / 12 / 60 秒，
 * 对应 {@code /light percent X}、{@code /light light X}、{@code /light delay Y}）。
 *
 * <p><b>诊断日志</b>：带 {@code [mzycBuild/诊断]} 的几行只写日志文件、不碰任何 UI 文字。
 */
public class LightBlockEntity extends BlockEntity {
    /** 世界时间 13000~23000 即夜晚（和 MC 自带昼夜一致，/time set night = 13000）。 */
    public static final long NIGHT_START = 13000L;
    public static final long NIGHT_END = 23000L;

    /** 1 秒 = 20 tick。 */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * 每 5 tick 才重新判定一次（0.25 秒）。
     * 分钟级的随机延迟用不着 tick 级精度，这样能把几千块方块的散列开销砍掉 80%。
     */
    private static final long CHECK_INTERVAL = 5L;

    /** 轮次计算的兜底上限（极端参数下别把服务端卡死；正常参数远远到不了）。 */
    private static final int MAX_FLEX_ROUNDS = 100_000;

    /** 「第 r 轮」散列用的盐（"FLEX_RND" 的 ASCII），固定住 ⇒ 同一存档的重分配序列永远一致。 */
    private static final long FLEX_ROUND_SALT = 0x464C45585F524E44L;
    /** 偏差（jitter）散列用的盐（"FLEX_JIT" 的 ASCII）。 */
    private static final long FLEX_JITTER_SALT = 0x464C45585F4A4954L;
    /** 「第几轮被熄灭」散列用的盐（"EXTINGSH" 的 ASCII）。 */
    private static final long EXTINGUISH_SALT = 0x455854494E475348L;

    // ---------------- 跨 tick / 跨存档保留的「今晚」状态（存进 NBT） ----------------

    /** 今晚是否已经掷过骰（以「本地观察到处于夜晚」为准）。 */
    private boolean nightLatched;
    /** 观察到天黑的那一刻，用 gameTime 记（它不受 doDaylightCycle 影响，永远在走）。 */
    private long nightStartedAt;
    /** 本晚的编号（{@code gameTime / 24000}），入夜时锁存，整夜不变（永夜里也不会漂）。 */
    private long nightIndex;
    /** 今晚这块掷骰的结果，整夜不变（第 0 轮的亮/灭）。 */
    private boolean nightRoll;
    /**
     * 今晚这块要等多少 tick 才亮，入夜时和骰子一起锁存。
     *
     * <p>必须锁存、不能每 tick 重算：重算用的是「当前 gameTime / 24000」当夜晚编号，
     * 一旦 gameTime 跨过 24000 的整数倍，同一个编号就变了 ⇒ 算出来的延迟目标突然换成另一个值。
     * 于是「入夜时刻恰好落在边界前一小段（不到最长延迟）」的那批方块，会在等了一半时
     * 被换掉目标，表现为「灭一下再亮 / 亮一下又灭」的抽搐
     * （回归脚本 {@code _tools/verify_night_fix.py} 在该边界场景下实测约 13.5% 的方块中招）。
     * 锁存后整夜目标固定，彻底消除。
     */
    private long nightDelayTicks;

    /** 上一次 {@link #evaluate} 算出的轮次，只给诊断日志用。 */
    private int lastFlexRound;

    // ======================= 临时诊断（根因钉死即删） =======================

    /** 每 200 tick（10 秒）打一行汇总，由「本 tick 第一个被 tick 的方块」负责打。 */
    private static final long REPORT_INTERVAL = 200L;
    private static final AtomicLong LAST_REPORT = new AtomicLong(Long.MIN_VALUE);
    private static final AtomicInteger CREATED_LOGGED = new AtomicInteger();
    private static final AtomicInteger CHANGE_LOGGED = new AtomicInteger();
    private static int winTicked;
    private static int winLit;

    // =====================================================================

    public LightBlockEntity(BlockPos pos, BlockState state) {
        super(MzycBuild.LIGHT_BLOCK_ENTITY, pos, state);
        if (CREATED_LOGGED.getAndIncrement() < 8) {
            MzycBuild.LOGGER.info("[mzycBuild/诊断] 方块实体已创建 @{} 初始状态={}", pos, state);
        }
    }

    public void tick(World world, BlockPos pos, BlockState state) {
        // getTicker 只在服务端返回非 null，这里再兜一次底
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        // 每 5 tick 才真正判定一次
        long gameTime = serverWorld.getTime();
        if (gameTime % CHECK_INTERVAL != 0L) {
            return;
        }

        LightConfig config = LightConfig.get(serverWorld);
        long timeOfDay = serverWorld.getTimeOfDay();
        boolean night = timeOfDay >= NIGHT_START && timeOfDay < NIGHT_END;

        // 「今晚」的锁存 / 解锁
        if (!night) {
            if (nightLatched) {
                nightLatched = false;
                markDirty();
            }
        } else if (!nightLatched) {
            nightLatched = true;
            nightStartedAt = gameTime;
            // world.getTime() 是游戏总刻数，除以 24000 得到「第几天」。
            // 夜里这段时间恰好整段落在同一天内，所以这就是「本晚」的编号。锁存住防永夜漂移。
            nightIndex = gameTime / 24000L;
            nightRoll = rollsOn(pos, nightIndex, config.getPercent());
            nightDelayTicks = delayTicksOn(pos, nightIndex, config.getDelaySeconds());
            markDirty();
        }

        // ---------------- 该不该亮 ----------------
        long elapsed = night ? (gameTime - nightStartedAt) : 0L;
        boolean wantLit = evaluate(serverWorld, pos, config, night);

        winTicked++;
        if (wantLit) {
            winLit++;
        }

        boolean changed = applyState(serverWorld, pos, wantLit, config.getLightLevel());
        if (changed && CHANGE_LOGGED.getAndIncrement() < 12) {
            MzycBuild.LOGGER.info("[mzycBuild/诊断] 状态变更 @{} → lit={} level={} ｜ gameTime={} t={} night={} 掷中={} 轮={} 已等={}/{}",
                    pos, wantLit, config.getLightLevel(), gameTime, timeOfDay, night, nightRoll, lastFlexRound, elapsed, nightDelayTicks);
        }

        // 每个汇报刻只让第一个方块打一行（LAST_REPORT.getAndSet 保证幂等）
        if (gameTime % REPORT_INTERVAL == 0L && LAST_REPORT.getAndSet(gameTime) != gameTime) {
            MzycBuild.LOGGER.info("[mzycBuild/诊断] gameTime={} t={} night={} 设置={}%/{}/{}s 黄flex={} ｜ 本窗口被 tick 方块={} 应亮={} 已等={} 当前状态={}",
                    gameTime, timeOfDay, night, config.getPercent(), config.getLightLevel(),
                    config.getDelaySeconds(), config.isYellowFlexOn(), winTicked, winLit, elapsed, state);
            winTicked = 0;
            winLit = 0;
        }
    }

    /**
     * 按当前设置算出「这一刻该不该亮」。{@code tick} 和 {@link #refreshNow} 共用同一份逻辑，
     * 保证「指令立即生效」走出来的结果和正常 tick 完全一致（不会出现两种口径）。
     */
    private boolean evaluate(ServerWorld world, BlockPos pos, LightConfig config, boolean night) {
        long elapsed = night ? (world.getTime() - nightStartedAt) : 0L;
        boolean wantLit = false;
        lastFlexRound = 0;
        if (night && elapsed >= nightDelayTicks) {
            // 已经过了「本块第一次亮灯」的时刻
            if (!config.isYellowFlexOn()) {
                wantLit = nightRoll;                       // 关着 → 一整夜只掷一次
            } else {
                // 从「第一次亮灯」起算，叠加本块固定偏差，再求当前轮次
                long jitter = flexJitterTicks(pos, config.getYellowFlexJitterTicks());
                long sinceFirstLight = (elapsed - nightDelayTicks) - jitter;
                lastFlexRound = flexRoundAt(sinceFirstLight,
                        config.getYellowFlexTimeTicks(), config.getYellowFlexGrowTicks());
                wantLit = lastFlexRound <= 0
                        ? nightRoll                                    // 第 0 轮 = 入夜那次掷骰
                        : flexRoundLit(pos, nightIndex, lastFlexRound, nightRoll,
                                config.getYellowFlexPercent(), config.getPercent(),
                                LightIndex.nightLitCount(world, nightIndex, config.getPercent()));
            }
        }
        // 总开关：全灭优先于分组开关；「黑着」时仍照常锁存状态，一开就立刻恢复节奏
        if (config.isForceOff() || !config.isYellowOn()) {
            wantLit = false;
        }
        return wantLit;
    }

    /**
     * 立刻按当前设置重算并写状态 —— 给「指令改完立即生效」用，绕开 5 tick 节流，
     * 也覆盖那些<b>已加载但不会 tick</b>（超出模拟距离）的方块。见 {@link LightIndex}。
     */
    public void refreshNow(ServerWorld world) {
        boolean night = isNight(world);
        if (night && !nightLatched) {
            // 还没锁存今晚：亮不亮根本没法算，交给下一次正常 tick 自己锁存
            return;
        }
        LightConfig config = LightConfig.get(world);
        applyState(world, pos, evaluate(world, pos, config, night), config.getLightLevel());
    }

    /** 世界时间落在 13000~23000 即夜晚。 */
    public static boolean isNight(ServerWorld world) {
        long timeOfDay = world.getTimeOfDay();
        return timeOfDay >= NIGHT_START && timeOfDay < NIGHT_END;
    }

    /**
     * 把「该不该亮 + 当前亮度」写进方块状态。
     *
     * <p>基准状态从世界里现取（{@code world.getBlockState(pos)}），不用 tick 传进来的缓存 {@code state}；
     * 再用 {@code current == target} 精确判断要不要写，免得缓存落后时反复绕回旧值。
     *
     * @return 是否真的写入了新状态
     */
    private boolean applyState(ServerWorld world, BlockPos pos, boolean lit, int level) {
        BlockState current = world.getBlockState(pos);
        if (!current.isOf(MzycBuild.LIGHT_BLOCK)) {
            // 方块已经不在了（被替换 / 区块边缘），什么也别做
            return false;
        }
        BlockState target = current.with(LightBlock.LIT, lit).with(LightBlock.LEVEL, level);
        if (current == target) {
            return false;
        }
        world.setBlockState(pos, target, Block.NOTIFY_ALL);
        return true;
    }

    // ---------------------------------------------------------------- 存档

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putBoolean("NightLatched", nightLatched);
        nbt.putLong("NightStartedAt", nightStartedAt);
        nbt.putLong("NightIndex", nightIndex);
        nbt.putBoolean("NightRoll", nightRoll);
        nbt.putLong("NightDelayTicks", nightDelayTicks);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        nightLatched = nbt.getBoolean("NightLatched");
        nightStartedAt = nbt.getLong("NightStartedAt");
        // 老存档没有 NightIndex：用锁存的入夜时刻反推（同一晚结果一致）
        nightIndex = nbt.contains("NightIndex") ? nbt.getLong("NightIndex") : (nightStartedAt / 24000L);
        nightRoll = nbt.getBoolean("NightRoll");
        nightDelayTicks = nbt.getLong("NightDelayTicks");
    }

    // ---------------------------------------------------------------- 随机（确定性散列）

    /**
     * 今晚这块亮不亮。命中率就是 {@code percent}%，同一块在同一晚结果恒定（整夜不闪），
     * 换一晚换一个结果。0% 恒不亮，100% 恒亮。
     */
    public static boolean rollsOn(BlockPos pos, long nightIndex, int percent) {
        if (percent <= 0) {
            return false;
        }
        if (percent >= 100) {
            return true;
        }
        return Math.floorMod(nightHash(pos, nightIndex), 100L) < percent;
    }

    /** 这块今晚要等多少 tick 才亮：0 ~ delaySeconds*20。确定性随机，跨重启不变。 */
    public static long delayTicksOn(BlockPos pos, long nightIndex, int delaySeconds) {
        long maxTicks = Math.max(0L, delaySeconds) * TICKS_PER_SECOND;
        return Math.floorMod(splitmix(nightHash(pos, nightIndex)), maxTicks + 1L);
    }

    // ---------------------------------------------------------------- 黄灯重分配（第 r 轮）

    /**
     * 第 {@code round} 轮这块亮不亮。
     *
     * <p>第 0 轮刻意和 {@link #rollsOn} **完全等价**（同一个散列），保证「刚入夜那次掷骰」
     * 和没开 flex 时的表现一模一样。第 ≥1 轮用「夜晚编号 + 轮次」再散列一次得到全新结果。
     * 同一轮内结果恒定，所以不会在轮内闪烁。
     */
    public static boolean rollsOnRound(BlockPos pos, long nightIndex, int round, int litPercent) {
        if (litPercent <= 0) {
            return false;
        }
        if (litPercent >= 100) {
            return true;
        }
        return Math.floorMod(roundHash(pos, nightIndex, round), 100L) < litPercent;
    }

    /**
     * 第 {@code round}（≥1）轮这块亮不亮。
     *
     * <p><b>{@code percent X} 已指定</b>：只在「**本晚被点亮**」的灯（{@code nightRoll}）里，
     * 每轮熄灭**当前还亮着**的 X%。被熄灭的不会自己再亮（只灭不亮），所以越到后半夜越暗；
     * 也绝不会点亮本晚原本没亮的灯。收尾见 {@link #flexEndRound}。
     *
     * <p><b>未指定 percent</b>：对**全部**方块按 {@code /light percent} 重新分配一次
     * 点亮 / 熄灭（有熄灭也有点亮）—— 对应「重新分配一次点亮/熄灭的灯」。
     *
     * @param nightLitCount 本晚第 0 轮的亮灯总数（由 {@link LightIndex} 数出来）
     */
    public static boolean flexRoundLit(BlockPos pos, long nightIndex, int round,
                                       boolean nightRoll, int extinguishPercent, int percent,
                                       int nightLitCount) {
        if (extinguishPercent == LightConfig.YELLOW_FLEX_PERCENT_UNSET) {
            return rollsOnRound(pos, nightIndex, round, percent);
        }
        return nightRoll && (long) round < extinguishRound(pos, nightIndex, extinguishPercent, nightLitCount);
    }

    /**
     * 「本晚点亮集」里这块**到第几轮**才被熄灭。
     *
     * <p>基础是**几何分布**：每轮独立地以 X% 的概率被熄灭，于是「活过 R 轮」的概率恰好 = {@code (1 - X/100)^R}。
     * 等价于「每轮熄灭还亮着的 X%」，但能在 O(1) 内一次算出「第几轮熄」——
     * 永恒黑夜档里轮次会一直涨，逐轮循环会把服务端拖垮。
     *
     * <p>再加**收尾**：轮次被 {@link #flexEndRound} 夹住。因为「灯很少时 X% 会算出小数」
     * （比如只剩 3 盏、X=20% ⇒ 0.6 盏），按「整除不了就全灭」处理，所以最后那几盏会在同一轮一起灭掉，
     * 不会留一两点鬼火拖到天亮。
     *
     * <p>{@code X <= 0} ⇒ 永不熄灭（{@code Long.MAX_VALUE}）；{@code X >= 100} ⇒ 第 1 轮全灭。
     */
    public static long extinguishRound(BlockPos pos, long nightIndex, int extinguishPercent, int nightLitCount) {
        if (extinguishPercent <= 0) {
            return Long.MAX_VALUE;
        }
        if (extinguishPercent >= 100) {
            return 1L;
        }
        double survive = 1.0 - extinguishPercent / 100.0;              // q ∈ (0, 1)
        long salt = Hash.mix(nightIndex * 0x9E3779B97F4A7C15L + EXTINGUISH_SALT);
        long bits = Hash.of(pos.asLong(), salt);
        double u = (((bits >>> 40) & 0xFFFFFFL) + 0.5) / 16777216.0;    // 均匀落在 (0, 1)
        // D = 1 + floor(ln(u)/ln(q)) ⇒ P(D > k) = q^k，即 P(活过 k 轮) = (1 - X/100)^k
        long draw = 1L + (long) Math.floor(Math.log(u) / Math.log(survive));
        return Math.min(draw, flexEndRound(nightLitCount, extinguishPercent));
    }

    /**
     * 收尾轮次：从 {@code nightLitCount} 盏灯开始，每轮「熄灭还亮着的 X%」——
     * 也就是 {@code n ← n - floor(n·X/100)}；<b>当这个数量不足 1 盏（floor 为 0）时，把剩下的全灭</b>。
     * 返回走完全灭总共需要几轮。这一轮之后灯全黑，不会再留零星几盏。
     *
     * <p>{@code nightLitCount <= 0}（没数出来 / 没有灯）时返回 {@code Long.MAX_VALUE}，即不收尾 ——
     * 宁可退回纯概率模型，也绝不能因为数不出灯数就把全场灭掉。
     */
    public static long flexEndRound(int nightLitCount, int extinguishPercent) {
        if (extinguishPercent <= 0) {
            return Long.MAX_VALUE;
        }
        if (extinguishPercent >= 100) {
            return 1L;
        }
        if (nightLitCount <= 0) {
            return Long.MAX_VALUE;
        }
        long n = nightLitCount;
        long round = 0;
        while (n > 0) {
            long killed = n * extinguishPercent / 100L;    // 整除（地板）；不足 1 盏 ⇒ 0
            round++;
            if (killed <= 0) {
                break;                                      // 小数 ⇒ 剩下的全灭
            }
            n -= killed;
        }
        return Math.max(1L, round);
    }

    /**
     * 给定「距本块第一次亮灯」的时间（tick，可为负），返回当前轮次。
     *
     * <p>第 k 轮（k≥1）的起点 = 前 k 个间隔之和，第 i 个间隔 = {@code Y + (i-1)·X}（夹到 ≥1 tick）。
     * 于是 {@code grow=5, Y=60} ⇒ 间隔 60、65、70…；{@code grow=-5} ⇒ 60、55、50…
     */
    public static int flexRoundAt(long sinceFirstLight, long firstIntervalTicks, long growTicks) {
        if (sinceFirstLight <= 0L) {
            return 0;
        }
        long acc = 0L;
        long interval = Math.max(1L, firstIntervalTicks);
        int round = 0;
        while (true) {
            long step = Math.max(1L, interval);
            if (acc + step > sinceFirstLight) {
                return round;
            }
            acc += step;
            round++;
            // 间隔已夹到 1 且不再增长 ⇒ 之后每轮恒 1 tick，直接线性求解，避免长夜里的空转
            if (step <= 1L && growTicks <= 0L) {
                long remaining = sinceFirstLight - acc;
                return round + (int) Math.min(Integer.MAX_VALUE, remaining);
            }
            if (round >= MAX_FLEX_ROUNDS) {
                return round;
            }
            interval += growTicks;
        }
    }

    /**
     * 本块固定的偏差（tick），落在 {@code [-jitter, +jitter]} 闭区间。
     * 让各块的重分配时刻错开，不会全世界同一瞬间一起变。{@code jitter <= 0} 时恒为 0。
     */
    public static long flexJitterTicks(BlockPos pos, long jitter) {
        if (jitter <= 0L) {
            return 0L;
        }
        // Hash.range 返回 [0, bound] 闭区间（bound+1 个取值）：要 [-j, +j]（2j+1 个取值）=> bound = 2j
        long span = 2L * jitter;
        return Hash.range(pos.asLong(), FLEX_JITTER_SALT, span) - jitter;
    }

    /** (坐标, 第几晚) → 充分散列的 64 位值。 */
    private static long nightHash(BlockPos pos, long nightIndex) {
        // 等价于 splitmix(nightIndex * 0x9E3779B97F4A7C15L + pos.asLong())，抽到 Hash 里共用
        return Hash.of(pos.asLong(), nightIndex);
    }

    /** (坐标, 夜晚编号, 轮次) → 充分散列的 64 位值；第 0 轮退化成 {@link #nightHash}。 */
    private static long roundHash(BlockPos pos, long nightIndex, int round) {
        if (round <= 0) {
            return nightHash(pos, nightIndex);
        }
        long roundSalt = Hash.mix(nightIndex * 0x9E3779B97F4A7C15L + FLEX_ROUND_SALT + round);
        return Hash.of(pos.asLong(), roundSalt);
    }

    /** splitmix64 的三步混合（和 {@link Hash#mix} 是同一份实现）。 */
    private static long splitmix(long value) {
        return Hash.mix(value);
    }
}
