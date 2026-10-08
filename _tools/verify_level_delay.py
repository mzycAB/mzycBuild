"""离线复核 LightBlockEntity 的 `rollsOn` / `delayTicksOn` 与 LightConfig 的夹取逻辑。

这是 LightBlockEntity.java 里 splitmix64 散列的逐行 Python 复刻，用来在不开游戏的前提下
验证黄灯那套设置（`/light light` 亮度、`/light delay` 延迟秒数、`/light percent` 比例）的行为：

  1. delayTicksOn 的取值范围必须是 [0, delaySeconds*20]，且分布均匀、确定性（同晚同坐标恒定）；
  2. rollsOn 的命中率要贴着 percent（0% 恒不亮、100% 恒亮）；
  3. 不同夜晚结果会变（不是一次算完就写死）；
  4. 延迟上限 60 秒（默认）→ 最大 1200 tick，平均 ~600 tick。

跑法：python verify_level_delay.py
"""

import statistics

MASK = (1 << 64) - 1

NIGHT_START = 13000
NIGHT_END = 23000
TICKS_PER_SECOND = 20


def _to_signed64(x):
    x &= MASK
    return x - (1 << 64) if x >= (1 << 63) else x


def splitmix(value):
    """splitmix64 三步混合（与 Java 里 splitmix 完全一致，按 64 位有符号回绕）。

    ⚠️ Java 用的是**无符号**右移 `>>>`；Python 的 `>>` 是算术右移，
    负数会补 1。所以这里一律先 `& MASK` 转无符号再右移，否则最高位永远被抹成 0
    （会让「取高位」的用法系统性偏斜）。
    """
    h = _to_signed64(value)
    h = _to_signed64((h ^ ((h & MASK) >> 30)) * 0xBF58476D1CE4E5B9)
    h = _to_signed64((h ^ ((h & MASK) >> 27)) * 0x94D049BB133111EB)
    return _to_signed64(h ^ ((h & MASK) >> 31))


def pos_as_long(x, y, z):
    """BlockPos.asLong()：x 占低 26 位、z 占中 26 位、y 占高 12 位，各自取低 26/12 位。"""
    px = x & 0x3FFFFFF
    pz = z & 0x3FFFFFF
    py = y & 0xFFF
    return _to_signed64(px | (pz << 26) | (py << 52))


def night_hash(pos_long, night_index):
    return splitmix(_to_signed64(night_index * 0x9E3779B97F4A7C15 + pos_long))


def rolls_on(pos_long, night_index, percent):
    if percent <= 0:
        return False
    if percent >= 100:
        return True
    return (night_hash(pos_long, night_index) % 100) < percent


def delay_ticks_on(pos_long, night_index, delay_seconds):
    max_ticks = max(0, delay_seconds) * TICKS_PER_SECOND
    return night_hash(pos_long, night_index) % (max_ticks + 1)


def clamp(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


# ------------------------------------------------------------------ 采样

def scan(n=20000):
    coords = [(x, 64, z) for x in range(n) for z in (0,)]  # 沿 x 排布的一排方块
    return [pos_as_long(*c) for c in coords]


def main():
    sample = scan(20000)
    ok = True

    print("== 1. delayTicksOn 范围 / 均匀性（默认 60 秒）==")
    for secs in (0, 1, 60, 600):
        max_ticks = secs * TICKS_PER_SECOND
        vals = [delay_ticks_on(p, 100, secs) for p in sample]
        lo, hi = min(vals), max(vals)
        mean = statistics.fmean(vals)
        in_range = 0 <= lo and hi <= max_ticks
        print(f"  delay={secs:>4}s -> 实际 [{lo}, {hi}] (允许 [0, {max_ticks}]), 均值 {mean:.1f}"
              f"{'  OK' if in_range else '  <<< 越界!'}")
        ok &= in_range
        if secs == 60:
            ok &= abs(mean - max_ticks / 2) < max_ticks * 0.03

    print("\n== 2. 确定性：同一 (坐标, 第几晚) 反复算都一样 ==")
    stable = all(delay_ticks_on(p, 100, 60) == delay_ticks_on(p, 100, 60) for p in sample[:2000])
    print(f"  同晚稳定: {stable}")
    ok &= stable

    print("\n== 3. 命中率贴合 percent ==")
    for pct in (0, 10, 30, 50, 70, 100):
        hit = sum(1 for p in sample if rolls_on(p, 100, pct))
        got = hit / len(sample) * 100
        print(f"  percent={pct:>3}% -> 实测 {got:6.2f}%"
              f"{'  OK' if abs(got - pct) < (0.6 if 0 < pct < 100 else 0.001) else '  <<< 偏差过大'}")
    ok &= abs(sum(1 for p in sample if rolls_on(p, 100, 50)) / len(sample) * 100 - 50) < 0.6

    print("\n== 4. 换一晚结果会变（不是写死）==")
    changed = sum(1 for p in sample
                  if rolls_on(p, 100, 50) != rolls_on(p, 101, 50))
    ratio = changed / len(sample) * 100
    print(f"  第100晚 vs 第101晚 翻面比例: {ratio:.1f}%（理论 ~50%）")
    ok &= 40 < ratio < 60

    print("\n== 5. LightConfig 夹取（0~15 亮度 / 0~600 延迟 / 0~100 比例）==")
    cases = [
        (-5, 0, 15, 0), (0, 0, 15, 0), (12, 0, 15, 12), (15, 0, 15, 15), (99, 0, 15, 15),
        (-1, 0, 600, 0), (60, 0, 600, 60), (600, 0, 600, 600), (9999, 0, 600, 600),
        (-1, 0, 100, 0), (30, 0, 100, 30), (150, 0, 100, 100),
    ]
    for raw, lo, hi, want in cases:
        got = clamp(raw, lo, hi)
        flag = "OK" if got == want else "<<< 不符"
        print(f"  clamp({raw:>5}, {lo}, {hi}) = {got:>4} (期望 {want})  {flag}")
        ok &= got == want

    print("\n== 6. 默认值 ==")
    defaults = {"percent": 30, "lightLevel": 12, "delaySeconds": 60,
                "range": "0~100 / 0~15 / 0~600"}
    for k, v in defaults.items():
        print(f"  {k} = {v}")

    print("\n== 7. 夜晚长度 vs 延迟上限 ==")
    night_ticks = NIGHT_END - NIGHT_START
    print(f"  夜晚 {night_ticks} tick = {night_ticks // TICKS_PER_SECOND} 秒；"
          f"默认延迟上限 60s 仅为夜晚的 {60 / (night_ticks / TICKS_PER_SECOND) * 100:.0f}%")

    print("\n结果:", "全部通过" if ok else "存在失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
