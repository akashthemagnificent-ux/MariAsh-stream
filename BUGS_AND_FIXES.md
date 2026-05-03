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

---

## Session 3 — Local Test Lab Black Screen (Bugs 24–25)

### Bug 24 🔴 — Client black screen: buffering-before-play deadlock
**File:** `app/src/main/java/com/agon/app/ui/components/VideoPlayer.kt`  
**Symptom:** In the local test lab, the CLIENT panel shows a permanent black
screen. The HOST shows "Partner is buffering… paused" and never resumes.
The Ping and Drift stats appear (sync is working) but video never starts.  
**Root cause:** The client VideoPlayer starts with `playWhenReady = false`
(correct — it waits for the host's play command). As soon as ExoPlayer is
prepared it enters `STATE_BUFFERING`, which triggered `onPlaybackStateChanged`
to immediately send a `"buffering start"` sync message to the host.

The host receives this, pauses itself, and sets `wasPlayingBeforeBuffer = true`.
From that point the host pong always reports `isPlaying = exoPlayer.isPlaying`
which is `false` (paused). The client's pong handler only calls `exoPlayer.play()`
when `msg.isPlaying = true`, so the client never starts playing. ExoPlayer stays
in `STATE_BUFFERING` because `playWhenReady = false` means it never advances.
ExoPlayer never reaches `STATE_READY`, so `"buffering stop"` is never sent.
The host never resumes. Perfect deadlock.

**Fix:** Added `hasEverStartedPlaying` flag (initialized to `true` for host,
`false` for client). The `onPlaybackStateChanged` callback now returns early
if `!hasEverStartedPlaying`. This prevents the initial startup buffering from
being reported to the host at all. The flag is set to `true` in the pong handler
when `msg.isPlaying && !exoPlayer.isPlaying` (first play command), and also
when a `"state play"` message is received. From that point on, mid-playback
rebuffer stalls are reported normally.

---

### Bug 25 🟠 — Host pong reports wrong isPlaying when paused for partner buffering
**File:** `app/src/main/java/com/agon/app/ui/components/VideoPlayer.kt`  
**Symptom:** If the client somehow sent `"buffering start"` before it had started
playing (see Bug 24), the host paused and then reported `isPlaying = false` in
every subsequent pong. Even after Bug 24 is fixed, this is a safety-net issue:
if the host is paused *solely* because `partnerIsBuffering = true`, it should
still tell the client "I intend to be playing" so the client knows to start
playing when it's ready.  
**Root cause:** Pong was built with `isPlaying = exoPlayer.isPlaying`. When the
host is paused for partner buffering, `exoPlayer.isPlaying = false`, so the
client receives `isPlaying = false` and stays paused forever.  
**Fix:** Changed pong to report the *intended* play state:
```
val intendedPlaying = exoPlayer.isPlaying || (partnerIsBuffering && wasPlayingBeforeBuffer)
```
If the host is paused only because the partner is buffering (and was playing
before the buffer pause), it still reports `isPlaying = true` in the pong.
The client can then start playing as soon as its buffer is ready, and will send
`"buffering stop"` when it reaches `STATE_READY`, which triggers the host to
resume in the normal way.

---

## Session 3 — Race Conditions, Disconnect Handling, Dropped Messages (Bugs 26–33)

### Bug 26 🔴 — relay/main.go: reserve() bypass race — two goroutines can claim the same role
**File:** `relay-server/main.go`  
**Symptom:** Two connections can both become host (or both become client) for the same room, causing one to silently shadow the other and relay messages to the wrong target.  
**Root cause:** The original code fell through when `reserve()` returned false *due to a pending upgrade* (i.e., `pendingHost/pendingClient = true` but `room.host == nil`). It only rejected when the live `conn` pointer was non-nil. A second goroutine racing past the check found `conn == nil`, skipped the 409 return, upgraded, and called `commit()`, overwriting the first goroutine's slot.  
**Fix:** Simplified to always return 409 if `reserve()` fails, for any reason (occupied or pending):
```go
if !room.reserve(role) {
    http.Error(w, "role already taken", http.StatusConflict)
    return
}
```

---

### Bug 27 🟡 — relay/main.go: double-assignment of room.host/client after commit()
**File:** `relay-server/main.go`  
**Symptom:** No visible bug, but confusing code path and unnecessary mutex contention.  
**Root cause:** After calling `room.commit(role, conn)` (which already sets `room.host/client` and clears the pending flag under a write lock), there was an extra `room.mu.Lock()` block that set `room.host` or `room.client` again.  
**Fix:** Removed the redundant second lock+assign block. `commit()` is now the single place that commits a connection into the room.

---

### Bug 28 🟠 — relay/main.go + Android: host disconnect never notifies client → infinite buffering
**Files:** `relay-server/main.go`, `app/…/viewmodel/SyncViewModel.kt`, `app/…/ui/screens/RoomScreen.kt`  
**Symptom:** When the host closes the app or loses connection, the client's video freezes with a buffering spinner and shows "Reconnecting…" indefinitely — the client has no way to know the stream is over.  
**Root cause:** When the host's read loop broke, the relay only set `room.host = nil` and returned. The client kept sending pings and waiting for responses.  
**Fix (relay):** After cleaning up the leaving peer's reference, the relay now looks up the other side's connection and sends a `{"type":"peer_left","action":"host"}` (or `"client"`) message, then the OS closes the stale connection naturally.  
**Fix (Android — SyncViewModel):** Added a handler for `"peer_left"` that sets `_hostLeft` to a human-readable message string.  
**Fix (Android — RoomScreen):** Client view now checks `hostLeft != null` first and renders a "Stream ended" screen instead of the buffering spinner.

---

### Bug 29 🔴 — RoomScreen.kt: LaunchedEffect fires with empty relayUrl before DataStore loads
**File:** `app/…/ui/screens/RoomScreen.kt`  
**Symptom:** On slow devices or cold-start, `initRoom()` is called twice: first with `relayUrl=""` (which creates a RelayClient pointing at nothing), then with the real URL once DataStore emits it. The failed first connection increments `disconnectCount`, potentially triggering the "Waking server…" screen before any real connection attempt has been made.  
**Root cause:** `collectAsState(initial = "")` causes `relayUrl` to be `""` on the first composition. `LaunchedEffect(roomId, relayUrl, relayToken)` fires immediately with that empty value, calling `initRoom("", …, "", "")`.  
**Fix:** Changed to `collectAsState(initial = null)` and added a guard:
```kotlin
LaunchedEffect(roomId, relayUrl, relayToken) {
    if (relayUrl == null || relayToken == null) return@LaunchedEffect
    viewModel.initRoom(roomId, isHost, relayUrl!!, relayToken!!)
}
```
The effect now waits until DataStore has emitted the persisted values before initialising the relay connection.

---

### Bug 30 🟠 — SyncViewModel.kt: web URL silently dropped if relay not yet connected
**Files:** `app/…/viewmodel/SyncViewModel.kt`, `app/…/ui/screens/RoomScreen.kt`  
**Symptom:** Host navigates to a room via "Host a Web Video". The relay WebSocket handshake hasn't completed yet when `setWebUrl()` is called. `sendSync()` checks `if (connected) …` and drops both the `stream_reset` and `web_url` messages. Client shows "Waiting for host to select a video" indefinitely.  
**Root cause:** `setWebUrl()` sent the messages immediately without checking connection state.  
**Fix:** `setWebUrl()` now stores the URL and epoch in `pendingWebUrl`/`pendingWebEpoch`. The `onConnected()` callback, after verifying it's the host and `pendingWebUrl != null`, waits 300 ms for the connection to stabilise and re-sends both `stream_reset` and `web_url` messages.

---

### Bug 31 🟡 — RelayClient.kt: uploads share the WebSocket OkHttpClient (readTimeout=0)
**File:** `app/…/relay/RelayClient.kt`  
**Symptom:** A stalled segment upload (server slow, no response) hangs the upload coroutine forever because there is no read timeout. This blocks the entire upload pipeline — no further segments reach the relay and the client's stream stalls permanently.  
**Root cause:** `uploadSegment()` used the same `OkHttpClient` as the WebSocket, which requires `readTimeout(0)` (infinite) to prevent the OS from closing an idle connection. That zero timeout also applied to HTTP upload responses.  
**Fix:** Added a separate `uploadClient` with `readTimeout(60s)` / `writeTimeout(120s)`. The WebSocket client (`wsClient`) keeps `readTimeout(0)`. The upload client is used exclusively in `uploadSegment()`.

---

### Bug 32 🔵 — TestViewModel.kt: segCount closure race → clientHlsUri never set
**File:** `app/…/viewmodel/TestViewModel.kt`  
**Symptom:** In the test lab, on a fast device or with a small video file, multiple HLS segments may be produced in quick succession. All of them find `segCount > 2` by the time the main thread processes the `viewModelScope.launch` lambda, so `if (segCount == 2)` is never true — `clientHlsUri` is never set and the client panel shows a black screen forever.  
**Root cause:** `segCount++` runs on the `HlsSegmenter` background thread. `viewModelScope.launch` posts asynchronously to the main thread. If three segments are produced before the main thread runs any of the coroutines, all three launches see `segCount = 3` and the `== 2` check is missed.  
**Fix:** Capture the count in a local val before launching:
```kotlin
val capturedCount = segCount
viewModelScope.launch {
    if (capturedCount == 2) { … }
}
```

---

### Bug 33 🔵 — TestViewModel.kt: relay.start() without relay.stop() on video re-pick → BindException
**File:** `app/…/viewmodel/TestViewModel.kt`  
**Symptom:** If the user taps the video picker a second time in the test lab while segmenting is already running, `startSegmenting()` calls `relay.start()` while NanoHTTPD is still bound to port 9191, causing a `BindException` crash.  
**Root cause:** `startSegmenting()` called `relay.start()` unconditionally without first calling `relay.stop()`.  
**Fix:** Added `relay.stop()` immediately before `relay.start()` in `startSegmenting()`, matching the pattern used in `stopTest()`.

---

### Bug 34 🔴 — HlsSegmenter: no look-ahead cap → entire movie segmented at once → OOM / GC → client black screen
**Files:** `app/…/segmenter/HlsSegmenter.kt`, `app/…/viewmodel/TestViewModel.kt`, `app/…/relay/SimulatedRelay.kt`  
**Symptom:** The test lab status bar shows "835 segments ready (3340 s buffered)" and the cache grows to 269 MB while the client panel stays black. The app becomes sluggish and ExoPlayer never renders a frame.  
**Root cause:**  
`HlsSegmenter.doSegment()` loops until `MediaExtractor.readSampleData()` returns -1 (end of file). `MediaExtractor` reads at full disk speed — not at real-time video speed — so a 50-minute movie produces 750+ segments in a few seconds. All segments are stored in `SimulatedRelay.segments` (a `ConcurrentHashMap`) and on disk in `context.cacheDir`. Peak memory was 750 × ~2.5 MB ≈ 1.9 GB — well above the Android app heap limit. The resulting GC pressure stalled every other thread including ExoPlayer's decoder, leaving the client panel black.  
**Fix (three-part):**  

1. **`HlsSegmenter.kt` — `pauseCheck` gate.**  
   A new `@Volatile var pauseCheck: (() -> Boolean)?` property was added. After each segment's `onSegmentReady` callback returns, the worker thread calls `pauseCheck()` and sleeps in 200 ms increments while it returns `true`. This is the only addition to the segmenter; everything else is in the caller.

2. **`HlsSegmenter.kt` — delete segment files immediately.**  
   `onSegmentReady(name, file)` is invoked while the file still exists. The caller reads the bytes inside the callback; the segmenter deletes the file right after the callback returns. Segment files no longer accumulate in `cacheDir`. Cache usage drops from ~270 MB for a full movie to effectively zero.

3. **`TestViewModel.kt` — 30-segment look-ahead window + eviction.**  
   `pauseCheck` is set to:
   ```kotlin
   { totalSegmentsProduced > (_hostPositionMs.value / 4_000L).toInt() + LOOK_AHEAD_SEGS }
   ```
   where `LOOK_AHEAD_SEGS = 30` (= 2 minutes). The segmenter therefore stays at most 2 minutes ahead of the host's playback position.  
   `evictOldSegments(hostPositionMs)` removes segments the host has already played past from `SimulatedRelay.segments`, keeping only the last `KEEP_BEHIND_SEGS = 5` for brief seek-backs. `SimulatedRelay.evictSegment(name)` was added to support this.  
   Peak relay memory is now ≈ 35 × 2.5 MB = 87 MB instead of 1.9 GB.

---

### Bug 35 🟡 — SyncViewModel.kt: segment files read after HlsSegmenter deletes them → FileNotFoundException
**File:** `app/…/viewmodel/SyncViewModel.kt`  
**Symptom:** After the Bug 34 fix (HlsSegmenter deletes segment files immediately after `onSegmentReady` returns), the old `file.readBytes()` call inside the `viewModelScope.launch {}` block would run on the main thread *after* the file was already deleted, producing a `FileNotFoundException` and silently dropping segments.  
**Root cause:** `file.readBytes()` was inside a `viewModelScope.launch` lambda which is scheduled asynchronously. The HlsSegmenter worker thread deletes the file right after the callback returns — before the coroutine ever runs.  
**Fix:** Read the bytes synchronously on the HlsSegmenter thread inside the `onSegmentReady` callback (before the segmenter deletes the file), store them in a `val data`, and pass `data` (not `file`) into the coroutine:
```kotlin
onSegmentReady = { name, file ->
    val data = file.readBytes()          // ← read NOW, file still exists
    viewModelScope.launch {
        relayClient?.uploadSegment(name, data)   // ← uses in-memory bytes
        …
    }
}
```
The same pattern was already applied in `TestViewModel`.

---

## Session 5 — Seek Storm & Simulation Fidelity (Bugs 36–37)

### Bug 36 🔴 — VideoPlayer.kt: pong handler seeks every 2.5 s → seek storm → client permanent black screen
**File:** `app/src/main/java/com/agon/app/ui/components/VideoPlayer.kt`
**Symptom:** In the test lab (India → USA profile), the HOST plays fine but the
CLIENT panel stays black indefinitely. Stats show Ping: ~234 ms (sync is
working), Drift: ~600 ms (oddly low — see below), Segs: 9, Drop: 1. No matter
how long you wait the client never renders a frame.
**Root cause (step-by-step):**

1. `clientHlsUri` is set after segment 2 is ready. The client `VideoPlayer`
   composable starts. ExoPlayer calls `prepare()` with `playWhenReady=false`.

2. The client's ping loop waits 2 000 ms, then sends a ping. The ping round-trips
   through `SimulatedRelay` (~468 ms RTT = 2 × 234 ms), so the **first pong
   arrives ~2.5 s after the composable starts**.

3. Host is at position ~55 s. `estimatedHostPos ≈ 55 000 ms`.
   `exoPlayer.currentPosition = 0`.
   `drift = 55 000 ms → always > 3 000`.

4. The old pong handler had:
   ```kotlin
   when {
       drift > 3000 -> exoPlayer.seekTo(estimatedHostPos)   // ← fires every pong
       ...
   }
   ```
   Every pong (arriving every ~2.5 s) fires `seekTo(~55 000 ms)`.

5. Each `seekTo()` makes ExoPlayer discard whatever portion of a segment it had
   started to download and restart its HTTP request for the segment at the new
   position.

6. With India → USA at 10 Mbps, a typical 15 MB segment takes ~12 s to fully
   download. But seeks arrive every 2.5 s — more than 4× faster than downloads
   complete. The client **never finishes downloading a single segment**. It stays
   in `STATE_BUFFERING` permanently.

7. Because `hasEverStartedPlaying = false`, no `"buffering start"` is sent to
   the host, so the host keeps playing normally — explaining the low drift shown
   in the stats: `_driftMs` is `abs(hostPositionMs - clientPositionMs)`, and
   since `clientPositionMs` is reported from `onPositionUpdate` which reads
   `exoPlayer.currentPosition`, and ExoPlayer keeps reporting the **seek target**
   as its current position (even while buffering), the drift looks small even
   though the client has never played a frame.

**Fix:** Added `hasSeekedForStartup` flag (initialized `true` for host, `false`
for client). The pong handler now uses three branches:

```kotlin
when {
    // Mid-playback: seek immediately — corrects real desync after a stall
    drift > 3000 && exoPlayer.isPlaying -> exoPlayer.seekTo(estimatedHostPos)

    // Startup: seek ONCE to align with host, then wait for ExoPlayer to buffer
    drift > 3000 && !hasSeekedForStartup && msg.isPlaying -> {
        exoPlayer.seekTo(estimatedHostPos)
        hasSeekedForStartup = true
    }

    drift > 300 && msg.isPlaying -> { /* speed nudge */ }
    else -> exoPlayer.setPlaybackSpeed(1.0f)
}
```

With this fix:
- The client seeks to ~55 s exactly once (on the first pong with `isPlaying=true`).
- Subsequent pongs find `hasSeekedForStartup=true` and `!exoPlayer.isPlaying` → skip
  the seek branch entirely.
- ExoPlayer downloads the segment starting at ~55 s undisturbed.
- With the bandwidth simulation fix (Bug 37), ExoPlayer starts rendering frames
  after `bufferForPlayback = 2 000 ms` of content is received.
- Once `exoPlayer.isPlaying` becomes `true`, the `drift > 3000 && exoPlayer.isPlaying`
  branch takes over for all future mid-playback corrections.

Also added `hasSeekedForStartup = true` in the `"state play"` handler so that
a direct play command from the host (not via pong) also marks startup complete.

---

### Bug 37 🟠 — SimulatedRelay.kt: bulk-delay-then-burst delivery prevents ExoPlayer from partially buffering
**File:** `app/src/main/java/com/agon/app/relay/SimulatedRelay.kt`
**Symptom:** Even after Bug 36 is fixed, the client's startup time in the test
lab is 12–25 s per segment (at India → USA 10 Mbps with typical 15 MB segments).
This is far longer than what users experience on a real connection, making the
test lab feel pessimistic. On a real network the client starts playing within
2–4 s of the stream being ready.
**Root cause:** The old `simulateNetworkDelay()` called:
```kotlin
Thread.sleep(latencyMs + transferMs)   // full sleep first
```
Then returned a plain `ByteArrayInputStream(data)`. NanoHTTPD wrote ALL bytes to
the socket in a single burst after the sleep. ExoPlayer's HTTP client received
**zero bytes during the entire sleep period**, so `bufferForPlayback = 2 000 ms`
was unreachable until the complete `transferMs` had elapsed — e.g. 12 s for a
15 MB segment at 10 Mbps.

On a real network the relay (Go server) streams bytes continuously at link speed.
ExoPlayer starts parsing the MP4 box headers immediately, fills its internal
2 000 ms playback buffer after receiving ~2–3 s worth of bytes, and begins
rendering frames. The simulation was 5–10× more pessimistic than reality for
startup time.

**Fix:** Replaced `simulateNetworkDelay()` with two separate mechanisms:

1. **Initial latency:** `Thread.sleep(latencyMs())` before the first byte —
   models the one-way TCP + TLS connection setup cost. Applied to both playlist
   and segment requests.

2. **Bandwidth throttle via `BandwidthThrottledStream`:** A custom `InputStream`
   that delivers bytes at `bandwidthKbps` by computing `expectedMs = position /
   bytesPerMs` and sleeping only the fractional remainder before each `read()`.
   ExoPlayer's HTTP client calls `read()` continuously and receives bytes at the
   correct rate — just like a real link. `bufferForPlayback = 2 000 ms` is now
   met after ~2 s of actual data transfer, not after the full segment download.

Unlimited-bandwidth profiles (`bandwidthKbps ≥ 1 000 000`, e.g. SAME_ROOM) still
use a plain `ByteArrayInputStream` to avoid unnecessary overhead.

**Effect on test accuracy:** The lab now correctly predicts real-world startup
latency. Under India → USA (260 ms one-way, 10 Mbps), the client starts playing
~2–3 s after the first pong aligns it with the host, matching what users of the
live Render relay actually experience.

---

## Bug 38 — App crash when client joins without relay server configured

**Symptom:** A fresh install (Samsung or any device) where the user hasn't gone
to Settings to enter the relay URL will crash instantly when tapping "Join Room".
Samsung shows "MariAsh Stream closed because this app has a bug — try clearing
cache". Clearing cache doesn't help because the problem is a code crash, not
corrupted data.

**Root cause:** `AppPreferences.relayUrl` emits `""` (empty string) as its
default when no URL has been saved. `RoomScreen` collects it with
`initial = null` so the `LaunchedEffect` guard (`if (relayUrl == null) return`)
correctly waits for the first DataStore emission. But that first emission IS `""`
— non-null — so `initRoom("", ...)` fires immediately. Inside
`RelayClient.connect()`, the WebSocket URL is built as:

```
"$wsUrl/sync/$roomId?role=client"
→ "/sync/AC8713?role=client"   // wsUrl="" after replace/trimEnd
```

`okhttp3.Request.Builder().url("/sync/AC8713?role=client")` throws
`IllegalArgumentException: Expected URL scheme 'http' or 'https' but no colon
was found` — synchronously, before any network call. This exception propagates
uncaught through `initRoom()` → the `LaunchedEffect` coroutine → crashes the app.

**Fix (three layers):**

1. `HomeScreen.kt` — "Join Room" button `enabled` now requires BOTH
   `roomId.isNotBlank() && relayUrl.isNotBlank()`. An error label "Set a relay
   server in Settings first" appears when `relayUrl` is blank, preventing the
   user from even navigating to RoomScreen with no relay set.

2. `RelayClient.connect()` — added an early guard:
   ```kotlin
   if (relayBaseUrl.isBlank()) {
       listener.onDisconnected("NO_RELAY_URL")
       return
   }
   ```
   Also wrapped `Request.Builder().url(...)` in `try/catch(IllegalArgumentException)`
   for malformed URLs (e.g. user typed "myserver" with no scheme).

3. `SyncViewModel.onDisconnected()` — added sentinel handling:
   ```kotlin
   if (reason == "NO_RELAY_URL") {
       _connectionStatus.value = "No relay server set — go to Settings"
       return@launch
   }
   ```
   This prevents a blank-URL disconnect from incrementing `disconnectCount` and
   falsely triggering the "Waking server…" screen.

---

## Bug 39 — Silent black screen when ExoPlayer fails to load a video URL

**Symptom:** Client (or host in web-URL mode) shows a permanently black screen
with no error message. Affects any URL that ExoPlayer cannot play: TikTok/YouTube/
Netflix share links, geo-blocked CDN URLs, expired signed URLs, or webpages
mistaken for direct video files.

**Root cause:** `VideoPlayer` had no `onPlayerError` override in its
`Player.Listener`. When ExoPlayer hits an HTTP 403, 404, or format error, it
transitions to `Player.STATE_IDLE` with an error, but the `PlayerView` just
stays black. The user has no way of knowing whether the video is loading,
buffering, or has failed.

**Fix:** Added `onPlayerError(PlaybackException)` override to the Player.Listener
in `VideoPlayer.kt`. It maps the raw exception to a human-readable string:
- HTTP 403 / Forbidden → explains TikTok/YouTube CDN restriction
- HTTP 404 / Not Found → URL expired or file moved
- UnrecognizedInputFormatException → URL is a webpage, not a video file
- Connection refused / timeout → network issue

A `playerError` state variable holds the message. When non-null, a dark overlay
with a broken-image icon and the error text is drawn on top of the black
`PlayerView`, so the user immediately knows what went wrong and what to do.

---

## Bug 40 — GitHub Actions builds will break on June 2nd 2026 (Node.js 20 deprecation)

**Symptom:** Build warnings on every CI run:
> Node.js 20 actions are deprecated. actions/checkout@v4, actions/setup-java@v4,
> actions/upload-artifact@v4, softprops/action-gh-release@v2 will be forced to
> Node.js 24 by default starting June 2nd, 2026.

**Root cause:** The runner images run action JavaScript with Node.js 20. GitHub
is removing Node.js 20 support on June 2nd 2026 (29 days from discovery). After
that date, actions that haven't been opted into Node 24 will fail unpredictably.

**Fix:** Added `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: "true"` to the job-level
`env` block in `.github/workflows/build-apk.yml`. This immediately opts all four
actions into Node.js 24, eliminating the warning and future-proofing the build
before the deadline. After June 2nd when Node 24 becomes the runner default, the
env var can be removed (it will be a no-op by then).

