package com.yuvraj.connect4.engine;

/**
 * What a search found, per column (arrays of length 7): {@code winRates[c]} is the estimated chance that the player to
 * move wins by playing column c (NaN if the column is full), {@code visits[c]} is how many simulations went through it,
 * and {@code bestMove} is the column the AI picks.
 */
public record SearchResult(int bestMove, double[] winRates, long[] visits, long iterations, long elapsedNanos) { }
