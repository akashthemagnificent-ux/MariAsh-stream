# MariAsh Stream — Bugs & Fixes

Full history of every defect found and fixed across the Android app and the
Go relay server. Bugs 1–13 were fixed in prior sessions; Bugs 14–23 in this
session.

---

## Legend

| Severity | Meaning |
|----------|---------|
| 🔴 CRITICAL | Prevents the feature from working at all |
| 🟠 SIGNIFICANT | Causes noticeably wrong behaviour every session |
| 🟡 MEDIUM | Intermittently wrong; degrades quality |
| 🔵 MINOR | Cosmetic or edge-case |

---

## Session 1 — Compile Errors, Relay Bootstrap (Bugs 1–13)

### Bug 1 🔴 — Missing Kotlin imports across multiple files
**Files:** Several `.kt` files  
**Symptom:** Build fails with "Unresolved reference" errors before a single line of
app logic runs.  
**Root cause:** `import` statements referencing non-existent or renamed classes
(e.g. old Media3 alpha API names).  
**Fix:** Added correct imports; replaced removed APIs with their stable equivalents.

---

### Bug 2 🔴 — `HlsSegmenter` wrote segments to the app's external cache without
a `FileProvider` declaration
**Files:** `HlsSegmenter.kt`, `AndroidManifest.xml`  
**Symptom:** `FileUriExposedException` crash on Android 7+ when passing a `File`
URI to another component.  
**Fix:** Declared a `FileProvider` authority in the manifest; updated all file
sharing to use `FileProvider.getUriForFile()`.

---

### Bug 3 🔴 — `RelayClient` used `OkHttp` without the dependency declared
**Files:** `app/build.gradle.kts`  
**Symptom:** `ClassNotFoundException` for `okhttp3.*` at runtime.  
**Fix:** Added `implementation("com.squareup.okhttp3:okhttp:4.12.0")` to the
module's dependency block.

---

### Bug 4 🔴 — `SyncViewModel` referenced a deleted `AppPreferences` field
(`relayTokenFlow`)
**Files:** `SyncViewModel.kt`, `AppPreferences.kt`  
**Symptom:** Compile error: "Unresolved reference: relayTokenFlow".  
**Fix:** Renamed the DataStore key and the backing `Flow` to `relayToken` to
match across the two files.

---

### Bug 5 🔴 — `RoomScreen` collected a `SharedFlow` with `collectAsState()` instead of
`collectAsStateWithLifecycle()`
**Files:** `RoomScreen.kt`  
**Symptom:** Compose recomposition loop; app freezes on the Room screen.  
**Fix:** Replaced `collectAsState()` with the lifecycle-aware variant everywhere
a `SharedFlow` was observed in Compose.

---

### Bug 6 🔴 — `MediaMuxer` segment output format hardcoded to `MPEG_4` on a device
that outputs raw H.264 without a container
**Files:** `HlsSegmenter.kt`  
**Symptom:** Segments produced but ExoPlayer couldn't demux them; silent black
screen or format error.  
**Fix:** Switched output format to `MPEG_4` with explicit track configuration
so the container is always well-formed.

---

### Bug 7 🟠 — Relay server `render.yaml` used `type: web` without the `runtime: docker`
key, so Render attempted a Node.js build on a Go project
**Files:** `render.yaml`  
**Symptom:** Render CI error "No package.json found"; deployment never completed.  
**Fix:** Added `runtime: docker` and pointed `dockerfilePath` at the root
`Dockerfile`.

---

### Bug 8 🟠 — Relay server returned `Content-Type: text/plain` for `.m3u8` files
**Files:** `relay-server/main.go`  
**Symptom:** Some Android WebView-based players refused to parse the playlist;
ExoPlayer logged a MIME-type warning.  
**Fix:** Set `Content-Type: application/vnd.apple.mpegurl` for `.m3u8` and
`video/mp2t` for `.ts` in the `hlsHandler`.

---

### Bug 9 🟠 — Relay whitelist regex rejected `.mp4` segment filenames (only `.ts`
was allowed)
**Files:** `relay-server/main.go`  
**Symptom:** Host uploaded segments; relay returned 400 for every upload; client
received an empty playlist.  
**Fix:** Updated `validSegmentName` regex to
`^[a-zA-Z0-9._-]+$` with an extension allowlist of `.mp4`, `.m3u8`, `.ts`,
`.vtt`.

---

### Bug 10 🟡 — `go.sum` contained a stale hash for `golang.org/x/net`
**Files:** `relay-server/go.sum`  
**Symptom:** `go build` inside the Docker container failed with
"verifying golang.org/x/net: checksum mismatch".  
**Fix:** Ran `go mod tidy` inside the relay-server directory to regenerate the
correct checksums.

---

### Bug 11 🔵 — Root-level `Dockerfile` was missing; Render could not find it
**Files:** `Dockerfile` (created at repo root)  
**Symptom:** Render build step errored with "Dockerfile not found".  
**Fix:** Created a minimal multi-stage `Dockerfile` at the repo root that builds
the Go relay binary and runs it on a `gcr.io/distroless/base` image.

---

### Bug 12 🟠 — `syncHandler` in `main.go` was missing its closing `}` brace
**Files:** `relay-server/main.go`  
**Symptom:** `go build` error: "unexpected uploadHandler, expecting }".  
**Root cause:** A hand-edit during an earlier merge left the `syncHandler`
function body unclosed; the next function definition was parsed as a statement
inside `syncHandler`.  
**Fix:** Inserted the missing `}` at the correct indentation level (line 301).

---

### Bug 13 🔵 — `WakingServerScreen` not shown when Render free-tier instance sleeps
**Files:** `SyncViewModel.kt`, `RoomScreen.kt`  
**Symptom:** After 2+ WebSocket disconnects (Render cold-start), the user saw a
blank spinner with no explanation; many users force-quit thinking the app was
broken.  
**Fix:** Added a `disconnectCount` counter in `SyncViewModel`; after ≥ 2 failures
the new `WakingServerScreen` composable is shown with an animated cloud icon
and a live elapsed-seconds counter.

---

## Session 2 — Streaming Quality, Sync Loss, Branding (Bugs 14–23)

### Bug 14 🔴 — `#EXT-X-PLAYLIST-TYPE:EVENT` missing from HLS playlist — ROOT CAUSE of black screen
**File:** `app/src/main/java/com/agon/app/segmenter/HlsSegmenter.kt`  
**Symptom:** Client VideoPlayer shows a permanent black screen / buffering spinner.  
**Root cause (detailed):**  
Without `#EXT-X-PLAYLIST-TYPE:EVENT`, ExoPlayer classifies the playlist as a
**live stream** (because it has no `#EXT-X-ENDLIST` while new segments are being
added). In live mode ExoPlayer automatically seeks to the **live edge** — the
*last* segment in the playlist — rather than starting from segment 0.

For a watch-party:
1. Client loads a 2-segment playlist → live edge = segment 2 (seconds 4–8).
2. ExoPlayer tries to buffer 8 seconds **ahead** of the live edge.
3. No segment 3 exists yet (still encoding) → ExoPlayer enters `STATE_BUFFERING`.
4. Even if it does start playing from segment 2, the client is mid-video with no
   sync to the host.

`#EXT-X-PLAYLIST-TYPE:EVENT` tells ExoPlayer:
* Always start from segment 0 (beginning of content).
* New segments may be appended but nothing is ever removed.
* Switch to VOD mode only when `#EXT-X-ENDLIST` appears.

**Fix:** Added `#EXT-X-PLAYLIST-TYPE:EVENT` as the third header line in
`buildPlaylist()`.

---

### Bug 15 🔴 — `collectLatest` in sync handler silently drops messages
**File:** `app/src/main/java/com/agon/app/ui/components/VideoPlayer.kt`  
**Symptom:** Play/pause/seek commands from the host arrive but the client ignores
them; sync appears to "stop working" minutes into a session.  
**Root cause:**  
`collectLatest` cancels the *current* collection coroutine the moment a new
emission arrives. The handler ended with `delay(100)` — so any two sync messages
arriving within 100 ms of each other caused the first to be cancelled and
discarded. Since `isHandlingSync` was set to `true` at the top but `false` only
at the bottom after `delay(100)`, a cancelled coroutine left the flag permanently
`true` (see Bug 20).  
**Fix:** Changed `collectLatest` → `collect`. All messages are now processed
sequentially with no cancellation.

---

### Bug 16 🟠 — ExoPlayer `minBufferMs = 30 000` forces impossible buffering
**File:** `app/src/main/java/com/agon/app/ui/components/VideoPlayer.kt`  
**Symptom:** After the black screen is fixed (Bug 14), ExoPlayer still shows
"Buffering…" for 20–30 seconds before playback starts, even when segments are
available.  
**Root cause:**  
`DefaultLoadControl` was configured with `minBufferMs = 30_000`. This means
ExoPlayer will not consider the buffer "healthy" until it has 30 seconds of video
prefetched. With 4-second segments and a live-like playlist, ExoPlayer kept
re-entering `STATE_BUFFERING` waiting for more segment data that couldn't arrive
fast enough.  
**Fix:** Reduced to `minBufferMs = 8_000` (= 2 segments), `maxBufferMs = 60_000`,
`bufferForPlaybackMs = 2_000`, `bufferForPlaybackAfterRebuffer = 5_000`.

---

### Bug 17 🟠 — Client VideoPlayer starts with `playWhenReady = true` before receiving
host position
**File:** `app/src/main/java/com/agon/app/ui/components/VideoPlayer.kt`  
**Symptom:** On first load, the client's video plays from position 0 for up to
2 seconds before the sync engine seeks it forward to match the host; users see a
flash of the beginning of the video.  
**Root cause:** `exoPlayer.playWhenReady = true` was set unconditionally in
`LaunchedEffect(uri)` for both host and client.  
**Fix:** Changed to `exoPlayer.playWhenReady = isHost`. The client starts
`PAUSED`; the first `pong` from the host (which arrives within 2 seconds via the
ping/pong heartbeat) sets the correct position *then* calls `exoPlayer.play()`.

---

### Bug 18 🟠 — `proxyUrl` set on WebSocket `onConnected` before `stream_ready` —
causes ExoPlayer 404 loop
**File:** `app/src/main/java/com/agon/app/viewmodel/SyncViewModel.kt`  
**Symptom:** If the client joins a room before the host has selected a video, the
client VideoPlayer starts immediately, ExoPlayer requests a playlist that doesn't
exist yet, logs "404 Not Found", and enters an error state. By the time the host
starts streaming, ExoPlayer has given up or is in a broken state.  
**Root cause:** `onConnected` set `_proxyUrl.value = buildPlaylistUrl(...)` for
the client unconditionally, even when the host had not yet sent `stream_ready`.  
**Fix:** Removed the `proxyUrl` assignment from `onConnected`. The client now
starts receiving video *only* when `handleSyncMessage` processes a `stream_ready`
message — which the host sends only after at least 2 segments are uploaded.

---

### Bug 19 🟡 — Default relay URL hardcoded as `agon-relay.onrender.com`
**File:** `app/src/main/java/com/agon/app/viewmodel/SyncViewModel.kt`  
**Symptom:** Users who leave the relay URL blank silently connect to the old
"Agon" relay server instead of their own Render service; sessions appear to work
but video never transfers.  
**Fix:** Removed the hardcoded fallback. If `relayUrl` is blank the WebSocket
address is also blank and OkHttp immediately throws a connection error, giving
the user a clear "connection failed" status instead of a silent wrong-server
connection.

---

### Bug 20 🟡 — `isHandlingSync` flag stuck `true` after coroutine cancellation
**File:** `app/src/main/java/com/agon/app/ui/components/VideoPlayer.kt`  
**Symptom:** The host plays or pauses; the sync broadcast fires once; all
subsequent play/pause events are silently swallowed. Only a full app restart
clears it.  
**Root cause:** The sync handler checked `if (isHandlingSync) return` before
broadcasting host state changes. `isHandlingSync` was set `true` at the start of
each `collectLatest` block and `false` after `delay(100)`. When `collectLatest`
cancelled the coroutine mid-delay, the `false` assignment never ran, leaving the
flag permanently `true`.  
**Fix:** Wrapped the entire handler body in `try { … } finally { isHandlingSync = false }`.
The `finally` block runs even on cancellation, so the flag always resets. Combined
with the `collect` change (Bug 15), cancellation no longer happens anyway.

---

### Bug 21 🔵 — UI still shows "Agon Test Lab" branding
**File:** `app/src/main/java/com/agon/app/ui/screens/LocalTestScreen.kt`  
**Symptom:** The top app bar in the local-test screen reads "Agon Test Lab".  
**Fix:** Renamed to **"MariAsh Stream Test Lab"**.

---

### Bug 22 🔵 — Relay server startup log still says "Agon relay server"
**File:** `relay-server/main.go` (comment + log line)  
**Symptom:** Render logs show "Agon relay server starting…".  
**Fix:** Renamed both the file-header comment and the `log.Printf` to
**"MariAsh Stream relay server"**.

---

### Bug 23 🔵 — TestViewModel RTT measurement never fires; stats bar shows 0 ms
**File:** `app/src/main/java/com/agon/app/viewmodel/TestViewModel.kt`  
**Symptom:** In the Local Test Lab, the "RTT" / "latency" stats always show 0 ms
regardless of the simulated network profile.  
**Root cause:**  
The normal ping/pong flow is:
1. **Client** VideoPlayer sends `ping` every 2 s → `sendSyncAsClient` → relay →
   `onSyncToHost` → `routeToHost`.
2. `routeToHost` sees `type = "ping"` → falls through to `else` →
   `_hostSyncCmd.emit(ping)` → **Host** VideoPlayer handles ping → generates pong
   → `sendSyncAsHost(pong)` → relay → `onSyncToClient` → `routeToClient`.
3. `routeToClient` sees `type = "pong"` → previously fell through to `else` →
   `_clientSyncCmd.emit(pong)` → Client VideoPlayer updates `localLatencyMs` (a
   local variable; never surfaced to the stats bar).

`_measuredOneWayMs` was only updated in `routeToHost("pong")`, which would only
fire if the *host* sent a ping — something that never happens in normal operation.  
**Fix:** Added an explicit `"pong"` case to `routeToClient` that measures RTT
from `msg.timestamp`, updates `_measuredOneWayMs` and `_clientLatency`, *then*
still emits the pong to the client VideoPlayer (so position correction still works).
Removed the dead `"pong"` handler from `routeToHost`.

---

## Architecture Notes for Future Debugging

### HLS Flow (relay mode)
```
Host picks video
  → HlsSegmenter.segment() runs on background thread
  → produces seg_NNNNN.mp4 every ~4 seconds
  → SyncViewModel.onSegmentReady → RelayClient.uploadSegment (PUT /upload/{room}/{file})
  → SyncViewModel.onPlaylistReady → uploads playlist.m3u8 with #EXT-X-PLAYLIST-TYPE:EVENT
  → after 2 segments: host sends WebSocket "stream_ready" message
  → client receives stream_ready → sets proxyUrl → ExoPlayer loads /hls/{room}/playlist.m3u8
  → ExoPlayer fetches segments from relay (GET /hls/{room}/seg_NNNNN.mp4)
  → client VideoPlayer starts PAUSED; first pong from host seeks to correct position + plays
```

### Sync Protocol
| Message | Sender | Receiver | Purpose |
|---------|--------|----------|---------|
| `ping` | Client | Host | Initiate RTT measurement every 2 s |
| `pong` | Host | Client | Reply with position + isPlaying; client corrects drift |
| `state play` | Host | Client | User pressed play; seek to position then start |
| `state pause` | Host | Client | User pressed pause; pause then seek |
| `state seek` | Host | Client | User scrubbed; seek to position |
| `sync position` | Host | Client | Safety heartbeat every 2 s; correct if drift > 2 s |
| `stream_ready` | Host | Client | At least 2 segments uploaded; safe to start ExoPlayer |
| `stream_reset` | Host | Client | Host switched video; reset client ExoPlayer to new epoch |
| `buffering start/stop` | Either | Other | Tell partner to pause/resume while buffering |

### Relay Token (optional security)
- Set `RELAY_TOKEN=yourpassword` as an **Environment Variable** on the Render service.
- Open the app → Settings → **Relay Token** → enter the same password.
- If both sides are blank → no auth (open relay).
- Token is checked on every WebSocket upgrade, every segment upload, and every
  HLS playlist/segment download.
- Segments in the playlist are served with `?token=xxx` appended to their
  filenames so ExoPlayer's HTTP requests carry the token automatically.

### Render Free-Tier Cold Start
- The relay server sleeps after 15 minutes of inactivity.
- First WebSocket connection after sleep takes 30–60 seconds.
- `SyncViewModel` shows `WakingServerScreen` after 2+ reconnect failures.
- The screen shows an animated cloud + elapsed seconds counter.
- Once the server wakes, the WebSocket reconnects automatically and the screen
  disappears.

### Relay Server Segment Storage
- Segments are stored **in memory** (Go `sync.Map`).
- The server is **stateless across restarts** — if Render restarts the container,
  all segments are lost and the host must restart the stream.
- Segment `PUT` responses wait up to 30 s if the room doesn't exist yet
  (handles race between room creation and first upload).
- Segment `GET` responses also wait up to 30 s if the segment hasn't arrived yet
  (handles network latency between host upload and client fetch).
