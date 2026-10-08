package com.mzyc.build.util;

/**
 * 确定性散列：splitmix64 的三步混合（双射、雪崩效应好）。
 *
 * <p>「确定性」= 同样的输入永远得到同样的输出。模组里凡是需要「按坐标生成一个稳定的伪随机值」
 * 的地方都用它 —— 黄灯（每晚掷骰、随机延迟）和红灯（随机相位错开）共用这一份，
 * 免得两个类里各写一套、日后悄悄漂移。
 *
 * <p>它**不是** {@link java.util.Random}：不存状态、不随时间变，所以存档重启、区块卸载重载
 * 得到的结果完全一致。
 */
public final class Hash {
    private Hash() {
    }

    /** 输入任意 64 位 → 输出充分打散的 64 位（同输入同输出）。 */
    public static long mix(long value) {
        long h = value;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /**
     * 把 {@code (坐标, 用途编号)} 散列成一个 64 位值。
     * 乘一个奇数大常数做一次「打散坐标位」，再走 splitmix。
     */
    public static long of(long posAsLong, long usage) {
        return mix(usage * 0x9E3779B97F4A7C15L + posAsLong);
    }

    /** 取 [0, bound] 闭区间内的确定性伪随机值；{@code bound <= 0} 时恒为 0。 */
    public static long range(long posAsLong, long usage, long bound) {
        if (bound <= 0L) {
            return 0L;
        }
        return Math.floorMod(of(posAsLong, usage), bound + 1L);
    }
}
