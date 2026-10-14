package com.yuvraj.connect4.engine;

/** Iteration budget that adapts to the player. See SPEC.md section 4. */
public final class AdaptiveBudget {
    private static final long MIN = 100, MAX = 50000;
    private long iterations;

    public AdaptiveBudget(long startIterations) { iterations = clamp(startIterations); }

    private static long clamp(long v) { return Math.max(MIN, Math.min(MAX, v)); }

    public long iterations() { return iterations; }

    public void recordResult(int outcome) {
        if (outcome > 0) iterations = clamp(iterations * 2);
        else if (outcome < 0) iterations = clamp(iterations / 2);
    }
}
