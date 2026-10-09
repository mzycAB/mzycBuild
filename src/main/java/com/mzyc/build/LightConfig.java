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
 *   <li>{@code /light -f on|off} / {@code /lightall on|off} —— 「全灭」：关掉后黄 / 红一律不亮，
 *       无视其它一切设置（含 {@code /flexable}），直到重新打开</li>
 *   <li>{@code /flexable on|off} —— 夜里黄灯脱离一切设定、纯随机乱闪（默认关）</li>
 *   <li>{@code /light define default} —— 把上面所有设置一次性还原成出厂默认</li>
 * </ul>
 *
 * <p>出厂默认（= {@code /light define default} 写进去的那一套 = 第一次给存档装上模组时的默认）：
 * 比例 20%、亮度 12、延迟 60 秒；红灯亮 2.00 / 灭 2.00 秒、两端渐变各 0.50 秒、错开上限 1.00 秒且<b>开</b>；
 * 黄灯重分配<b>开</b>且是<b>熄灭模式</b>（间隔 60.00 秒、熄灭比例 10、偏差 2.00 秒、递增 0）——
 * 也就是夜里灯亮起来之后，每 60 秒自动灭掉 10% 还亮着的；{@code flexable} 关。
 *
 * <p><b>黄灯随机重分配（{@code /flex …}）</b> —— 两个正交的开关：功能是否生效 + 方向
 * <ul>
 *   <li>{@code /flex off time Y [percent X]} —— <b>熄灭模式</b>（并且把功能打开）：
 *       从「入夜第一次亮灯」起每 Y 秒，在「本晚被点亮」的灯里熄灭<b>当前还亮着</b>的 X%
 *       （只灭不亮、越点越暗；剩得不够一盏时一次全灭）。X 缺省 = 沿用已存的（出厂 10）</li>
 *   <li>{@code /flex on time Y [percent X]} —— <b>点亮模式</b>（并且把功能打开）：
 *       每 Y 秒对<b>全部</b>黄灯按 {@code /light percent} 重新分配点亮 / 熄灭（有灭也有亮）</li>
 *   <li>{@code /flex all off} —— 彻底关闭 flex 功能（一整夜只掷一次骰）；{@code /flex all on} 重新打开</li>
 *   <li>{@code /flex flex X} —— 给每块方块一个固定偏差时间（±X 秒），避免所有方块同一瞬间一起变</li>
 *   <li>{@code /flex grow X} —— 每轮间隔的递增量：第 k 轮间隔 = Y + (k-1)·X（可为负 ⇒ 间隔逐轮变短）</li>
 * </ul>
 *
 * <p>第一次加入模组、或新开一个存档时，各项都取各自的默认值；
 * 老存档里缺哪一项就补哪一项的默认值（按 key 分别判断，互不影响）。
 * 老存档的旧 flex（只有「开关 + 是否给了 percent」）会自动迁移成新模型：
 * 旧的「percent 没给」= 重新分配 ⇒ 点亮模式；「percent 给了」= 只灭不亮 ⇒ 熄灭模式。
 *
 * <p>固定存在<b>主世界</b>的归档里（<b>存档文件夹内部</b>：{@code <存档>/data/mzycbuild_light.dat}，
 * 服务端数据，跟着存档走、与客户端无关），所以主世界 / 下界 / 末地共用同一份设置，
 * 也不会出现「换个客户端设置就变了」。
 */
public class LightConfig extends PersistentState {
    // ---------------------------------------------------------------- 黄 / 通用：默认值与上下限

    /** 第一次加入模组时的夜晚点亮比例（%）。 */
    public static final int DEFAULT_PERCENT = 20;
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

    /** 默认灭→亮渐变 0.50 秒（{@code /hlight slow on 0.5}）。 */
    public static final int DEFAULT_RED_SLOW_ON_CENTIS = 50;
    /** 默认亮→灭渐变 0.50 秒（和亮起侧一样，合起来就是「亮灭切换渐变 = 0.5s」）。 */
    public static final int DEFAULT_RED_SLOW_OFF_CENTIS = 50;

    /** 渐变下限 0.00 = 硬开关（不做渐变），所以允许 0。 */
    public static final int MIN_RED_SLOW_CENTIS = 0;

    // ------------------------------------------------- 红灯随机错开（/hlight flex on|off X）

    /**
     * 红灯「错开」的随机时长上限（百分之一秒），默认 1.00 秒（{@code /hlight flex on 1}）。
     *
     * <p>开启后每块红方块按自己的坐标得到一个**固定**（确定性散列、跨重启不变）的相位偏移，
     * 偏移量落在 [0, X]。于是它们不再全世界齐亮齐灭，而是像城市灯火那样各自闪。
     * 关闭时偏移恒为 0，回到「全世界同步」。
     *
     * <p>偏移上限会被夹到一个完整周期内（见 {@code RedLightBlockEntity.desiredLevel}）——
     * 否则偏移绕圈之后又会和别的方块对齐，白错开。
     */
    public static final int DEFAULT_RED_FLEX_CENTIS = 100;

    public static final int MIN_RED_FLEX_CENTIS = 0;

    /** 开关默认**开**（每块红方块各自错开闪，不再齐亮齐灭）。 */
    public static final boolean DEFAULT_RED_FLEX_ON = true;

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

    /** {@code percent} 未指定时的哨兵值（旧存档迁移用；现在都会落成 {@value #DEFAULT_YELLOW_FLEX_PERCENT}）。 */
    public static final int YELLOW_FLEX_PERCENT_UNSET = -1;

    /** 出厂默认的熄灭比例（{@code /flex off time 60 percent 10} 里的 10）。 */
    public static final int DEFAULT_YELLOW_FLEX_PERCENT = 10;

    /** 偏差 / 递增默认：偏差 2.00 秒（{@code /flex flex 2}），递增 0（{@code /flex grow off}）。 */
    public static final int DEFAULT_YELLOW_FLEX_JITTER_CENTIS = 200;
    public static final int DEFAULT_YELLOW_FLEX_GROW_CENTIS = 0;
    /** 偏差下限 0（= 不偏差）。 */
    public static final int MIN_YELLOW_FLEX_JITTER_CENTIS = 0;
    /** 递增可为负（间隔逐次变短）。 */
    public static final int MIN_YELLOW_FLEX_GROW_CENTIS = -60000;

    // ---- 两个正交的开关：功能是否生效 + 生效时往哪个方向走 ----
    //
    // 用户 2026-10-09 定稿的语义：
    //   /flex off  = 熄灭模式（生效）：每 Y 秒在「本晚被点亮」的灯里熄灭当前还亮着的 X%
    //   /flex on   = 点亮模式（生效）：每 Y 秒对全部黄灯按 /light percent 重新分配点亮 / 熄灭
    //   /flex all off = 彻底关闭 flex 功能（整夜只掷一次骰）
    // 所以「生效与否」和「方向」必须分开存，否则 /flex all off 再 /flex all on 就丢了方向。

    /** 方向：熄灭模式（{@code /flex off}）。 */
    public static final int FLEX_DIR_EXTINGUISH = 1;
    /** 方向：点亮模式（{@code /flex on}）。 */
    public static final int FLEX_DIR_LIGHT = 2;

    /** 是否开启重分配，默认**开**（天黑后按下面的方向逐渐变化）。 */
    public static final boolean DEFAULT_YELLOW_FLEX_ON = true;
    /** 默认方向：熄灭模式。 */
    public static final int DEFAULT_YELLOW_FLEX_DIR = FLEX_DIR_EXTINGUISH;

    // ------------------------------------------------- 「纯随机乱闪」（/flexable …）

    /**
     * {@code /flexable on} 默认关。
     *
     * <p>开启后，<b>只要天黑了</b>，黄灯的亮灭就<b>彻底脱离</b> {@code /light percent}、
     * {@code /light delay}、{@code /flex …}、{@code /light light} 这套设定，改由每块方块
     * 自己按坐标散列出的一段随机节奏独立乱闪（见 {@code LightBlockEntity.flexableLit}）。
     * 只有 {@code /lightall off}（= {@code /light -f off}）和 {@code /light off} 这两个总闸
     * 仍然压得住它。
     */
    public static final boolean DEFAULT_YELLOW_FLEXABLE_ON = false;

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
    private static final String KEY_YELLOW_FLEX_DIR = "yellowFlexDir";
    private static final String KEY_YELLOW_FLEX_TIME_CENTIS = "yellowFlexTimeCentis";
    private static final String KEY_YELLOW_FLEX_PERCENT = "yellowFlexPercent";
    private static final String KEY_YELLOW_FLEX_JITTER_CENTIS = "yellowFlexJitterCentis";
    private static final String KEY_YELLOW_FLEX_GROW_CENTIS = "yellowFlexGrowCentis";
    private static final String KEY_YELLOW_FLEXABLE_ON = "yellowFlexableOn";

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
    private int yellowFlexDir = DEFAULT_YELLOW_FLEX_DIR;
    private int yellowFlexTimeCentis = DEFAULT_YELLOW_FLEX_TIME_CENTIS;
    private int yellowFlexPercent = DEFAULT_YELLOW_FLEX_PERCENT;
    private int yellowFlexJitterCentis = DEFAULT_YELLOW_FLEX_JITTER_CENTIS;
    private int yellowFlexGrowCentis = DEFAULT_YELLOW_FLEX_GROW_CENTIS;
    private boolean yellowFlexableOn = DEFAULT_YELLOW_FLEXABLE_ON;

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

    /** 生效方向：{@value #FLEX_DIR_EXTINGUISH} = 熄灭模式，{@value #FLEX_DIR_LIGHT} = 点亮模式。 */
    public int getYellowFlexDir() {
        return yellowFlexDir;
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

    /** {@code /flexable on|off}：开启后夜里黄灯脱离一切设定、纯随机乱闪。 */
    public boolean isYellowFlexableOn() {
        return yellowFlexableOn;
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

    /** 开 / 关整个 flex 功能（{@code /flex all on|off}）；值真的变了才标脏。方向不受影响。 */
    public void setYellowFlexOn(boolean value) {
        yellowFlexOn = setClamped(yellowFlexOn, value);
    }

    /** 设置生效方向（{@code /flex off} = 熄灭、{@code /flex on} = 点亮），夹到两个合法值。 */
    public void setYellowFlexDir(int value) {
        yellowFlexDir = setClamped(yellowFlexDir,
                value == FLEX_DIR_LIGHT ? FLEX_DIR_LIGHT : FLEX_DIR_EXTINGUISH,
                FLEX_DIR_EXTINGUISH, FLEX_DIR_LIGHT);
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

    /** 开关「纯随机乱闪」；值真的变了才标脏。 */
    public void setYellowFlexableOn(boolean value) {
        yellowFlexableOn = setClamped(yellowFlexableOn, value);
    }

    /**
     * 把设置一次性还原成出厂默认值 —— 对应 {@code /light define default}。
     *
     * <p>这份默认值就是指令清单本身：
     * {@code light light 12} / {@code light delay 60} / {@code light percent 20}
     * / {@code hlight slow on 0.5} / {@code hlight time on 2} / {@code hlight flex on 1}
     * / {@code flex flex 2} / {@code flex off time 60 percent 10} / {@code flex grow off}
     * （{@code /flex off} = 熄灭模式<b>生效</b> ⇒ 夜里灯会每 60 秒灭掉 10% 还亮着的），
     * 外加黄灯 / 红灯两个分组总开关恢复为「开」、乱闪恢复为「关」。
     *
     * <p>这套值和<b>第一次给存档装上模组</b>时用的默认值完全一致（见上面各 {@code DEFAULT_*} 常量）。
     *
     * <p><b>刻意不碰 {@code /lightall}（= {@code /light -f}）那个「全灭」总闸</b>：
     * 它是绝对值最高的总闸，只认 {@code /lightall on}，不能被任何别的指令顺手解开。
     */
    public void resetToDefaults() {
        percent = DEFAULT_PERCENT;
        lightLevel = DEFAULT_LIGHT_LEVEL;
        delaySeconds = DEFAULT_DELAY_SECONDS;
        redTimeOnCentis = DEFAULT_RED_TIME_ON_CENTIS;
        redTimeOffCentis = DEFAULT_RED_TIME_OFF_CENTIS;
        redSlowOnCentis = DEFAULT_RED_SLOW_ON_CENTIS;
        redSlowOffCentis = DEFAULT_RED_SLOW_OFF_CENTIS;
        redFlexOn = DEFAULT_RED_FLEX_ON;
        redFlexCentis = DEFAULT_RED_FLEX_CENTIS;
        yellowOn = DEFAULT_YELLOW_ON;
        redOn = DEFAULT_RED_ON;
        // forceOff（全灭）：保留原状，见上面的说明
        yellowFlexOn = DEFAULT_YELLOW_FLEX_ON;
        yellowFlexDir = DEFAULT_YELLOW_FLEX_DIR;
        yellowFlexTimeCentis = DEFAULT_YELLOW_FLEX_TIME_CENTIS;
        yellowFlexPercent = DEFAULT_YELLOW_FLEX_PERCENT;
        yellowFlexJitterCentis = DEFAULT_YELLOW_FLEX_JITTER_CENTIS;
        yellowFlexGrowCentis = DEFAULT_YELLOW_FLEX_GROW_CENTIS;
        yellowFlexableOn = DEFAULT_YELLOW_FLEXABLE_ON;
        markDirty();
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
        nbt.putInt(KEY_YELLOW_FLEX_DIR, yellowFlexDir);
        nbt.putInt(KEY_YELLOW_FLEX_TIME_CENTIS, yellowFlexTimeCentis);
        nbt.putInt(KEY_YELLOW_FLEX_PERCENT, yellowFlexPercent);
        nbt.putInt(KEY_YELLOW_FLEX_JITTER_CENTIS, yellowFlexJitterCentis);
        nbt.putInt(KEY_YELLOW_FLEX_GROW_CENTIS, yellowFlexGrowCentis);
        nbt.putBoolean(KEY_YELLOW_FLEXABLE_ON, yellowFlexableOn);
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
        // 方向：新存档直接读；老存档没有这个 key ⇒ 按旧语义反推
        //   旧「percent 未指定(-1)」= 对全场重新分配（有灭也有亮）⇒ 点亮模式
        //   旧「percent 有值」        = 只灭不亮                    ⇒ 熄灭模式
        if (nbt.contains(KEY_YELLOW_FLEX_DIR)) {
            config.yellowFlexDir = MathHelper.clamp(nbt.getInt(KEY_YELLOW_FLEX_DIR),
                    FLEX_DIR_EXTINGUISH, FLEX_DIR_LIGHT);
        } else if (nbt.contains(KEY_YELLOW_FLEX_PERCENT)
                && nbt.getInt(KEY_YELLOW_FLEX_PERCENT) == YELLOW_FLEX_PERCENT_UNSET) {
            config.yellowFlexDir = FLEX_DIR_LIGHT;
        }
        if (nbt.contains(KEY_YELLOW_FLEX_TIME_CENTIS)) {
            config.yellowFlexTimeCentis = MathHelper.clamp(nbt.getInt(KEY_YELLOW_FLEX_TIME_CENTIS),
                    MIN_YELLOW_FLEX_TIME_CENTIS, MAX_RED_CENTIS);
        }
        if (nbt.contains(KEY_YELLOW_FLEX_PERCENT)) {
            int stored = MathHelper.clamp(nbt.getInt(KEY_YELLOW_FLEX_PERCENT),
                    YELLOW_FLEX_PERCENT_UNSET, 100);
            // 旧的「未指定(-1)」哨兵在熄灭模式下等于「永不熄灭」，会被当成功能坏了 ⇒ 落到默认 10
            config.yellowFlexPercent = stored == YELLOW_FLEX_PERCENT_UNSET
                    ? DEFAULT_YELLOW_FLEX_PERCENT : stored;
        }
        if (nbt.contains(KEY_YELLOW_FLEX_JITTER_CENTIS)) {
            config.yellowFlexJitterCentis = MathHelper.clamp(nbt.getInt(KEY_YELLOW_FLEX_JITTER_CENTIS),
                    MIN_YELLOW_FLEX_JITTER_CENTIS, MAX_RED_CENTIS);
        }
        if (nbt.contains(KEY_YELLOW_FLEX_GROW_CENTIS)) {
            config.yellowFlexGrowCentis = MathHelper.clamp(nbt.getInt(KEY_YELLOW_FLEX_GROW_CENTIS),
                    MIN_YELLOW_FLEX_GROW_CENTIS, MAX_RED_CENTIS);
        }
        if (nbt.contains(KEY_YELLOW_FLEXABLE_ON)) {
            config.yellowFlexableOn = nbt.getBoolean(KEY_YELLOW_FLEXABLE_ON);
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
