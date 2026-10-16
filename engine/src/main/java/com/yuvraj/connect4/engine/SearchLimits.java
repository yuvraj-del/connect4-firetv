package com.yuvraj.connect4.engine;

/** When a search stops: after {@code timeMillis}, after {@code maxIterations} simulations, or whichever comes first (0 = no limit on that one). */
public record SearchLimits(long timeMillis, long maxIterations) { }
