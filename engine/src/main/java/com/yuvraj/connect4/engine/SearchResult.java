package com.yuvraj.connect4.engine;

/** Snapshot of a search. Arrays have length 7. See SPEC.md section 2. */
public record SearchResult(int bestMove, double[] winRates, long[] visits, long iterations, long elapsedNanos) { }
