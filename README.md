# WiZ Music Sync

An Android app that makes a WiZ Wi-Fi bulb react to whatever music is playing on your phone or tablet — Spotify, YouTube Music, anything — **without using the microphone**.

It captures the audio other apps play (Android's AudioPlaybackCapture), analyses it, and sends colour commands straight to the bulb over your local Wi-Fi. Music keeps playing to your Bluetooth speaker or headphones as normal.

## Two modes

- **Music sync** — the bulb follows the music, as described below.
- **Fixed colour** — pick any colour or white on a colour wheel and the bulb stays on it, whatever is playing. The bulb updates live as you drag. Audio capture is fully off in this mode.

## How the lights behave in Music sync

These are the defaults. Every row can be changed in the app under **Light colours**: tap a row to choose a colour on the wheel or a white (warm, neutral, daylight or any warmth), set its brightness, use **Try on bulb** to preview it, and **Reset colours** to go back. Changes apply immediately, even while syncing.

**Sensitivity** controls how easily the music reaches the brighter colours; the middle setting is the original behaviour.

| Music | Bulb |
|---|---|
| Quiet / background | Blue, 60% |
| Vocals / mids | Green, 70% |
| Synths / drive | Amber, 85% |
| High intensity | Magenta, 95% |
| Sudden bass kick | Red flash, 100% |
| Paused or silent for 1.2 s | Natural white (4200 K), 100% |

Colours step up instantly and fall back one tier at a time, so the light rises with the music and settles smoothly. When you stop syncing, the bulb returns to the "paused" light.

**Reset all settings** at the bottom of the screen puts everything — bulb IP, delay, sensitivity, mode, fixed colour and all colours — back to how it was on install.

## Install

Download `app-release.apk` from the [latest release](../../releases/latest), open it on your device and allow installing from that source.

Requires Android 10 or newer, and the device must be on the same Wi-Fi network as the bulb.

## Use

1. Tap **Scan** to find WiZ bulbs on your Wi-Fi and pick yours, or type its IP address. The scan only runs when you tap the button.
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
- `MainActivity.kt` — the settings screen and the two modes.

No third-party libraries.
