"""离线复核红灯新模型（RedLightBlockEntity / LightConfig / LightCommand 的逐行 Python 复刻）。

对应这一轮的需求：红灯指令全部重写为
  /hlight time on|off X   亮起 / 熄灭时间
  /hlight slow on|off X   灭→亮 / 亮→灭 渐变时间
  /hlight flex on|off [X] 错开每块亮灭循环的起点 + 随机错开时长

⚠️ 时钟分工（固定不变）：
  · 判昼夜 -> timeOfDay (dayTime，永夜档冻在 13000)
  · 算相位 -> gameTime (游戏总刻数，每 tick 无条件 +1)

验证：
  1. 默认（亮 2.00 / 灭 2.00 / 渐变各 0.10 秒）：周期 = on+off，波形首尾连续、无突跳；
  2. 亮/灭时长可独立调，周期随之为 on+off，亮着占比 ≈ on/(on+off)；
  3. 渐变是「含在」亮/灭阶段头部的：slow>阶段长时被夹到阶段长；slow=0 退化成硬开关；
  4. 极端：on=0 ⇒ 恒灭；off=0 ⇒ 恒亮；
  5. 错开（flex）：offset 确定性、铺得开；开启后同一刻既有亮又有灭，关闭后全部一致；
  6. 解析/格式：2 位小数精确往返；档位量化（默认亮度 12）只出现 0/6/12。

跑法：python verify_hlight.py
"""

import math
import statistics

MASK = (1 << 64) - 1
NIGHT_START = 13000
NIGHT_END = 23000
TICKS_PER_SECOND = 20
CENTIS_PER_TICK = 5
MAX_RED_CENTIS = 60000

# 默认（与 LightConfig 保持一致）
DEF_TIME_ON_CENTIS = 200
DEF_TIME_OFF_CENTIS = 200
DEF_SLOW_ON_CENTIS = 10
DEF_SLOW_OFF_CENTIS = 10
DEF_FLEX_CENTIS = 400

RED_PHASE_SALT = 0x5245445F50485F52


# ------------------------------------------------------------------ 数值复刻

def _to_signed64(x):
    x &= MASK
    return x - (1 << 64) if x >= (1 << 63) else x


def splitmix(value):
    """splitmix64 三步混合（Java 用无符号右移 `>>>`；Python `>>` 是算术右移，需先转无符号）。"""
    h = _to_signed64(value)
    h = _to_signed64((h ^ ((h & MASK) >> 30)) * 0xBF58476D1CE4E5B9)
    h = _to_signed64((h ^ ((h & MASK) >> 27)) * 0x94D049BB133111EB)
    return _to_signed64(h ^ ((h & MASK) >> 31))


def hash_mix(value):
    return splitmix(value)


def hash_of(pos_long, usage):
    return splitmix(_to_signed64(usage * 0x9E3779B97F4A7C15 + pos_long))


def hash_range(pos_long, usage, bound):
    if bound <= 0:
        return 0
    return hash_of(pos_long, usage) % (bound + 1)


def pos_as_long(x, y, z):
    return _to_signed64((x & 0x3FFFFFF) | ((z & 0x3FFFFFF) << 26) | ((y & 0xFFF) << 52))


def centis_to_ticks(centis):
    """LightConfig.centisToTicks：Java Math.round（四舍五入到 +∞）→ 至少 0。"""
    return max(0, int(math.floor(centis / float(CENTIS_PER_TICK) + 0.5)))


def format_centis(centis):
    return "%d.%02d" % (centis // 100, centis % 100)


def java_round(x):
    """Java Math.round(double) = floor(x + 0.5)。"""
    return int(math.floor(x + 0.5))


# ------------------------------------------------------------------ 核心逻辑

def blink_fraction(game_time, on_ticks, off_ticks, slow_on_ticks, slow_off_ticks):
    on = max(0, on_ticks)
    off = max(0, off_ticks)
    if on <= 0:
        return 0.0
    if off <= 0:
        return 1.0
    period = on + off
    p = game_time % period
    if p < on:
        fade = min(max(0, slow_on_ticks), on)
        if fade <= 0:
            return 1.0
        return p / fade if p < fade else 1.0
    q = p - on
    fade = min(max(0, slow_off_ticks), off)
    if fade <= 0:
        return 0.0
    return 1.0 - q / fade if q < fade else 0.0


def fraction_at(time_of_day, game_time, on, off, slow_on, slow_off):
    if time_of_day < NIGHT_START or time_of_day >= NIGHT_END:
        return 0.0
    return blink_fraction(game_time, on, off, slow_on, slow_off)


def desired_level(time_of_day, game_time, on, off, slow_on, slow_off, max_level):
    return java_round(fraction_at(time_of_day, game_time, on, off, slow_on, slow_off) * max_level)


def period_of(on_ticks, off_ticks):
    return max(1, max(0, on_ticks) + max(0, off_ticks))


def desired_level_flex(pos_long, game_time, on, off, slow_on, slow_off, max_level, flex_on, flex_ticks):
    phase = game_time
    if flex_on:
        bound = min(flex_ticks, period_of(on, off))
        phase += hash_range(pos_long, RED_PHASE_SALT, bound)
    return desired_level(13000, phase, on, off, slow_on, slow_off, max_level)


# ------------------------------------------------------------------ 测试

def main():
    ok = True
    on, off = centis_to_ticks(DEF_TIME_ON_CENTIS), centis_to_ticks(DEF_TIME_OFF_CENTIS)
    so, sf = centis_to_ticks(DEF_SLOW_ON_CENTIS), centis_to_ticks(DEF_SLOW_OFF_CENTIS)

    print("== 0. 默认换算 ==")
    print(f"  亮 {format_centis(DEF_TIME_ON_CENTIS)}s = {on} tick / 灭 {format_centis(DEF_TIME_OFF_CENTIS)}s = {off} tick"
          f" / 渐变 {so}+{sf} tick；周期 {period_of(on, off)} tick = {period_of(on, off) / TICKS_PER_SECOND:.2f}s")
    ok &= (on, off, so, sf) == (40, 40, 2, 2)

    print("\n== 1. 默认波形：周期 = on+off，首尾连续无突跳 ==")
    period = period_of(on, off)
    vals = [blink_fraction(gt, on, off, so, sf) for gt in range(period)]
    max_jump = max(abs(vals[(i + 1) % period] - vals[i]) for i in range(period))
    zeros = sum(1 for v in vals if v == 0.0)
    ones = sum(1 for v in vals if v == 1.0)
    half = sum(1 for v in vals if v >= 0.5)
    print(f"  周期 {period} tick；取值 {sorted(set(vals))}")
    print(f"  满亮 {ones} tick，全灭 {zeros} tick，>=0.5 共 {half} tick")
    print(f"  相邻两 tick 最大跳变 {max_jump:.3f}（渐变 2 tick ⇒ 每步 0.5 为上限）")
    ok &= (zeros, ones, half) == (39, 39, 41) and max_jump <= 0.5 + 1e-9

    print("\n== 2. 亮/灭时长独立可调，亮着占比 ≈ on/(on+off) ==")
    for on_c, off_c in [(100, 200), (200, 200), (400, 200), (100, 600)]:
        o, f = centis_to_ticks(on_c), centis_to_ticks(off_c)
        per = period_of(o, f)
        v = [blink_fraction(gt, o, f, 2, 2) for gt in range(per)]
        lit = sum(1 for x in v if x >= 0.5) / per * 100
        expect = o / per * 100
        print(f"  亮 {format_centis(on_c)}s/灭 {format_centis(off_c)}s -> 周期 {per} tick，实测亮 {lit:.1f}%（理论 {expect:.1f}%）")
        ok &= abs(lit - expect) < 3

    print("\n== 3. 渐变含在阶段头部；超长被夹；0 = 硬开关 ==")
    v = [blink_fraction(gt, 40, 40, 100, 100) for gt in range(80)]
    print(f"  slow=100(>阶段) 时前 6 tick = {v[:6]}")
    # fade 被夹成 40 = 整个亮起阶段，所以整段都在爬升；熄灭阶段一开头就是 1.0
    ok &= abs(v[0] - 0.0) < 1e-9 and abs(v[39] - 39 / 40) < 1e-9 and abs(v[40] - 1.0) < 1e-9 and abs(v[41] - 39 / 40) < 1e-9
    hard = [blink_fraction(gt, 40, 40, 0, 0) for gt in range(80)]
    print(f"  slow=0 时取值集合 = {sorted(set(hard))}（应只有 0.0 / 1.0）")
    ok &= set(hard) == {0.0, 1.0}
    # 渐变只影响头部：slow=6 时，p=0..5 是斜坡、p>=6 满亮
    ramp = [blink_fraction(gt, 40, 40, 6, 6) for gt in range(8)]
    print(f"  slow=6 时前 8 tick = {[round(x, 3) for x in ramp]}")
    ok &= abs(ramp[1] - 1 / 6) < 1e-9 and abs(ramp[6] - 1.0) < 1e-9

    print("\n== 4. 极端：on=0 恒灭 / off=0 恒亮 ==")
    a = {blink_fraction(gt, 0, 40, 2, 2) for gt in range(80)}
    b = {blink_fraction(gt, 40, 0, 2, 2) for gt in range(80)}
    print(f"  on=0 取值集合 {a}（应 {{0.0}}）；off=0 取值集合 {b}（应 {{1.0}}）")
    ok &= a == {0.0} and b == {1.0}

    print("\n== 5. 昼夜判定：白天恒 0 ==")
    day = {fraction_at(tod, gt, on, off, so, sf) for tod in (0, 6000, 12999, 23000, 23999) for gt in range(0, 80, 7)}
    print(f"  白天/边界取值集合 = {day}（应 {{0.0}}）")
    ok &= day == {0.0}

    print("\n== 6. 错开（flex）：确定性 + 铺得开 + 关闭时全一致 ==")
    sample = [pos_as_long(x, 64, 0) for x in range(2000)]
    # 确定性
    det = all(hash_range(p, RED_PHASE_SALT, 80) == hash_range(p, RED_PHASE_SALT, 80) for p in sample[:500])
    offs = [hash_range(p, RED_PHASE_SALT, 80) for p in sample]
    distinct = len(set(offs))
    in_range = min(offs) >= 0 and max(offs) <= 80
    print(f"  确定性 {det}；offset 取值 {distinct} 种，范围 [{min(offs)}, {max(offs)}]（应 ⊆ [0, 80]）")
    ok &= det and distinct > 40 and in_range

    gt = 500_000
    lv_flex = [desired_level_flex(p, gt, on, off, so, sf, 12, True, 80) for p in sample]
    lv_sync = [desired_level_flex(p, gt, on, off, so, sf, 12, False, 80) for p in sample]
    lit_flex = sum(1 for x in lv_flex if x > 0) / len(sample) * 100
    print(f"  开启 flex：同一刻出现档位 {sorted(set(lv_flex))}，亮着比例 {lit_flex:.1f}%"
          f"（on/(on+off)≈{on / (on + off) * 100:.0f}%）")
    print(f"  关闭 flex：同一刻档位 {sorted(set(lv_sync))}（应只有 1 种 = 全世界同步）")
    ok &= len(set(lv_flex)) > 1 and len(set(lv_sync)) == 1 and 35 < lit_flex < 65

    # 错开量 X 会被夹到一个完整周期内
    clamped = min(600 * TICKS_PER_SECOND, period_of(on, off))
    offs2 = [hash_range(p, RED_PHASE_SALT, clamped) for p in sample]
    print(f"  X=600s 时夹到周期 {clamped} tick；offset 范围 [{min(offs2)}, {max(offs2)}]")
    ok &= max(offs2) <= clamped

    print("\n== 7. 解析 / 格式：2 位小数精确往返 ==")
    bad = []
    for s in ["0.05", "0.10", "2.00", "4.00", "0.30", "600.00", "1.234"]:
        from decimal import Decimal, ROUND_HALF_UP
        c = int((Decimal(s) * 100).quantize(Decimal(1), rounding=ROUND_HALF_UP))
        back = format_centis(c)
        bad.append((s, c, back))
    for s, c, back in bad:
        print(f"  {s!r:>9} -> {c:>6} centis -> {back!r}")
    ok &= format_centis(int(Decimal('0.30') * 100)) == "0.30"
    ok &= format_centis(int(Decimal('1.234') * 100 + Decimal('0.5'))) == "1.23"

    print("\n== 8. 档位量化：默认亮度 12 ⇒ 只出现 0 / 6 / 12 ==")
    per = period_of(on, off)
    lv = [desired_level(13000, gt, on, off, so, sf, 12) for gt in range(per)]
    print(f"  一个周期里的档位 = {sorted(set(lv))}")
    ok &= set(lv) == {0, 6, 12}

    print("\n结果:", "全部通过" if ok else "存在失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
