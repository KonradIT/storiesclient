# Stories Client

<div align="center">

![](./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

</div>

Standalone third party Android client for **Ray-Ban Stories (2021)** ("stella") glasses: connect over BLE, download full-resolution photos/videos over WiFi-Direct,
and manage the glasses without the official Meta app.

Kotlin rewrite of the earlier Java RE-focused prototype; built entirely from reverse-engineering the glasses firmware plus live BLE captures.

<p align="center">
  <img src="./screenshots/image.jpg" width="500"/>
</p>

Project started in May 2026 against Meta RayBan Gen 1 (the 4K glasses), briefly moved to Gen 2, no real progress for months, decided to find my old RayBan Stories from the pile of old tech junk, that was the charm that worked, less complexity and security (no airship).

## Features

- **Pairing-free connect** to already-owned glasses: Needs Meta AI app once just for extracting secrets (see TODO).
- **Gallery**: 3-column squircle grid, newest first, BLE thumbnails (`get_asset_content_v2`), pull to
  refresh, new-capture notifications, synced badge for media already on the phone.
- **Detail view**: full-res preview when saved, metadata, `HDR|BURST · 0 · LEFT` frame picker driven by
  the per-frame `ImageAssetMetadata.ev` (median-EV right frame = main; bracket vs burst detected per scene).
- **Download** over WiFi-Direct (phone = group owner, `stationmode_connect` + `start_webserver`), with live
  progress; MP4 downloads also fetch the IMU/gyro sidecar `.bin` for Gyroflow. Saved to `DCIM/StoriesClient`
  as `stories_<cid8>[suffix].<jpg|mp4|bin>`.
- **Delete** a capture off the glasses (32-hex id guard; byte-exact request), only offered after a save.
- **Undistort**: fisheye -> rectilinear (Kannala-Brandt) for 2592x1944 photos, saved as `_rect.jpg`.
- **Status pill**: glasses battery, case battery, storage level, firmware, plus two MCU settings
  (video length 30/60 s, system sounds level) read and written live.
- **Debug log drawer**: long-press the pill.

## Identity

Nothing sensitive ships in the APK. Tap **Load keys…** and pick a JSON with the owner identity
(`app_rsa_priv_pkcs8_b64`, `bootstrap_ticket_hex`, optional `serial` / `device_label`). The rotating
resume ticket is persisted per session. See `EXTRACT_SECRETS_CONFIG.md` in the research repo for how the
identity is extracted from an owning phone.

## Roadmap / TODO

1) Extract RSA private key and bootstrap ticket. WIP.

Two possible ways:

- Frida hook (how I did it)
- Meta AI smali patch + adb logging

2) RayBan Meta Gen 1 / Gen 2 (Supernova) support:

In progres... been at it for ~7 months now. RE'ing the RayBan stories was a byproduct of that which turned out to be easier.



## Building

Toolchain: Gradle 8.7 wrapper, AGP 8.5.2, Kotlin 1.9.24, JDK 17+ (JDK 21 used), compileSdk 34, minSdk 29.

```
./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # wire-format golden tests
```

## Layout

| package | role |
|---|---|
| `datax/` | DataX framing `[type:1][size:2 LE][payload]`, MTU chunking + reassembly |
| `proto/` | hand-rolled FlatBuffers reader/writer + `stella.security` / `stella.srvs` message layouts |
| `auth/` | identity store, Phase-B handshake, AES-256-GCM `EncryptedPayload` codec |
| `ble/` | GATT transport for the DataX characteristic; CompanionDeviceManager discovery |
| `control/` | encrypted RPC channel: 45-service registration, capture listing, thumbnails, device state, MCU settings, webserver bring-up, delete |
| `media/` | webserver HTTPS client, MediaStore saver, synced index, photo undistortion |
| `wifi/` | WiFi-Direct group host (used) and SoftAP joiner (alternative transport) |
| `ui/` | status pill, gallery cells/adapter, detail overlay, progress bar |


## LLM usage

Claude Code was used, with models spanning Opus 4.6, 4.7, 4.8 and 5. Fable refuses to work on this.

Next up: trying out Z Ai / Moonshot Ai models such as Kimi and GLM. [Less guardrails for RE on those models](https://ericpardee.github.io/fire-hd-ownership/)

## Tests

`app/src/test` holds golden-vector tests: every registration frame, request body, security message and
the session cipher are compared byte-for-byte against vectors generated from the validated Java
implementation (`golden_wire.txt`). Run them before touching anything in `control/` or `proto/`.
