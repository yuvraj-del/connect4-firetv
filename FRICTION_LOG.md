# Friction log

Problems I hit while building Connect Four AI for Fire TV (October 2026), with what I tried and what would have helped.

## 1. Running the Vega simulator on Windows
- **Severity:** High
- **Task:** Set up a Fire TV development environment on my Windows laptop.
- **Steps:** Followed the hackathon Resources page to the Vega SDK install guide.
- **Expected:** An installer for my operating system, as with Android Studio.
- **Actual:** The Vega SDK and Vega Virtual Device support only macOS and Ubuntu. Windows and WSL are explicitly unsupported.
- **Workaround:** Targeted Fire OS instead and tested on the Android TV emulator in Android Studio.
- **Suggestion:** State "macOS / Ubuntu only" on the hackathon Resources page itself, and link Windows users straight to the Fire OS path.

## 2. Which emulator counts for the demo
- **Severity:** Medium
- **Task:** Confirm I could submit without buying a Fire TV device.
- **Steps:** Read the Fire TV track requirements, then searched the rules.
- **Expected:** The requirements to list every accepted way to show the app running.
- **Actual:** The requirements mention only an actual Fire TV device or the Fire TV/Vega simulator. That the Android TV emulator is accepted appears only in the FAQ.
- **Workaround:** Found it in the FAQ.
- **Suggestion:** Add "or the Android TV emulator" to the Fire TV requirements text.

## 3. Testing remote buttons without hardware
- **Severity:** Low
- **Task:** Test the Menu and Play/Pause buttons in the emulator.
- **Steps:** Tried the laptop keyboard, then the emulator's controls.
- **Expected:** A way to press every Fire TV remote button.
- **Actual:** Arrow keys and Enter map to the D-pad, but I found no easy way to send Menu or Play/Pause.
- **Workaround:** Made every feature reachable with the D-pad and Select alone, keeping Menu and Play/Pause as shortcuts.
- **Suggestion:** Document a keyboard mapping for all Fire TV remote buttons, or ship a Fire TV remote skin for the emulator.
