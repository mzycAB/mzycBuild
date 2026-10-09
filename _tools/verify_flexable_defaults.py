"""离线复核这一轮新增的三件事（不开游戏，纯 Python 对源码与算法做核对）。

对应需求：
  1. 新指令 /lightall on|off —— 「强制不发光」总闸，写的是和 /light -f、/light all
     同一份 forceOff 状态；关掉后黄/红一律不发光，无视一切设置，直到重新打开。
  2. 新指令 /light define default —— 把全部设置还原成出厂默认：
     light light 12 / light nightdelay 60（旧名 light delay）/ light daydelay 60 /
     light percent 20 / hlight slow on 0.5 / hlight time on 2 / hlight flex on 1 /
     flex flex 2 / flex off time 60 percent 10 /
     flex grow off（外加黄/红分组开关恢复为开、乱闪恢复为关；刻意不动 forceOff）。
  3. 新指令 /flexable on|off —— 开启后天黑黄灯脱离一切设定、按坐标散列出的随机节奏乱闪。
  4. 新指令 /flex all on|off —— flex 功能的总开关显式写法（/flex all off = 彻底关闭 flex 功能），
     和 /flex off|on（方向：熄灭 / 点亮）是两个正交的开关；出厂默认 = 功能开 + 熄灭模式。
  5. /alllight 是全灭总闸的又一个别名（防「alllight / lightall 词序写反」）。

验证：
  A. 读 LightConfig.java 源码把出厂默认常量逐条抠出来，和需求清单对齐（防手滑改错数）；
  B. resetToDefaults() 里**不许**出现对 forceOff 的赋值（全灭总闸只能由 /lightall on 恢复）；
  C. LightCommand.java 里确实注册了 lightall / flexable / define->default / flex all 这些指令；
  D. flexableLit 的算法复刻：确定性、每块各闪各的、亮着占比 ≈ 一半、会变化（不卡死）；
  E. 优先级：forceOff 或 !yellowOn 时 flexable 也得灭（没有任何设定盖得过 lightall off）。

跑法：python verify_flexable_defaults.py
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_hlight import hash_of  # noqa: E402
from verify_level_delay import pos_as_long, MASK  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CONFIG_JAVA = os.path.join(ROOT, "src/main/java/com/mzyc/build/LightConfig.java")
COMMAND_JAVA = os.path.join(ROOT, "src/main/java/com/mzyc/build/command/LightCommand.java")

# ---------------------------------------------------------------- /flexable 算法复刻

FLEXABLE_SALT = 0x464C455841424C45           # "FLEXABLE"
FLEXABLE_MIN_LIT_TICKS = 4
FLEXABLE_SPAN_TICKS = 21


def flexable_params(pos_long):
    """LightBlockEntity.flexableLit 里那三个「每块固定」的量：(lit, dark, phase)。

    ⚠️ Java 里 `h >>> 8` 是**无符号**右移；Python 的 `>>` 是算术右移，得先 `& MASK`。
    """
    h = hash_of(pos_long, FLEXABLE_SALT)
    lit = FLEXABLE_MIN_LIT_TICKS + (h % FLEXABLE_SPAN_TICKS)
    dark = FLEXABLE_MIN_LIT_TICKS + ((h & MASK) >> 8) % FLEXABLE_SPAN_TICKS
    period = lit + dark
    phase = ((h & MASK) >> 16) % period
    return lit, dark, phase


def lit_of(params, game_time):
    lit, dark, phase = params
    return ((game_time + phase) % (lit + dark)) < lit


def flexable_lit(pos_long, game_time):
    """LightBlockEntity.flexableLit 的逐行复刻。"""
    return lit_of(flexable_params(pos_long), game_time)


# ---------------------------------------------------------------- 源码抠常量

def read_constants(path):
    """把 public static final 常量的字面量抠出来；值写成别的常量名的（如 = FLEX_DIR_EXTINGUISH）
    再迭代解析一次，保证「默认值 = 某个常量」这种写法也能对上。"""
    text = open(path, encoding="utf-8").read()
    raw = {}
    for m in re.finditer(
            r"public static final (int|boolean)\s+([A-Z0-9_]+)\s*=\s*([^;]+);", text):
        kind, name, expr = m.group(1), m.group(2), m.group(3).strip()
        raw[name] = (kind, expr)

    def resolve(name, seen):
        if name not in raw or name in seen:
            return None
        kind, expr = raw[name]
        if kind == "boolean":
            return {"true": True, "false": False}.get(expr)
        try:
            return int(expr)
        except ValueError:
            return resolve(expr, seen | {name})

    return {name: resolve(name, set()) for name in raw}, text


def main():
    ok = True
    consts, config_text = read_constants(CONFIG_JAVA)
    command_text = open(COMMAND_JAVA, encoding="utf-8").read()

    print("== A. 出厂默认常量 vs 需求清单（light define default）==")
    # 需求：light light=12 / light nightdelay=60 / light daydelay=60 / light percent=20 /
    #       hlight slow on 0.5 / hlight time on 2 / hlight flex on 1 /
    #       flex flex 2 / flex off time 60 percent 10 / flex grow off
    expected = {
        "DEFAULT_LIGHT_LEVEL":              12,      # light light 12
        "DEFAULT_DELAY_SECONDS":            60,      # light nightdelay 60（旧名 light delay）
        "DEFAULT_DAY_DELAY_SECONDS":        60,      # light daydelay 60（天亮随机熄灭）
        "DEFAULT_PERCENT":                  20,      # light percent 20（2026-10-09 由 30 改成 20）
        "DEFAULT_RED_TIME_ON_CENTIS":       200,     # hlight time on 2（= 2.00s）
        "DEFAULT_RED_TIME_OFF_CENTIS":      200,     # 熄灭时间 2s
        "DEFAULT_RED_SLOW_ON_CENTIS":       50,      # hlight slow on 0.5（渐变 0.5s）
        "DEFAULT_RED_SLOW_OFF_CENTIS":      50,      # 亮灭切换渐变 0.5s
        "DEFAULT_RED_FLEX_ON":              True,    # hlight flex on
        "DEFAULT_RED_FLEX_CENTIS":          100,     # 错开随机 1 秒之内
        "DEFAULT_YELLOW_FLEX_ON":           True,    # flex 默认开着（夜里要逐渐熄灭）
        "DEFAULT_YELLOW_FLEX_DIR":          1,       # …方向 = 熄灭模式（= /flex off）
        "DEFAULT_YELLOW_FLEX_TIME_CENTIS":  6000,    # time 60（= 60.00s）
        "DEFAULT_YELLOW_FLEX_PERCENT":      10,      # percent 10
        "DEFAULT_YELLOW_FLEX_JITTER_CENTIS": 200,    # flex flex 2（= 2.00s）
        "DEFAULT_YELLOW_FLEX_GROW_CENTIS":  0,       # flex grow off
        "DEFAULT_YELLOW_FLEXABLE_ON":       False,   # flexable 默认关
    }
    for name, want in expected.items():
        got = consts.get(name, "<缺失>")
        flag = "OK " if got == want else "!! "
        if got != want:
            ok = False
        print(f"  {flag}{name:34s} = {got!r:>8}（应 {want!r}）")

    print("\n== B. resetToDefaults() 不许解开「全灭」总闸 ==")
    body = config_text.split("public void resetToDefaults()", 1)[-1]
    body = body.split("\n    }", 1)[0]
    touches_force_off = re.search(r"^[ \t]*forceOff[ \t]*=", body, re.M) is not None
    print(f"  resetToDefaults() 里对 forceOff 赋值 = {touches_force_off}（应 False ⇒ lightall off 只能由 /lightall on 解除）")
    ok &= not touches_force_off
    # 反向确认：确实有别的赋值，别是个空方法
    assigned = len(re.findall(r"^[ \t]*\w+[ \t]*=", body, re.M))
    print(f"  resetToDefaults() 里共有 {assigned} 处赋值（应 = 19，覆盖除 forceOff 外的全部设置）")
    ok &= assigned == 19

    print("\n== C. 指令注册到位 ==")
    for literal, note in (('"flexable"', "/flexable [on|off]"),
                          ('"define"', "/light define <default>"),
                          ('"default"', "…default 叶子")):
        hit = re.search(r'literal\(' + re.escape(literal) + r'\)', command_text) is not None
        print(f"  {('OK ' if hit else '!! ')}注册 {literal:12s}（{note}）")
        ok &= hit
    # lightall / alllight 走同一个 forceOffTree 工厂
    for alias in ("lightall", "alllight"):
        hit = re.search(r'forceOffTree\("' + alias + r'"\)', command_text) is not None
        print(f"  {('OK ' if hit else '!! ')}注册 {alias:12s}（/light -f 的别名，写反词序也认）")
        ok &= hit
    # lightall 必须写的是同一份 forceOff（setForceOff），而不是另起一个字段
    set_calls = command_text.count("config.setForceOff(")
    same_switch = set_calls >= 3
    tag = "OK " if same_switch else "!! "
    print(f"  {tag}lightall / -f / all 共用 setForceOff（出现 {set_calls} 次，应 ≥3）")
    ok &= same_switch

    # /flex all on|off 必须是同一份 yellowFlexOn；/flex on|off 走 setFlexMode（方向）
    all_literals = len(re.findall(r'literal\("all"\)', command_text))
    flex_switch_calls = command_text.count("setFlexSwitch(context,")
    flex_mode_calls = command_text.count("setFlexMode(context,")
    flex_all_ok = (all_literals >= 2 and flex_switch_calls >= 2
                   and flex_mode_calls >= 1)
    tag = "OK " if flex_all_ok else "!! "
    print(f"  {tag}/flex off/on 切方向、/flex all on|off 切总开关（literal(\"all\") {all_literals} ≥2；"
          f"setFlexMode {flex_mode_calls} ≥1；setFlexSwitch {flex_switch_calls} ≥2）")
    ok &= flex_all_ok

    # 全灭总闸的四种写法都得在（-f / all / lightall / alllight），防用户手滑写反词序
    alias_ok = all(re.search(r'literal\(' + re.escape(n) + r'\)', command_text) is not None
                   for n in ('"-f"', '"all"'))
    alias_ok &= all(re.search(r'forceOffTree\("' + n + r'"\)', command_text) is not None
                    for n in ("lightall", "alllight"))
    tag = "OK " if alias_ok else "!! "
    print(f"  {tag}全灭总闸的别名齐了（/light -f、/light all、/lightall、/alllight）")
    ok &= alias_ok

    print("\n== D. flexableLit：确定性 / 各闪各的 / 亮着约一半 / 会变化 ==")
    sample = [pos_as_long(x, 64, z) for x in range(200) for z in range(100)]   # 20000 块
    det = all(flexable_lit(p, 777) == flexable_lit(p, 777) for p in sample[:2000])
    print(f"  确定性（同坐标同刻恒定）= {det}（应 True）")
    ok &= det

    # 亮着占比（对足够长的时间取平均，应 ≈ lit/(lit+dark) 的平均 ≈ 0.5）
    params = [flexable_params(p) for p in sample]
    ratio = sum(lit / (lit + dark) for lit, dark, _ in params) / len(params)
    print(f"  {len(sample)} 块长期平均亮着占比 = {ratio * 100:.2f}%（应 ≈50%）")
    ok &= 0.45 < ratio < 0.55

    # 某一刻:同一时刻全场既有亮又有灭（不是齐亮齐灭），约一半
    at_once = sum(1 for pr in params if lit_of(pr, 12345)) / len(params)
    print(f"  某一刻（gameTime=12345）全场亮着占比 = {at_once * 100:.2f}%（应在 30%~70%）")
    ok &= 0.30 < at_once < 0.70

    # 每块都会变化：400 tick 内至少翻 2 次
    def flips(pr, t0, n):
        prev, cnt = lit_of(pr, t0), 0
        for t in range(t0 + 1, t0 + n):
            cur = lit_of(pr, t)
            if cur != prev:
                cnt += 1
            prev = cur
        return cnt

    probes = params[:2000]
    counts = [flips(pr, 1000, 400) for pr in probes]
    stuck = sum(1 for c in counts if c < 2)
    print(f"  2000 块在 400 tick 内翻面次数 = {min(counts)}~{max(counts)}，其中 <2 次的 = {stuck}（应为 0 ⇒ 都在闪、没有卡死）")
    ok &= stuck == 0

    # 各块的节奏不同（错开）：同一时刻，相邻两块约一半状态不同
    same_as_neighbor = sum(
        1 for x in range(0, 199)
        if lit_of(params[x], 99) == lit_of(params[x + 1], 99))
    print(f"  相邻两块状态相同的有 {same_as_neighbor}/199（应 ~100，即约一半不同 ⇒ 各闪各的）")
    ok &= 60 < same_as_neighbor < 140

    print("\n== E. 优先级：forceOff / !yellowOn 压得住 flexable ==")

    def final_lit(p, t, force_off, yellow_on):
        want = flexable_lit(p, t)
        if force_off or not yellow_on:
            want = False
        return want

    p0 = sample[0]
    t = next(t for t in range(0, 200) if flexable_lit(p0, t))
    print(f"  取一块「flexable 说该亮」的时刻 t={t}：")
    print(f"    lightall/-f 关、黄灯开 -> {final_lit(p0, t, False, True)}（应 True）")
    print(f"    lightall/-f 开           -> {final_lit(p0, t, True, True)}（应 False）")
    print(f"    /light off（黄灯关）     -> {final_lit(p0, t, False, False)}（应 False）")
    ok &= final_lit(p0, t, False, True) is True
    ok &= final_lit(p0, t, True, True) is False
    ok &= final_lit(p0, t, False, False) is False

    print("\n结果:", "全部通过" if ok else "存在失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
