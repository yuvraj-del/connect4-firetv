package com.yuvraj.connect4.engine;

/** 0 = unlimited for that dimension; at least one must be > 0. See SPEC.md section 2. */
public record SearchLimits(long timeMillis, long maxIterations) { }
