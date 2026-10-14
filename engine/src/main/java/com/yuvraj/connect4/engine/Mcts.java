package com.yuvraj.connect4.engine;

import java.util.SplittableRandom;
import java.util.function.Consumer;

/** Monte Carlo tree search. See SPEC.md section 2. */
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
        while (iter < maxIter) {
            if ((iter & (CHECK_EVERY - 1)) == 0) {
                if (Thread.currentThread().isInterrupted()) break;  // cancelled: return what we have
                if (timed || onProgress != null) {
                    long elapsed = System.nanoTime() - start;
                    if (elapsed >= budget) break;
                    if (onProgress != null && elapsed >= nextProgress) {
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
