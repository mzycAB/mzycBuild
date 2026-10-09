"""离线复核黄灯「夜间随机重分配」（/flex … 与 LightBlockEntity 的逐行 Python 复刻）。

对应这一轮的需求（2026-10-09 定稿）：
  /light on|off            黄灯总开关
  /light h on|off          红灯总开关
  /light -f / lightall / alllight on|off   全灭（黄/红一律不亮，无视一切设置）
  /flex off time Y [percent X]  熄灭模式（生效、出厂默认）：入夜第一次亮灯后每 Y 秒，
                                在「本晚被点亮」的灯里熄灭当前还亮着的 X%（只越点越少）
  /flex on  time Y [percent X]  点亮模式（生效）：全部黄灯按 /light percent 重新分配点亮/熄灭
  /flex all off            彻底关闭 flex 功能（一整夜只掷一次骰）
  /flex flex X             每块 ±X 秒固定偏差（避免同一瞬间一起变）
  /flex grow X             每轮间隔递增：第 k 轮间隔 = Y + (k-1)·X（可负 -> 间隔变短）

验证：
  1. flexRoundAt 的轮次边界：grow=+5 -> 间隔 60/65/70…；grow=-5 -> 60/55/50…；
     且带「间隔夹到 ≥1 tick」的短路版与朴素累计版结果完全一致；
  2. 第 0 轮 == 老的「今晚掷骰」（保证开了 flex 的第一次亮灯不变）；
  3. 第 ≥1 轮：同一块同一轮恒定（轮内不闪），不同轮结果不同（确实在洗牌）；
  4. 亮灯规则：熄灭模式 -> 只在本晚点亮集里每轮熄灭「当前还亮着」的 X%（绝不点亮本晚没亮的，越点越少）；
     点亮模式 -> 全场按 /light percent 重新分配（有灭也有亮）；
  4e. 收尾：剩得不够一盏（n*X% < 1）时剩下的全灭，不会留零星鬼火；
  5. 偏差 jitter：确定性、落在 [-X, +X]、=0 时为 0；
  6. 总开关：forceOff 或 !yellowOn 时恒不亮；
  7. 出厂默认（熄灭 60s/10%、点亮比例 20%）下，一整夜确实单调变暗并最终归零
     —— 即用户要的「夜里不会亮一次就不熄灭」。

跑法：python verify_flex.py
"""

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_level_delay import _to_signed64, pos_as_long, night_hash, rolls_on, MASK  # noqa: E402
from verify_hlight import hash_mix, hash_of, hash_range                              # noqa: E402

TICKS_PER_SECOND = 20
CENTIS_PER_TICK = 5

FLEX_ROUND_SALT = 0x464C45585F524E44       # "FLEX_RND"
FLEX_JITTER_SALT = 0x464C45585F4A4954      # "FLEX_JIT"
YELLOW_FLEX_PERCENT_UNSET = -1

# 两个正交的开关（与 LightConfig 一致）：功能是否生效 + 方向
FLEX_DIR_EXTINGUISH = 1                     # /flex off —— 熄灭模式（出厂默认）
FLEX_DIR_LIGHT = 2                          # /flex on  —— 点亮模式


# ------------------------------------------------------------------ 数值复刻

def centis_to_ticks(centis):
    import math
    return max(0, int(math.floor(centis / float(CENTIS_PER_TICK) + 0.5)))


def flex_round_at(since_first_light, first_interval_ticks, grow_ticks):
    """LightBlockEntity.flexRoundAt 的复刻（含 grow<=0 且间隔夹到 1 时的线性短路）。"""
    if since_first_light <= 0:
        return 0
    acc = 0
    interval = max(1, first_interval_ticks)
    rnd = 0
    while True:
        step = max(1, interval)
        if acc + step > since_first_light:
            return rnd
        acc += step
        rnd += 1
        if step <= 1 and grow_ticks <= 0:
            return rnd + (since_first_light - acc)
        if rnd >= 100000:
            return rnd
        interval += grow_ticks


def flex_round_at_naive(since_first_light, first_interval_ticks, grow_ticks):
    """朴素累计版（无短路），用来交叉核对 flex_round_at。"""
    if since_first_light <= 0:
        return 0
    acc = 0
    interval = max(1, first_interval_ticks)
    rnd = 0
    while True:
        step = max(1, interval)
        if acc + step > since_first_light:
            return rnd
        acc += step
        rnd += 1
        if rnd > 5_000_000:
            return rnd
        interval += grow_ticks


def round_hash(pos_long, night_index, rnd):
    if rnd <= 0:
        return night_hash(pos_long, night_index)
    salt = hash_mix(_to_signed64(night_index * 0x9E3779B97F4A7C15 + FLEX_ROUND_SALT + rnd))
    return hash_of(pos_long, salt)


def rolls_on_round(pos_long, night_index, rnd, lit_percent):
    if lit_percent <= 0:
        return False
    if lit_percent >= 100:
        return True
    return (round_hash(pos_long, night_index, rnd) % 100) < lit_percent


EXTINGUISH_SALT = 0x455854494E475348       # "EXTINGSH"


def flex_end_round(night_lit_count, extinguish_percent):
    """LightBlockEntity.flexEndRound 的复刻：计数过程 n ← n - n*X//100，不足 1 盏就全灭。"""
    if extinguish_percent <= 0:
        return 1 << 62
    if extinguish_percent >= 100:
        return 1
    if night_lit_count <= 0:
        return 1 << 62
    n = night_lit_count
    rnd = 0
    while n > 0:
        killed = n * extinguish_percent // 100
        rnd += 1
        if killed <= 0:
            break
        n -= killed
    return max(1, rnd)


def extinguish_round(pos_long, night_index, extinguish_percent, night_lit_count):
    """LightBlockEntity.extinguishRound 的复刻：几何分布 + 收尾轮次夹取。"""
    if extinguish_percent <= 0:
        return 1 << 62
    if extinguish_percent >= 100:
        return 1
    survive = 1.0 - extinguish_percent / 100.0
    salt = hash_mix(_to_signed64(night_index * 0x9E3779B97F4A7C15 + EXTINGUISH_SALT))
    bits = hash_of(pos_long, salt) & MASK
    u = (((bits >> 40) & 0xFFFFFF) + 0.5) / 16777216.0
    draw = 1 + math.floor(math.log(u) / math.log(survive))
    return min(draw, flex_end_round(night_lit_count, extinguish_percent))


def flex_round_lit(pos_long, night_index, rnd, night_roll, direction, extinguish_percent, percent,
                   night_lit_count=0):
    """LightBlockEntity.flexRoundLit 的复刻（第 rnd ≥ 1 轮亮不亮）。

    direction == FLEX_DIR_LIGHT  -> 点亮模式：全部黄灯按 /light percent 重新分配（有灭也有亮）
    否则                          -> 熄灭模式：本晚点亮集里每轮熄灭还亮着的 X%（只灭不亮）
    """
    if direction == FLEX_DIR_LIGHT:
        return rolls_on_round(pos_long, night_index, rnd, percent)
    return bool(night_roll) and rnd < extinguish_round(pos_long, night_index, extinguish_percent,
                                                       night_lit_count)


def flex_jitter_ticks(pos_long, jitter):
    if jitter <= 0:
        return 0
    # Hash.range 返回 [0, bound] 闭区间（bound+1 个取值）：要 [-j, +j] -> bound = 2j
    span = 2 * jitter
    return hash_range(pos_long, FLEX_JITTER_SALT, span) - jitter


def round_start_ticks(rnd, first_interval, grow):
    """第 rnd 轮之前的累计时间（用于人眼核对边界）。"""
    acc, interval = 0, max(1, first_interval)
    for _ in range(rnd):
        acc += max(1, interval)
        interval += grow
    return acc


# ------------------------------------------------------------------ 测试

def main():
    ok = True
    sample = [pos_as_long(x, 64, 0) for x in range(20000)]
    night = 100

    print("== 1. flexRoundAt 轮次边界（Y=60s，grow=+5s / -5s）==")
    y = centis_to_ticks(6000)          # 60s -> 1200 tick
    grow_p = centis_to_ticks(500)      # +5s -> 100 tick
    grow_n = -centis_to_ticks(500)     # -5s -> -100 tick
    for label, grow, expect_starts in (
            ("grow=+5", grow_p, [0, 1200, 2500, 3900]),
            ("grow=-5", grow_n, [0, 1200, 2300, 3300])):
        starts = [round_start_ticks(k, y, grow) for k in range(4)]
        print(f"  {label}: 第0..3轮起点 = {starts} tick（= {[s / TICKS_PER_SECOND for s in starts]} s）")
        ok &= starts == expect_starts
    # 边界归属：第 1 轮从 1200 开始，第 2 轮从 2500 开始（grow=+5）
    probes = {1199: 0, 1200: 1, 2499: 1, 2500: 2, 3899: 2, 3900: 3}
    got = {t: flex_round_at(t, y, grow_p) for t in probes}
    print(f"  grow=+5 边界归属 = {got}")
    ok &= got == probes

    print("\n== 1b. 短路版 vs 朴素累计版（多种参数，含间隔被夹到 1）==")
    mismatch = 0
    for first in (1, 5, 400, 1200, 9999):
        for grow in (-60000, -5000, -100, 0, 1, 100, 5000):
            for t in (0, 1, 7, 999, 1200, 12345, 60000, 240000):
                a = flex_round_at(t, first, grow)
                b = flex_round_at_naive(t, first, grow)
                if a != b:
                    mismatch += 1
                    if mismatch <= 5:
                        print(f"  MISMATCH t={t} first={first} grow={grow}: {a} vs {b}")
    print(f"  穷举 5×7×8 = 280 组，不一致 {mismatch} 组")
    ok &= mismatch == 0

    print("\n== 2. 第 0 轮 == 老的「今晚掷骰」（开了 flex 第一次亮灯不变）==")
    diff = sum(1 for p in sample if rolls_on_round(p, night, 0, 50) != rolls_on(p, night, 50))
    print(f"  500 块里第 0 轮与 rollsOn 不同 = {diff}（应为 0）")
    ok &= diff == 0

    print("\n== 3. 轮内恒定 / 跨轮变化（确实在重分配）==")
    stable = all(rolls_on_round(p, night, 3, 50) == rolls_on_round(p, night, 3, 50) for p in sample[:2000])
    changed_13 = sum(1 for p in sample if rolls_on_round(p, night, 1, 50) != rolls_on_round(p, night, 3, 50))
    print(f"  轮内恒定: {stable}；第1轮 vs 第3轮 翻面比例 {changed_13 / len(sample) * 100:.1f}%（应 ~50%）")
    ok &= stable and 40 < changed_13 / len(sample) * 100 < 60
    # 每一轮自己的亮灯比例都贴 50%
    for rnd in (1, 2, 3, 7):
        lit = sum(1 for p in sample if rolls_on_round(p, night, rnd, 50)) / len(sample) * 100
        ok &= abs(lit - 50) < 0.6

    print("\n== 4. 第 ≥1 轮亮灯规则 ==")
    # 4a. 点亮模式 -> 对全部方块按 /light percent 重新分配（含本晚原本没亮的）
    for p in (30, 50):
        lit = sum(1 for q in sample if flex_round_lit(q, night, 1, False, FLEX_DIR_LIGHT, 0, p))
        got = lit / len(sample) * 100
        print(f"  点亮模式，/light percent={p} -> 全场亮 {got:6.2f}%（应 ~{p}%；本晚没亮的也会被点亮）")
        ok &= abs(got - p) < 0.6
    # 4b. 熄灭模式指定 percent X -> 本晚点亮集里累积熄灭，活过 R 轮 ≈ (1-X/100)^R
    night_lit = [q for q in sample if rolls_on(q, night, 30)]          # 本晚点亮集（P=30）
    print(f"  （本晚 30% 命中 => 点亮集 {len(night_lit)} / {len(sample)} = {len(night_lit) / len(sample) * 100:.1f}%）")
    for x in (0, 30, 50, 80, 100):
        q = (100 - x) / 100.0
        nl = len(night_lit)
        line = [f"percent {x:>3}:"]
        for rnd in (1, 2, 3):
            surv = sum(1 for p in night_lit if flex_round_lit(p, night, rnd, True, FLEX_DIR_EXTINGUISH, x, 30, nl))
            frac = surv / len(night_lit) * 100 if night_lit else 0.0
            line.append(f"R{rnd}={frac:5.1f}%(理论 {q ** rnd * 100:5.1f}%)")
            if 0 < q < 1:
                ok &= abs(frac - q ** rnd * 100) < 1.0
        print("  " + "  ".join(line))
    # 4c. 关键不变式 1：熄灭模式绝不会点亮「本晚原本没亮」的灯
    nl = len(night_lit)
    leak = sum(1 for p in sample if (not rolls_on(p, night, 30))
               and any(flex_round_lit(p, night, rnd, False, FLEX_DIR_EXTINGUISH, 50, 30, nl)
                       for rnd in (1, 2, 3, 5)))
    print(f"  「本晚没亮」的方块在熄灭模式后续轮次被点亮次数 = {leak}（应为 0）")
    ok &= leak == 0
    # 4d. 关键不变式 2：越点越暗 —— 第 R+1 轮点亮集 ⊆ 第 R 轮点亮集
    nonmono = 0
    for p in night_lit:
        for rnd in (1, 2, 5, 9):
            if (flex_round_lit(p, night, rnd + 1, True, FLEX_DIR_EXTINGUISH, 50, 30, nl)
                    and not flex_round_lit(p, night, rnd, True, FLEX_DIR_EXTINGUISH, 50, 30, nl)):
                nonmono += 1
    print(f"  「下一轮亮了但本轮没亮」的方块次数 = {nonmono}（应为 0 = 单调变暗）")
    ok &= nonmono == 0

    print("\n== 4e. 收尾：X% 不足一盏时「剩下的全灭」 ==")
    for n in (1, 2, 3, 4, 5, 10, 100):
        end = flex_end_round(n, 20)
        print(f"  {n:>4} 盏 / 每轮灭 20% -> 第 {end} 轮全灭")
    # 「小数就全灭」：n·20% < 1 时第 1 轮就全灭
    ok &= all(flex_end_round(n, 20) == 1 for n in (1, 2, 3, 4))
    ok &= flex_end_round(5, 20) == 2          # 5->4 (灭1) -> 4*20%=0.8 小数 -> 全灭
    ok &= flex_end_round(100, 20) == 18
    ok &= flex_end_round(0, 20) == (1 << 62)  # 数不出灯数时绝不收尾（否则会把全场灭掉）
    # 用真实采样验证：到 R_end 轮时一个都不剩，且 R_end-1 轮还有灯
    for x in (20, 50):
        end = flex_end_round(nl, x)
        alive_end = sum(1 for p in night_lit
                        if flex_round_lit(p, night, end, True, FLEX_DIR_EXTINGUISH, x, 30, nl))
        alive_before = sum(1 for p in night_lit
                           if flex_round_lit(p, night, end - 1, True, FLEX_DIR_EXTINGUISH, x, 30, nl))
        print(f"  采样 {nl} 盏 / X={x}: 第 {end - 1} 轮还剩 {alive_before} 盏，第 {end} 轮剩 {alive_end} 盏（应为 0）")
        ok &= alive_end == 0 and alive_before > 0

    print("\n== 5. 偏差 jitter：确定性 / 落在 [-X, +X] / =0 时为 0 ==")
    for j in (0, 100, 1200):
        vals = [flex_jitter_ticks(p, j) for p in sample]
        det = all(flex_jitter_ticks(p, j) == flex_jitter_ticks(p, j) for p in sample[:500])
        print(f"  X={j:>4} tick -> 范围 [{min(vals)}, {max(vals)}]（应 ⊆ [{-j}, {j}]），确定性 {det}")
        ok &= min(vals) >= -j and max(vals) <= j and det
    ok &= all(flex_jitter_ticks(p, 0) == 0 for p in sample[:500])

    print("\n== 6. 总开关：forceOff / !yellowOn 时恒不亮 ==")
    def final_lit(force_off, yellow_on, round_no, lp):
        """复刻 LightBlockEntity.tick 的最终判定（用点亮模式 + lp=100 让「本应亮」成为确定事件）。"""
        want = round_no <= 0 or flex_round_lit(sample[0], night, round_no, True,
                                               FLEX_DIR_LIGHT, 0, lp)
        want = bool(want)
        if force_off or not yellow_on:
            want = False
        return want

    print(f"  黄开+红/全灭关 -> {final_lit(False, True, 2, 100)}（应 True）")
    print(f"  forceOff 开     -> {final_lit(True, True, 2, 100)}（应 False）")
    print(f"  yellowOn 关     -> {final_lit(False, False, 2, 100)}（应 False）")
    ok &= final_lit(False, True, 2, 100) is True
    ok &= final_lit(True, True, 2, 100) is False
    ok &= final_lit(False, False, 2, 100) is False

    print("\n== 7. 出厂默认（熄灭模式 60s / 10%、点亮比例 20%）→ 夜里灯确实越来越少直到全灭 ==")
    night2 = 200
    lit0 = [q for q in sample if rolls_on(q, night2, 20)]        # 入夜第一次亮灯（percent 20）
    nl2 = len(lit0)
    print(f"  入夜点亮 {nl2} / {len(sample)} = {nl2 / len(sample) * 100:.1f}%（percent 20）")
    ok &= abs(nl2 / len(sample) * 100 - 20) < 0.6
    interval = centis_to_ticks(6000)                             # 60s -> 1200 tick
    end_round = flex_end_round(nl2, 10)                          # 理论「走到全灭」的轮次
    timeline = []
    for rnd in range(0, end_round + 1):
        alive = nl2 if rnd == 0 else sum(
            1 for p in lit0 if flex_round_lit(p, night2, rnd, True, FLEX_DIR_EXTINGUISH, 10, 20, nl2))
        timeline.append(alive)
        if rnd <= 14 or alive == 0 or rnd == end_round:
            print(f"    第 {rnd:>2} 轮（入夜后 {rnd * interval / 20:6.1f} 秒）：还亮着 {alive:>5} / {nl2}")
    print(f"  理论收尾轮次 = {end_round}；单调不增 = "
          f"{all(timeline[i] >= timeline[i + 1] for i in range(len(timeline) - 1))}；"
          f"最终归零 = {timeline[-1] == 0}")
    ok &= all(timeline[i] >= timeline[i + 1] for i in range(len(timeline) - 1))
    ok &= timeline[-1] == 0
    # 每轮大约剩 90%（10% 熄灭）—— 第 1 轮就该掉掉 ~10%
    drop1 = (nl2 - timeline[1]) / nl2 * 100 if nl2 else 0
    print(f"  第 1 轮灭掉的占比 = {drop1:.1f}%（应 ≈10%）")
    ok &= 5 < drop1 < 15

    print("\n结果:", "全部通过" if ok else "存在失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
