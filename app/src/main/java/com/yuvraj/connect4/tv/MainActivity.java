package com.yuvraj.connect4.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.yuvraj.connect4.engine.SearchResult;
import com.yuvraj.connect4.game.GameSession;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The whole app: one full-screen BoardView, Fire TV remote input, and a background thread for the AI.
 *
 * Board:      Left/Right = pick column   Select = drop (rematch when it's over)   Up = hint   Down = action bar
 * Action bar: Left/Right = choose button  Select = press it   Up or Back = back to the board
 *             Buttons: Hint, difficulty (Easy/Medium/Hard/Adaptive), 1 or 2 players, New game
 * Shortcuts:  Menu = 1/2 players, Play/Pause = new game; on a keyboard H, D, M, N.
 */
public final class MainActivity extends Activity {

    /** The AI always "thinks" at least this long so players can watch the win-chance bars move. */
    private static final long MIN_THINK_MS = 900;
    /** How many snapshots of the AI's search to show while it thinks. */
    private static final int THINK_FRAMES = 18;
    /** How long the AI's final estimates stay on screen before its disc drops. */
    private static final long HOLD_RESULT_MS = 250;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "connect4-ai");
        t.setDaemon(true);
        return t;
    });

    private GameSession session;
    private BoardView view;
    private GameSession.Mode mode = GameSession.Mode.VS_AI;
    private GameSession.Level level = GameSession.Level.MEDIUM;
    /** Bumped whenever a search's result should be thrown away (new game, new session, move played). */
    private int searchToken = 0;
    private boolean searching = false;
    private Future<?> running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new BoardView(this);
        setContentView(view);
        newSession();
    }

    @Override
    protected void onDestroy() {
        cancelSearch();
        worker.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ input
    /** Where the D-pad is pointed: the board (pick a column) or the action bar under the score. */
    private boolean barFocused = false;
    private BoardView.Action focused = BoardView.Action.HINT;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean repeat = event.getRepeatCount() > 0;
        // shortcuts that work anywhere (remote media keys + emulator keyboard)
        switch (keyCode) {
            case KeyEvent.KEYCODE_H: if (!repeat) { requestHint(); } return redraw();
            case KeyEvent.KEYCODE_D: if (!repeat) cycleLevel(); return redraw();
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_M: if (!repeat) toggleMode(); return redraw();
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_N: if (!repeat) newGame(); return redraw();
            default: break;
        }
        boolean select = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
        boolean left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND;
        boolean right = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;

        if (barFocused) {
            if (left || right) { stepFocus(right ? 1 : -1); return redraw(); }
            if (select) { if (!repeat) activate(focused); return redraw(); }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_BACK) { setBarFocus(false); return redraw(); }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            return super.onKeyDown(keyCode, event);
        }

        if (left || right) { session.moveCursor(right ? 1 : -1); return redraw(); }
        if (select) {
            if (repeat) return true;
            if (session.phase() == GameSession.Phase.GAME_OVER) { if (!view.justEnded()) newGame(); }
            else if (session.dropAtCursor()) afterMove();
            return redraw();
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { if (!repeat) requestHint(); return redraw(); }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { if (!repeat) setBarFocus(true); return redraw(); }
        return super.onKeyDown(keyCode, event);
    }

    private boolean redraw() { view.invalidate(); return true; }

    private void setBarFocus(boolean onBar) {
        barFocused = onBar;
        if (!BoardView.actionVisible(focused, session)) focused = BoardView.Action.HINT;
        view.setFocus(barFocused, focused);
    }

    private void stepFocus(int dir) {
        BoardView.Action[] all = BoardView.Action.values();
        int i = focused.ordinal();
        for (int n = 0; n < all.length; n++) {
            i = Math.floorMod(i + dir, all.length);
            if (BoardView.actionVisible(all[i], session)) { focused = all[i]; break; }
        }
        view.setFocus(true, focused);
    }

    private void activate(BoardView.Action a) {
        switch (a) {
            case HINT: setBarFocus(false); requestHint(); break;      // back to the board so Select drops on the hint
            case LEVEL: cycleLevel(); break;
            case PLAYERS: toggleMode(); break;
            case NEW_GAME: setBarFocus(false); newGame(); break;
        }
    }

    private void cycleLevel() {
        if (session.mode() != GameSession.Mode.VS_AI) return;
        session.cycleLevel();
        level = session.level();
    }

    private void toggleMode() {
        mode = mode == GameSession.Mode.VS_AI ? GameSession.Mode.TWO_PLAYER : GameSession.Mode.VS_AI;
        newSession();
    }

    // ------------------------------------------------------------------ game flow
    /** Throws away any running AI/hint search and interrupts it so the worker thread frees up immediately. */
    private void cancelSearch() {
        searchToken++;
        searching = false;
        if (running != null) { running.cancel(true); running = null; }
    }

    private void newSession() {
        cancelSearch();
        session = new GameSession(mode, level, System.nanoTime());
        view.setSession(session);
        if (!BoardView.actionVisible(focused, session)) focused = BoardView.Action.HINT;
        view.setFocus(barFocused, focused);
        maybeStartAi();
    }

    private void newGame() {
        cancelSearch();
        session.newGame();
        view.resetForNewGame();
        maybeStartAi();
    }

    private void afterMove() {
        cancelSearch();         // any running hint is now stale
        view.pieceAdded();
        view.setLive(null, false, false);
        maybeStartAi();
    }

    private void maybeStartAi() {
        if (searching || session.phase() != GameSession.Phase.AI_THINKING) return;
        final GameSession.Job job = session.aiJob();
        final int token = ++searchToken;
        final long t0 = SystemClock.uptimeMillis();
        searching = true;
        view.setLive(null, true, false);
        running = worker.submit(() -> {
            // Real intermediate results of the search. On a slow stick they arrive spread out and show live;
            // on a fast machine they all arrive within milliseconds, so we replay them over the thinking time.
            java.util.List<SearchResult> frames = new java.util.ArrayList<>();
            SearchResult result = job.runInFrames(THINK_FRAMES, snap -> {
                frames.add(snap);
                ui.post(() -> { if (token == searchToken) view.setLive(snap, true, false); });
            });
            frames.add(result);
            long elapsed = SystemClock.uptimeMillis() - t0;
            long replay = Math.max(0, MIN_THINK_MS - elapsed);
            if (replay > 0) {
                long step = replay / frames.size();
                for (int i = 0; i < frames.size(); i++) {
                    final SearchResult f = frames.get(i);
                    ui.postDelayed(() -> { if (token == searchToken) view.setLive(f, true, false); }, step * i);
                }
            } else {
                ui.post(() -> { if (token == searchToken) view.setLive(result, true, false); });
            }
            long wait = replay + HOLD_RESULT_MS;    // let the final bars (best column in lavender) sit for a moment
            ui.postDelayed(() -> {
                if (token != searchToken) return;
                searching = false;
                running = null;
                if (session.applyAiMove(job, result)) {
                    view.aiPlayed(result);
                    afterMove();
                }
            }, wait);
        });
    }

    private void requestHint() {
        if (searching || session.phase() != GameSession.Phase.HUMAN_TURN) return;
        final GameSession.Job job = session.hintJob();
        final int token = ++searchToken;
        searching = true;
        view.setLive(null, false, false);
        running = worker.submit(() -> {
            SearchResult result = job.run(snap -> ui.post(() -> {
                if (token == searchToken) view.setLive(snap, false, false);
            }));
            ui.post(() -> {
                if (token != searchToken) return;
                searching = false;
                running = null;
                if (session.applyHint(job, result)) view.setLive(result, false, true);
                else view.setLive(null, false, false);
            });
        });
    }
}
