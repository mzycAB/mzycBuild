package com.mzyc.build;

import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.PersistentState;

/**
 * 灯光方块的世界级设置，随存档保存（`<存档>/data/mzycbuild_light.dat`）。
 *
 * <p>设置分成两组，各自对应一条指令：
 *
 * <p><b>黄 / 通用（/light …）</b>
 * <ul>
 *   <li>{@code /light percent X} —— 夜晚点亮比例（%，默认 30）</li>
 *   <li>{@code /light light X} —— 灯光亮度（0~15，和原版光源方块同一套语义，默认 12）</li>
 *   <li>{@code /light delay Y} —— 点亮延迟上限（秒，实际延迟在 0~Y 秒之间随机，默认 60）</li>
 * </ul>
 *
 * <p><b>红灯（/hlight …）</b> —— 全部支持 2 位小数，内部按「百分之一秒」存整数
 * <ul>
 *   <li>{@code /hlight time on X} —— 亮起时间（一盏灯亮多久，默认 2.00 秒）</li>
 *   <li>{@code /hlight time off X} —— 熄灭时间（一盏灯灭多久，默认 2.00 秒）</li>
 *   <li>{@code /hlight slow on X} —— 灭→亮渐变时间（默认 0.10 秒）</li>
 *   <li>{@code /hlight slow off X} —— 亮→灭渐变时间（默认 0.10 秒）</li>
 *   <li>{@code /hlight flex on|off [X]} —— 是否「错开」每块红方块的亮/灭循环起点 + 随机错开时长（默认关 / 4.00 秒）</li>
 * </ul>
 *
 * <p>红块的「一个完整周期」= 亮起时间 + 熄灭时间，默认 2.00 + 2.00 = 4.00 秒。
 * 渐变是**含在**亮/灭时长之内的：亮起阶段的头 {@code slow on} 秒由暗升到满，
 * 熄灭阶段的头 {@code slow off} 秒由满降到暗，其余时间维持满亮 / 全灭。
 *
 * <p><b>总开关</b>
 * <ul>
 *   <li>{@code /light on|off} —— 黄灯总开关（默认开）</li>
 *   <li>{@code /light h on|off} —— 红灯总开关（默认开）</li>
 *   <li>{@code /light -f on|off} —— 「全灭」：关掉后黄 / 红一律不亮，无视其它一切设置，直到 {@code /light -f on}</li>
 * </ul>
 *
 * <p><b>黄灯随机重分配（{@code /flex …}）</b>
 * <ul>
 *   <li>{@code /flex on time Y [percent X]} —— 入夜第一次亮灯之后，每 Y 秒重新洗牌一次：
 *       给了 {@code percent X} ⇒ 在「本晚被点亮」的灯里每轮熄灭**当前还亮着**的 X%
 *       （只灭不亮、越点越暗；剩得不够一盏时一次全灭）；
 *       没给 ⇒ 对全部黄灯按 {@code /light percent} 重新分配点亮 / 熄灭（有灭也有亮）</li>
 *   <li>{@code /flex off} —— 关掉重分配（一整夜只掷一次骰）</li>
 *   <li>{@code /flex flex X} —— 给每块方块一个固定偏差时间（±X 秒），避免所有方块同一瞬间一起变</li>
 *   <li>{@code /flex grow X} —— 每轮间隔的递增量：第 k 轮间隔 = Y + (k-1)·X（可为负 ⇒ 间隔逐轮变短）</li>
 * </ul>
 *
 * <p>第一次加入模组、或新开一个存档时，各项都取各自的默认值；
 * 老存档里缺哪一项就补哪一项的默认值（按 key 分别判断，互不影响）。
 *
 * <p>固定存在<b>主世界</b>的归档里，所以主世界 / 下界 / 末地共用同一份设置，
 * 不会出现「换个维度设置就变了」。
 */
public class LightConfig extends PersistentState {
    // ---------------------------------------------------------------- 黄 / 通用：默认值与上下限

    /** 第一次加入模组时的夜晚点亮比例（%）。 */
    public static final int DEFAULT_PERCENT = 30;
    /** 第一次加入模组时的灯光亮度。 */
    public static final int DEFAULT_LIGHT_LEVEL = 12;
    /** 第一次加入模组时的点亮延迟上限（秒）。 */
    public static final int DEFAULT_DELAY_SECONDS = 60;

    public static final int MIN_PERCENT = 0;
    public static final int MAX_PERCENT = 100;

    /** 和原版 `minecraft:light` 的 level 属性一致：0~15。 */
    public static final int MIN_LIGHT_LEVEL = 0;
    public static final int MAX_LIGHT_LEVEL = 15;

    /** 上限放到 600 秒；夜晚本身只有 500 秒，超过 500 的延迟等于「那晚不亮」。 */
    public static final int MIN_DELAY_SECONDS = 0;
    public static final int MAX_DELAY_SECONDS = 600;

    // ---------------------------------------------------------------- 红灯时间单位

    /** 1 tick = 0.05 秒 = 5 个百分之一秒。 */
    public static final int CENTIS_PER_TICK = 5;

    /** 红灯所有「小数秒」参数的统一上限：600.00 秒。 */
    public static final int MAX_RED_CENTIS = 60000;

    // ------------------------------------------------- 红灯亮起 / 熄灭时间（/hlight time on|off X）

    /** 默认亮起时间 2.00 秒（一盏灯亮多久）。 */
    public static final int DEFAULT_RED_TIME_ON_CENTIS = 200;
    /** 默认熄灭时间 2.00 秒（一盏灯灭多久）。 */
    public static final int DEFAULT_RED_TIME_OFF_CENTIS = 200;

    /**
     * 亮/灭时长的下限。
     *
     * <p>允许 0：{@code time on 0} = 永远不亮、{@code time off 0} = 永远亮着，
     * 这两个极端有时正是用户要的（比如先摆一排「常亮」的红灯）。
     */
    public static final int MIN_RED_TIME_CENTIS = 0;

    // ------------------------------------------------- 红灯明暗渐变（/hlight slow on|off X）

    /** 默认灭→亮渐变 0.10 秒（= 2 tick，和原版观感一致）。 */
    public static final int DEFAULT_RED_SLOW_ON_CENTIS = 10;
    /** 默认亮→灭渐变 0.10 秒。 */
    public static final int DEFAULT_RED_SLOW_OFF_CENTIS = 10;

    /** 渐变下限 0.00 = 硬开关（不做渐变），所以允许 0。 */
    public static final int MIN_RED_SLOW_CENTIS = 0;

    // ------------------------------------------------- 红灯随机错开（/hlight flex on|off X）

    /**
     * 红灯「错开」的随机时长上限（百分之一秒），默认 4.00 秒。
     *
     * <p>开启后每块红方块按自己的坐标得到一个**固定**（确定性散列、跨重启不变）的相位偏移，
     * 偏移量落在 [0, X]。于是它们不再全世界齐亮齐灭，而是像城市灯火那样各自闪。
     * 关闭时偏移恒为 0，回到「全世界同步」。
     *
     * <p>偏移上限会被夹到一个完整周期内（见 {@code RedLightBlockEntity.desiredLevel}）——
     * 否则偏移绕圈之后又会和别的方块对齐，白错开。默认周期 4.00 秒，所以默认给 4.00。
     */
    public static final int DEFAULT_RED_FLEX_CENTIS = 400;

    public static final int MIN_RED_FLEX_CENTIS = 0;

    /** 开关默认**关**（保持「全世界同步闪烁」）。 */
    public static final boolean DEFAULT_RED_FLEX_ON = false;

    // ------------------------------------------------- 总开关（/light on|off、/light h on|off、/light -f on|off）

    /** 黄灯总开关默认开。 */
    public static final boolean DEFAULT_YELLOW_ON = true;
    /** 红灯总开关默认开。 */
    public static final boolean DEFAULT_RED_ON = true;
    /** 「全灭」默认关（关掉后黄/红一律不亮，无论其它设置）。 */
    public static final boolean DEFAULT_FORCE_OFF = false;

    // ------------------------------------------------- 黄灯随机重分配（/flex …）

    /** 默认重分配间隔 60.00 秒。 */
    public static final int DEFAULT_YELLOW_FLEX_TIME_CENTIS = 6000;
    /** 间隔下限 0.05 秒（= 1 tick）。 */
    public static final int MIN_YELLOW_FLEX_TIME_CENTIS = 5;

    /** {@code percent} 未指定时的哨兵值（表示沿用 {@code /light percent} 那条比例）。 */
    public static final int YELLOW_FLEX_PERCENT_UNSET = -1;

    /** 偏差 / 递增默认 0。 */
    public static final int DEFAULT_YELLOW_FLEX_JITTER_CENTIS = 0;
    public static final int DEFAULT_YELLOW_FLEX_GROW_CENTIS = 0;
    /** 偏差下限 0（= 不偏差）。 */
    public static final int MIN_YELLOW_FLEX_JITTER_CENTIS = 0;
    /** 递增可为负（间隔逐次变短）。 */
    public static final int MIN_YELLOW_FLEX_GROW_CENTIS = -60000;

    /** 是否开启重分配，默认关。 */
    public static final boolean DEFAULT_YELLOW_FLEX_ON = false;

    // ---------------------------------------------------------------- 持久化样板

    private static final String STATE_ID = "mzycbuild_light";
    private static final String KEY_PERCENT = "litPercent";
    private static final String KEY_LIGHT_LEVEL = "lightLevel";
    private static final String KEY_DELAY_SECONDS = "delaySeconds";
    private static final String KEY_RED_TIME_ON_CENTIS = "redTimeOnCentis";
    private static final String KEY_RED_TIME_OFF_CENTIS = "redTimeOffCentis";
    private static final String KEY_RED_SLOW_ON_CENTIS = "redSlowOnCentis";
    private static final String KEY_RED_SLOW_OFF_CENTIS = "redSlowOffCentis";
    private static final String KEY_RED_FLEX_ON = "redFlexOn";
    private static final String KEY_RED_FLEX_CENTIS = "redFlexCentis";

    private static final String KEY_YELLOW_ON = "yellowOn";
    private static final String KEY_RED_ON = "redOn";
    private static final String KEY_FORCE_OFF = "forceOff";
    private static final String KEY_YELLOW_FLEX_ON = "yellowFlexOn";
    private static final String KEY_YELLOW_FLEX_TIME_CENTIS = "yellowFlexTimeCentis";
    private static final String KEY_YELLOW_FLEX_PERCENT = "yellowFlexPercent";
    private static final String KEY_YELLOW_FLEX_JITTER_CENTIS = "yellowFlexJitterCentis";
    private static final String KEY_YELLOW_FLEX_GROW_CENTIS = "yellowFlexGrowCentis";

    // 旧版 key（v1.0.1204 之前）：只在迁移时读，不再写。
    private static final String LEGACY_KEY_HALF_PERIOD_CENTIS = "redHalfPeriodCentis";
    private static final String LEGACY_KEY_RANDOM_DELAY_ON = "redRandomDelayOn";
    private static final String LEGACY_KEY_RANDOM_DELAY_CENTIS = "redRandomDelayCentis";

    private static final PersistentState.Type<LightConfig> TYPE = new PersistentState.Type<>(
            LightConfig::new, LightConfig::fromNbt, DataFixTypes.LEVEL);

    // ---------------------------------------------------------------- 实际数据

    private int percent = DEFAULT_PERCENT;
    private int lightLevel = DEFAULT_LIGHT_LEVEL;
    private int delaySeconds = DEFAULT_DELAY_SECONDS;
    private int redTimeOnCentis = DEFAULT_RED_TIME_ON_CENTIS;
    private int redTimeOffCentis = DEFAULT_RED_TIME_OFF_CENTIS;
    private int redSlowOnCentis = DEFAULT_RED_SLOW_ON_CENTIS;
    private int redSlowOffCentis = DEFAULT_RED_SLOW_OFF_CENTIS;
    private boolean redFlexOn = DEFAULT_RED_FLEX_ON;
    private int redFlexCentis = DEFAULT_RED_FLEX_CENTIS;

    private boolean yellowOn = DEFAULT_YELLOW_ON;
    private boolean redOn = DEFAULT_RED_ON;
    private boolean forceOff = DEFAULT_FORCE_OFF;
    private boolean yellowFlexOn = DEFAULT_YELLOW_FLEX_ON;
    private int yellowFlexTimeCentis = DEFAULT_YELLOW_FLEX_TIME_CENTIS;
    private int yellowFlexPercent = YELLOW_FLEX_PERCENT_UNSET;
    private int yellowFlexJitterCentis = DEFAULT_YELLOW_FLEX_JITTER_CENTIS;
    private int yellowFlexGrowCentis = DEFAULT_YELLOW_FLEX_GROW_CENTIS;

    // ---------------------------------------------------------------- 取值

    public int getPercent() {
        return percent;
    }

    public int getLightLevel() {
        return lightLevel;
    }

    public int getDelaySeconds() {
        return delaySeconds;
    }

    public int getRedTimeOnCentis() {
        return redTimeOnCentis;
    }

    public int getRedTimeOffCentis() {
        return redTimeOffCentis;
    }

    public int getRedSlowOnCentis() {
        return redSlowOnCentis;
    }

    public int getRedSlowOffCentis() {
        return redSlowOffCentis;
    }

    public int getRedFlexCentis() {
        return redFlexCentis;
    }

    public boolean isRedFlexOn() {
        return redFlexOn;
    }

    /** 百分之一秒 → tick（向下取整到最近 1 tick；0.05 秒正好 1 tick）。 */
    public static long centisToTicks(int centis) {
        return Math.max(0L, Math.round(centis / (double) CENTIS_PER_TICK));
    }

    public long getRedTimeOnTicks() {
        return centisToTicks(redTimeOnCentis);
    }

    public long getRedTimeOffTicks() {
        return centisToTicks(redTimeOffCentis);
    }

    public long getRedSlowOnTicks() {
        return centisToTicks(redSlowOnCentis);
    }

    public long getRedSlowOffTicks() {
        return centisToTicks(redSlowOffCentis);
    }

    public long getRedFlexTicks() {
        return centisToTicks(redFlexCentis);
    }

    /** 一个完整闪烁周期（tick）= 亮起 + 熄灭。至少 1，避免除零。 */
    public long getRedPeriodTicks() {
        return Math.max(1L, getRedTimeOnTicks() + getRedTimeOffTicks());
    }

    // ---- 总开关

    public boolean isYellowOn() {
        return yellowOn;
    }

    public boolean isRedOn() {
        return redOn;
    }

    /** 「全灭」：为 true 时黄 / 红一律不亮，无视其它一切设置。 */
    public boolean isForceOff() {
        return forceOff;
    }

    // ---- 黄灯随机重分配

    public boolean isYellowFlexOn() {
        return yellowFlexOn;
    }

    public int getYellowFlexTimeCentis() {
        return yellowFlexTimeCentis;
    }

    public long getYellowFlexTimeTicks() {
        return Math.max(1L, centisToTicks(yellowFlexTimeCentis));
    }

    /** {@code /flex on time Y percent X} 的 X；{@value #YELLOW_FLEX_PERCENT_UNSET} 表示未指定。 */
    public int getYellowFlexPercent() {
        return yellowFlexPercent;
    }

    public int getYellowFlexJitterCentis() {
        return yellowFlexJitterCentis;
    }

    public long getYellowFlexJitterTicks() {
        return centisToTicks(yellowFlexJitterCentis);
    }

    public int getYellowFlexGrowCentis() {
        return yellowFlexGrowCentis;
    }

    /** 每轮间隔的递增量（tick），可为负。 */
    public long getYellowFlexGrowTicks() {
        return Math.round(yellowFlexGrowCentis / (double) CENTIS_PER_TICK);
    }

    /** 把百分之一秒格式化成「2.00」「0.50」这样的两位小数字符串（查询指令只回值）。 */
    public static String formatCentis(int centis) {
        return String.format(java.util.Locale.ROOT, "%d.%02d", centis / 100, centis % 100);
    }

    // ---------------------------------------------------------------- 写入

    /** 写入并夹到 0~100；只有真的变了才标脏，避免每 tick 触发存档写。 */
    public void setPercent(int value) {
        percent = setClamped(percent, value, MIN_PERCENT, MAX_PERCENT);
    }

    /** 写入并夹到 0~15。 */
    public void setLightLevel(int value) {
        lightLevel = setClamped(lightLevel, value, MIN_LIGHT_LEVEL, MAX_LIGHT_LEVEL);
    }

    /** 写入并夹到 0~600。 */
    public void setDelaySeconds(int value) {
        delaySeconds = setClamped(delaySeconds, value, MIN_DELAY_SECONDS, MAX_DELAY_SECONDS);
    }

    /** 写入并夹到 0.00~600.00 秒（以百分之一秒为单位）。 */
    public void setRedTimeOnCentis(int value) {
        redTimeOnCentis = setClamped(redTimeOnCentis, value, MIN_RED_TIME_CENTIS, MAX_RED_CENTIS);
    }

    public void setRedTimeOffCentis(int value) {
        redTimeOffCentis = setClamped(redTimeOffCentis, value, MIN_RED_TIME_CENTIS, MAX_RED_CENTIS);
    }

    public void setRedSlowOnCentis(int value) {
        redSlowOnCentis = setClamped(redSlowOnCentis, value, MIN_RED_SLOW_CENTIS, MAX_RED_CENTIS);
    }

    public void setRedSlowOffCentis(int value) {
        redSlowOffCentis = setClamped(redSlowOffCentis, value, MIN_RED_SLOW_CENTIS, MAX_RED_CENTIS);
    }

    /** 写入并夹到 0.00~600.00 秒（0 = 偏移恒为 0，效果等同关闭）。 */
    public void setRedFlexCentis(int value) {
        redFlexCentis = setClamped(redFlexCentis, value, MIN_RED_FLEX_CENTIS, MAX_RED_CENTIS);
    }

    /** 开关「错开」；值真的变了才标脏。 */
    public void setRedFlexOn(boolean value) {
        if (value != redFlexOn) {
            redFlexOn = value;
            markDirty();
        }
    }

    // ---- 总开关

    public void setYellowOn(boolean value) {
        yellowOn = setClamped(yellowOn, value);
    }

    public void setRedOn(boolean value) {
        redOn = setClamped(redOn, value);
    }

    public void setForceOff(boolean value) {
        forceOff = setClamped(forceOff, value);
    }

    // ---- 黄灯随机重分配

    public void setYellowFlexOn(boolean value) {
        yellowFlexOn = setClamped(yellowFlexOn, value);
    }

    public void setYellowFlexTimeCentis(int value) {
        yellowFlexTimeCentis = setClamped(yellowFlexTimeCentis, value,
                MIN_YELLOW_FLEX_TIME_CENTIS, MAX_RED_CENTIS);
    }

    /** 写入 {@code 0~100}；{@value #YELLOW_FLEX_PERCENT_UNSET} = 恢复「未指定」。 */
    public void setYellowFlexPercent(int value) {
        yellowFlexPercent = setClamped(yellowFlexPercent, value,
                YELLOW_FLEX_PERCENT_UNSET, 100);
    }

    public void setYellowFlexJitterCentis(int value) {
        yellowFlexJitterCentis = setClamped(yellowFlexJitterCentis, value,
                MIN_YELLOW_FLEX_JITTER_CENTIS, MAX_RED_CENTIS);
    }

    /** 递增可为负，范围 {@value #MIN_YELLOW_FLEX_GROW_CENTIS} ~ {@value #MAX_RED_CENTIS}（百分之一秒）。 */
    public void setYellowFlexGrowCentis(int value) {
        yellowFlexGrowCentis = setClamped(yellowFlexGrowCentis, value,
                MIN_YELLOW_FLEX_GROW_CENTIS, MAX_RED_CENTIS);
    }

    private boolean setClamped(boolean current, boolean value) {
        if (value != current) {
            markDirty();
        }
        return value;
    }

    private int setClamped(int current, int value, int min, int max) {
        int clamped = MathHelper.clamp(value, min, max);
        if (clamped != current) {
            markDirty();
        }
        return clamped;
    }

    // ---------------------------------------------------------------- 读写

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putInt(KEY_PERCENT, percent);
        nbt.putInt(KEY_LIGHT_LEVEL, lightLevel);
        nbt.putInt(KEY_DELAY_SECONDS, delaySeconds);
        nbt.putInt(KEY_RED_TIME_ON_CENTIS, redTimeOnCentis);
        nbt.putInt(KEY_RED_TIME_OFF_CENTIS, redTimeOffCentis);
        nbt.putInt(KEY_RED_SLOW_ON_CENTIS, redSlowOnCentis);
        nbt.putInt(KEY_RED_SLOW_OFF_CENTIS, redSlowOffCentis);
        nbt.putBoolean(KEY_RED_FLEX_ON, redFlexOn);
        nbt.putInt(KEY_RED_FLEX_CENTIS, redFlexCentis);
        nbt.putBoolean(KEY_YELLOW_ON, yellowOn);
        nbt.putBoolean(KEY_RED_ON, redOn);
        nbt.putBoolean(KEY_FORCE_OFF, forceOff);
        nbt.putBoolean(KEY_YELLOW_FLEX_ON, yellowFlexOn);
        nbt.putInt(KEY_YELLOW_FLEX_TIME_CENTIS, yellowFlexTimeCentis);
        nbt.putInt(KEY_YELLOW_FLEX_PERCENT, yellowFlexPercent);
        nbt.putInt(KEY_YELLOW_FLEX_JITTER_CENTIS, yellowFlexJitterCentis);
        nbt.putInt(KEY_YELLOW_FLEX_GROW_CENTIS, yellowFlexGrowCentis);
        return nbt;
    }

    public static LightConfig fromNbt(NbtCompound nbt) {
        LightConfig config = new LightConfig();
        if (nbt.contains(KEY_PERCENT)) {
            config.percent = MathHelper.clamp(nbt.getInt(KEY_PERCENT), MIN_PERCENT, MAX_PERCENT);
        }
        if (nbt.contains(KEY_LIGHT_LEVEL)) {
            config.lightLevel = MathHelper.clamp(nbt.getInt(KEY_LIGHT_LEVEL), MIN_LIGHT_LEVEL, MAX_LIGHT_LEVEL);
        }
        if (nbt.contains(KEY_DELAY_SECONDS)) {
            config.delaySeconds = MathHelper.clamp(nbt.getInt(KEY_DELAY_SECONDS), MIN_DELAY_SECONDS, MAX_DELAY_SECONDS);
        }

        // ---- 红灯新字段（缺哪个补哪个的默认值）
        if (nbt.contains(KEY_RED_TIME_ON_CENTIS)) {
            config.redTimeOnCentis = MathHelper.clamp(nbt.getInt(KEY_RED_TIME_ON_CENTIS),
                    MIN_RED_TIME_CENTIS, MAX_RED_CENTIS);
        }
        if (nbt.contains(KEY_RED_TIME_OFF_CENTIS)) {
            config.redTimeOffCentis = MathHelper.clamp(nbt.getInt(KEY_RED_TIME_OFF_CENTIS),
                    MIN_RED_TIME_CENTIS, MAX_RED_CENTIS);
        }
        if (nbt.contains(KEY_RED_SLOW_ON_CENTIS)) {
            config.redSlowOnCentis = MathHelper.clamp(nbt.getInt(KEY_RED_SLOW_ON_CENTIS),
                    MIN_RED_SLOW_CENTIS, MAX_RED_CENTIS);
        }
        if (nbt.contains(KEY_RED_SLOW_OFF_CENTIS)) {
            config.redSlowOffCentis = MathHelper.clamp(nbt.getInt(KEY_RED_SLOW_OFF_CENTIS),
                    MIN_RED_SLOW_CENTIS, MAX_RED_CENTIS);
        }
        if (nbt.contains(KEY_RED_FLEX_ON)) {
            config.redFlexOn = nbt.getBoolean(KEY_RED_FLEX_ON);
        }
        if (nbt.contains(KEY_RED_FLEX_CENTIS)) {
            config.redFlexCentis = MathHelper.clamp(nbt.getInt(KEY_RED_FLEX_CENTIS),
                    MIN_RED_FLEX_CENTIS, MAX_RED_CENTIS);
        }

        // ---- 总开关（布尔必须用 contains 判断，否则老存档会被读成 false）
        if (nbt.contains(KEY_YELLOW_ON)) {
            config.yellowOn = nbt.getBoolean(KEY_YELLOW_ON);
        }
        if (nbt.contains(KEY_RED_ON)) {
            config.redOn = nbt.getBoolean(KEY_RED_ON);
        }
        if (nbt.contains(KEY_FORCE_OFF)) {
            config.forceOff = nbt.getBoolean(KEY_FORCE_OFF);
        }

        // ---- 黄灯随机重分配
        if (nbt.contains(KEY_YELLOW_FLEX_ON)) {
            config.yellowFlexOn = nbt.getBoolean(KEY_YELLOW_FLEX_ON);
        }
        if (nbt.contains(KEY_YELLOW_FLEX_TIME_CENTIS)) {
            config.yellowFlexTimeCentis = MathHelper.clamp(nbt.getInt(KEY_YELLOW_FLEX_TIME_CENTIS),
                    MIN_YELLOW_FLEX_TIME_CENTIS, MAX_RED_CENTIS);
        }
        if (nbt.contains(KEY_YELLOW_FLEX_PERCENT)) {
            config.yellowFlexPercent = MathHelper.clamp(nbt.getInt(KEY_YELLOW_FLEX_PERCENT),
                    YELLOW_FLEX_PERCENT_UNSET, 100);
        }
        if (nbt.contains(KEY_YELLOW_FLEX_JITTER_CENTIS)) {
            config.yellowFlexJitterCentis = MathHelper.clamp(nbt.getInt(KEY_YELLOW_FLEX_JITTER_CENTIS),
                    MIN_YELLOW_FLEX_JITTER_CENTIS, MAX_RED_CENTIS);
        }
        if (nbt.contains(KEY_YELLOW_FLEX_GROW_CENTIS)) {
            config.yellowFlexGrowCentis = MathHelper.clamp(nbt.getInt(KEY_YELLOW_FLEX_GROW_CENTIS),
                    MIN_YELLOW_FLEX_GROW_CENTIS, MAX_RED_CENTIS);
        }

        // ---- 旧存档迁移：把「半周期」摊成 亮=灭=半周期，「随机延迟」搬进 flex
        if (!nbt.contains(KEY_RED_TIME_ON_CENTIS) && nbt.contains(LEGACY_KEY_HALF_PERIOD_CENTIS)) {
            int half = MathHelper.clamp(nbt.getInt(LEGACY_KEY_HALF_PERIOD_CENTIS),
                    MIN_RED_TIME_CENTIS, MAX_RED_CENTIS);
            config.redTimeOnCentis = half;
            if (!nbt.contains(KEY_RED_TIME_OFF_CENTIS)) {
                config.redTimeOffCentis = half;
            }
        }
        if (!nbt.contains(KEY_RED_FLEX_ON) && nbt.contains(LEGACY_KEY_RANDOM_DELAY_ON)) {
            config.redFlexOn = nbt.getBoolean(LEGACY_KEY_RANDOM_DELAY_ON);
        }
        if (!nbt.contains(KEY_RED_FLEX_CENTIS) && nbt.contains(LEGACY_KEY_RANDOM_DELAY_CENTIS)) {
            config.redFlexCentis = MathHelper.clamp(nbt.getInt(LEGACY_KEY_RANDOM_DELAY_CENTIS),
                    MIN_RED_FLEX_CENTIS, MAX_RED_CENTIS);
        }
        return config;
    }

    /** 取主世界归档里的那一份；没有就按默认值新建。 */
    public static LightConfig get(ServerWorld world) {
        return world.getServer().getOverworld()
                .getPersistentStateManager()
                .getOrCreate(TYPE, STATE_ID);
    }
}
