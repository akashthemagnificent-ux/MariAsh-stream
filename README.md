# Agon — Intercontinental Watch Party App

Stream large local movie files (5+ GB) with zero quality/FPS loss to your partner on the other side of the world.

## Features
- **Zero re-encoding** — FFmpegKit remuxes your local file to HLS segments without touching the video/audio data. Original bitrate, FPS, and all audio tracks preserved.
- **2-minute offline buffer** — ExoPlayer buffers 2 minutes ahead so brief internet drops don't interrupt playback.
- **Intercontinental sync** — NTP-style ping/pong with smart drift correction: >3s drift → hard seek; 300ms–3s → speed nudge (1.05×/0.95×).
- **Test Lab** — honest split-screen simulation of India→USA (260ms), India→Europe (180ms), Worst Case Mobile (400ms, 5% loss) and more, all on a single phone.
- **Free relay server** — deploy to Render.com for free, no credit card needed. URL is configurable in the app Settings.
- **PiP mode** — swipe home, video keeps playing.
- **Reactions** — emoji reactions synced in real time.

## Architecture

```
Host phone               Relay server (Render.com)      Partner phone
──────────               ──────────────────────────     ─────────────
Local movie file
  │
  ▼
FFmpegKit (remux to HLS)
  │  4-second .ts segments
  ▼
OkHttp PUT /upload/{room}/{seg}  →  stored in memory  →  GET /hls/{room}/{seg}
                                                                │
WebSocket /sync/{room}?role=host ↔  relay broadcasts  ↔  ExoPlayer HLS
```

## Building

### Requirements
- Android SDK (min API 24, target API 35)
- Java 17+

### Debug build
```bash
./gradlew assembleDebug
```
APK → `app/build/outputs/apk/debug/app-debug.apk`

## Relay Server

The `relay-server/` directory contains a Go server that:
- Relays WebSocket sync messages between host and client
- Stores HLS segments in memory (host uploads, client downloads)

### Deploy free on Render
1. Fork this repo
2. Go to render.com → New → Web Service → connect your fork
3. Use the `relay-server/` directory, Docker runtime
4. Free plan, region: Singapore (closest to India)
5. Copy the `.onrender.com` URL into Agon → Settings

## Test Lab

Open the Test Lab inside the app to simulate continent-level latency and packet loss on a single phone:
- HOST panel (green) plays the local file directly
- CLIENT panel (blue) plays via the SimulatedRelay NanoHTTPD server with configurable delay/jitter/packet-loss/bandwidth throttle
- Live stats: drift, one-way latency, upload KB/s, download KB/s, dropped packets

## Automated APK builds (GitHub Actions)

On every push, GitHub Actions now builds a debug APK automatically and publishes it in two places:
- **Artifacts** for that workflow run (`MariAsh-Stream-debug-apk`)
- A rolling **pre-release** tagged `latest-apk` containing the newest APK (renamed with version, e.g. `MariAsh-Stream-v1.0+1-debug.apk`)

Workflow file: `.github/workflows/build-apk.yml`.

### Versioning behavior on every push
- GitHub Actions creates a **fresh APK build** on every push that matches that commit's source tree.
- If code/resources are unchanged between two commits, the APK content can be effectively identical even though CI rebuilt it.
- The workflow updates the same rolling release tag (`latest-apk`) and replaces the attached file there, so the public download link stays stable.
- Each workflow run still keeps its own artifact copy (`MariAsh-Stream-debug-apk`) in Actions for run-by-run traceability.

### Local relay build note
If `go build` fails with a `proxy.golang.org ... Forbidden` error, it usually means the current environment blocks outbound module downloads. This is an environment/network restriction, not a source-code compile error in the relay.

### If no APK appears after merge
Check these in order:
1. **Actions enabled** for the repo/org and workflow runs are not blocked by policy.
2. **Workflow run succeeded** (`Actions` tab → `Build Android APK`).
3. **Repository Actions permissions** allow writing releases (`Settings → Actions → General → Workflow permissions: Read and write`).
4. If artifact exists but no release APK, open the failed step in `Publish APK to "latest-apk" release` for error details.
5. If no run started, confirm the workflow file exists on the default branch at `.github/workflows/build-apk.yml`.
