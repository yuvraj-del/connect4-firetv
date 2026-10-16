package com.yuvraj.connect4.tests;

import com.yuvraj.connect4.engine.*;
import com.yuvraj.connect4.game.GameSession;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;

/**
 * Dependency-free test suite for the engine and game logic: board rules, 3,000 random games replayed against a simple
 * reference board, tactics, timing, cancellation, speed, game flow and playing strength.
 * Usage: java -cp build com.yuvraj.connect4.tests.TestRunner [--fast]
 */
public final class TestRunner {

    interface Check { void run() throws Exception; }
    record T(String name, boolean slow, Check check) { }

    static final List<T> TESTS = new ArrayList<>();
    static void test(String name, Check c) { TESTS.add(new T(name, false, c)); }
    static void slow(String name, Check c) { TESTS.add(new T(name, true, c)); }

    // ---------- tiny assertion helpers ----------
    static void check(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
    static void eq(Object exp, Object act, String what) {
        if (!Objects.equals(exp, act)) throw new AssertionError(what + ": expected " + exp + " but was " + act);
    }
    static void throwsEx(Class<? extends Throwable> type, Check c, String what) {
        try { c.run(); } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }
    static String moves(int... cols1) { StringBuilder sb = new StringBuilder(); for (int c : cols1) sb.append(c); return sb.toString(); }

    static final String DRAW = "242222246341543663717511153741653355766774";

    static {
        // =============== BOARD ===============
        test("board.uses_bitboards", () -> {
            int longs = 0;
            for (Field f : Board.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                check(!f.getType().isArray(), "Board instance field '" + f.getName() + "' is an array; the board must be stored as bitboards");
                if (f.getType() == long.class) longs++;
            }
            check(longs >= 2, "Board needs at least two long bitboard fields, found " + longs);
        });
        test("board.empty", () -> {
            Board b = new Board();
            eq(0, b.currentPlayer(), "currentPlayer");
            eq(0, b.moveCount(), "moveCount");
            eq(-1, b.winner(), "winner");
            check(!b.isTerminal() && !b.isDraw(), "empty board not terminal");
            for (int c = 0; c < 7; c++) check(b.canPlay(c), "canPlay " + c);
            check(!b.canPlay(-1) && !b.canPlay(7), "out of range columns not playable");
            eq("[0, 1, 2, 3, 4, 5, 6]", Arrays.toString(b.legalMoves()), "legalMoves");
            for (int c = 0; c < 7; c++) for (int r = 0; r < 6; r++) eq(-1, b.cell(c, r), "cell");
        });
        test("board.fromMoves_and_cells", () -> {
            Board b = Board.fromMoves("4453");
            eq(0, b.cell(3, 0), "cell(3,0)");
            eq(1, b.cell(3, 1), "cell(3,1)");
            eq(0, b.cell(4, 0), "cell(4,0)");
            eq(1, b.cell(2, 0), "cell(2,0)");
            eq(-1, b.cell(3, 2), "cell(3,2)");
            eq(4, b.moveCount(), "moveCount");
            eq(0, b.currentPlayer(), "currentPlayer");
            eq(0, Board.fromMoves("").moveCount(), "empty string");
        });
        test("board.fromMoves_rejects_bad_input", () -> {
            throwsEx(IllegalArgumentException.class, () -> Board.fromMoves("8"), "column 8");
            throwsEx(IllegalArgumentException.class, () -> Board.fromMoves("0"), "column 0");
            throwsEx(IllegalArgumentException.class, () -> Board.fromMoves("1a"), "letter");
            throwsEx(IllegalArgumentException.class, () -> Board.fromMoves("1111111"), "7th piece in a column");
        });
        test("board.full_column_and_bad_columns", () -> {
            Board b = Board.fromMoves("111111");
            check(!b.canPlay(0), "full column not playable");
            throwsEx(IllegalArgumentException.class, () -> b.play(0), "play full column");
            throwsEx(IllegalArgumentException.class, () -> b.play(-1), "play -1");
            throwsEx(IllegalArgumentException.class, () -> b.play(7), "play 7");
            eq("[1, 2, 3, 4, 5, 6]", Arrays.toString(b.legalMoves()), "legalMoves");
            check(!b.isWinningMove(0), "isWinningMove on full column is false");
        });
        test("board.horizontal_win", () -> winCase("1122334", 0, 3));
        test("board.vertical_win", () -> winCase("1212121", 0, 0));
        test("board.diagonal_up_win", () -> winCase(moves(1, 2, 2, 3, 3, 4, 3, 4, 4, 7, 4), 0, 3));
        test("board.diagonal_down_win", () -> winCase(moves(7, 6, 6, 5, 5, 4, 5, 4, 4, 1, 4), 0, 3));
        test("board.second_player_win", () -> winCase("71122334", 1, 3));
        test("board.draw", () -> {
            Board b = Board.fromMoves(DRAW);
            eq(42, b.moveCount(), "moveCount");
            eq(-1, b.winner(), "winner");
            check(b.isDraw(), "isDraw");
            check(b.isTerminal(), "isTerminal");
            eq(0, b.legalMoves().length, "no legal moves");
            Board almost = Board.fromMoves(DRAW.substring(0, 41));
            check(!almost.isDraw() && !almost.isTerminal(), "41 moves is not a draw");
        });
        test("board.copy_is_independent", () -> {
            Board a = Board.fromMoves("4455");
            Board c = a.copy();
            c.play(5);
            eq(4, a.moveCount(), "original moveCount after copy played");
            eq(-1, a.cell(5, 0), "original cell untouched");
            eq(0, c.cell(5, 0), "copy cell set");
            check(a.key() != c.key(), "keys differ after divergence");
            eq(a.key(), Board.fromMoves("4455").key(), "same position same key");
        });
        test("board.differential_vs_reference_3000_games", () -> {
            Random rnd = new Random(12345);
            Map<Long, String> keyToGrid = new HashMap<>();
            Map<String, Long> gridToKey = new HashMap<>();
            for (int game = 0; game < 3000; game++) {
                Board b = new Board();
                ReferenceBoard r = new ReferenceBoard();
                StringBuilder hist = new StringBuilder();
                while (true) {
                    compare(b, r, hist.toString());
                    String grid = r.gridString();
                    long k = b.key();
                    String prev = keyToGrid.putIfAbsent(k, grid);
                    check(prev == null || prev.equals(grid), "key collision for different positions at moves " + hist);
                    Long prevKey = gridToKey.putIfAbsent(grid, k);
                    check(prevKey == null || prevKey == k, "same position produced different keys at moves " + hist);
                    if (r.terminal()) {
                        throwsEx(IllegalStateException.class, () -> b.play(0), "play after game over (moves " + hist + ")");
                        break;
                    }
                    List<Integer> legal = new ArrayList<>();
                    for (int c = 0; c < 7; c++) if (r.canPlay(c)) legal.add(c);
                    int c = legal.get(rnd.nextInt(legal.size()));
                    b.play(c);
                    r.play(c);
                    hist.append(c + 1);
                }
            }
        });

        // =============== MCTS ===============
        test("mcts.rejects_bad_input", () -> {
            Mcts m = new Mcts(1);
            throwsEx(IllegalArgumentException.class, () -> m.search(new Board(), new SearchLimits(0, 0)), "no limits");
            throwsEx(IllegalStateException.class, () -> m.search(Board.fromMoves("1122334"), new SearchLimits(0, 100)), "terminal root");
            throwsEx(IllegalStateException.class, () -> m.search(Board.fromMoves(DRAW), new SearchLimits(0, 100)), "drawn root");
        });
        test("mcts.result_shape", () -> {
            Board b = Board.fromMoves("111111443");
            SearchResult res = new Mcts(7).search(b, new SearchLimits(0, 4000));
            eq(7, res.winRates().length, "winRates length");
            eq(7, res.visits().length, "visits length");
            eq(4000L, res.iterations(), "iterations");
            check(Double.isNaN(res.winRates()[0]), "full column winRate is NaN");
            eq(0L, res.visits()[0], "full column visits");
            long sum = 0;
            int most = -1;
            for (int c = 0; c < 7; c++) {
                sum += res.visits()[c];
                if (c > 0) {
                    double w = res.winRates()[c];
                    check(w >= 0 && w <= 1, "winRate in [0,1] for col " + c + ": " + w);
                    if (most == -1 || res.visits()[c] > res.visits()[most]) most = c;
                }
            }
            eq(4000L, sum, "sum of visits equals iterations");
            check(b.canPlay(res.bestMove()), "bestMove legal");
            eq(res.visits()[most], res.visits()[res.bestMove()], "bestMove has the most visits");
            check(res.elapsedNanos() > 0, "elapsedNanos > 0");
        });
        test("mcts.does_not_mutate_root", () -> {
            Board b = Board.fromMoves("4433");
            long key = b.key();
            new Mcts(3).search(b, new SearchLimits(0, 3000));
            eq(key, b.key(), "key after search");
            eq(4, b.moveCount(), "moveCount after search");
        });
        test("mcts.deterministic_with_seed", () -> {
            Board b = Board.fromMoves("43");
            SearchResult a = new Mcts(42).search(b, new SearchLimits(0, 6000));
            SearchResult c = new Mcts(42).search(b, new SearchLimits(0, 6000));
            eq(a.bestMove(), c.bestMove(), "bestMove");
            eq(Arrays.toString(a.visits()), Arrays.toString(c.visits()), "visits");
        });
        test("mcts.takes_immediate_win_40_positions", () -> {
            List<String[]> cases = tacticalPositions(true, 40, 777);
            for (String[] cs : cases) {
                Board b = Board.fromMoves(cs[0]);
                for (long iters : new long[]{1, 50, 3000}) {
                    int mv = new Mcts(iters).search(b, new SearchLimits(0, iters)).bestMove();
                    check(cs[1].indexOf((char) ('0' + mv)) >= 0,
                            "moves '" + cs[0] + "': winning columns " + cs[1] + " but chose " + mv + " at " + iters + " iterations");
                }
            }
        });
        test("mcts.blocks_immediate_threat_40_positions", () -> {
            List<String[]> cases = tacticalPositions(false, 40, 888);
            for (String[] cs : cases) {
                Board b = Board.fromMoves(cs[0]);
                for (long iters : new long[]{1, 50, 3000}) {
                    int mv = new Mcts(iters + 5).search(b, new SearchLimits(0, iters)).bestMove();
                    eq(Integer.parseInt(cs[1]), mv, "moves '" + cs[0] + "' must block at " + iters + " iterations");
                }
            }
        });
        test("mcts.respects_time_budget", () -> {
            Mcts m = new Mcts(5);
            m.search(new Board(), new SearchLimits(100, 0)); // warm-up
            for (long budget : new long[]{100, 300}) {
                long t0 = System.nanoTime();
                SearchResult r = m.search(Board.fromMoves("4"), new SearchLimits(budget, 0));
                long ms = (System.nanoTime() - t0) / 1_000_000;
                check(ms >= budget - 20 && ms <= budget + 50, "budget " + budget + "ms but search took " + ms + "ms");
                check(r.iterations() > 100, "time-limited search ran only " + r.iterations() + " iterations");
            }
            long t0 = System.nanoTime();
            SearchResult r = m.search(new Board(), new SearchLimits(5000, 2000));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            eq(2000L, r.iterations(), "iteration limit reached before time limit");
            check(ms < 2000, "should stop at iteration limit, took " + ms + "ms");
        });
        test("mcts.progress_callback", () -> {
            List<SearchResult> snaps = new ArrayList<>();
            Thread caller = Thread.currentThread();
            boolean[] wrongThread = {false};
            new Mcts(9).search(new Board(), new SearchLimits(550, 0), s -> {
                if (Thread.currentThread() != caller) wrongThread[0] = true;
                snaps.add(s);
            }, 100);
            check(!wrongThread[0], "onProgress must run on the calling thread");
            check(snaps.size() >= 3 && snaps.size() <= 8, "expected ~5 progress callbacks, got " + snaps.size());
            long last = -1;
            for (SearchResult s : snaps) {
                eq(7, s.winRates().length, "snapshot winRates length");
                check(s.iterations() >= last, "iterations must not decrease across snapshots");
                last = s.iterations();
            }
        });
        test("mcts.speed_150k_per_second", () -> {
            Mcts m = new Mcts(11);
            m.search(new Board(), new SearchLimits(1000, 0)); // JIT warm-up
            double best = 0;
            for (int i = 0; i < 2; i++) {
                SearchResult r = m.search(new Board(), new SearchLimits(1000, 0));
                best = Math.max(best, r.iterations() / (r.elapsedNanos() / 1e9));
            }
            System.out.printf("    speed: %.0f iterations/sec%n", best);
            check(best >= 150_000, String.format("only %.0f iterations/sec, need 150000", best));
        });

        // =============== AI + ADAPTIVE ===============
        test("ai.choose_and_hint", () -> {
            for (Difficulty d : Difficulty.values()) {
                Ai ai = new Ai(d, 3);
                Board b = Board.fromMoves("4455");
                int mv = ai.chooseMove(b);
                check(b.canPlay(mv), d + " chose illegal move " + mv);
                eq(4, b.moveCount(), d + " must not mutate the board");
                Board w = Board.fromMoves("112233");
                eq(3, ai.chooseMove(w), d + " must take the immediate win");
            }
            long t0 = System.nanoTime();
            SearchResult h = new Ai(Difficulty.EASY, 1).hint(Board.fromMoves("44"), 200);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(ms <= 250, "hint(200ms) took " + ms + "ms");
            check(h.iterations() > 0, "hint ran no iterations");
            long t1 = System.nanoTime();
            new Ai(Difficulty.EASY, 1).chooseMove(new Board());
            check((System.nanoTime() - t1) / 1_000_000 < 300, "EASY (300 iterations) should be near-instant");
        });
        test("adaptive.budget_rules", () -> {
            AdaptiveBudget a = new AdaptiveBudget(1000);
            eq(1000L, a.iterations(), "start");
            a.recordResult(1); eq(2000L, a.iterations(), "after human win");
            a.recordResult(0); eq(2000L, a.iterations(), "after draw");
            a.recordResult(-1); eq(1000L, a.iterations(), "after human loss");
            for (int i = 0; i < 20; i++) a.recordResult(1);
            eq(50000L, a.iterations(), "clamped high");
            for (int i = 0; i < 30; i++) a.recordResult(-1);
            eq(100L, a.iterations(), "clamped low");
            eq(100L, new AdaptiveBudget(5).iterations(), "start clamped low");
            eq(50000L, new AdaptiveBudget(10_000_000).iterations(), "start clamped high");
        });

        // =============== ROBUSTNESS (reviewer findings) ===============
        test("mcts.huge_limits_do_not_overflow", () -> {
            SearchResult r = new Mcts(1).search(new Board(), new SearchLimits(Long.MAX_VALUE, 3000));
            eq(3000L, r.iterations(), "Long.MAX_VALUE time budget must not end the search early");
            int[] calls = {0};
            new Mcts(1).search(new Board(), new SearchLimits(0, 20000), s -> calls[0]++, Long.MAX_VALUE);
            eq(0, calls[0], "huge progress interval must never fire");
        });
        test("mcts.runs_past_tree_size_cap", () -> {
            Board b = Board.fromMoves("4");
            SearchResult r = new Mcts(2).search(b, new SearchLimits(0, 900_000));
            eq(900_000L, r.iterations(), "iterations past the node cap");
            check(b.canPlay(r.bestMove()), "legal best move after hitting the cap");
            long sum = 0;
            for (long v : r.visits()) sum += v;
            eq(900_000L, sum, "visits still sum to iterations");
        });

        test("mcts.stops_when_thread_interrupted", () -> {
            SearchResult[] out = new SearchResult[1];
            Thread t = new Thread(() -> out[0] = new Mcts(4).search(new Board(), new SearchLimits(60_000, 0)));
            t.start();
            Thread.sleep(150);
            long t0 = System.nanoTime();
            t.interrupt();
            t.join(2000);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(!t.isAlive(), "a 60 s search must stop promptly when its thread is interrupted");
            check(ms < 100, "stopped " + ms + "ms after interrupt");
            check(out[0] != null && new Board().canPlay(out[0].bestMove()), "cancelled search still returns a legal move");
        });

        // =============== GAME SESSION (what the TV app drives) ===============
        test("session.cursor_skips_full_columns_and_wraps", () -> {
            GameSession s = new GameSession(GameSession.Mode.TWO_PLAYER, GameSession.Level.EASY, 1);
            eq(3, s.cursor(), "cursor starts in the middle");
            for (int i = 0; i < 6; i++) check(s.dropAtCursor(), "drop " + i);
            check(!s.board().canPlay(3), "column 3 full");
            eq(4, s.cursor(), "cursor moves off a column once it fills");
            s.moveCursor(-1);
            eq(2, s.cursor(), "moving left skips the full column");
            for (int i = 0; i < 3; i++) s.moveCursor(-1);
            eq(6, s.cursor(), "wraps from the left edge to the right");
            s.moveCursor(1);
            eq(0, s.cursor(), "wraps from the right edge to the left");
        });
        test("session.two_player_flow_and_winning_cells", () -> {
            GameSession s = new GameSession(GameSession.Mode.TWO_PLAYER, GameSession.Level.EASY, 1);
            for (int col : new int[]{0, 0, 1, 1, 2, 2}) { while (s.cursor() != col) s.moveCursor(1); check(s.dropAtCursor(), "drop " + col); }
            eq(GameSession.Phase.HUMAN_TURN, s.phase(), "still playing");
            while (s.cursor() != 3) s.moveCursor(1);
            s.dropAtCursor();
            eq(GameSession.Phase.GAME_OVER, s.phase(), "game over after four in a row");
            eq(0, s.winner(), "red wins");
            eq(1, s.humanWins(), "player 1 score");
            boolean[][] w = s.winningCells();
            int n = 0;
            for (int c = 0; c < 7; c++) for (int r = 0; r < 6; r++) if (w[c][r]) { n++; check(r == 0 && c <= 3, "winning cell at " + c + "," + r); }
            eq(4, n, "four winning cells");
            check(!s.dropAtCursor(), "no moves after game over");
        });
        test("session.vs_ai_turns_and_stale_jobs", () -> {
            GameSession s = new GameSession(GameSession.Mode.VS_AI, GameSession.Level.EASY, 7);
            eq(GameSession.Phase.HUMAN_TURN, s.phase(), "human starts game 1");
            eq(1, s.aiPlayer(), "AI is yellow in game 1");
            check(s.aiJob() == null, "no AI job on the human's turn");
            check(s.dropAtCursor(), "human drop");
            eq(GameSession.Phase.AI_THINKING, s.phase(), "AI's turn");
            check(!s.dropAtCursor(), "human can't move during AI turn");
            GameSession.Job job = s.aiJob();
            SearchResult r = job.run(null);
            check(s.applyAiMove(job, r), "apply AI move");
            eq(2, s.board().moveCount(), "two pieces");
            eq(GameSession.Phase.HUMAN_TURN, s.phase(), "back to human");
            check(!s.applyAiMove(job, r), "same job can't be applied twice");
            s.dropAtCursor();
            GameSession.Job stale = s.aiJob();
            SearchResult sr = stale.run(null);
            s.newGame();
            check(!s.applyAiMove(stale, sr), "job from the previous game is rejected");
            eq(GameSession.Phase.HUMAN_TURN, s.phase(), "restarting an unfinished game keeps the same starter");
            eq(1, s.aiPlayer(), "AI still yellow after an abandoned game");
        });
        test("session.ai_takes_wins_and_scores", () -> {
            GameSession s = new GameSession(GameSession.Mode.VS_AI, GameSession.Level.EASY, 3);
            int games = 0;
            while (games < 4) {
                if (s.phase() == GameSession.Phase.GAME_OVER) {
                    games++;
                    int before = s.aiPlayer();
                    s.newGame();
                    eq(1 - before, s.aiPlayer(), "starter swaps after a finished game");
                    eq(s.aiPlayer() == 0 ? GameSession.Phase.AI_THINKING : GameSession.Phase.HUMAN_TURN, s.phase(), "red moves first");
                    continue;
                }
                if (s.phase() == GameSession.Phase.AI_THINKING) { GameSession.Job j = s.aiJob(); check(s.applyAiMove(j, j.run(null)), "apply"); }
                else s.dropAtCursor(); // weak human: always the cursor column
            }
            eq(4, s.humanWins() + s.aiWins() + s.draws(), "every game scored once");
            check(s.aiWins() >= 3, "AI should beat an always-same-column human; won " + s.aiWins());
        });
        test("session.hint_moves_cursor_and_clears", () -> {
            GameSession s = new GameSession(GameSession.Mode.TWO_PLAYER, GameSession.Level.EASY, 5);
            for (int col : new int[]{0, 6, 1, 6, 2}) { while (s.cursor() != col) s.moveCursor(1); s.dropAtCursor(); }
            // yellow to move; red threatens column 3
            GameSession.Job h = s.hintJob();
            long t0 = System.nanoTime();
            SearchResult r = h.run(null);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(ms <= GameSession.HINT_MILLIS + 60, "hint took " + ms + "ms");
            check(s.applyHint(h, r), "apply hint");
            eq(3, s.cursor(), "hint points at the block");
            check(s.hint() != null, "hint stored");
            s.dropAtCursor();
            check(s.hint() == null, "hint cleared after a move");
            check(!s.applyHint(h, r), "old hint rejected");
        });
        test("session.levels_and_adaptive", () -> {
            GameSession s = new GameSession(GameSession.Mode.VS_AI, GameSession.Level.HARD, 1);
            eq(30000L, s.aiIterations(), "HARD budget");
            s.cycleLevel();
            eq(GameSession.Level.ADAPTIVE, s.level(), "HARD -> ADAPTIVE");
            long before = s.aiIterations();
            // play one full game against the adaptive AI; the budget must move in the right direction for the result
            while (s.phase() != GameSession.Phase.GAME_OVER) {
                if (s.phase() == GameSession.Phase.AI_THINKING) { GameSession.Job j = s.aiJob(); s.applyAiMove(j, j.run(null)); }
                else s.dropAtCursor();
            }
            long after = s.aiIterations();
            if (s.aiWins() == 1) eq(before / 2, after, "AI win halves the adaptive budget");
            else if (s.humanWins() == 1) eq(before * 2, after, "human win doubles the adaptive budget");
            s.cycleLevel();
            eq(GameSession.Level.EASY, s.level(), "ADAPTIVE wraps to EASY");
        });

        // =============== STRENGTH (slow) ===============
        slow("strength.mcts2000_vs_random_19of20", () -> {
            double score = 0;
            for (int g = 0; g < 20; g++) {
                final int gg = g;
                Random rnd = new Random(1000 + g);
                Player mcts = b -> new Mcts(5000 + gg).search(b, new SearchLimits(0, 2000)).bestMove();
                Player random = b -> { int[] l = b.legalMoves(); return l[rnd.nextInt(l.length)]; };
                score += playGame(mcts, random, g % 2 == 0);
            }
            System.out.println("    score vs random: " + score + "/20");
            check(score >= 19, "MCTS(2000) scored only " + score + "/20 vs random");
        });
        slow("strength.mcts20000_vs_mcts500_14of20", () -> {
            double score = 0;
            for (int g = 0; g < 20; g++) {
                final int gg = g;
                Player strong = b -> new Mcts(9000 + gg).search(b, new SearchLimits(0, 20000)).bestMove();
                Player weak = b -> new Mcts(7000 + gg).search(b, new SearchLimits(0, 500)).bestMove();
                score += playGame(strong, weak, g % 2 == 0);
            }
            System.out.println("    score 20000 vs 500: " + score + "/20");
            check(score >= 14, "MCTS(20000) scored only " + score + "/20 vs MCTS(500)");
        });
    }

    interface Player { int move(Board b); }

    /** Returns 1 / 0.5 / 0 for player a. */
    static double playGame(Player a, Player b, boolean aFirst) {
        Board board = new Board();
        int aSide = aFirst ? 0 : 1;
        while (!board.isTerminal()) {
            Board snapshot = board.copy();
            int mv = (board.currentPlayer() == aSide ? a : b).move(snapshot);
            check(board.canPlay(mv), "player chose illegal move " + mv);
            board.play(mv);
        }
        if (board.winner() == -1) return 0.5;
        return board.winner() == aSide ? 1 : 0;
    }

    static void winCase(String moves1, int expectedWinner, int lastCol) {
        String before = moves1.substring(0, moves1.length() - 1);
        Board pre = Board.fromMoves(before);
        check(pre.isWinningMove(lastCol), "isWinningMove(" + lastCol + ") after '" + before + "'");
        eq(-1, pre.winner(), "no winner before final move");
        Board b = Board.fromMoves(moves1);
        eq(expectedWinner, b.winner(), "winner after '" + moves1 + "'");
        check(b.isTerminal() && !b.isDraw(), "terminal, not draw");
        for (int c = 0; c < 7; c++) check(!b.canPlay(c), "no column playable after win");
        eq(0, b.legalMoves().length, "no legal moves after win");
        throwsEx(IllegalStateException.class, () -> b.play(6), "play after win");
    }

    static void compare(Board b, ReferenceBoard r, String hist) {
        String at = " (moves '" + hist + "')";
        eq(r.toMove(), b.currentPlayer(), "currentPlayer" + at);
        eq(r.moves, b.moveCount(), "moveCount" + at);
        eq(r.winner, b.winner(), "winner" + at);
        eq(r.full() && r.winner == -1, b.isDraw(), "isDraw" + at);
        eq(r.terminal(), b.isTerminal(), "isTerminal" + at);
        List<Integer> legal = new ArrayList<>();
        for (int c = -1; c <= 7; c++) {
            eq(r.canPlay(c), b.canPlay(c), "canPlay(" + c + ")" + at);
            if (c >= 0 && c < 7) {
                eq(r.wouldWin(c), b.isWinningMove(c), "isWinningMove(" + c + ")" + at);
                if (r.canPlay(c)) legal.add(c);
                for (int row = 0; row < 6; row++) eq(r.g[c][row], b.cell(c, row), "cell(" + c + "," + row + ")" + at);
            }
        }
        eq(legal.toString(), Arrays.toString(b.legalMoves()), "legalMoves" + at);
    }

    /** Random non-terminal positions where the side to move can win now (win=true) or must block exactly one threat. */
    static List<String[]> tacticalPositions(boolean win, int count, long seed) {
        Random rnd = new Random(seed);
        List<String[]> out = new ArrayList<>();
        while (out.size() < count) {
            ReferenceBoard r = new ReferenceBoard();
            StringBuilder hist = new StringBuilder();
            int len = 6 + rnd.nextInt(24);
            for (int i = 0; i < len && !r.terminal(); i++) {
                List<Integer> legal = new ArrayList<>();
                for (int c = 0; c < 7; c++) if (r.canPlay(c) && !r.wouldWin(c)) legal.add(c);
                if (legal.isEmpty()) break;
                int c = legal.get(rnd.nextInt(legal.size()));
                r.play(c);
                hist.append(c + 1);
            }
            if (r.terminal()) continue;
            StringBuilder wins = new StringBuilder();
            for (int c = 0; c < 7; c++) if (r.wouldWin(c)) wins.append(c);
            if (win) {
                if (wins.length() > 0) out.add(new String[]{hist.toString(), wins.toString()});
            } else {
                if (wins.length() > 0) continue;
                // opponent threats: columns where, if the opponent were to move, they would win immediately
                ReferenceBoard opp = r.copy();
                opp.moves++; // flip side to move for threat detection only
                List<Integer> threats = new ArrayList<>();
                for (int c = 0; c < 7; c++) if (opp.wouldWin(c)) threats.add(c);
                if (threats.size() == 1) out.add(new String[]{hist.toString(), String.valueOf(threats.get(0))});
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        boolean fast = Arrays.asList(args).contains("--fast");
        int pass = 0, fail = 0, skipped = 0;
        List<String> failures = new ArrayList<>();
        ExecutorService ex = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        for (T t : TESTS) {
            if (fast && t.slow()) { skipped++; continue; }
            long t0 = System.nanoTime();
            Future<?> f = ex.submit(() -> { t.check().run(); return null; });
            String err = null;
            try {
                f.get(t.slow() ? 300 : 90, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                f.cancel(true);
                err = "TIMEOUT";
            } catch (ExecutionException ee) {
                Throwable c = ee.getCause();
                if (c instanceof AssertionError) {
                    err = c.getMessage();
                } else {
                    StackTraceElement[] st = c.getStackTrace();
                    err = c + (st.length > 0 ? " at " + st[0] : "");
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (err == null) {
                pass++;
                System.out.printf("PASS %-45s %6d ms%n", t.name(), ms);
            } else {
                fail++;
                String line = String.format("FAIL %-45s %6d ms  %s", t.name(), ms, err);
                System.out.println(line);
                failures.add(line);
            }
        }
        System.out.printf("%nSUMMARY: %d passed, %d failed, %d skipped%n", pass, fail, skipped);
        System.exit(fail == 0 ? 0 : 1);
    }
}
