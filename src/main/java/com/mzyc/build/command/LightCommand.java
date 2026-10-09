package com.mzyc.build.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mzyc.build.LightConfig;
import com.mzyc.build.LightIndex;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 全部调节指令。
 *
 * <h2>黄 / 通用（{@code /light …}）</h2>
 * <ul>
 *   <li>{@code /light percent X} —— 夜晚点亮比例（%，0~100，默认 20）</li>
 *   <li>{@code /light light X} —— 灯光亮度（0~15，和原版光源方块同一套语义，默认 12）</li>
 *   <li>{@code /light nightdelay Y} —— <b>天黑</b>随机点亮秒数（0~600，默认 60；实际延迟在 0~Y 秒之间随机）。
 *       {@code /light delay Y} 是它的旧名，仍然可用（同一个字段）</li>
 *   <li>{@code /light daydelay Y} —— <b>天亮</b>随机熄灭秒数（0~600，默认 60）：
 *       天亮那一刻还亮着的灯，各自在 0~Y 秒内随机熄灭完，Y 秒内全黑（不再是天一亮就一起灭）</li>
 *   <li>{@code /light on|off} —— 黄灯总开关（默认开）</li>
 *   <li>{@code /light h on|off} —— 红灯总开关（默认开）</li>
 *   <li>{@code /light -f on|off} / {@code /light all on|off} / {@code /lightall on|off} /
 *       {@code /alllight on|off} —— 「全灭」：四者写同一个总闸，关掉后黄 / 红一律不发光，
 *       无视一切设置（含 {@code /flexable}），直到重新打开</li>
 *   <li>{@code /light define default} —— 把上面所有设置一次性还原成出厂默认（见 {@link LightConfig#resetToDefaults()}）</li>
 * </ul>
 *
 * <h2>纯随机乱闪（{@code /flexable …}）</h2>
 * <ul>
 *   <li>{@code /flexable on|off} —— 开：天黑了黄灯就脱离一切设定、每块各自随机亮灭（默认关）</li>
 * </ul>
 *
 * <h2>红灯（{@code /hlight …}，X 均支持 2 位小数）</h2>
 * <ul>
 *   <li>{@code /hlight time on X} —— 亮起时间（默认 2.00 秒）</li>
 *   <li>{@code /hlight time off X} —— 熄灭时间（默认 2.00 秒）</li>
 *   <li>{@code /hlight slow on X} —— 灭→亮渐变时间（默认 0.50 秒）</li>
 *   <li>{@code /hlight slow off X} —— 亮→灭渐变时间（默认 0.50 秒）</li>
 *   <li>{@code /hlight flex on|off [X]} —— 是否「错开」每块红方块的亮/灭循环起点 + 随机错开时长（默认<b>开</b> / 1.00 秒）</li>
 * </ul>
 *
 * <h2>黄灯夜间随机重分配（{@code /flex …}）—— 功能开关与方向是两个正交的开关</h2>
 * <ul>
 *   <li>{@code /flex off time Y [percent X]} —— <b>熄灭模式</b>（并打开功能）：入夜第一次亮灯后每 Y 秒，
 *       在「本晚被点亮」的灯里熄灭**当前还亮着**的 X%（只灭不亮，越到后半夜越暗；
 *       剩得不够一盏时一次全灭；X=0 不变、X=100 第 1 轮全灭）。<b>这是出厂默认</b>（Y=60、X=10）</li>
 *   <li>{@code /flex on time Y [percent X]} —— <b>点亮模式</b>（并打开功能）：每 Y 秒对全部黄灯
 *       按 {@code /light percent} 重新分配一次点亮 / 熄灭（**有熄灭也有点亮**）</li>
 *   <li>{@code /flex all off} —— 彻底关闭 flex 功能（一整夜只掷一次骰）；{@code /flex all on} 重新打开</li>
 *   <li>{@code /flex flex X} —— 每块 ±X 秒的固定偏差，避免同一瞬间一起变（默认 2.00 秒）</li>
 *   <li>{@code /flex grow X} —— 每轮间隔递增量：第 k 轮间隔 = Y + (k-1)·X；可填负数（60→55→50…），默认 0</li>
 * </ul>
 *
 * <p><b>所有写指令都是「立即生效」的</b>：写完当场把全场已加载的灯光方块刷新一遍
 * （见 {@link com.mzyc.build.LightIndex}），所以 {@code /light -f off} 是**当场**黄+红全灭，
 * 既不等黄灯 5 tick 节流，也能灭掉超出模拟距离、不会 tick 的方块。
 *
 * <p>不带参数的写法都是**查询**，只回当前数值：
 * <ul>
 *   <li>{@code /light} → {@code 比例 亮度 天黑秒数 天亮秒数 黄开关 红开关 全灭}（如 {@code 20 12 60 60 1 1 0}）</li>
 *   <li>{@code /light h}/{@code /light -f} → 开关值 {@code 1}/{@code 0}</li>
 *   <li>{@code /lightall} / {@code /alllight} → 全灭开关值 {@code 1}/{@code 0}；{@code /flexable} → 乱闪开关值 {@code 1}/{@code 0}</li>
 *   <li>{@code /hlight} → 六个数（开关 错开值 亮起值 熄灭值 渐变亮值 渐变灭值）；
 *       {@code /hlight flex} → {@code 1 1.00}；{@code /hlight time}/{@code /hlight slow} → {@code 2.00 2.00}</li>
 *   <li>{@code /flex} → {@code 开关 方向 间隔 熄灭比例 偏差 递增}
 *       （如出厂默认 {@code 1 1 60.00 10 2.00 0.00}；方向 {@code 1} = 熄灭、{@code 2} = 点亮）；
 *       {@code /flex all} → 总开关值 {@code 1}/{@code 0}</li>
 * </ul>
 *
 * <p>用户定的铁规矩：<b>指令反馈只回「指令执行成功」/「指令执行失败」，查询只回数值。</b>
 * 所以这里故意用 {@link StringArgumentType} 自己解析数字 —— 非数字、超范围这些情况
 * 都能统一回「指令执行失败」，而不是冒出原版的长句报错。
 */
public final class LightCommand {
    /** 指令反馈文案（只此两句，别加长句）。 */
    private static final Text FEEDBACK_OK = Text.literal("指令执行成功");
    private static final Text FEEDBACK_FAIL = Text.literal("指令执行失败");

    private LightCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(buildLight());
        dispatcher.register(buildHlight());
        dispatcher.register(buildFlex());
        dispatcher.register(buildLightAll());
        dispatcher.register(buildAllLightAlias());
        dispatcher.register(buildFlexable());
    }

    // ---------------------------------------------------------------- /light

    private static LiteralArgumentBuilder<ServerCommandSource> buildLight() {
        return CommandManager.literal("light")
                // 光秃秃 /light = 查询全部数值
                .executes(LightCommand::queryLightAll)

                // 黄灯总开关：/light on | /light off
                .then(CommandManager.literal("on")
                        .executes(context -> applyBoolean(context, config -> config.setYellowOn(true))))
                .then(CommandManager.literal("off")
                        .executes(context -> applyBoolean(context, config -> config.setYellowOn(false))))

                // 红灯总开关：/light h [on|off]
                .then(CommandManager.literal("h")
                        .executes(context -> queryBoolean(context, LightConfig::isRedOn))
                        .then(CommandManager.literal("on")
                                .executes(context -> applyBoolean(context, config -> config.setRedOn(true))))
                        .then(CommandManager.literal("off")
                                .executes(context -> applyBoolean(context, config -> config.setRedOn(false)))))

                // 全灭：/light -f [on|off]
                .then(CommandManager.literal("-f")
                        .executes(context -> queryBoolean(context, LightConfig::isForceOff))
                        .then(CommandManager.literal("on")
                                .executes(context -> applyBoolean(context, config -> config.setForceOff(true))))
                        .then(CommandManager.literal("off")
                                .executes(context -> applyBoolean(context, config -> config.setForceOff(false)))))

                // 「全灭」的等价写法：/light all [on|off]（和 /light -f、/lightall 共用同一个总闸）
                .then(CommandManager.literal("all")
                        .executes(context -> queryBoolean(context, LightConfig::isForceOff))
                        .then(CommandManager.literal("on")
                                .executes(context -> applyBoolean(context, config -> config.setForceOff(true))))
                        .then(CommandManager.literal("off")
                                .executes(context -> applyBoolean(context, config -> config.setForceOff(false)))))

                // 出厂默认：/light define default
                .then(CommandManager.literal("define")
                        .then(CommandManager.literal("default")
                                .executes(LightCommand::resetDefaults)))

                // 整数参数：percent | light | nightdelay | daydelay
                .then(intLeaf("percent", LightConfig.MIN_PERCENT, LightConfig.MAX_PERCENT,
                        LightConfig::getPercent, LightConfig::setPercent))
                .then(intLeaf("light", LightConfig.MIN_LIGHT_LEVEL, LightConfig.MAX_LIGHT_LEVEL,
                        LightConfig::getLightLevel, LightConfig::setLightLevel))
                // 天黑随机点亮秒数：新名字 nightdelay；旧名字 delay 仍然认（同一个字段，防旧笔记/旧习惯失效）
                .then(intLeaf("nightdelay", LightConfig.MIN_DELAY_SECONDS, LightConfig.MAX_DELAY_SECONDS,
                        LightConfig::getDelaySeconds, LightConfig::setDelaySeconds))
                .then(intLeaf("delay", LightConfig.MIN_DELAY_SECONDS, LightConfig.MAX_DELAY_SECONDS,
                        LightConfig::getDelaySeconds, LightConfig::setDelaySeconds))
                // 天亮随机熄灭秒数
                .then(intLeaf("daydelay", LightConfig.MIN_DELAY_SECONDS, LightConfig.MAX_DELAY_SECONDS,
                        LightConfig::getDayDelaySeconds, LightConfig::setDayDelaySeconds));
    }

    // ---------------------------------------------------------------- /hlight

    /** 拼出整棵 {@code /hlight} 指令树。 */
    private static LiteralArgumentBuilder<ServerCommandSource> buildHlight() {
        return CommandManager.literal("hlight")
                .executes(LightCommand::queryHlightAll)

                // /hlight flex [on|off] [X]
                .then(CommandManager.literal("flex")
                        .executes(LightCommand::queryRedFlex)
                        .then(CommandManager.literal("on")
                                .executes(context -> setRedFlex(context, null, true))
                                .then(CommandManager.argument("value", StringArgumentType.word())
                                        .executes(context -> setRedFlex(context,
                                                StringArgumentType.getString(context, "value"), true))))
                        .then(CommandManager.literal("off")
                                .executes(context -> setRedFlex(context, null, false))
                                .then(CommandManager.argument("value", StringArgumentType.word())
                                        .executes(context -> setRedFlex(context,
                                                StringArgumentType.getString(context, "value"), false)))))

                // /hlight time on|off [X]
                .then(CommandManager.literal("time")
                        .executes(context -> queryPair(context,
                                LightConfig::getRedTimeOnCentis, LightConfig::getRedTimeOffCentis))
                        .then(centisLeaf("on",
                                LightConfig.MIN_RED_TIME_CENTIS, LightConfig.MAX_RED_CENTIS,
                                LightConfig::getRedTimeOnCentis, LightConfig::setRedTimeOnCentis))
                        .then(centisLeaf("off",
                                LightConfig.MIN_RED_TIME_CENTIS, LightConfig.MAX_RED_CENTIS,
                                LightConfig::getRedTimeOffCentis, LightConfig::setRedTimeOffCentis)))

                // /hlight slow on|off [X]
                .then(CommandManager.literal("slow")
                        .executes(context -> queryPair(context,
                                LightConfig::getRedSlowOnCentis, LightConfig::getRedSlowOffCentis))
                        .then(centisLeaf("on",
                                LightConfig.MIN_RED_SLOW_CENTIS, LightConfig.MAX_RED_CENTIS,
                                LightConfig::getRedSlowOnCentis, LightConfig::setRedSlowOnCentis))
                        .then(centisLeaf("off",
                                LightConfig.MIN_RED_SLOW_CENTIS, LightConfig.MAX_RED_CENTIS,
                                LightConfig::getRedSlowOffCentis, LightConfig::setRedSlowOffCentis)));
    }

    // ---------------------------------------------------------------- /flex（黄灯重分配）

    private static LiteralArgumentBuilder<ServerCommandSource> buildFlex() {
        return CommandManager.literal("flex")
                // 光秃秃 /flex = 查询：开关 方向 间隔 熄灭比例 偏差 递增
                .executes(LightCommand::queryFlexAll)

                // /flex on [time Y [percent X]] —— 点亮模式（生效）
                .then(CommandManager.literal("on")
                        .executes(context -> setFlexMode(context, LightConfig.FLEX_DIR_LIGHT))
                        .then(flexTimeTree(LightConfig.FLEX_DIR_LIGHT)))
                // /flex off [time Y [percent X]] —— 熄灭模式（生效）
                .then(CommandManager.literal("off")
                        .executes(context -> setFlexMode(context, LightConfig.FLEX_DIR_EXTINGUISH))
                        .then(flexTimeTree(LightConfig.FLEX_DIR_EXTINGUISH)))

                // /flex all [on|off] —— 功能总开关：
                //   /flex all off = 彻底关闭 flex 功能（整夜只掷一次骰）
                //   /flex all on  = 重新打开（沿用上次的方向）
                .then(CommandManager.literal("all")
                        .executes(context -> queryBoolean(context, LightConfig::isYellowFlexOn))
                        .then(CommandManager.literal("on")
                                .executes(context -> setFlexSwitch(context, true)))
                        .then(CommandManager.literal("off")
                                .executes(context -> setFlexSwitch(context, false))))

                // /flex flex X —— 偏差
                .then(CommandManager.literal("flex")
                        .executes(LightCommand::queryFlexJitter)
                        .then(CommandManager.argument("X", StringArgumentType.word())
                                .executes(context -> setFlexJitter(context,
                                        StringArgumentType.getString(context, "X")))))

                // /flex grow X —— 间隔递增（可负）
                .then(CommandManager.literal("grow")
                        .executes(LightCommand::queryFlexGrow)
                        .then(CommandManager.argument("X", StringArgumentType.word())
                                .executes(context -> setFlexGrow(context,
                                        StringArgumentType.getString(context, "X")))));
    }

    // ---------------------------------------------------------------- /lightall（全灭总闸）

    /**
     * {@code /lightall [on|off]} —— 「强制不发光」总闸，写的是和 {@code /light -f}、
     * {@code /light all} <b>同一份</b> {@code forceOff} 状态（互为别名）。
     *
     * <p>{@code off} 一按下去，黄 / 红方块当场一律不发光（不管白天还是夜晚、不管其它任何设置，
     * 连 {@code /flexable on} 的纯随机乱闪也压得住），直到输入 {@code /lightall on}（或等价的
     * {@code /light -f on} / {@code /light all on}）才恢复。
     *
     * <p>不带参数 = 查询，只回 {@code 1}/{@code 0}。
     */
    private static LiteralArgumentBuilder<ServerCommandSource> buildLightAll() {
        return forceOffTree("lightall");
    }

    /**
     * {@code /alllight [on|off]} —— 和 {@code /lightall} 完全等价的写法。
     *
     * <p>纯粹是防手滑：用户 2026-10-09 反馈「输入 alllight off 之后灯还亮着」——
     * 十有八九是把词序写反了（{@code alllight} vs {@code lightall}），原版对不存在的指令
     * 只会回一句「未知指令」，灯当然照旧亮。两个名字都注册上，写错哪半边都照样管用。
     */
    private static LiteralArgumentBuilder<ServerCommandSource> buildAllLightAlias() {
        return forceOffTree("alllight");
    }

    /** 全灭总闸的指令树（{@code lightall} / {@code alllight} / {@code -f} / {@code all} 共用同一份状态）。 */
    private static LiteralArgumentBuilder<ServerCommandSource> forceOffTree(String name) {
        return CommandManager.literal(name)
                .executes(context -> queryBoolean(context, LightConfig::isForceOff))
                .then(CommandManager.literal("on")
                        .executes(context -> applyBoolean(context, config -> config.setForceOff(true))))
                .then(CommandManager.literal("off")
                        .executes(context -> applyBoolean(context, config -> config.setForceOff(false))));
    }

    // ---------------------------------------------------------------- /flexable（纯随机乱闪）

    /**
     * {@code /flexable [on|off]} —— 黄灯「纯随机乱闪」：开启后只要天黑了，黄灯的亮灭就彻底
     * 脱离 {@code /light percent}、{@code /light delay}、{@code /flex …} 这套设定，每块按坐标
     * 散列出的随机节奏独立亮灭（见 {@code LightBlockEntity.flexableLit}）。
     *
     * <p>压得住它的只有 {@code /lightall off}（= {@code /light -f off}）和 {@code /light off}。
     * 不带参数 = 查询，只回 {@code 1}/{@code 0}。
     */
    private static LiteralArgumentBuilder<ServerCommandSource> buildFlexable() {
        return CommandManager.literal("flexable")
                .executes(context -> queryBoolean(context, LightConfig::isYellowFlexableOn))
                .then(CommandManager.literal("on")
                        .executes(context -> applyBoolean(context, config -> config.setYellowFlexableOn(true))))
                .then(CommandManager.literal("off")
                        .executes(context -> applyBoolean(context, config -> config.setYellowFlexableOn(false))));
    }

    /** 共用的 {@code time Y [percent X]} 子树；{@code dir} 决定这次要切到哪个方向。 */
    private static LiteralArgumentBuilder<ServerCommandSource> flexTimeTree(int dir) {
        return CommandManager.literal("time")
                .then(CommandManager.argument("Y", StringArgumentType.word())
                        .executes(context -> applyFlex(context, dir,
                                StringArgumentType.getString(context, "Y"), null))
                        .then(CommandManager.literal("percent")
                                .then(CommandManager.argument("X", StringArgumentType.word())
                                        .executes(context -> applyFlex(context, dir,
                                                StringArgumentType.getString(context, "Y"),
                                                StringArgumentType.getString(context, "X"))))));
    }

    // ---------------------------------------------------------------- 查询

    /** {@code /light}：回全部数值（比例 亮度 天黑秒数 天亮秒数 黄开关 红开关 全灭）。 */
    private static int queryLightAll(CommandContext<ServerCommandSource> context) {
        LightConfig config = LightConfig.get(context.getSource().getWorld());
        String value = config.getPercent() + " " + config.getLightLevel() + " " + config.getDelaySeconds()
                + " " + config.getDayDelaySeconds()
                + " " + (config.isYellowOn() ? "1" : "0")
                + " " + (config.isRedOn() ? "1" : "0")
                + " " + (config.isForceOff() ? "1" : "0");
        context.getSource().sendFeedback(() -> Text.literal(value), false);
        return 1;
    }

    /** {@code /light h} / {@code /light -f}：回开关值 {@code 1}/{@code 0}。 */
    private static int queryBoolean(CommandContext<ServerCommandSource> context, Predicate<LightConfig> getter) {
        boolean on = getter.test(LightConfig.get(context.getSource().getWorld()));
        context.getSource().sendFeedback(() -> Text.literal(on ? "1" : "0"), false);
        return 1;
    }

    /** {@code /hlight}：回全部六个数（开关 错开值 亮起值 熄灭值 渐变亮值 渐变灭值）。 */
    private static int queryHlightAll(CommandContext<ServerCommandSource> context) {
        LightConfig config = LightConfig.get(context.getSource().getWorld());
        String value = (config.isRedFlexOn() ? "1 " : "0 ")
                + LightConfig.formatCentis(config.getRedFlexCentis()) + " "
                + LightConfig.formatCentis(config.getRedTimeOnCentis()) + " "
                + LightConfig.formatCentis(config.getRedTimeOffCentis()) + " "
                + LightConfig.formatCentis(config.getRedSlowOnCentis()) + " "
                + LightConfig.formatCentis(config.getRedSlowOffCentis());
        context.getSource().sendFeedback(() -> Text.literal(value), false);
        return 1;
    }

    /** {@code /hlight flex}：回「开关 数值」（如 {@code 1 4.00}）。 */
    private static int queryRedFlex(CommandContext<ServerCommandSource> context) {
        LightConfig config = LightConfig.get(context.getSource().getWorld());
        String value = (config.isRedFlexOn() ? "1 " : "0 ")
                + LightConfig.formatCentis(config.getRedFlexCentis());
        context.getSource().sendFeedback(() -> Text.literal(value), false);
        return 1;
    }

    /** {@code /hlight time} / {@code /hlight slow}：回「on值 off值」（如 {@code 2.00 2.00}）。 */
    private static int queryPair(CommandContext<ServerCommandSource> context,
                                 ToIntFunction<LightConfig> onGetter, ToIntFunction<LightConfig> offGetter) {
        LightConfig config = LightConfig.get(context.getSource().getWorld());
        String value = LightConfig.formatCentis(onGetter.applyAsInt(config)) + " "
                + LightConfig.formatCentis(offGetter.applyAsInt(config));
        context.getSource().sendFeedback(() -> Text.literal(value), false);
        return 1;
    }

    /** {@code /flex}：回「开关 方向 间隔 熄灭比例 偏差 递增」（方向 1 = 熄灭、2 = 点亮）。 */
    private static int queryFlexAll(CommandContext<ServerCommandSource> context) {
        LightConfig config = LightConfig.get(context.getSource().getWorld());
        String value = (config.isYellowFlexOn() ? "1 " : "0 ")
                + config.getYellowFlexDir() + " "
                + LightConfig.formatCentis(config.getYellowFlexTimeCentis()) + " "
                + config.getYellowFlexPercent() + " "
                + LightConfig.formatCentis(config.getYellowFlexJitterCentis()) + " "
                + LightConfig.formatCentis(config.getYellowFlexGrowCentis());
        context.getSource().sendFeedback(() -> Text.literal(value), false);
        return 1;
    }

    /** {@code /flex flex}：回偏差值。 */
    private static int queryFlexJitter(CommandContext<ServerCommandSource> context) {
        LightConfig config = LightConfig.get(context.getSource().getWorld());
        String value = LightConfig.formatCentis(config.getYellowFlexJitterCentis());
        context.getSource().sendFeedback(() -> Text.literal(value), false);
        return 1;
    }

    /** {@code /flex grow}：回递增值。 */
    private static int queryFlexGrow(CommandContext<ServerCommandSource> context) {
        LightConfig config = LightConfig.get(context.getSource().getWorld());
        String value = LightConfig.formatCentis(config.getYellowFlexGrowCentis());
        context.getSource().sendFeedback(() -> Text.literal(value), false);
        return 1;
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 写完之后统一走这里：<b>立刻</b>把全场已加载的灯光方块按新设置刷新一遍，再回「指令执行成功」。
     *
     * <p>有了这一步，{@code /light -f off} 是**当场**全部熄灭（不等黄灯那 5 tick 节流，
     * 也能灭掉超出模拟距离、根本不会 tick 的方块）—— 对应「无论什么情况，立即熄灭」。
     * 指令是低频操作，扫一遍全场可忽略。
     */
    private static int ok(CommandContext<ServerCommandSource> context) {
        LightIndex.refreshAll(context.getSource().getServer());
        context.getSource().sendFeedback(() -> FEEDBACK_OK, false);
        return 1;
    }

    /** 应用一个写开关的动作，回「指令执行成功」。 */
    private static int applyBoolean(CommandContext<ServerCommandSource> context, Consumer<LightConfig> setter) {
        setter.accept(LightConfig.get(context.getSource().getWorld()));
        return ok(context);
    }

    /** {@code /light define default}：把全部设置一次性还原成出厂默认，并当场刷新全场。 */
    private static int resetDefaults(CommandContext<ServerCommandSource> context) {
        LightConfig.get(context.getSource().getWorld()).resetToDefaults();
        return ok(context);
    }

    /** {@code /flex all on|off}：只切功能总开关（方向不变，上次是熄灭就还是熄灭）。 */
    private static int setFlexSwitch(CommandContext<ServerCommandSource> context, boolean enable) {
        LightConfig config = LightConfig.get(context.getSource().getWorld());
        config.setYellowFlexOn(enable);
        return ok(context);
    }

    /**
     * {@code /flex on|off}（不带 time）：切方向，并且把功能打开。
     *
     * <p>按用户定的语义：{@code /flex off} = 熄灭模式（生效），{@code /flex on} = 点亮模式（生效）。
     */
    private static int setFlexMode(CommandContext<ServerCommandSource> context, int dir) {
        LightConfig config = LightConfig.get(context.getSource().getWorld());
        config.setYellowFlexDir(dir);
        config.setYellowFlexOn(true);
        return ok(context);
    }

    /**
     * {@code /flex on|off time Y [percent X]}：写入间隔（可选比例）并切到该方向、同时打开功能。
     * 先全部校验再落盘。
     *
     * <p>{@code percent} 在熄灭模式下是「每轮熄灭还亮着的 X%」；在点亮模式下只存不用
     * （留给熄灭模式），所以两个方向都接受这个参数。
     */
    private static int applyFlex(CommandContext<ServerCommandSource> context, int dir,
                                 String rawTime, String rawPercent) {
        Integer timeCentis = parseCentis(rawTime,
                LightConfig.MIN_YELLOW_FLEX_TIME_CENTIS, LightConfig.MAX_RED_CENTIS);
        Integer percent = null;
        if (rawPercent != null) {
            percent = parseInRange(rawPercent, 0, 100);
        }
        if (timeCentis == null || (rawPercent != null && percent == null)) {
            context.getSource().sendError(FEEDBACK_FAIL);
            return 0;
        }
        LightConfig config = LightConfig.get(context.getSource().getWorld());
        config.setYellowFlexTimeCentis(timeCentis);
        if (percent != null) {
            config.setYellowFlexPercent(percent);
        }
        config.setYellowFlexDir(dir);
        config.setYellowFlexOn(true);
        return ok(context);
    }

    /** {@code /flex flex X}：写入偏差（0~600 秒）。 */
    private static int setFlexJitter(CommandContext<ServerCommandSource> context, String rawValue) {
        Integer centis = parseCentis(rawValue,
                LightConfig.MIN_YELLOW_FLEX_JITTER_CENTIS, LightConfig.MAX_RED_CENTIS);
        if (centis == null) {
            context.getSource().sendError(FEEDBACK_FAIL);
            return 0;
        }
        LightConfig.get(context.getSource().getWorld()).setYellowFlexJitterCentis(centis);
        return ok(context);
    }

    /** {@code /flex grow X}：写入递增（-600 ~ +600 秒）。 */
    private static int setFlexGrow(CommandContext<ServerCommandSource> context, String rawValue) {
        Integer centis = parseCentis(rawValue,
                LightConfig.MIN_YELLOW_FLEX_GROW_CENTIS, LightConfig.MAX_RED_CENTIS);
        if (centis == null) {
            context.getSource().sendError(FEEDBACK_FAIL);
            return 0;
        }
        LightConfig.get(context.getSource().getWorld()).setYellowFlexGrowCentis(centis);
        return ok(context);
    }

    /**
     * {@code /hlight flex on|off [X]} 的实际处理。
     *
     * @param rawValue 需要一并写入的「错开时长」；为 null 表示不改（沿用已存的）
     * @param on       这次要开还是关
     */
    private static int setRedFlex(CommandContext<ServerCommandSource> context, String rawValue, boolean on) {
        LightConfig config = LightConfig.get(context.getSource().getWorld());
        if (rawValue != null) {
            Integer centis = parseCentis(rawValue,
                    LightConfig.MIN_RED_FLEX_CENTIS, LightConfig.MAX_RED_CENTIS);
            if (centis == null) {
                context.getSource().sendError(FEEDBACK_FAIL);
                return 0;
            }
            config.setRedFlexCentis(centis);
        }
        config.setRedFlexOn(on);
        return ok(context);
    }

    // ---------------------------------------------------------------- 指令叶子构造

    /**
     * 注册一条「{@code X}」形式的整数子指令（挂在 /light 下），支持查询与写入。
     */
    private static LiteralArgumentBuilder<ServerCommandSource> intLeaf(
            String name, int min, int max,
            ToIntFunction<LightConfig> getter, BiConsumer<LightConfig, Integer> setter) {
        return CommandManager.literal(name)
                // 不带参数 = 查询（按规矩只回数值）
                .executes(context -> {
                    int current = getter.applyAsInt(LightConfig.get(context.getSource().getWorld()));
                    context.getSource().sendFeedback(() -> Text.literal(Integer.toString(current)), false);
                    return 1;
                })
                .then(CommandManager.argument("value", StringArgumentType.word())
                        .executes(context -> {
                            Integer value = parseInRange(StringArgumentType.getString(context, "value"), min, max);
                            if (value == null) {
                                context.getSource().sendError(FEEDBACK_FAIL);
                                return 0;
                            }
                            setter.accept(LightConfig.get(context.getSource().getWorld()), value);
                            return ok(context);
                        }));
    }

    /**
     * 注册一条「{@code X}」形式的**小数秒**子指令（挂在 /hlight 下），支持查询与写入。
     * 内部按「百分之一秒」存整数，所以 2 位小数精确无损。
     */
    private static LiteralArgumentBuilder<ServerCommandSource> centisLeaf(
            String name, int minCentis, int maxCentis,
            ToIntFunction<LightConfig> getter, ObjIntConsumer<LightConfig> setter) {
        return CommandManager.literal(name)
                .executes(context -> {
                    int centis = getter.applyAsInt(LightConfig.get(context.getSource().getWorld()));
                    context.getSource().sendFeedback(() -> Text.literal(LightConfig.formatCentis(centis)), false);
                    return 1;
                })
                .then(CommandManager.argument("value", StringArgumentType.word())
                        .executes(context -> {
                            Integer centis = parseCentis(StringArgumentType.getString(context, "value"),
                                    minCentis, maxCentis);
                            if (centis == null) {
                                context.getSource().sendError(FEEDBACK_FAIL);
                                return 0;
                            }
                            setter.accept(LightConfig.get(context.getSource().getWorld()), centis);
                            return ok(context);
                        }));
    }

    // ---------------------------------------------------------------- 解析工具

    /** 解析出 [min, max] 内的整数，非法一律返回 null。 */
    private static Integer parseInRange(String raw, int min, int max) {
        try {
            int value = Integer.parseInt(raw);
            return value >= min && value <= max ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * 解析「秒」并换算成百分之一秒的整数（所有小数指令共用）。
     *
     * <p>用 {@link BigDecimal} 而不是 {@code Double.parseDouble}：这样 {@code "0.30"} 精确等于 30，
     * 不会有二进制浮点误差；超过 2 位小数的输入（如 {@code "1.234"}）按四舍五入取到 2 位。
     * 非数字、超出 [min, max] 一律返回 null（由调用方统一回「指令执行失败」）。
     */
    private static Integer parseCentis(String raw, int minCentis, int maxCentis) {
        try {
            int centis = new BigDecimal(raw)
                    .movePointRight(2)                       // 秒 -> 百分之一秒
                    .setScale(0, RoundingMode.HALF_UP)       // 取整到 0.01 秒
                    .intValueExact();
            return centis >= minCentis && centis <= maxCentis ? centis : null;
        } catch (ArithmeticException | NumberFormatException notAValidNumber) {
            return null;
        }
    }
}
