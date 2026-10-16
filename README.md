<img src="logo.svg" width="128" alt="AngryBot logo">

# AngryBot

An Android app that records you playing Angry Birds 2 level 11 and then replays it on its own. It runs on the phone by itself, with no PC, cable or root needed.

## How it works
AngryBot is an accessibility service. It puts a small floating control bar over the game:

| Button | What it does |
|---|---|
| ● Rec | A faint red layer covers the screen. Each drag or tap you make is saved, then sent to the game when you lift your finger. |
| ▶ Play | Replays the saved moves with the same timing. |
| ⟳ Loop | Replays again and again until you tap ■ Stop. |
| ■ Stop | Stops recording or playback. |
| ↶ Undo | Deletes the last saved move. |

Drag the bar by its **⠿ AngryBot** label.

## Download
**[⬇ Download AngryBot.apk](https://github.com/AlienHomie007/AngryBot/releases/latest/download/AngryBot.apk)**

Open that link on your Android phone, then open the downloaded file. Allow "Install unknown apps" when the phone asks. If you're updating, uninstall the old AngryBot first.

## Use it
1. Open AngryBot → **Open Accessibility settings** → turn on **AngryBot**.
2. Open Angry Birds 2 and go to level 11's start screen, the one with the **Play** button.
3. Tap **● Rec**. Play the level, including the Play tap and any card choices. While recording:
   - Pull the slingshot and let go. The red line shows your drag, and the shot fires when you lift your finger. You don't see the game's aiming line while recording, so aim by eye.
   - For a bird's ability, tap once while it's flying.
   - Wait for each move to happen before you touch the screen again. Touching too early cancels it.
4. After you win, also tap through the result screens back to level 11's start screen, then tap **■ Stop**. Recording those taps is what lets **⟳ Loop** start the next run.
5. If a shot missed, tap **● Rec** again to start a new recording (it replaces the old one).
6. From level 11's start screen, tap **▶ Play** or **⟳ Loop**.

## AI mode (Android 11+)
Instead of replaying your moves, the AI plays by itself. About once a second it takes a screenshot and works out what's going on:

| It sees | It does |
|---|---|
| Things still moving | Waits for the screen to settle |
| A green button (Play, Continue, Retry) | Presses it. **It skips any button showing a gem price.** |
| Pigs, with the scene still | Aims at a pig using slingshot physics and fires. It taps mid-flight to use the bird's power. |
| Only a red close (X) button | Closes the pop-up |
| Nothing it recognizes for 15 s | Taps the middle of the screen to nudge the game on |

After every shot it checks whether a pig disappeared and where the bird landed, then corrects its aim, so it gets more accurate as it plays. If a pig survives several shots, it tries a high arc and then a different pig. It only acts while Angry Birds 2 is on screen.

**Set up once:** open a level, tap **🎯 Calib**, then pull the bird *all the way back* and let go. This tells the AI where the slingshot is and how far it pulls.

**Play:** tap **🧠 AI**. Tap it again, or tap **■ Stop**, to stop.

**If it misreads the screen:** turn on **👁 Debug** to see what it detects (cyan = pigs, yellow = buttons, orange = close X). Use **📸 Snap** to save the screen to *Pictures/AngryBot*, then share the images so the colour rules in `Vision.java` can be tuned.

## Limits
- It replays your moves exactly. It doesn't look at the screen. If Angry Birds 2 deals a different bird card, shows a pop-up or offer, or the physics play out slightly differently, a run can fail. Running Loop helps.
- Keep the phone in the same orientation and display settings you recorded with.
- Rovio's terms don't allow bots. Using one could get your account flagged, mainly in competitive modes like Arena or Clans. Use it at your own risk.
