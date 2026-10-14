# Connect Four AI for Fire TV

A family Connect Four game for Fire TV, played with the remote, against an AI you can watch think: while it searches, a bar above each column shows its live estimated win chance.

- **Engine:** pure Java 17, bitboards, Monte Carlo tree search (~800k simulated games/sec on a laptop), tactical win/block rules, a tree-size cap so it's safe on a TV stick's memory.
- **App:** Android (Fire OS) TV app, plain Android SDK, no libraries. One Canvas-drawn screen.
- **Modes:** 1 player vs AI (Easy / Medium / Hard / Adaptive) or 2 players. Hint button, swapping first move each game, running score.
- **The AI explains itself:** live win-chance bars while it thinks, a sentence after each move, and a "Who's ahead" bar.

## Run the app (no Fire TV device needed)
1. Install Android Studio. In **Device Manager → Create Virtual Device → TV → Television (1080p)**, pick an API 34 image and start it.
2. **File → Open** this folder. Let Gradle sync (first time downloads Gradle 8.11 and the Android plugin).
3. Press **Run ▶**. The app launches on the TV emulator.

Controls (D-pad + Select only):
- **Board:** Left/Right choose a column, Select drops (or starts a rematch), Up asks for a hint, Down opens the button row.
- **Button row:** Left/Right choose Hint, difficulty, 1 or 2 players, or New game; Select presses it; Up or Back returns to the board.
- **Shortcuts:** Menu toggles 1/2 players, Play/Pause starts a new game. On a keyboard: arrows, Enter, H, D, M, N.

## Layout
| Path | What |
| --- | --- |
| `app/` | The Fire TV app: `MainActivity` (remote input, AI thread) and `BoardView` (all drawing) |
| `engine/src/main/java/.../engine` | The engine: bitboard `Board`, Monte Carlo tree search `Mcts`, difficulty levels |
| `engine/src/main/java/.../game` | `GameSession`: turns, cursor, AI and hint jobs, scores, testable without Android |
| `tests/` | Dependency-free test suite: correctness, 3,000 games replayed against a reference board, tactics, timing, speed, strength |
| `verify.sh` | Compiles the engine and runs every test (`--fast` skips the strength games) |

## Run the tests
Requires Java 17+.
```bash
./verify.sh
```

## How the app uses the engine
`app/build.gradle` adds `engine/src/main/java` as a source folder, so the app and the test suite compile the exact same engine code. The AI search runs on a background thread; every ~60 ms it posts a snapshot of per-column win rates to the screen, which draws them as bars.

## License
Code: MIT (see `LICENSE`). The Fredoka font in `app/src/main/assets/fonts` is under the SIL Open Font License (`OFL.txt` next to it).
