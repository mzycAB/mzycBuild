"""离线复核「永夜档」下两种灯的实际表现（LightBlockEntity / RedLightBlockEntity 的逐行复刻）。

这是第 5 轮修复的核心回归脚本，专门证明「黄灯红灯都不亮」的根因已被钉死并修掉：

  背景（建筑档常见配置）：/time set night + /gamerule doDaylightCycle false
    -> timeOfDay (dayTime) 冻在 13000；gameTime (getTime()) 每 tick 照走。

  根因（修复前）：
    黄灯 用 timeOfDay - NIGHT_START 当「已等时长」 => 恒等 0 => 延迟永远等不到；
    红灯 用 timeOfDay 当闪烁相位             => blinkFraction(13000) 恒为 0 => 永灭。

  本脚本验证修复后（判昼夜用 dayTime，算时长/相位一律用 gameTime）：
    A. 永夜档里黄灯延迟会真正走完，走完后一直亮，不会抽搐；
    B. 延迟「入夜锁存」修掉了跨 gameTime/24000 边界时的目标跳变（边缘档）；
    C. 30% 命中率下，被点亮方块比例约 30%；
    D. 正常昼夜循环档里，入夜锁存 / 天亮解锁 依然正确；
    E. 永夜档里红灯相位照常推进（修复前恒 0）。

跑法：python verify_night_fix.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_level_delay import rolls_on, delay_ticks_on, pos_as_long    # noqa: E402
from verify_hlight import fraction_at, desired_level                     # noqa: E402

NIGHT_START = 13000
NIGHT_END = 23000
TICKS_PER_SECOND = 20
CHECK_INTERVAL = 5          # LightBlockEntity.tick 的实际判定步长
DAY_TICKS = 24000


def simulate_yellow(pos_long, percent, delay_seconds, start_gt, ticks,
                    time_of_day_of, latch_delay=True):
    """LightBlockEntity.tick 的逐行复刻。

    time_of_day_of(gt) -> 该时刻的 dayTime（永夜档恒 13000；正常档按 24000 循环）。
    latch_delay=True 为当前实现（延迟入夜时锁存）；
    latch_delay=False 复刻旧写法（延迟每 tick 用 gt//24000 重算）。
    """
    latched = False
    started = 0
    roll = False
    latched_delay = 0
    states = []
    for gt in range(start_gt, start_gt + ticks, CHECK_INTERVAL):
        tod = time_of_day_of(gt)
        night = NIGHT_START <= tod < NIGHT_END
        if not night:
            if latched:
                latched = False
        elif not latched:
            latched = True
            started = gt
            ni = gt // DAY_TICKS
            roll = rolls_on(pos_long, ni, percent)
            latched_delay = delay_ticks_on(pos_long, ni, delay_seconds)
        elapsed = (gt - started) if night else 0
        delay = latched_delay if latch_delay else delay_ticks_on(pos_long, gt // DAY_TICKS, delay_seconds)
        states.append(bool(night and roll and elapsed >= delay))
    return states


def toggles_after_first_lit(states):
    """第一次点亮之后，状态翻转了几次；返回 (翻转次数, 末态是否亮)。从未点亮返回 (-1, False)。"""
    first = next((i for i, s in enumerate(states) if s), None)
    if first is None:
        return -1, False
    t = sum(1 for i in range(first + 1, len(states)) if states[i] != states[i - 1])
    return t, states[-1]


def main():
    ok = True
    eternal = lambda gt: 13000                      # 永夜：dayTime 永远是 13000
    normal = lambda gt: (gt + 6000) % DAY_TICKS     # 正常：dayTime 跟着循环
    pos = pos_as_long(0, 64, 0)

    print("== A. 永夜档：黄灯延迟走完后必须稳定长亮、不抽搐 ==")
    start, span = 100_000, 2 * DAY_TICKS            # 连续观察 40 分钟
    states = simulate_yellow(pos, 100, 60, start, span, eternal, latch_delay=True)
    t, lit = toggles_after_first_lit(states)
    first_lit = next((i for i, s in enumerate(states) if s), None)
    print(f"  100% 命中 / 延迟上限 60s：")
    print(f"    首次点亮于观察窗第 {first_lit * CHECK_INTERVAL} tick（应 <= 1200 = 60s）")
    print(f"    首次点亮后翻转 {t} 次，末态亮={lit}")
    ok &= t == 0 and lit is True and first_lit is not None and first_lit * CHECK_INTERVAL <= 1200

    print("\n== B. 延迟「入夜锁存」修掉跨 gameTime/24000 边界的目标跳变 ==")
    # 让锁存发生在紧贴 24000 边界之前，使旧写法在边界处重新抽一个延迟，可能 > 已等时长 => 灭一下。
    boundary_start = DAY_TICKS - 1000
    new_s = simulate_yellow(pos, 100, 60, boundary_start, 4 * DAY_TICKS, eternal, latch_delay=True)
    old_s = simulate_yellow(pos, 100, 60, boundary_start, 4 * DAY_TICKS, eternal, latch_delay=False)
    nt, nlit = toggles_after_first_lit(new_s)
    print(f"  当前实现（延迟锁存）   : 首次点亮后翻转 {nt} 次，末态亮={nlit}")
    # 扫一批坐标，统计旧写法里有多少会因边界换延迟而抽搐
    sample = [pos_as_long(x, 64, 0) for x in range(3000)]
    flicker = 0
    for p in sample:
        ot, _ = toggles_after_first_lit(
            simulate_yellow(p, 100, 60, boundary_start, 4 * DAY_TICKS, eternal, latch_delay=False))
        if ot > 0:
            flicker += 1
    print(f"  旧写法（延迟每 tick 重算）: 3000 块里有 {flicker} 块在边界处出现过翻转")
    print(f"  ⇒ 锁存把这类『等了一半目标突然被换掉』的抽搐彻底消除")
    ok &= nt == 0 and nlit is True

    print("\n== C. 永夜档：30% 命中率 => 约 30% 方块被点亮 ==")
    lit = 0
    for p in sample:
        s = simulate_yellow(p, 30, 60, start, span, eternal)
        lit += 1 if s[-1] else 0
    ratio = lit / len(sample) * 100
    print(f"  {len(sample)} 块里末态点亮 {lit} 块 = {ratio:.1f}%（设置 30%）")
    ok &= abs(ratio - 30) < 3

    print("\n== D. 正常昼夜循环档：入夜锁存 / 天亮解锁 依然正确 ==")
    s = simulate_yellow(pos, 100, 0, 0, 3 * DAY_TICKS, normal)
    on_night = lit_night = on_day = lit_day = 0
    for i, v in enumerate(s):
        gt = i * CHECK_INTERVAL
        if NIGHT_START <= normal(gt) < NIGHT_END:
            on_night += 1
            lit_night += 1 if v else 0
        else:
            on_day += 1
            lit_day += 1 if v else 0
    print(f"  夜晚判定 {on_night} 次，点亮 {lit_night} 次（100%/0s 应全部点亮）")
    print(f"  白天判定 {on_day} 次，点亮 {lit_day} 次（应恒为 0）")
    ok &= lit_night == on_night and lit_day == 0

    print("\n== E. 永夜档：红灯相位照常推进（修复前恒 0）==")
    frozen_t = 13000
    on = off = 40                   # 默认 /hlight time on|off 2.00 秒
    so = sf = 2                     # 默认 /hlight slow on|off 0.10 秒
    per = on + off                  # 一个完整周期
    vals = [desired_level(frozen_t, gt, on, off, so, sf, 12) for gt in range(100_000, 100_000 + per)]
    on_ticks = sum(1 for gt in range(per) if fraction_at(frozen_t, gt, on, off, so, sf) > 0.5)
    print(f"  一个周期（{per} tick）里出现过的档位 = {sorted(set(vals))}")
    print(f"  其中 fraction>0.5 的 tick = {on_ticks} / {per}（约一半 = 亮 {on / TICKS_PER_SECOND:.1f} 秒）")
    print(f"  修复前 blinkFraction(13000) = 0.00 ⇒ 档位恒为 [0]（永灭）")
    # 亮度 12 时：0.1 秒 = 2 tick 的渐变只能写出 0 / 6 / 12 三档（引擎极限）
    ok &= max(vals) > 0 and len(set(vals)) >= 3 and per * 0.4 <= on_ticks <= per * 0.6

    print("\n结果:", "全部通过" if ok else "存在失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
