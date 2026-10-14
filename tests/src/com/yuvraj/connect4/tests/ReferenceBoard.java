package com.yuvraj.connect4.tests;

/** Deliberately naive 2D-array Connect Four used as ground truth for differential testing. */
final class ReferenceBoard {
    static final int W = 7, H = 6;
    final int[][] g = new int[W][H];
    final int[] h = new int[W];
    int moves = 0;
    int winner = -1;

    ReferenceBoard() {
        for (int[] col : g) java.util.Arrays.fill(col, -1);
    }

    ReferenceBoard copy() {
        ReferenceBoard r = new ReferenceBoard();
        for (int c = 0; c < W; c++) r.g[c] = g[c].clone();
        System.arraycopy(h, 0, r.h, 0, W);
        r.moves = moves;
        r.winner = winner;
        return r;
    }

    int toMove() { return moves % 2; }
    boolean full() { return moves == W * H; }
    boolean terminal() { return winner != -1 || full(); }
    boolean canPlay(int c) { return c >= 0 && c < W && h[c] < H && !terminal(); }

    void play(int c) {
        int p = toMove();
        g[c][h[c]] = p;
        h[c]++;
        moves++;
        if (fourAt(p)) winner = p;
    }

    boolean wouldWin(int c) {
        if (!canPlay(c)) return false;
        int p = toMove();
        g[c][h[c]] = p;
        boolean w = fourAt(p);
        g[c][h[c]] = -1;
        return w;
    }

    boolean fourAt(int p) {
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int c = 0; c < W; c++)
            for (int r = 0; r < H; r++)
                for (int[] d : dirs) {
                    int k = 0;
                    while (k < 4) {
                        int cc = c + d[0] * k, rr = r + d[1] * k;
                        if (cc < 0 || cc >= W || rr < 0 || rr >= H || g[cc][rr] != p) break;
                        k++;
                    }
                    if (k == 4) return true;
                }
        return false;
    }

    String gridString() {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < W; c++) for (int r = 0; r < H; r++) sb.append((char) ('a' + g[c][r] + 1));
        return sb.toString();
    }
}
