# AngryBot for PC

This is a Windows version of the [AngryBot](https://github.com/AlienHomie007/AngryBot) recorder, made for Angry Birds 2 in **Google Play Games on PC**. You play a level once with the mouse, and AngryBot replays your moves with the same timing. It's a single `AngryBot.exe` with nothing to install.

Google Play Games treats a left-button mouse drag as a finger on the touch screen. AngryBot records those drags and plays them back. Positions are saved relative to the game window, so moving the window doesn't break a recording.

## Controls

A small floating bar stays on top of the game:

| Button | What it does |
|---|---|
| ● Rec | Starts a new recording. Play normally; every click and drag on the game is saved. |
| ▶ Play | Replays the saved moves with the same timing. |
| ⟳ Loop | Replays again and again until you stop it. |
| ■ Stop | Stops recording or playback. **F8** does the same from anywhere. |
| ↶ Undo | Deletes the last saved move. |
| ⌖ Pick | Use this if AngryBot can't find the game: tap it, then click the game window. |
| ✕ | Closes AngryBot. |

Drag the bar by its **⠿ AngryBot** label. Clicking the bar never takes focus away from the game.

**Moving or clicking the mouse during playback stops it.** That's how you take control back.

## Download

**[⬇ Download AngryBot-PC.exe](https://github.com/AlienHomie007/AngryBot/releases/latest/download/AngryBot-PC.exe)**

The file isn't signed, so Windows may show "Windows protected your PC". Click **More info**, then **Run anyway**.

## Build it yourself

Double-click `build.cmd`, or run it from a terminal. It uses the C# compiler built into Windows (.NET Framework 4) and creates `AngryBot.exe`. On GitHub, `.github/workflows/build-pc.yml` does the same on every change to `pc/` and publishes the result as `AngryBot-PC.exe`.

## Use it

1. Open Angry Birds 2 in Google Play Games and go to level 11's start screen, the one with the **Play** button.
2. Start `AngryBot.exe`. The status line should say *Ready*. If it says *open Angry Birds 2*, tap **⌖ Pick** and click the game.
3. Tap **● Rec**, then play the level with the mouse, including the Play click and any card choices.
   - To shoot, press on the bird, pull back and let go.
   - For a bird's ability, click once while it's flying.
   - Wait for each move to finish before the next one.
4. After you win, also click through the result screens back to level 11's start screen. Then tap **■ Stop** or press **F8**. Recording those clicks is what lets **⟳ Loop** start the next run.
5. From level 11's start screen, tap **▶ Play** or **⟳ Loop**. Leave the mouse alone while it plays.

The recording is saved to `%APPDATA%\AngryBot\level11.txt` and is still there the next time you start AngryBot.

## Differences from the Android app

- You don't need a red recording layer. AngryBot watches the mouse while the game receives your input directly, so you see the game's normal aiming line while you record.
- Each drag is replayed along its real path at its real speed. That includes how long you held before letting go.
- Playback stops if the game window closes, is minimised or isn't the front window. It won't click into other apps.

## Limits

- It replays your moves exactly and doesn't look at the screen. A different bird card, a pop-up or offer, or slightly different physics can make a run fail. Running Loop helps.
- **Keep the game window the same size** as when you recorded. Moving the window is fine. Resizing it scales the moves, but the game's layout may not scale the same way.
- While it plays, it uses your real mouse cursor, so you can't use the PC at the same time.
- Rovio's terms don't allow bots, and Google Play Games may not like automated input either. Your account could be flagged, mainly in competitive modes like Arena or Clans. Use it at your own risk.

## Files

```
build.cmd          builds AngryBot.exe (and tests\SelfTest.exe)
src\Common.cs      game-window finding, mouse input and hook, floating bar, icon
src\Recorder.cs    recording, playback, saving and loading
tests\SelfTest.cs  SelfTest.exe: file format checks
                   SelfTest.exe --live: records and replays drags on a test window
                   (takes over the mouse for about 3 seconds)
```
