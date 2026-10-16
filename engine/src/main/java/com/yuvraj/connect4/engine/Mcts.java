package com.yuvraj.connect4.engine;

import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * The AI: Monte Carlo tree search (MCTS) with the UCT selection rule.
 *
 * <p>Instead of hand-written rules about what makes a good Connect Four move, the AI learns by playing. Each
 * iteration does four steps:
 * <ol>
 *   <li><b>Select:</b> walk down the tree of moves it has already explored, at each level picking the move with the best
 *       balance of "has won a lot so far" and "hasn't been tried much yet" (the UCT formula, exploration constant 1.4).</li>
 *   <li><b>Expand:</b> add one new, untried move to the tree.</li>
 *   <li><b>Simulate:</b> finish the game from there with random moves.</li>
 *   <li><b>Back-propagate:</b> walk back up, crediting a win (1), draw (0.5) or loss (0) to every move on the path,
 *       each from the point of view of the player who made it.</li>
 * </ol>
 * After thousands of iterations the most-visited first move is the best one, and each column's win rate is what the
 * TV draws as the live bars. Two safety rules always apply on top: take an immediate win, otherwise block the
 * opponent's immediate win.
 *
 * <p>Built for a streaming stick: the search checks the clock every 64 iterations, reports progress to a callback
 * (that's what animates the bars), stops promptly if its thread is interrupted, and caps the tree at
 * {@link #MAX_NODES} nodes so a long search can't run out of memory.
 */
public final class Mcts {
    private static final double C = 1.4;
    private static final int CHECK_EVERY = 64;
    /** Tree size cap (~90 bytes/node, so ~36 MB). Past it the search keeps running playouts but stops growing the tree,
     *  so a long hint can't run a Fire TV stick out of memory. */
    static final int MAX_NODES = 400_000;

    private static final class Node {
        final Node parent;
        final int move;
        final int mover;  // player who made `move`
        final Node[] children = new Node[Board.WIDTH];
        int untried;      // bitmask of columns not yet expanded
        double wins;      // from the view of `mover`
        long visits;
        Node(Node parent, int move, int mover, int untried) {
            this.parent = parent;
            this.move = move;
            this.mover = mover;
            this.untried = untried;
        }
    }

    private final SplittableRandom rng;
    private int nodeCount;

    public Mcts(long seed) { rng = new SplittableRandom(seed); }

    public SearchResult search(Board root, SearchLimits limits) {
        return search(root, limits, null, 0);
    }

    public SearchResult search(Board root, SearchLimits limits, Consumer<SearchResult> onProgress, long progressIntervalMillis) {
        return search(root, limits, onProgress, progressIntervalMillis, 0);
    }

    /**
     * Like {@link #search(Board, SearchLimits, Consumer, long)}, but if {@code progressEveryIterations > 0} the progress
     * callback fires every that-many iterations instead of on a timer. A fast search can then still be shown step by
     * step: the TV replays these real intermediate results so people can watch the AI's estimates settle.
     */
    public SearchResult search(Board root, SearchLimits limits, Consumer<SearchResult> onProgress,
                               long progressIntervalMillis, long progressEveryIterations) {
        if (limits == null || limits.timeMillis() < 0 || limits.maxIterations() < 0
                || (limits.timeMillis() == 0 && limits.maxIterations() == 0)) {
            throw new IllegalArgumentException("need a positive time or iteration limit");
        }
        if (root.isTerminal()) throw new IllegalStateException("game is over");

        long start = System.nanoTime();
        boolean timed = limits.timeMillis() > 0;
        long budget = timed ? millisToNanos(limits.timeMillis()) : Long.MAX_VALUE;
        long interval = millisToNanos(Math.max(1, progressIntervalMillis));
        long nextProgress = interval; // offset from start, compared as elapsed time (overflow-safe)
        long maxIter = limits.maxIterations() > 0 ? limits.maxIterations() : Long.MAX_VALUE;

        Node rootNode = new Node(null, -1, -1, root.legalMask());
        Board scratch = root.copy();
        nodeCount = 1;
        long iter = 0;
        boolean byIterations = onProgress != null && progressEveryIterations > 0;
        long sinceProgress = 0;
        while (iter < maxIter) {
            if (byIterations && ++sinceProgress > progressEveryIterations) {
                sinceProgress = 1;
                onProgress.accept(snapshot(root, rootNode, iter, System.nanoTime() - start));
            }
            if ((iter & (CHECK_EVERY - 1)) == 0) {
                if (Thread.currentThread().isInterrupted()) break;  // cancelled: return what we have
                if (timed || onProgress != null) {
                    long elapsed = System.nanoTime() - start;
                    if (elapsed >= budget) break;
                    if (onProgress != null && !byIterations && elapsed >= nextProgress) {
                        onProgress.accept(snapshot(root, rootNode, iter, elapsed));
                        nextProgress = elapsed > Long.MAX_VALUE - interval ? Long.MAX_VALUE : elapsed + interval;
                    }
                }
            }
            iterate(rootNode, root, scratch);
            iter++;
        }
        return snapshot(root, rootNode, iter, System.nanoTime() - start);
    }

    private void iterate(Node rootNode, Board root, Board b) {
        b.setFrom(root);
        Node n = rootNode;
        // selection
        while (n.untried == 0 && !b.isTerminal()) {
            Node best = null;
            double bestScore = -1;
            double logN = Math.log(n.visits);
            for (int c = 0; c < Board.WIDTH; c++) {
                Node ch = n.children[c];
                if (ch == null) continue;
                double s = ch.wins / ch.visits + C * Math.sqrt(logN / ch.visits);
                if (s > bestScore) { bestScore = s; best = ch; }
            }
            n = best;
            b.play(n.move);
        }
        // expansion (skipped once the tree hits MAX_NODES; the playout still runs from here)
        if (n.untried != 0 && nodeCount < MAX_NODES) {
            nodeCount++;
            int pick = rng.nextInt(Integer.bitCount(n.untried));
            int m = n.untried;
            for (int i = 0; i < pick; i++) m &= m - 1;
            int col = Integer.numberOfTrailingZeros(m);
            n.untried &= ~(1 << col);
            int mover = b.currentPlayer();
            b.play(col);
            Node ch = new Node(n, col, mover, b.legalMask());
            n.children[col] = ch;
            n = ch;
        }
        // simulation
        while (!b.isTerminal()) {
            int m = b.legalMask();
            int pick = rng.nextInt(Integer.bitCount(m));
            for (int i = 0; i < pick; i++) m &= m - 1;
            b.play(Integer.numberOfTrailingZeros(m));
        }
        int w = b.winner();
        // backpropagation
        for (; n != null; n = n.parent) {
            n.visits++;
            if (n.parent == null) continue;
            n.wins += w == -1 ? 0.5 : (w == n.mover ? 1.0 : 0.0);
        }
    }

    private static long millisToNanos(long ms) {
        return ms >= Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : ms * 1_000_000L;
    }

    private SearchResult snapshot(Board root, Node rootNode, long iter, long elapsed) {
        double[] rates = new double[Board.WIDTH];
        long[] visits = new long[Board.WIDTH];
        int best = -1;
        for (int c = 0; c < Board.WIDTH; c++) {
            if (!root.canPlay(c)) { rates[c] = Double.NaN; continue; }
            Node ch = rootNode.children[c];
            if (ch == null) { rates[c] = 0.5; continue; }
            visits[c] = ch.visits;
            rates[c] = ch.wins / ch.visits;
            if (best == -1 || visits[c] > visits[best]
                    || (visits[c] == visits[best] && rates[c] > rates[best])) best = c;
        }
        if (best == -1) best = root.legalMoves()[0];
        for (int c = 0; c < Board.WIDTH; c++) {
            if (root.isWinningMove(c)) { best = c; break; }
        }
        if (!root.isWinningMove(best)) {
            for (int c = 0; c < Board.WIDTH; c++) {
                if (root.opponentWinsAt(c)) { best = c; break; }
            }
        }
        return new SearchResult(best, rates, visits, iter, elapsed);
    }
}
