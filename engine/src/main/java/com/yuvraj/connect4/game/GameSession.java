package com.yuvraj.connect4.game;

import com.yuvraj.connect4.engine.*;

import java.util.function.Consumer;

/**
 * Everything the TV screen needs, with no Android code: turns, the column cursor, AI and hint jobs, scores,
 * winning-line cells, and difficulty (fixed or adaptive).
 *
 * Threading: call every method on the UI thread. AI and hint searches run inside {@link Job#run}, which only
 * touches its own copy of the board, so it can run on a background thread. Results are applied back on the UI
 * thread with {@link #applyAiMove}; jobs from an earlier game are rejected.
 */
public final class GameSession {
    // Player 0 is red and always moves first; player 1 is yellow.
    // In VS_AI mode the human and AI swap colours after each finished game.

    public enum Mode { VS_AI, TWO_PLAYER }

    public enum Level {
        EASY(300), MEDIUM(3000), HARD(30000), ADAPTIVE(0);
        final long iterations;
        Level(long iterations) { this.iterations = iterations; }
        public Level next() { return values()[(ordinal() + 1) % values().length]; }
    }

    public enum Phase { HUMAN_TURN, AI_THINKING, GAME_OVER }

    public static final long HINT_MILLIS = 800;

    /** A search to run off the UI thread. */
    public static final class Job {
        final int generation;
        final Board board;
        final SearchLimits limits;
        final long seed;
        Job(int generation, Board board, SearchLimits limits, long seed) {
            this.generation = generation; this.board = board; this.limits = limits; this.seed = seed;
        }
        /** Runs the search; onProgress (may be null) is called about every 60 ms on the thread running this job. */
        public SearchResult run(Consumer<SearchResult> onProgress) {
            return new Mcts(seed).search(board, limits, onProgress, 60);
        }
    }

    private final Mode mode;
    private Level level;
    private final AdaptiveBudget adaptive = new AdaptiveBudget(1500);
    private long seed;

    private Board board;
    private Phase phase;
    private int cursor = 3;
    private int lastCol = -1;
    private int generation = 0;
    private boolean aiStarts = false;
    private int humanWins, aiWins, draws;
    private SearchResult hint;

    public GameSession(Mode mode, Level level, long seed) {
        this.mode = mode;
        this.level = level;
        this.seed = seed;
        startGame();
    }

    // ---------------------------------------------------------------- queries
    public Mode mode() { return mode; }
    public Level level() { return level; }
    public Phase phase() { return phase; }
    public Board board() { return board.copy(); }
    public int cell(int col, int row) { return board.cell(col, row); }
    public int cursor() { return cursor; }
    public int lastMoveColumn() { return lastCol; }
    public int currentPlayer() { return board.currentPlayer(); }
    public int winner() { return board.winner(); }
    public boolean isDraw() { return board.isDraw(); }
    public int humanWins() { return humanWins; }
    public int aiWins() { return aiWins; }
    public int draws() { return draws; }
    /** Latest hint for the current position, or null. Cleared whenever a piece is played. */
    public SearchResult hint() { return hint; }

    /** Iterations the AI gets for its next move. */
    public long aiIterations() {
        return level == Level.ADAPTIVE ? adaptive.iterations() : level.iterations;
    }

    /** True for the 4 (or more) cells that form the winning line(s); all false if nobody has won. */
    public boolean[][] winningCells() {
        boolean[][] out = new boolean[Board.WIDTH][Board.HEIGHT];
        int w = board.winner();
        if (w == -1) return out;
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int c = 0; c < Board.WIDTH; c++)
            for (int r = 0; r < Board.HEIGHT; r++)
                for (int[] d : dirs) {
                    int k = 0;
                    while (k < 4) {
                        int cc = c + d[0] * k, rr = r + d[1] * k;
                        if (cc < 0 || cc >= Board.WIDTH || rr < 0 || rr >= Board.HEIGHT || board.cell(cc, rr) != w) break;
                        k++;
                    }
                    if (k == 4) for (int i = 0; i < 4; i++) out[c + d[0] * i][r + d[1] * i] = true;
                }
        return out;
    }

    // ---------------------------------------------------------------- actions
    /** Moves the cursor left (-1) or right (+1), skipping full columns, wrapping at the edges. */
    public void moveCursor(int delta) {
        if (phase != Phase.HUMAN_TURN || delta == 0) return;
        int step = delta > 0 ? 1 : -1;
        int c = cursor;
        for (int i = 0; i < Board.WIDTH; i++) {
            c = Math.floorMod(c + step, Board.WIDTH);
            if (board.canPlay(c)) { cursor = c; return; }
        }
    }

    /** Human drops a piece in the cursor column. Returns false if it isn't the human's turn. */
    public boolean dropAtCursor() {
        if (phase != Phase.HUMAN_TURN || !board.canPlay(cursor)) return false;
        playAndAdvance(cursor);
        return true;
    }

    /** The AI search for this turn, or null if it isn't the AI's turn. */
    public Job aiJob() {
        if (phase != Phase.AI_THINKING) return null;
        return new Job(generation, board.copy(), new SearchLimits(0, aiIterations()), nextSeed());
    }

    /** A time-limited search for the hint button, or null if it isn't a human's turn. */
    public Job hintJob() {
        if (phase != Phase.HUMAN_TURN) return null;
        return new Job(generation, board.copy(), new SearchLimits(HINT_MILLIS, 0), nextSeed());
    }

    /** Applies a finished AI search. Returns false (and changes nothing) if the job is stale or it's not the AI's turn. */
    public boolean applyAiMove(Job job, SearchResult result) {
        if (job == null || result == null || job.generation != generation || phase != Phase.AI_THINKING
                || job.board.key() != board.key() || !board.canPlay(result.bestMove())) return false;
        playAndAdvance(result.bestMove());
        return true;
    }

    /** Stores a finished hint and moves the cursor to the suggested column. Ignored if stale. */
    public boolean applyHint(Job job, SearchResult result) {
        if (job == null || result == null || job.generation != generation || phase != Phase.HUMAN_TURN
                || job.board.key() != board.key()) return false;
        hint = result;
        if (board.canPlay(result.bestMove())) cursor = result.bestMove();
        return true;
    }

    public void cycleLevel() {
        level = level.next();
    }

    /** Starts the next game. In VS_AI mode the first move alternates after a finished game; restarting an unfinished game keeps the same starter. */
    public void newGame() {
        if (phase == Phase.GAME_OVER) aiStarts = mode == Mode.VS_AI && !aiStarts;
        startGame();
    }

    // ---------------------------------------------------------------- internals
    private void startGame() {
        generation++;
        board = new Board();
        cursor = 3;
        lastCol = -1;
        hint = null;
        phase = aiStarts ? Phase.AI_THINKING : Phase.HUMAN_TURN;
    }

    /** Which player (0 red / 1 yellow) the AI is in VS_AI mode. */
    public int aiPlayer() { return aiStarts ? 0 : 1; }

    private boolean isAiTurn() { return mode == Mode.VS_AI && board.currentPlayer() == aiPlayer(); }

    private void playAndAdvance(int col) {
        board.play(col);
        lastCol = col;
        hint = null;
        if (board.isTerminal()) {
            phase = Phase.GAME_OVER;
            recordResult();
            return;
        }
        phase = isAiTurn() ? Phase.AI_THINKING : Phase.HUMAN_TURN;
        if (phase == Phase.HUMAN_TURN && !board.canPlay(cursor)) moveCursor(1);
    }

    private void recordResult() {
        int w = board.winner();
        if (w == -1) draws++;
        else if (mode == Mode.TWO_PLAYER) { if (w == 0) humanWins++; else aiWins++; }
        else if (w == aiPlayer()) aiWins++;
        else humanWins++;
        if (mode == Mode.VS_AI && level == Level.ADAPTIVE) adaptive.recordResult(w == -1 ? 0 : (w == aiPlayer() ? -1 : 1));
    }

    private long nextSeed() {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return seed;
    }
}
