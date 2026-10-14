package com.yuvraj.connect4.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import com.yuvraj.connect4.engine.Board;
import com.yuvraj.connect4.engine.SearchResult;
import com.yuvraj.connect4.game.GameSession;

import java.util.Locale;

/**
 * The whole TV screen, drawn on a Canvas: a toy-blue board with real holes on a game-table felt, glossy discs that
 * fall behind the frame, the AI's live "thought" bars above the columns, and a right-hand rail with the turn,
 * the AI's own words, the score, and a D-pad-navigable action bar.
 */
public final class BoardView extends View {

    // ---- palette: game night on a felt table ----
    private static final int FELT_TOP = 0xFF123B36, FELT_BOTTOM = 0xFF0B2724;
    private static final int BLUE_HI = 0xFF4677EE, BLUE = 0xFF2B5BD7, BLUE_LO = 0xFF1D3F9F, BLUE_RIM = 0xFF17337F;
    private static final int RED = 0xFFE5383B, RED_HI = 0xFFFF7A6E, RED_LO = 0xFFA81E26;
    private static final int YEL = 0xFFF4B41A, YEL_HI = 0xFFFFDD6B, YEL_LO = 0xFFB97F05;
    private static final int IVORY = 0xFFF4EBD9, SAGE = 0xFFA9C2B9;
    private static final int THOUGHT = 0xFFB7A6FF;

    /** Buttons in the action bar, left to right. */
    enum Action { HINT, LEVEL, PLAYERS, NEW_GAME }

    private static final long PULSE_MS = 3000;

    private GameSession session;
    private SearchResult live;            // running/last search snapshot for the strip
    private boolean liveIsAi;
    private boolean liveDone;             // the search in `live` has finished
    private int aiPlayedCol = -1;         // the AI's last move and how it rated it
    private double aiPlayedRate = Double.NaN;

    private boolean barFocused;
    private Action focusedAction = Action.HINT;

    private int animCol = -1, animRow = -1;
    private long animStart, animDur;
    private long gameOverAt = -1;

    // ---- layout (computed in onSizeChanged) ----
    private float cell, discR, holeR, boardL, boardT, boardW, boardH, railX, railW, unit;
    private final Path boardPath = new Path();
    private Shader feltShader, boardShader;
    private Shader redShader, yelShader;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Typeface bold, medium;

    public BoardView(Context context) {
        super(context);
        setFocusable(true);
        setKeepScreenOn(true);
        stroke.setStyle(Paint.Style.STROKE);
        bold = loadFont(context, "fonts/fredoka_bold.ttf", Typeface.DEFAULT_BOLD);
        medium = loadFont(context, "fonts/fredoka_medium.ttf", Typeface.DEFAULT);
    }

    private static Typeface loadFont(Context c, String path, Typeface fallback) {
        try { return Typeface.createFromAsset(c.getAssets(), path); } catch (RuntimeException e) { return fallback; }
    }

    // ------------------------------------------------------------------ state from the activity
    void setSession(GameSession s) { session = s; resetForNewGame(); }

    void resetForNewGame() {
        live = null;
        aiPlayedCol = -1;
        animCol = -1;
        gameOverAt = -1;
        invalidate();
    }

    void setLive(SearchResult result, boolean isAi, boolean done) {
        live = result;
        liveIsAi = isAi;
        liveDone = done;
        invalidate();
    }

    /** Remember the AI's move so the rail can say what it played and how confident it is. */
    void aiPlayed(SearchResult result) {
        aiPlayedCol = result.bestMove();
        aiPlayedRate = result.winRates()[aiPlayedCol];
    }

    void setFocus(boolean onBar, Action action) { barFocused = onBar; focusedAction = action; invalidate(); }

    /** True for a moment after a game ends, so a mashed Select doesn't skip the result. */
    boolean justEnded() { return gameOverAt >= 0 && SystemClock.uptimeMillis() - gameOverAt < 500; }

    /** Call right after a piece is played so it falls into place. */
    void pieceAdded() {
        int col = session.lastMoveColumn();
        if (col < 0) return;
        int row = Board.HEIGHT - 1;
        while (row >= 0 && session.cell(col, row) == -1) row--;
        animCol = col;
        animRow = row;
        animStart = SystemClock.uptimeMillis();
        animDur = 260 + 45L * (Board.HEIGHT - 1 - row);   // longer fall from higher up
        invalidate();
    }

    static String levelLabel(GameSession.Level l) {
        switch (l) {
            case EASY: return "Easy";
            case MEDIUM: return "Medium";
            case HARD: return "Hard";
            default: return "Adaptive";
        }
    }

    static String actionLabel(Action a, GameSession s) {
        switch (a) {
            case HINT: return "Hint";
            case LEVEL: return levelLabel(s.level());
            case PLAYERS: return s.mode() == GameSession.Mode.VS_AI ? "1 player" : "2 players";
            default: return "New game";
        }
    }

    /** The difficulty button only exists when there's an AI to be difficult. */
    static boolean actionVisible(Action a, GameSession s) {
        return a != Action.LEVEL || s.mode() == GameSession.Mode.VS_AI;
    }

    // ------------------------------------------------------------------ layout
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        unit = h / 1080f;
        float margin = 56 * unit;
        // board (6 rows) + thought strip above it (2.15 cells) must fit the height
        // board (6 rows) + thought strip + labels, keeping the labels inside the TV title-safe area
        cell = Math.min((h - 2 * margin) / (Board.HEIGHT + 2.9f), w * 0.50f / Board.WIDTH);
        discR = cell * 0.42f;
        holeR = cell * 0.37f;
        boardW = cell * Board.WIDTH;
        boardH = cell * Board.HEIGHT;
        boardL = 96 * unit;
        boardT = h - margin - boardH - cell * 0.12f;
        railX = boardL + boardW + 120 * unit;
        railW = w - railX - 80 * unit;

        feltShader = new RadialGradient(w * 0.35f, h * 0.45f, Math.max(w, h) * 0.75f,
                FELT_TOP, FELT_BOTTOM, Shader.TileMode.CLAMP);
        boardShader = new LinearGradient(0, boardT - cell * 0.15f, 0, boardT + boardH + cell * 0.15f,
                new int[]{BLUE_HI, BLUE, BLUE_LO}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);
        redShader = discShader(RED_HI, RED, RED_LO);
        yelShader = discShader(YEL_HI, YEL, YEL_LO);

        boardPath.reset();
        boardPath.setFillType(Path.FillType.EVEN_ODD);
        rect.set(boardL - cell * 0.15f, boardT - cell * 0.15f, boardL + boardW + cell * 0.15f, boardT + boardH + cell * 0.15f);
        boardPath.addRoundRect(rect, cell * 0.28f, cell * 0.28f, Path.Direction.CW);
        for (int c = 0; c < Board.WIDTH; c++)
            for (int r = 0; r < Board.HEIGHT; r++)
                boardPath.addCircle(colX(c), rowY(r), holeR, Path.Direction.CW);
    }

    /** Disc shading centred at (0,0): lit from the upper left, like moulded plastic. */
    private Shader discShader(int hi, int mid, int lo) {
        return new RadialGradient(-discR * 0.35f, -discR * 0.4f, discR * 1.45f,
                new int[]{hi, mid, lo}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
    }

    private float colX(int c) { return boardL + (c + 0.5f) * cell; }
    private float rowY(int r) { return boardT + (Board.HEIGHT - 1 - r + 0.5f) * cell; }
    private float hoverY() { return boardT - cell * 0.72f; }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas canvas) {
        if (session == null || feltShader == null) return;
        int w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        boolean animating = false;

        fill.setColor(0xFFFFFFFF);          // opaque: a shader is modulated by the paint's alpha
        fill.setShader(feltShader);
        canvas.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        boolean over = session.phase() == GameSession.Phase.GAME_OVER;
        if (!over) gameOverAt = -1;
        else if (gameOverAt < 0) gameOverAt = now;
        boolean pulsing = over && now - gameOverAt < PULSE_MS;
        boolean[][] win = over ? session.winningCells() : null;
        boolean someoneWon = over && session.winner() != -1;

        drawThoughtStrip(canvas);

        // hovering disc above the cursor column
        boolean dropping = animCol >= 0 && now - animStart < animDur;
        if (session.phase() == GameSession.Phase.HUMAN_TURN && !dropping) {
            drawDisc(canvas, colX(session.cursor()), hoverY(), session.currentPlayer(), barFocused ? 110 : 255);
        }
        if (dropping) animating = true;

        // board shadow on the felt
        rect.set(boardL - cell * 0.15f, boardT - cell * 0.15f + cell * 0.14f,
                boardL + boardW + cell * 0.15f, boardT + boardH + cell * 0.15f + cell * 0.14f);
        fill.setColor(0x66000000);
        canvas.drawRoundRect(rect, cell * 0.28f, cell * 0.28f, fill);

        // discs sit BEHIND the board, so the frame overlaps their edges like the real toy
        boolean fallingNow = animCol >= 0 && now - animStart < animDur;
        for (int c = 0; c < Board.WIDTH; c++) {
            for (int r = 0; r < Board.HEIGHT; r++) {
                int who = session.cell(c, r);
                if (who == -1 || (fallingNow && c == animCol && r == animRow)) continue;
                int alpha = someoneWon && !win[c][r] ? 130 : 255;
                drawDisc(canvas, colX(c), rowY(r), who, alpha);
            }
        }
        if (fallingNow) {
            float t = (now - animStart) / (float) animDur;
            float y = hoverY() + (rowY(animRow) - hoverY()) * bounce(t);
            drawDisc(canvas, colX(animCol), y, session.cell(animCol, animRow), 255);
            animating = true;
        }

        // the board itself, with holes cut out
        fill.setColor(0xFFFFFFFF);
        fill.setShader(boardShader);
        canvas.drawPath(boardPath, fill);
        fill.setShader(null);
        stroke.setColor(BLUE_RIM);
        stroke.setStrokeWidth(cell * 0.045f);
        for (int c = 0; c < Board.WIDTH; c++)
            for (int r = 0; r < Board.HEIGHT; r++)
                canvas.drawCircle(colX(c), rowY(r), holeR, stroke);
        // a soft highlight along the top edge of the plastic
        rect.set(boardL - cell * 0.05f, boardT - cell * 0.11f, boardL + boardW + cell * 0.05f, boardT - cell * 0.05f);
        fill.setColor(0x33FFFFFF);
        canvas.drawRoundRect(rect, cell * 0.04f, cell * 0.04f, fill);

        // winning line
        if (someoneWon) {
            float p = pulsing ? 0.5f + 0.5f * (float) Math.sin(now / 150.0) : 1f;
            stroke.setColor(IVORY);
            stroke.setAlpha((int) (140 + 115 * p));
            stroke.setStrokeWidth(cell * (0.05f + 0.03f * p));
            for (int c = 0; c < Board.WIDTH; c++)
                for (int r = 0; r < Board.HEIGHT; r++)
                    if (win[c][r]) canvas.drawCircle(colX(c), rowY(r), holeR * 0.78f, stroke);
            stroke.setAlpha(255);
            if (pulsing) animating = true;
        }

        drawRail(canvas, h);

        if (animating) postInvalidateOnAnimation();
    }

    private void drawDisc(Canvas canvas, float cx, float cy, int player, int alpha) {
        drawDisc(canvas, cx, cy, player, alpha, discR);
    }

    /** Draws a disc of any radius by scaling the canvas, so the cached shaders are reused. */
    private void drawDisc(Canvas canvas, float cx, float cy, int player, int alpha, float radius) {
        canvas.save();
        canvas.translate(cx, cy);
        if (radius != discR) canvas.scale(radius / discR, radius / discR);
        fill.setShader(player == 0 ? redShader : yelShader);
        fill.setAlpha(alpha);
        canvas.drawCircle(0, 0, discR, fill);
        fill.setShader(null);
        // the moulded ridge every Connect Four checker has
        stroke.setStrokeWidth(discR * 0.07f);
        stroke.setColor(player == 0 ? RED_LO : YEL_LO);
        stroke.setAlpha(alpha * 150 / 255);
        canvas.drawCircle(0, 0, discR * 0.66f, stroke);
        stroke.setColor(player == 0 ? RED_HI : YEL_HI);
        stroke.setAlpha(alpha * 90 / 255);
        canvas.drawCircle(-discR * 0.02f, -discR * 0.03f, discR * 0.60f, stroke);
        // specular glint
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(alpha * 70 / 255);
        rect.set(-discR * 0.55f, -discR * 0.72f, -discR * 0.05f, -discR * 0.42f);
        canvas.drawOval(rect, fill);
        fill.setAlpha(255);
        stroke.setAlpha(255);
        canvas.restore();
    }

    /** Falls, hits the bottom, bounces once a little. */
    private static float bounce(float t) {
        if (t < 0.78f) { float u = t / 0.78f; return u * u; }
        float u = (t - 0.78f) / 0.22f;              // 0..1 over the bounce
        return 1f - 0.06f * (float) Math.sin(Math.PI * u);
    }

    /** Win-chance bars above each column, from the side to move's point of view. */
    private void drawThoughtStrip(Canvas canvas) {
        if (live == null) return;
        int color = liveIsAi ? THOUGHT : (session.currentPlayer() == 0 ? RED : YEL);
        float baseY = hoverY() - discR - cell * 0.14f;
        float maxH = cell * 0.95f;
        float barW = cell * 0.46f;
        double[] rates = live.winRates();
        int best = live.bestMove();
        text.setTypeface(bold);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Math.max(22f, cell * 0.24f));
        for (int c = 0; c < Board.WIDTH; c++) {
            double p = rates[c];
            if (Double.isNaN(p)) continue;
            float x = colX(c);
            float rad = barW * 0.22f;
            rect.set(x - barW / 2, baseY - maxH, x + barW / 2, baseY);
            fill.setColor(0x1FF4EBD9);
            canvas.drawRoundRect(rect, rad, rad, fill);
            float hgt = Math.max(rad * 2, maxH * (float) p);     // height is the real percentage
            rect.set(x - barW / 2, baseY - hgt, x + barW / 2, baseY);
            fill.setColor(color);
            fill.setAlpha(c == best ? 255 : 120);
            canvas.drawRoundRect(rect, rad, rad, fill);
            fill.setAlpha(255);
            text.setColor(c == best ? IVORY : SAGE);
            canvas.drawText(Math.round(p * 100) + "%", x, baseY - maxH - cell * 0.10f, text);
        }
    }

    private void drawRail(Canvas canvas, int h) {
        float x = railX;
        boolean vsAi = session.mode() == GameSession.Mode.VS_AI;
        GameSession.Phase ph = session.phase();
        text.setTextAlign(Paint.Align.LEFT);

        // small wordmark, deliberately quiet
        text.setTypeface(medium);
        text.setColor(SAGE);
        text.setTextSize(34 * unit);
        canvas.drawText("Connect Four", x, 104 * unit, text);

        // headline: whose turn / who won, led by their disc
        int who;
        String headline;
        if (ph == GameSession.Phase.GAME_OVER) {
            int w = session.winner();
            who = w;
            if (w == -1) headline = "It's a draw";
            else if (vsAi) headline = w == session.aiPlayer() ? "The AI wins" : "You win!";
            else headline = (w == 0 ? "Red" : "Yellow") + " wins!";
        } else {
            who = session.currentPlayer();
            if (ph == GameSession.Phase.AI_THINKING) headline = "AI is thinking";
            else if (vsAi) headline = "Your turn";
            else headline = (who == 0 ? "Red" : "Yellow") + "'s turn";
        }
        float headY = 268 * unit;
        float iconX = x + discR * 0.62f;
        if (who >= 0) drawDisc(canvas, iconX, headY - 32 * unit, who, 255, discR * 0.62f);
        text.setTypeface(bold);
        text.setColor(IVORY);
        float hx = who >= 0 ? x + discR * 1.62f : x;
        float size = 92 * unit;
        text.setTextSize(size);
        float tw = text.measureText(headline), room = railX + railW - hx;
        if (tw > room) text.setTextSize(size * room / tw);          // shrink to fit, never overflow
        canvas.drawText(headline, hx, headY, text);

        // the AI's voice (or the hint, or what to do next)
        text.setTypeface(medium);
        text.setTextSize(40 * unit);
        text.setColor(liveIsAi && live != null && !liveDone ? THOUGHT : IVORY);
        drawWrapped(canvas, message(), x, headY + 84 * unit, railW, 52 * unit, 2);

        drawWhosAhead(canvas, x, headY + 210 * unit);

        // score
        float sy = h - 330 * unit;
        float r = discR * 0.62f;
        int left = vsAi ? 1 - session.aiPlayer() : 0, right = vsAi ? session.aiPlayer() : 1;
        drawScore(canvas, x, sy, r, left, vsAi ? "You" : "Red", session.humanWins());
        drawScore(canvas, x + railW * 0.36f, sy, r, right, vsAi ? "AI" : "Yellow", session.aiWins());
        text.setTypeface(medium);
        text.setTextSize(30 * unit);
        text.setColor(SAGE);
        int d = session.draws();
        canvas.drawText(d == 0 ? "No draws yet" : d == 1 ? "1 draw" : d + " draws", x + railW * 0.72f, sy + 12 * unit, text);

        drawActionBar(canvas, x, h - 190 * unit);
    }

    /** Red-vs-yellow tug of war from the latest evaluation (the AI's own rating of its move, or a hint). */
    private void drawWhosAhead(Canvas canvas, float x, float y) {
        double pRed;
        if (session.phase() == GameSession.Phase.GAME_OVER) return;
        if (live != null && !liveIsAi && liveDone) {
            double p = live.winRates()[live.bestMove()];
            pRed = session.currentPlayer() == 0 ? p : 1 - p;
        } else if (session.mode() == GameSession.Mode.VS_AI && aiPlayedCol >= 0 && !Double.isNaN(aiPlayedRate)) {
            pRed = session.aiPlayer() == 0 ? aiPlayedRate : 1 - aiPlayedRate;
        } else {
            return;
        }
        float barW = railW * 0.86f, barH = 20 * unit;
        text.setTypeface(medium);
        text.setTextSize(30 * unit);
        text.setColor(SAGE);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("Who's ahead", x, y, text);
        float by = y + 26 * unit;
        float split = x + barW * (float) pRed;
        rect.set(x, by, x + barW, by + barH);
        fill.setColor(YEL);
        canvas.drawRoundRect(rect, barH / 2, barH / 2, fill);
        rect.set(x, by, Math.max(x + barH, split), by + barH);
        fill.setColor(RED);
        canvas.drawRoundRect(rect, barH / 2, barH / 2, fill);
        if (split > x + barH && split < x + barW - barH) {
            rect.set(split - barH / 2, by, split, by + barH);
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, fill);   // square off the join
        }
        fill.setColor(IVORY);
        canvas.drawRect(x + barW / 2 - 1.5f * unit, by - 8 * unit, x + barW / 2 + 1.5f * unit, by + barH + 8 * unit, fill);
        boolean vsAi = session.mode() == GameSession.Mode.VS_AI;
        String redName = vsAi ? (session.aiPlayer() == 0 ? "AI" : "You") : "Red";
        String yelName = vsAi ? (session.aiPlayer() == 1 ? "AI" : "You") : "Yellow";
        text.setTypeface(bold);
        text.setTextSize(32 * unit);
        text.setColor(IVORY);
        canvas.drawText(redName + " " + Math.round(pRed * 100) + "%", x, by + barH + 44 * unit, text);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(Math.round((1 - pRed) * 100) + "% " + yelName, x + barW, by + barH + 44 * unit, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawScore(Canvas canvas, float x, float y, float r, int player, String label, int wins) {
        drawDisc(canvas, x + r, y - 10 * unit, player, 255, r);
        text.setTypeface(bold);
        text.setColor(IVORY);
        text.setTextSize(64 * unit);
        canvas.drawText(String.valueOf(wins), x + r * 2.6f, y + 22 * unit, text);
        text.setTypeface(medium);
        text.setTextSize(28 * unit);
        text.setColor(SAGE);
        canvas.drawText(label, x + r * 2.6f, y + 62 * unit, text);
    }

    private void drawActionBar(Canvas canvas, float x, float y) {
        float hgt = 72 * unit, gap = 14 * unit, padX = 26 * unit;
        text.setTextSize(30 * unit);
        text.setTextAlign(Paint.Align.CENTER);
        float cx = x;
        for (Action a : Action.values()) {
            if (!actionVisible(a, session)) continue;
            String label = actionLabel(a, session);
            text.setTypeface(bold);
            float bw = text.measureText(label) + padX * 2;
            boolean focused = barFocused && a == focusedAction;
            rect.set(cx, y, cx + bw, y + hgt);
            if (focused) {
                fill.setColor(IVORY);
                canvas.drawRoundRect(rect, hgt / 2, hgt / 2, fill);
                text.setColor(FELT_BOTTOM);
            } else {
                fill.setColor(0x24F4EBD9);
                canvas.drawRoundRect(rect, hgt / 2, hgt / 2, fill);
                text.setColor(IVORY);
            }
            canvas.drawText(label, cx + bw / 2, y + hgt / 2 + 11 * unit, text);
            cx += bw + gap;
        }
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(medium);
        text.setTextSize(28 * unit);
        text.setColor(SAGE);
        String tip = barFocused ? "Press Up to go back to the board" : "Press Down for options";
        canvas.drawText(tip, x, y + hgt + 46 * unit, text);
    }

    private String message() {
        boolean vsAi = session.mode() == GameSession.Mode.VS_AI;
        GameSession.Phase ph = session.phase();
        if (ph == GameSession.Phase.GAME_OVER) return "Press Select for a rematch.";
        if (ph == GameSession.Phase.AI_THINKING) {
            if (live == null) return "Looking at every column…";
            return String.format(Locale.US, "%,d games played out in my head so far.", live.iterations());
        }
        if (live != null && !liveIsAi) {
            if (!liveDone) return String.format(Locale.US, "Searching for your best move… %,d games so far.", live.iterations());
            return String.format(Locale.US, "Column %d gives you the best chance: %d%%.",
                    live.bestMove() + 1, Math.round(live.winRates()[live.bestMove()] * 100));
        }
        if (vsAi && aiPlayedCol >= 0 && !Double.isNaN(aiPlayedRate)) {
            long pct = Math.round(aiPlayedRate * 100);
            String mood = pct >= 80 ? " Looking good for me." : pct <= 30 ? " You've got me worried." : "";
            return String.format(Locale.US, "I played column %d. I give myself %d%%.%s", aiPlayedCol + 1, pct, mood);
        }
        if (vsAi) return "Pick a column and press Select. Press Up for a hint.";
        return "Pass the remote after each move.";
    }

    /** Draws text wrapped to maxWidth, at most maxLines lines. */
    private void drawWrapped(Canvas canvas, String s, float x, float y, float maxWidth, float lineH, int maxLines) {
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        for (String word : words) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (text.measureText(trial) > maxWidth && line.length() > 0) {
                canvas.drawText(line.toString(), x, y, text);
                y += lineH;
                if (++lines >= maxLines) return;
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(trial);
            }
        }
        if (line.length() > 0) canvas.drawText(line.toString(), x, y, text);
    }
}
