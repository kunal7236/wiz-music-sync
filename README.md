# WiZ Music Sync

An Android app that makes a WiZ Wi-Fi bulb react to whatever music is playing on your phone or tablet — Spotify, YouTube Music, anything — **without using the microphone**.

It captures the audio other apps play (Android's AudioPlaybackCapture), analyses it, and sends colour commands straight to the bulb over your local Wi-Fi. Music keeps playing to your Bluetooth speaker or headphones as normal.

There is also a **Windows 11 app** with the same features — see [Windows app](#windows-app) below.

## Two modes

- **Music sync** — the bulb follows the music, as described below.
- **Fixed colour** — pick any colour or white on a colour wheel and the bulb stays on it, whatever is playing. The bulb updates live as you drag. Audio capture is fully off in this mode.

## Power and schedules

**On / Off** buttons switch the bulb directly (On brings back whatever it showed last). Off also stops music sync.

**Schedules** turn the bulb on or off automatically:
- **After a delay** — e.g. off in 30 minutes, as a sleep timer. Runs once.
- **At a time** — e.g. on at 07:00 on weekdays. Once, every day, weekdays or chosen days; repeating schedules can be paused with their switch.

Schedules run on your phone, not on the bulb, so the phone must be on the bulb's Wi-Fi when one is due. They survive a restart; one-off schedules that came due while the phone was off are skipped. If a schedule can't reach the bulb you get a notification. For reliable timing, set the app's battery usage to *Unrestricted* — the app shows a shortcut when that's needed.

## How the lights behave in Music sync

These are the defaults. Every row can be changed in the app under **Light colours**: tap a row to choose a colour on the wheel or a white (warm, neutral, daylight or any warmth), set its brightness, use **Try on bulb** to preview it, and **Reset colours** to go back. Changes apply immediately, even while syncing.

**Sensitivity** controls how easily the music reaches the brighter colours; the middle setting is the original behaviour.

**Keep the colour when music pauses** (on by default): the bulb stays on the colour it was showing until the music resumes. Turn it off to switch to the "Paused or silent" light instead.

| Music | Bulb |
|---|---|
| Quiet / background | Blue, 60% |
| Vocals / mids | Green, 70% |
| Synths / drive | Amber, 85% |
| High intensity | Magenta, 95% |
| Sudden bass kick | Red flash, 100% |
| Paused or silent for 1.2 s (only when "Keep the colour" is off) | Natural white (4200 K), 100% |

Colours step up instantly and fall back one tier at a time, so the light rises with the music and settles smoothly. When you stop syncing, the bulb returns to the "paused" light.

**Reset all settings** at the bottom of the screen puts everything — bulb IP, delay, sensitivity, mode, fixed colour, all colours and all schedules — back to how it was on install.

## Install

Download `app-release.apk` from the [latest release](../../releases/latest), open it on your device and allow installing from that source.

Requires Android 10 or newer, and the device must be on the same Wi-Fi network as the bulb.

## Use

1. Tap **Scan** to find WiZ bulbs on your Wi-Fi and pick yours, or type its IP address. If the bulb later stops answering at that address (for example the router gave it a new one), the app scans by itself when you open it, press On/Off or start syncing: with one bulb on the network it switches to the new address automatically; with several it asks you to pick. Scheduled on/off does the same in the background.
2. Tap **Start sync** and allow:
   - **Microphone → While using the app.** Android files audio capture under this permission; the app never opens the mic.
   - **Notifications → Allow.** Shows a notification with a **Stop** button while syncing.
   - **"Start recording or casting?" → Entire screen.** Needed to capture other apps' audio. Nothing is recorded or saved.
3. Play music.

If the lights change before you hear the beat (common with Bluetooth speakers, which add 150–250 ms of lag), drag the **Speaker delay** slider until they line up. It applies immediately while syncing.

## Limitations

- Apps can opt out of audio capture. Most music apps allow it; Netflix and other DRM video apps do not, and the bulb will stay white with those.
- Android asks for the casting permission each time you start syncing.
- The bulb is controlled over the WiZ local UDP API (port 38899), so the phone and bulb must be on the same network.

## Build from source

Open the project in Android Studio and run it, or:

```bash
./gradlew assembleDebug
```

Release builds are signed only if `~/.android-keystores/wizmusicsync.properties` exists (with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`); otherwise `assembleRelease` produces an unsigned APK.

## How it works

- `LightEngine.kt` — the audio analysis: RMS level, 35–160 Hz bass energy, rolling averages, colour tiers, bass-drop detection and idle detection, computed on 1024-sample chunks at 48 kHz.
- `LightTheme.kt` — the colour or white for each state, with the defaults above.
- `SyncService.kt` — foreground service that captures media audio, runs the engine and sends `setPilot` UDP commands, delayed by the speaker-delay setting.
- `WizBulb.kt` — WiZ UDP payloads, bulb discovery (broadcast `registration`/`getPilot`, then `getSystemConfig` for the model) and all saved settings.
- `ColorWheelView.kt`, `LightPickerView.kt` — the colour wheel and the colour/white picker.
- `Schedule.kt`, `Scheduler.kt`, `ScheduleDialog.kt` — power schedules: next-run calculation (unit-tested), exact alarms re-armed after reboot, the receiver that switches the bulb, and the add-schedule dialog.
- `MainActivity.kt` — the settings screen and the two modes.

No third-party libraries.

## Windows app

`windows/` holds a Windows 10/11 version with the same features: Music sync and Fixed colour modes, the colour wheel, editable colours, speaker delay, sensitivity, keep-colour-on-pause, Scan and auto-scan, On/Off and schedules.

What's different on Windows:
- **No permission prompts.** It captures whatever the PC is playing directly (WASAPI loopback of the default output) and follows you when you switch speakers or headphones.
- **Runs in the system tray.** Closing the window keeps sync and schedules running; the tray menu has Start/Stop sync, Turn bulb on/off and Exit. Optional **Start with Windows** opens it hidden in the tray at sign-in.
- **Finds the bulb on first launch**, so there is no IP to type.
- Schedules run while the app is open or in the tray. If the PC was asleep at a schedule's time, it is skipped rather than run late.
- Settings live in `%APPDATA%\WizMusicSync\settings.json`.

### Install

Download `WizMusicSync-Windows.exe` from the [Windows release](../../releases/tag/windows-v1.5) and run it — a single self-contained file, nothing else to install. It isn't code-signed, so Windows SmartScreen may say "Windows protected your PC": choose **More info → Run anyway**.

### Build

Requires the .NET 10 SDK.

```bash
dotnet test windows/WizMusicSync.Tests
dotnet publish windows/WizMusicSync.App -c Release -r win-x64 --self-contained -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true
```

- `WizMusicSync.Core` — the light engine (a line-for-line port of the Android one, checked against the same Python reference), WiZ protocol, settings and schedule runner. Unit-tested in `WizMusicSync.Tests`.
- `WizMusicSync.App` — WPF with the built-in Windows 11 (Fluent) theme: window, colour wheel, tray icon, audio capture.
