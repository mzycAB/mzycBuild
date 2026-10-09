"""离线复核这一轮的四件事（不开游戏，纯 Python 对源码与算法做核对）。

需求：
  1. 天亮时灯火**不再一起灭**：天亮那一刻还亮着的灯，各自在 0~`/light daydelay` 秒内陆续熄灭，
     例如 `light daydelay 60` ⇒ 天亮后 60 秒内全部熄完，而不是天一亮就「啪」地全灭。
  2. 指令改名与新增：`light nightdelay`（天黑随机点亮秒数，旧名 `light delay` 仍认）、
     新增 `light daydelay`（天亮随机熄灭秒数）。
  3. 默认同步：`light define default` 与「第一次给存档装上模组」都要带上 `light daydelay 60`。
  4. `mb help` / `MB help` 打开分级 UI，界面里是按钮 + 输入框，且**不写任何操作反馈小字**。

验证：
  A. dayOffTicks 的取值范围 / 均匀性 / 确定性（同晚同坐标恒定）；
  B. 逐行复刻 LightBlockEntity.tick 的新状态机：天亮后 60 秒内全灭、且中间是「陆续灭」不是一起灭；
     daydelay = 0 时退回「天一亮立刻全灭」（老行为）；
  C. 天亮时没亮的灯在整个窗口里都不会诡异地亮起来；
  D. dayOffTicks 与 delayTicksOn（天黑那个）互不相关（不会「亮得晚也灭得晚」）；
  E. 源码核对：默认常量、resetToDefaults 同步、指令叶子、NBT 字段；
  F. UI：分级菜单覆盖全部功能点、且界面代码里没有「操作完成 / 成功 / 失败」这类反馈小字。

跑法：python verify_daydelay.py
"""

import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_level_delay import (delay_ticks_on, night_hash, pos_as_long,  # noqa: E402
                                rolls_on, splitmix, _to_signed64)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
JAVA = os.path.join(ROOT, "src/main/java/com/mzyc/build")
CONFIG_JAVA = os.path.join(JAVA, "LightConfig.java")
COMMAND_JAVA = os.path.join(JAVA, "command/LightCommand.java")
BLOCK_JAVA = os.path.join(JAVA, "block/LightBlockEntity.java")
UI_COMMAND_JAVA = os.path.join(JAVA, "command/UiCommand.java")
UI_SERVER_JAVA = os.path.join(JAVA, "net/UiServer.java")
UI_DIR = os.path.join(JAVA, "client/gui")

NIGHT_START = 13000
NIGHT_END = 23000
DAY_TICKS = 24000
TICKS_PER_SECOND = 20
CHECK_INTERVAL = 5          # LightBlockEntity.tick 的实际判定步长

DAY_DELAY_SALT = 0x4441595F444C4159      # "DAY_DLAY"
DAWN_GT = 500_000                        # 观察窗里「天亮」那一刻的 gameTime
CLOCK_OFFSET = (NIGHT_END - DAWN_GT) % DAY_TICKS


def clock(game_time):
    """gameTime -> dayTime：让 gt == DAWN_GT 时 dayTime 正好是 23000（= 天亮那一瞬）。"""
    return (game_time + CLOCK_OFFSET) % DAY_TICKS


def day_off_ticks(pos_long, night_index, day_seconds):
    """LightBlockEntity.dayOffTicks 的逐行复刻。"""
    max_ticks = max(0, day_seconds) * TICKS_PER_SECOND
    mixed = splitmix(_to_signed64(night_hash(pos_long, night_index) ^ DAY_DELAY_SALT))
    return mixed % (max_ticks + 1)


def simulate(pos_long, percent, night_seconds, day_seconds, span_ticks, start_gt):
    """LightBlockEntity.tick 的逐行复刻（含新加的「天亮随机熄灭」锁存）。

    为了把天亮这件事看干净，这里按 `/flex all off`（一整夜只掷一次骰）跑 ——
    天亮随机熄灭与夜间重分配是正交的两件事，混在一起反而看不清。
    返回 {gameTime: 这一刻该不该亮}。
    """
    night_latched = False
    night_started = 0
    night_index = 0
    night_roll = False
    night_delay = 0
    day_latched = False
    day_started = 0
    day_fade_lit = False

    out = {}
    for gt in range(start_gt, start_gt + span_ticks, CHECK_INTERVAL):
        night = NIGHT_START <= clock(gt) < NIGHT_END

        if not night:
            if night_latched:
                # 刚观察到天亮：锁存起点 + 把「此刻还亮着吗」冻下来
                night_latched = False
                day_latched = True
                day_started = gt
                day_fade_lit = night_roll and (gt - night_started) >= night_delay
        elif not night_latched:
            night_latched = True
            day_latched = False      # 新的一晚，白天的窗口作废
            night_started = gt
            night_index = gt // DAY_TICKS
            night_roll = rolls_on(pos_long, night_index, percent)
            night_delay = delay_ticks_on(pos_long, night_index, night_seconds)

        if night:
            want = night_roll and (gt - night_started) >= night_delay
        else:
            want = (day_latched and day_fade_lit
                    and (gt - day_started) < day_off_ticks(pos_long, night_index, day_seconds))
        out[gt] = want
    return out


def dawn_lit(states, t_ticks):
    """天亮后 t_ticks 刻，这批方块里还亮着的数量。"""
    return sum(1 for s in states if s.get(DAWN_GT + t_ticks))


def main():
    ok = True
    sample = [pos_as_long(x, 64, z) for x in range(400) for z in range(50)]   # 20000 块
    night_index = 100

    print("== A. dayOffTicks 范围 / 均匀性 / 确定性 ==")
    for secs in (0, 1, 60, 600):
        max_ticks = secs * TICKS_PER_SECOND
        vals = [day_off_ticks(p, night_index, secs) for p in sample]
        lo, hi, mean = min(vals), max(vals), statistics.fmean(vals)
        good = 0 <= lo and hi <= max_ticks
        print(f"  daydelay={secs:>4}s -> 实际 [{lo}, {hi}]（允许 [0, {max_ticks}]），均值 {mean:.1f}"
              f"{'  OK' if good else '  <<< 越界!'}")
        ok &= good
        if secs == 60:
            ok &= abs(mean - max_ticks / 2) < max_ticks * 0.03
        if secs == 0:
            ok &= hi == 0
    stable = all(day_off_ticks(p, night_index, 60) == day_off_ticks(p, night_index, 60)
                 for p in sample[:3000])
    print(f"  同晚同坐标恒定（确定性） = {stable}（应 True）")
    ok &= stable

    print(f"\n== B. 天亮不再是「一起灭」：{DAWN_GT} 处天亮，窗口 60 秒 ==")
    probes = sample[:4000]
    start_gt = DAWN_GT - 10000        # 正好从入夜那一刻（dayTime 13000）开始观察
    sim_night_index = start_gt // DAY_TICKS
    margin = 90 * TICKS_PER_SECOND    # 天亮后再观察 90 秒（足够盖住 60 秒的窗口）
    span = 10000 + margin
    grid = list(range(0, margin, CHECK_INTERVAL))

    for day_secs, label in ((60, "默认 daydelay 60"), (0, "daydelay 0（老行为）")):
        states = [simulate(p, 100, 0, day_secs, span, start_gt) for p in probes]
        print(f"  -- {label}（percent 100% / 天亮前全部亮着）--")
        for t_sec in (0, 15, 30, 45, 59):
            lit = dawn_lit(states, t_sec * TICKS_PER_SECOND)
            print(f"    天亮后 {t_sec:>2}s：还亮着 {lit:>4}/{len(probes)} = {lit / len(probes) * 100:5.1f}%")
            if day_secs == 0:
                ok &= lit == 0
        last_lit = max((t for s in states for t in grid if s.get(DAWN_GT + t)), default=0)
        print(f"    最后一次「还亮着」的时刻 = 天亮后 {last_lit} tick = {last_lit / TICKS_PER_SECOND:.2f}s"
              f"（应 <= {day_secs}s）")
        ok &= last_lit <= day_secs * TICKS_PER_SECOND
        if day_secs == 60:
            mid = dawn_lit(states, 30 * TICKS_PER_SECOND) / len(probes)
            print(f"    天亮后 30s 还亮着 {mid * 100:.1f}%（应约一半 ⇒ 「陆续灭」而不是一起灭）")
            ok &= 0.3 < mid < 0.7
        flap = 0
        for s in states:
            seq = [s.get(DAWN_GT + t) for t in grid if t <= day_secs * TICKS_PER_SECOND]
            if any(seq[i] and not seq[i - 1] for i in range(1, len(seq))):
                flap += 1
        print(f"    窗口内出现「灭一下又亮」的方块 = {flap}（应为 0）")
        ok &= flap == 0

    print("\n== C. 天亮时没亮的灯，整个窗口都不会亮起来 ==")
    never = [p for p in probes if not rolls_on(p, sim_night_index, 30)]
    bad = 0
    for p in never:
        s = simulate(p, 30, 0, 60, span, start_gt)
        if any(s.get(DAWN_GT + t) for t in range(0, 60 * TICKS_PER_SECOND, CHECK_INTERVAL)):
            bad += 1
    print(f"  取 {len(never)} 块「当晚没点亮（percent 30 未掷中）」的灯，窗口内亮起来的 = {bad}（应为 0）")
    ok &= bad == 0

    print("\n== D. 两个随机互不相关（不会「亮得晚也灭得晚」）==")
    d_off = [day_off_ticks(p, night_index, 60) / 1200 for p in sample]
    d_on = [delay_ticks_on(p, night_index, 60) / 1200 for p in sample]
    n = len(d_off)
    mo, mn = statistics.fmean(d_off), statistics.fmean(d_on)
    cov = sum((d_off[i] - mo) * (d_on[i] - mn) for i in range(n)) / n
    corr = cov / (statistics.pstdev(d_off) * statistics.pstdev(d_on))
    print(f"  dayOffTicks 与 delayTicksOn 的相关系数 = {corr:+.4f}（应约 0，|corr| < 0.05）")
    ok &= abs(corr) < 0.05

    print("\n== E. 源码核对：默认值 / 指令 / 状态 ==")
    config_text = open(CONFIG_JAVA, encoding="utf-8").read()
    command_text = open(COMMAND_JAVA, encoding="utf-8").read()
    block_text = open(BLOCK_JAVA, encoding="utf-8").read()

    def const(name):
        m = re.search(r"public static final int\s+" + name + r"\s*=\s*([^;]+);", config_text)
        return int(m.group(1)) if m else None

    checks = [
        ("DEFAULT_DAY_DELAY_SECONDS == 60", const("DEFAULT_DAY_DELAY_SECONDS") == 60),
        ("DEFAULT_DELAY_SECONDS（天黑）== 60", const("DEFAULT_DELAY_SECONDS") == 60),
        ("resetToDefaults 同步 dayDelaySeconds",
         re.search(r"dayDelaySeconds\s*=\s*DEFAULT_DAY_DELAY_SECONDS", config_text) is not None),
        ("存盘用 dayDelaySeconds 这个 key",
         'KEY_DAY_DELAY_SECONDS = "dayDelaySeconds"' in config_text),
        ("老存档缺这个 key 时落回默认（fromNbt 用 contains 判断）",
         "if (nbt.contains(KEY_DAY_DELAY_SECONDS))" in config_text),
        ("指令叶子 light nightdelay（旧名 delay 仍注册）",
         'intLeaf("nightdelay"' in command_text and 'intLeaf("delay"' in command_text),
        ("指令叶子 light daydelay", 'intLeaf("daydelay"' in command_text),
        ("daydelay 走同一个字段（get/set 成对）",
         "LightConfig::getDayDelaySeconds, LightConfig::setDayDelaySeconds" in command_text),
        ("/light 查询串带上 daydelay", '+ " " + config.getDayDelaySeconds()' in command_text),
        ("白天锁存状态（dayLatched / dayFadeLit / dayStartedAt）",
         all(k in block_text for k in ("dayLatched", "dayFadeLit", "dayStartedAt"))),
        ("天亮时刻用 gameTime 记（不是 dayTime）", "dayStartedAt = gameTime;" in block_text),
        ("dayOffTicks 存在且用独立盐", "public static long dayOffTicks(" in block_text
         and "DAY_DELAY_SALT" in block_text),
        ("新状态存盘（DayLatched/DayStartedAt/DayFadeLit）",
         all(k in block_text for k in ('"DayLatched"', '"DayStartedAt"', '"DayFadeLit"'))),
    ]
    for name, good in checks:
        print(f"  {'OK ' if good else '!! '}{name}")
        ok &= good

    print("\n== F. UI：/mb help 分级菜单 + 不写反馈小字 ==")
    ui_files = sorted(f for f in os.listdir(UI_DIR) if f.endswith(".java"))
    ui_text = "".join(open(os.path.join(UI_DIR, f), encoding="utf-8").read() for f in ui_files)
    ui_command_text = open(UI_COMMAND_JAVA, encoding="utf-8").read()

    must_have = [
        "light percent", "light light", "light nightdelay", "light daydelay",
        "light on", "light off", "light h on", "light h off",
        "lightall on", "lightall off", "flexable on", "flexable off",
        "hlight time on", "hlight time off", "hlight slow on", "hlight slow off",
        "hlight flex on", "hlight flex off",
        "flex all on", "flex all off", "flex on", "flex off", "flex flex", "flex grow",
        "light define default",
    ]
    missing = [c for c in must_have if c not in ui_text]
    print(f"  界面覆盖的功能点 {len(must_have) - len(missing)}/{len(must_have)}"
          f"{'' if not missing else '  缺: ' + ', '.join(missing)}")
    ok &= not missing

    for cls, label in (("TextFieldWidget", "输入框"), ("ButtonWidget", "按钮")):
        hit = cls in ui_text
        print(f"  {'OK ' if hit else '!! '}界面里有{label}（{cls}）")
        ok &= hit

    main_screen = open(os.path.join(UI_DIR, "UiMainScreen.java"), encoding="utf-8").read()
    children = [c for c in ("UiLightScreen", "UiRedScreen", "UiFlexScreen", "UiSwitchScreen")
                if c in main_screen]
    print(f"  {'OK ' if len(children) == 4 else '!! '}一级界面挂了 {len(children)}/4 个二级界面"
          f"（{', '.join(children)}）")
    ok &= len(children) == 4
    base = open(os.path.join(UI_DIR, "UiScreen.java"), encoding="utf-8").read()
    back = ("返回" in base) and ("关闭" in base) and ('literal("刷新")' in base)
    print(f"  {'OK ' if back else '!! '}二级界面共用底座（返回 / 刷新 / 关闭）")
    ok &= back

    entry = all(t in ui_command_text for t in ('build("mb")', 'build("MB")', 'literal("help")'))
    print(f"  {'OK ' if entry else '!! '}/mb help、/MB help（含光秃秃的 /mb）都注册了")
    ok &= entry

    forbidden = ["操作完成", "已完成", "设置成功", "成功！", "失败！"]
    # 只看**字符串字面量**（注释里为了说明「不许出现什么」当然会提到这些词）
    literals = " ".join(re.findall(r'"((?:[^"\\]|\\.)*)"', ui_text))
    hit = [w for w in forbidden if w in literals]
    print(f"  {'OK ' if not hit else '!! '}界面文字里没有操作反馈小字"
          f"{'' if not hit else '（发现: ' + ', '.join(hit) + '）'}")
    ok &= not hit

    client_text = open(os.path.join(UI_DIR, "UiClient.java"), encoding="utf-8").read()
    summary = "UI执行成功" in client_text and "UI执行失败" in client_text
    print(f"  {'OK ' if summary else '!! '}成功 / 失败只在退出界面时汇总一条（UiClient）")
    ok &= summary

    silent = "withSilent()" in open(UI_SERVER_JAVA, encoding="utf-8").read()
    print(f"  {'OK ' if silent else '!! '}UI 里的指令在服务端静音执行（不刷「指令执行成功」）")
    ok &= silent

    print(f"\n  （界面文件 {len(ui_files)} 个：{', '.join(ui_files)}）")
    print("\n结果:", "全部通过" if ok else "存在失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
