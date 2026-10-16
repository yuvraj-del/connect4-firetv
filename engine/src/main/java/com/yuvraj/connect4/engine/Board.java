package com.yuvraj.connect4.engine;

/**
 * A Connect Four board stored in two 64-bit numbers ("bitboards") instead of a grid.
 *
 * <p>Each column gets 7 bits: 6 for its cells (bit 0 = bottom row) plus one empty sentinel bit on top, so the
 * board uses 49 bits of a {@code long}:
 * <pre>
 *   .  .  .  .  .  .  .     sentinel row (always 0)
 *   5 12 19 26 33 40 47     top row
 *   4 11 18 25 32 39 46
 *   3 10 17 24 31 38 45
 *   2  9 16 23 30 37 44
 *   1  8 15 22 29 36 43
 *   0  7 14 21 28 35 42     bottom row
 * </pre>
 * {@code mask} has a 1 for every disc on the board and {@code position} has a 1 for every disc of the player whose
 * turn it is. That makes the expensive operations cheap:
 * <ul>
 *   <li><b>Dropping a disc:</b> adding a column's bottom bit to {@code mask} carries up to the first empty cell, so a
 *       move is one addition and one OR, with no loop.</li>
 *   <li><b>Checking four in a row:</b> shifting the board by 1 (vertical), 7 (horizontal), 6 or 8 (the diagonals) and
 *       AND-ing it with itself finds every run of four in about a dozen bit operations. The sentinel row stops lines
 *       from wrapping from one column into the next.</li>
 *   <li><b>Switching turns:</b> {@code position ^= mask} turns "my discs" into "the other player's discs".</li>
 * </ul>
 * This is what lets the AI simulate hundreds of thousands of games a second.
 */
public final class Board {
    public static final int WIDTH = 7, HEIGHT = 6;

    private static final int COL_BITS = HEIGHT + 1;
    private static final long BOTTOM_ROW = 0b0000001_0000001_0000001_0000001_0000001_0000001_0000001L;

    /** Discs of the player whose turn it is (1 bit per disc). */
    private long position;
    /** Every disc on the board, both players. */
    private long mask;
    private int moves;
    private int winner = -1;

    public Board() { }

    private Board(Board o) {
        setFrom(o);
    }

    void setFrom(Board o) {
        position = o.position;
        mask = o.mask;
        moves = o.moves;
        winner = o.winner;
    }

    /** Builds a board from a move list such as "4453", using 1-based columns (handy for tests). */
    public static Board fromMoves(String s) {
        if (s == null) throw new IllegalArgumentException("null moves");
        Board b = new Board();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '1' || ch > '7') throw new IllegalArgumentException("bad character '" + ch + "' at " + i);
            int col = ch - '1';
            if (!b.canPlay(col)) throw new IllegalArgumentException("illegal move '" + ch + "' at " + i);
            b.play(col);
        }
        return b;
    }

    public Board copy() { return new Board(this); }

    private static long topBit(int col) { return 1L << (col * COL_BITS + HEIGHT - 1); }
    private static long bottomBit(int col) { return 1L << (col * COL_BITS); }
    private static long columnMask(int col) { return ((1L << HEIGHT) - 1) << (col * COL_BITS); }

    public boolean canPlay(int col) {
        return col >= 0 && col < WIDTH && winner == -1 && (mask & topBit(col)) == 0;
    }

    public void play(int col) {
        if (isTerminal()) throw new IllegalStateException("game is over");
        if (col < 0 || col >= WIDTH) throw new IllegalArgumentException("column out of range: " + col);
        if ((mask & topBit(col)) != 0) throw new IllegalArgumentException("column full: " + col);
        long newMask = mask | (mask + bottomBit(col));   // the +1 carries up the column to the first empty cell
        if (hasFour(position | (newMask & columnMask(col) & ~mask))) winner = moves & 1;
        position ^= mask;           // switch perspective to the next player
        mask = newMask;
        moves++;
    }

    /** True if the discs in {@code p} contain four in a row in any direction (see the class comment). */
    private static boolean hasFour(long p) {
        long m = p & (p >> COL_BITS);
        if ((m & (m >> (2 * COL_BITS))) != 0) return true;        // horizontal
        m = p & (p >> (COL_BITS + 1));
        if ((m & (m >> (2 * (COL_BITS + 1)))) != 0) return true;  // diagonal
        m = p & (p >> (COL_BITS - 1));
        if ((m & (m >> (2 * (COL_BITS - 1)))) != 0) return true;  // anti-diagonal
        m = p & (p >> 1);
        return (m & (m >> 2)) != 0;                               // vertical
    }

    public int currentPlayer() { return moves & 1; }

    public int moveCount() { return moves; }

    public boolean isWinningMove(int col) {
        if (!canPlay(col)) return false;
        return hasFour(position | ((mask | (mask + bottomBit(col))) & columnMask(col) & ~mask));
    }

    /** True if the player NOT to move would win by dropping in col (ignoring turn order). */
    boolean opponentWinsAt(int col) {
        if (!canPlay(col)) return false;
        long opp = position ^ mask;
        return hasFour(opp | ((mask | (mask + bottomBit(col))) & columnMask(col) & ~mask));
    }

    /** Bitmask of playable columns (bit c set if column c is playable). */
    int legalMask() {
        if (isTerminal()) return 0;
        int m = 0;
        for (int c = 0; c < WIDTH; c++) if ((mask & topBit(c)) == 0) m |= 1 << c;
        return m;
    }

    public int winner() { return winner; }

    public boolean isDraw() { return winner == -1 && moves == WIDTH * HEIGHT; }

    public boolean isTerminal() { return winner != -1 || moves == WIDTH * HEIGHT; }

    public int cell(int col, int row) {
        if (col < 0 || col >= WIDTH || row < 0 || row >= HEIGHT) return -1;
        long bit = 1L << (col * COL_BITS + row);
        if ((mask & bit) == 0) return -1;
        return (position & bit) != 0 ? (moves & 1) : 1 - (moves & 1);
    }

    public int[] legalMoves() {
        int m = legalMask();
        int[] out = new int[Integer.bitCount(m)];
        int n = 0;
        for (int c = 0; c < WIDTH; c++) if ((m & (1 << c)) != 0) out[n++] = c;
        return out;
    }

    /** A unique number for this position (same discs, same player to move, same key). */
    public long key() { return position + mask + BOTTOM_ROW; }
}
