# Experimental Mac USB to Android Emulator bridge

This is an explicit, debug-only desktop lab. It is not a production transport or a vehicle integration.

Verified on 2026-10-27 with an Apple Silicon Mac, Android 11 ARM64 emulator and a USB iPhone17,2 running iOS 27.2:

- USBMUX/Lockdown and CarKit TLS opened successfully.
- The actual DiPlay iAP2 engine completed identification and MFi challenge/response; the phone returned `0xaa05`.
- The phone announced wired CarPlay availability and accepted the start-session flow.
- AirPlay became active and CarPlay video was decoded on the emulator.
- A tap in the emulator opened the iPhone's CarPlay Settings screen.
- The packaged runner repeated authentication and live video successfully; full-screen video used the emulator's 1920×720 viewport.
- Audio playback, microphone/Siri, calls and prolonged operation are not yet validated. This lab disables microphone input.

## Architecture

`iPhone CarKit -> macOS usbmuxd/libimobiledevice -> loopback TCP 19777 -> adb reverse -> DiPlay iAP2`

`iPhone NCM -> macOS native NCM driver -> BPF Ethernet relay -> loopback TCP 19778 -> adb reverse -> DiPlay app-scoped VPN -> AirPlay/media decoder`

The NCM relay needs an administrator prompt to open BPF. It binds only to localhost and an explicitly discovered USB iPhone interface. It changes no Mac routes or network configuration, writes no packet capture, and accepts outbound IPv6 only for the fixed lab address `fe80::d1:1` (plus its duplicate-address check). Unrelated Android IPv6 control packets are discarded. Each relay handles one connection and stops after three minutes; idle listeners expire after two minutes. The Activity has its own three-minute cleanup timer.

## Build

Use the repository's documented JDK/SDK setup. `libimobiledevice`, `idevice_id`, `clang`, and the macOS system libpcap are needed on the Mac; the script currently uses Homebrew's Apple Silicon prefix `/opt/homebrew`.

The ordinary source build contains no accessory identity. Supply the explicit external runtime assets described in `docs/BUILD.md`. The verified lab used only the two runtime identity assets from the official `DiPlay-0.2.21.apk`, whose SHA-256 is `916c0708beb1022d4ea9678ff6a46dfb6d234f707837a2cbd80d615e8e2bb2e3`. Keep them and any pairing records outside Git.

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" \
DIPLAY_AUTH_ASSETS_DIR=/absolute/external/runtime-assets \
./gradlew :mobile:assembleStandaloneDebug
```

The local lab was compiled with already installed NDK 27.2.12479018 using `-PdiplayNdkVersion=27.2.12479018`. Omitting that explicit option preserves upstream's NDK 28.2.13676358 requirement.

## Run

Start an ARM64 Android emulator, connect exactly one iPhone by USB, unlock it and trust the Mac. The iPhone must already expose an active ordinary NCM interface. `find-ncm.py` resolves it from the iPhone's IORegistry subtree rather than assuming `en26`.

```sh
bash tools/mac-usb/run.sh
```

The script verifies `ro.kernel.qemu=1`, installs only the debug package, configures two ADB reverse mappings, requests the macOS administrator prompt, and launches the lab Activity. Accept Android's local VPN dialog in the emulator on the first run and any CarPlay prompt on the iPhone. For another emulator serial, set `DIPLAY_EMULATOR` explicitly.

The Activity displays the actual live video. Touches are mapped back to the iPhone. Exiting or reaching the time limit tears down the app's VPN and the helper connections. The runner removes its ADB reverse mappings. The iPhone may briefly show Connecting after a diagnostic session ends; it is not evidence of a live media session.

For the control-only test, start `carkit-relay`, reverse TCP 19777, and launch `com.shilapi.xcertplay.MacUsbLabActivity` in the same debug package. It deliberately stops immediately after successful accessory authentication.

Never publish runtime identity files, Mac trust/pairing records, or captures containing phone data. The native helpers log only stages, counters, and limited rejected network-header diagnostics.

## Validation

The repository CI command (shared/common/home unit tests, mobile/home/maphost lint and debug builds) passed locally with the explicit NDK override above. Test reports contain 3,397 tests: zero failures/errors and one skipped test. Native helpers compile with `-Wall -Wextra -Werror`; the public-tree credential scan passes. Loopback transport tests cover timeout/EOF distinction, partial frame retention and oversized-frame rejection.

This proves the stated Mac/emulator/iPhone lab flow only. It does not prove vehicle compatibility or a production CarPlay receiver.

## Navigation debug window

The Mac USB lab Activity now opens a movable, collapsible navigation log over the live CarPlay surface. Drag its header, use `−` / `+` to collapse or expand, and scroll the body to inspect recent events. It needs no Android system-overlay permission.

The existing iAP2 subscription sends `0x5200` for RouteGuidance component 42. The panel observes the existing reader's `onIncoming` callback and shows only `0x5201` route, `0x5202` maneuver and `0x5204` lane packets. It displays per-type counters and time since the last packet. No second reader or vehicle-output adapter is attached.

Known route fields include state, current road, arrival epoch seconds, remaining seconds/meters, distance to maneuver and maneuver indices. Maneuver packets show index, raw Apple maneuver code, road after the maneuver and driving side. Lane packets show lane index, status and angles. Unknown or incorrectly sized fields remain raw TLV hex; decoded values are per-packet fields, not an inferred merged route state.

Start actual route guidance in the iPhone app to test it; displaying a map alone does not prove metadata delivery. An empty panel means no matching packets have arrived, even if CarPlay video is working.

`run.sh` exports `navigation.log` alongside its other session logs. The app stores up to 2 MiB in `files/mac-usb-navigation.log`, with timestamps, decoded fields and complete `PAYLOAD_HEX` for each navigation packet; its on-screen history is bounded. Each session replaces the app's previous log. To collect it while the session is still active:

```sh
"$HOME/Library/Android/sdk/platform-tools/adb" -s emulator-5554 \
  shell run-as com.shihab.diplay.hudtest cat files/mac-usb-navigation.log > /tmp/diplay-navigation.log
```

These local logs can contain street names and route details. They are not written to logcat or committed to this repository.

Live navigation validation on 2026-10-27: with route guidance started in 2GIS, this lab received `0x5201` and `0x5202`, including route state, ETA, remaining distance/time and two maneuver records. `0x5204` was not observed on that short stationary route; this does not establish whether 2GIS supports lane output on other routes. Dragging the panel was tested over live video. Compact (960×540) and full (1920×720) layouts were inspected at 1.3× font scale, then the emulator's original settings were restored.
