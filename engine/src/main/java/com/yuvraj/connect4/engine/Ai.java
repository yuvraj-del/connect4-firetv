package com.yuvraj.connect4.engine;

/** Simple entry point to the AI: pick a move at a difficulty level, or run a time-limited search for a hint. */
public final class Ai {
    private final Difficulty difficulty;
    private final Mcts mcts;

    public Ai(Difficulty d, long seed) {
        this.difficulty = d;
        this.mcts = new Mcts(seed);
    }

    public int chooseMove(Board b) {
        long iters = switch (difficulty) {
            case EASY -> 300;
            case MEDIUM -> 3000;
            case HARD -> 30000;
        };
        return mcts.search(b, new SearchLimits(0, iters)).bestMove();
    }

    public SearchResult hint(Board b, long timeMillis) {
        return mcts.search(b, new SearchLimits(timeMillis, 0));
    }
}
