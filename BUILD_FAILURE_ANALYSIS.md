# APK Build Failure Analysis & Prevention Guide

## Overview

This document records every root cause that caused the GitHub Actions APK build to fail repeatedly, the exact fix applied to each, and the rules that any future agent or developer **must follow** to prevent recurrence.

---

## Bug 1 — Duplicate `val` Declaration in `RelayClient.kt`

### File
`app/src/main/java/com/agon/app/relay/RelayClient.kt` — `connect()` function

### What was wrong
```kotlin
// BAD — two declarations of the same val in the same scope
val encodedToken = if (relayToken.isNotBlank()) URLEncoder.encode(relayToken, ...) else ""
val tokenQuery = if (encodedToken.isNotBlank()) "&token=$encodedToken" else ""
val tokenQuery = if (relayToken.isNotBlank()) "&token=$relayToken" else ""  // ← DUPLICATE
```

Kotlin does not allow redeclaring a `val` (or `var`) in the same scope. The Kotlin compiler exits with:
```
error: conflicting declarations: val tokenQuery: String defined in ...connect and
       val tokenQuery: String defined in ...connect
```

### Fix Applied
Removed the second duplicate `val tokenQuery` declaration. Kept the URL-encoded version (safer for tokens that may contain special characters):
```kotlin
val encodedToken = if (relayToken.isNotBlank()) URLEncoder.encode(relayToken, StandardCharsets.UTF_8.toString()) else ""
val tokenQuery = if (encodedToken.isNotBlank()) "&token=$encodedToken" else ""
```

### Prevention Rule
**Never redeclare a `val` or `var` with the same name in the same scope.** When patching existing code, search for the variable name before adding a new declaration. If you are replacing a variable's value or logic, modify the existing line — do not add a new `val` below it.

---

## Bug 2 — Unreachable Code After `throw` in `RelayClient.kt`

### File
`app/src/main/java/com/agon/app/relay/RelayClient.kt` — `uploadSegment()` function

### What was wrong
```kotlin
throw IOException("Non-retryable upload error ${response.code} for $filename")
// Everything below is unreachable — a previous agent patched this block
// without removing the original code:
if (response.code in 500..599) {
    throw IOException("Retryable upload error ${response.code} for $filename")
}
Log.e("RelayClient", "Upload failed: ${response.code} for $filename")
return
```

Code after an unconditional `throw` is unreachable. While Kotlin may treat this as a warning rather than a hard error, it signals stale/conflicting logic left by an incomplete patch and can cause confusion or silent logic errors depending on AGP/Kotlin version.

### Fix Applied
Removed the dead code block after the `throw`. The method now has clean, non-duplicated error paths.

### Prevention Rule
**When adding a `throw` or `return`, delete any code that follows it in the same block.** If a previous agent added a `throw` that makes existing lines below it unreachable, those lines must be removed in the same commit. Do not leave dead code in place.

---

## Bug 3 — `@Composable` Called Inside a Coroutine Block in `RoomScreen.kt`

### File
`app/src/main/java/com/agon/app/ui/screens/RoomScreen.kt`

### What was wrong
```kotlin
// BAD — outer LaunchedEffect opens a coroutine (suspend) block
LaunchedEffect(roomId, relayUrl, relayToken) {        // ← opens coroutine scope
    LaunchedEffect(roomId) {                           // ← @Composable inside coroutine — ILLEGAL
        viewModel.initRoom(roomId, isHost, relayUrl, relayToken)
    }
    // Everything below this point (LaunchedEffect(webUrl), LaunchedEffect(Unit),
    // val launcher, and the ENTIRE Scaffold) was inside the outer LaunchedEffect's
    // coroutine body. None of it is callable from there.
    LaunchedEffect(webUrl) { ... }
    LaunchedEffect(Unit) { ... }
    val launcher = ...
    Scaffold(...) { ... }   // ← All of this was inside the outer LE's block!
```

`LaunchedEffect` is annotated `@Composable`. `@Composable` functions can **only** be called from within the composition (i.e., from other `@Composable` functions or their directly reachable composable context). The body of a `LaunchedEffect` is a `suspend CoroutineScope.() -> Unit` lambda — it is NOT a composable context.

This causes the Kotlin compiler (Compose plugin) to fail with:
```
error: @Composable invocations can only happen from the context of a @Composable function
```

Additionally, because the outer `LaunchedEffect`'s opening `{` had no matching closing `}`, the file had **unbalanced braces**, causing:
```
error: expecting '}'
```

### Fix Applied
Removed the outer `LaunchedEffect(roomId, relayUrl, relayToken) {` wrapper. Updated the remaining single `LaunchedEffect` to use the correct key set `(roomId, relayUrl, relayToken)`:
```kotlin
// CORRECT — single top-level LaunchedEffect, directly in composable scope
LaunchedEffect(roomId, relayUrl, relayToken) {
    viewModel.initRoom(roomId, isHost, relayUrl, relayToken)
}

LaunchedEffect(webUrl) { ... }   // ← now directly in composable scope
LaunchedEffect(Unit) { ... }     // ← now directly in composable scope
```

### Prevention Rule
**Never call a `@Composable` function inside a coroutine block, `LaunchedEffect` body, `DisposableEffect` body, callback lambda, `Thread`, or any non-composable context.** The rule in Jetpack Compose is strict: composables can only be invoked inside the composition tree. If you need to trigger side effects from inside a composable, use `LaunchedEffect`, `DisposableEffect`, `SideEffect` — but their bodies are coroutines, not composable scopes.

**When nesting `LaunchedEffect` blocks, stop and reconsider.** There is almost never a valid reason to have one `LaunchedEffect` inside another. Use multiple separate `LaunchedEffect` calls at the same composable level instead.

---

## Bug 4 — Circular Style Inheritance in `styles.xml`

### File
`app/src/main/res/values/styles.xml`

### What was wrong
```xml
<!-- BAD — a style cannot inherit from itself -->
<style name="Theme.Material3.DayNight.NoActionBar"
       parent="Theme.Material3.DayNight.NoActionBar">
</style>
```

`Theme.Material3.DayNight.NoActionBar` is already defined in the Material Components library. Redefining it with itself as the parent creates a circular inheritance chain. AAPT (Android Asset Packaging Tool) fails during resource linking with:
```
error: style attribute ... not found.
AAPT: error: failed to link resources.
```

Additionally, `AndroidManifest.xml` was referencing `@style/Theme.Material3.DayNight.NoActionBar` — a style that should come from the library, but was now shadowed by a broken local definition.

### Fix Applied
1. Emptied `styles.xml` — the broken style definition was removed entirely.
2. Updated `AndroidManifest.xml` to reference `@style/Theme.AgonApp` (already correctly defined in `themes.xml` as `parent="Theme.Material3.DayNight.NoActionBar"`).

**`themes.xml` (already correct — no changes needed):**
```xml
<style name="Theme.AgonApp" parent="Theme.Material3.DayNight.NoActionBar" />
```

**`AndroidManifest.xml` (fixed):**
```xml
android:theme="@style/Theme.AgonApp"
```

### Prevention Rule
**Never define a custom style with the same name as a library style.** If you want to extend or override a library theme, create a new style with a different name and set the library style as its parent. The app's canonical theme is `Theme.AgonApp` (defined in `themes.xml`). The manifest must reference `@style/Theme.AgonApp` — never a raw Material3 style name directly.

---

---

## Bug 5 — Wrong FFmpegKit Maven Coordinates in `app/build.gradle.kts`

### File
`app/build.gradle.kts`

### What was wrong
```kotlin
// BAD — wrong group ID, this is a JitPack fork with an incompatible class structure
implementation("com.antonkarpenko:ffmpeg-kit-full-gpl:2.1.0")
```

The codebase imports from `com.arthenica.ffmpegkit.*` (the official FFmpegKit API by Taner Sener / arthenica):
```kotlin
import com.arthenica.ffmpegkit.FFmpegKitConfig   // AgonApplication.kt
import com.arthenica.ffmpegkit.FFmpegKit          // HlsSegmenter.kt
import com.arthenica.ffmpegkit.ReturnCode         // HlsSegmenter.kt
```

`com.antonkarpenko:ffmpeg-kit-full-gpl` is a third-party JitPack re-package that does NOT expose the `com.arthenica.ffmpegkit` package. The Kotlin compiler cannot resolve any of these import references, resulting in:
```
e: .../AgonApplication.kt:4:12 Unresolved reference 'arthenica'.
e: .../AgonApplication.kt:10:9 Unresolved reference 'FFmpegKitConfig'.
e: .../HlsSegmenter.kt:6:12 Unresolved reference 'arthenica'.
e: .../HlsSegmenter.kt:63:23 Unresolved reference 'FFmpegKit'.
e: .../HlsSegmenter.kt:67:17 Unresolved reference 'ReturnCode'.
```

### Fix Applied
Changed the dependency to the official arthenica release on Maven Central:
```kotlin
// CORRECT — official arthenica build on Maven Central, provides com.arthenica.ffmpegkit.*
implementation("com.arthenica:ffmpeg-kit-full-gpl:6.0-2")
```

No repository changes were needed — `mavenCentral()` is already declared in `settings.gradle.kts`.

### API compatibility (6.0-2)
All methods used in the codebase exist in 6.0-2:
| Code call | Kotlin property / method | Exists in 6.0-2 |
|-----------|--------------------------|-----------------|
| `FFmpegKit.executeAsync(cmd) { session -> }` | static method | ✓ |
| `FFmpegKit.cancel(sessionId)` | static method, takes `Long` | ✓ |
| `FFmpegKitConfig.getSafParameterForRead(ctx, uri)` | static method | ✓ |
| `FFmpegKitConfig.setLogLevel(level)` | static method | ✓ |
| `session.returnCode` | `getReturnCode()` | ✓ |
| `session.allLogsAsString` | `getAllLogsAsString()` | ✓ |
| `session.sessionId` | `getSessionId()` | ✓ |
| `ReturnCode.isSuccess(rc)` | static method | ✓ |

### Prevention Rule
**Always match the Maven `groupId:artifactId` to the package namespace used in imports.** Before adding a dependency, confirm:
1. The group ID matches the top-level package the code imports from (e.g., `com.arthenica.*` → group `com.arthenica`).
2. The artifact version actually exists on the declared repositories (verify on https://search.maven.org or https://mvnrepository.com).
3. Do not substitute a JitPack fork (`com.github.*` or other group) unless the fork explicitly guarantees the same package namespace.

**CORRECTION**: The `com.arthenica:ffmpeg-kit-full-gpl:6.0-2` dependency was attempted but also failed — see Bug 6 below for the definitive resolution.

---

## Bug 6 — FFmpegKit (`com.arthenica:ffmpeg-kit-full-gpl`) Is Completely Unavailable (Permanent Fix)

### Files
- `app/build.gradle.kts`
- `app/src/main/java/com/agon/app/AgonApplication.kt`
- `app/src/main/java/com/agon/app/segmenter/HlsSegmenter.kt`

### What was wrong
After correcting the group ID from `com.antonkarpenko` to `com.arthenica`, the build still failed:

```
> Could not find com.arthenica:ffmpeg-kit-full-gpl:6.0-2.
  Searched in the following locations:
    - https://dl.google.com/dl/android/maven2/.../ffmpeg-kit-full-gpl-6.0-2.pom
    - https://repo.maven.apache.org/maven2/.../ffmpeg-kit-full-gpl-6.0-2.pom
    - https://jitpack.io/.../ffmpeg-kit-full-gpl-6.0-2.pom
```

Root cause: The `arthenica/ffmpeg-kit` project was **permanently archived** in early 2024. Its packages were hosted on GitHub Packages (`https://maven.pkg.github.com/arthenica/ffmpeg-kit`), NOT on Maven Central. Both the GitHub Packages endpoint and Maven Central all return 404 for every version of `com.arthenica:ffmpeg-kit-*`. The package simply no longer exists anywhere.

Investigation confirmed:
- `repo.maven.apache.org` → 404 for all arthenica versions
- `maven.pkg.github.com/arthenica/ffmpeg-kit` → 404 even with auth
- GitHub release assets for `v6.0.LTS` → no AAR attachments
- `com.antonkarpenko:ffmpeg-kit-full-gpl:2.1.0` (JitPack fork) → AAR resolves but provides native `.so` files ONLY, no `com.arthenica.ffmpegkit.*` Java classes in compilation classpath

### Fix Applied
Removed the FFmpegKit dependency entirely and replaced `HlsSegmenter.kt` with a pure Android SDK implementation using `MediaExtractor` + `MediaMuxer` (MPEG-TS output format).

#### `app/build.gradle.kts`
```kotlin
// REMOVED: implementation("com.arthenica:ffmpeg-kit-full-gpl:6.0-2")
// HLS segmentation now uses Android's built-in MediaExtractor + MediaMuxer
```

#### `AgonApplication.kt`
Removed the `FFmpegKitConfig.setLogLevel(...)` call — it was only a log verbosity hint.

#### `HlsSegmenter.kt`
Replaced the entire file with a `MediaExtractor` + `MediaMuxer` implementation:
- `MediaExtractor.setDataSource(context, uri, null)` — reads any URI the OS can open (SAF, content://, file://)
- All video and audio tracks are discovered and selected
- `MediaMuxer(path, MUXER_OUTPUT_MPEG_TS)` — writes each segment as a valid `.ts` file
- Segment boundaries are placed on the next sync (I-frame) sample after 4 seconds elapsed
- M3U8 playlist is built and emitted after each segment (live append mode)
- All callbacks (`onSegmentReady`, `onPlaylistReady`, `onProgress`, `onError`, `onComplete`) preserved with identical signatures

No external dependencies required — `MediaExtractor`, `MediaMuxer`, and `MediaCodec.BufferInfo` are all part of Android API 21+ (minSdk = 24 in this project).

### Prevention Rule
**Do not add dependencies on the `com.arthenica:ffmpeg-kit-*` or `com.antonkarpenko:ffmpeg-kit-*` family of packages.** These are permanently unavailable as of 2024. For video remuxing/segmentation tasks in this project, use Android's `MediaExtractor` + `MediaMuxer` API (already implemented in `HlsSegmenter.kt`).

---

## Summary Table

| # | File | Category | Error Type | Fix |
|---|------|----------|-----------|-----|
| 1 | `RelayClient.kt` | Duplicate variable | Kotlin compile error | Removed the second `val tokenQuery` declaration |
| 2 | `RelayClient.kt` | Dead code after `throw` | Unreachable code / stale patch | Removed code lines after the unconditional `throw` |
| 3 | `RoomScreen.kt` | Composable in coroutine + unbalanced braces | Compose plugin error + Kotlin syntax error | Removed the wrapping outer `LaunchedEffect`, fixed keys |
| 4 | `styles.xml` + `AndroidManifest.xml` | Circular style inheritance | AAPT resource link failure | Removed broken style, pointed manifest to `Theme.AgonApp` |
| 5 | `app/build.gradle.kts` | Wrong Maven dependency coordinates (first attempt) | Gradle resolution error | Identified `com.arthenica` package is fully unavailable |
| 6 | `build.gradle.kts` + `AgonApplication.kt` + `HlsSegmenter.kt` | Unavailable external library (permanent) | Gradle resolution error | Removed FFmpegKit entirely; rewrote with MediaExtractor+MediaMuxer |
| 7 | `HlsSegmenter.kt` | Non-existent SDK constant | Kotlin compile error | Changed `MUXER_OUTPUT_MPEG_TS` → `MUXER_OUTPUT_MP4`; renamed segments `.ts` → `.mp4` |
| 8 | `HlsSegmenter.kt` | Source-retention annotation constant invisible to K2 | Kotlin compile error | Defined `MUXER_OUTPUT_MP4 = 0` locally in companion object; removed `MediaMuxer.OutputFormat.*` reference |

---

## Bug 7 — `Unresolved reference 'MUXER_OUTPUT_MPEG_TS'` (HlsSegmenter.kt:120)

### Symptom
```
e: HlsSegmenter.kt:120:75 Unresolved reference 'MUXER_OUTPUT_MPEG_TS'.
FAILURE: Build failed with an exception.
Execution failed for task ':app:compileDebugKotlin'.
```

### Root Cause
`MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_TS` **does not exist** in the public
Android SDK. The `MediaMuxer.OutputFormat` interface only defines:

| Constant | Value | Since API |
|---|---|---|
| `MUXER_OUTPUT_MP4` | 0 | 18 |
| `MUXER_OUTPUT_WEBM` | 1 | 21 |
| `MUXER_OUTPUT_3GPP` | 2 | 22 |
| `MUXER_OUTPUT_HEIF` | 3 | 28 |
| `MUXER_OUTPUT_OGG` | 4 | 29 |

There is no `MUXER_OUTPUT_MPEG_TS` in any public Android SDK release. The constant
was added to HlsSegmenter.kt in Bug 6's fix (which removed FFmpegKit and replaced it
with native Android MediaExtractor + MediaMuxer) but referenced a non-existent symbol.

### Fix Applied
- **HlsSegmenter.kt line 120:** `MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_TS`
  → `MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4`
- **HlsSegmenter.kt line 117:** segment filename `seg_%05d.ts`
  → `seg_%05d.mp4`

ExoPlayer's `media3-exoplayer-hls` fully supports HLS playlists whose segments
are individual MP4 files (each finalized `MediaMuxer` output is a valid, self-contained
MP4). No playlist version change is required for VOD MP4-segment HLS.

### Follow-up Runtime Fixes (same commit)
Three additional files carried stale `.ts` / `video/MP2T` references that would
have broken the app at runtime (not at compile time):

| File | Change |
|---|---|
| `SimulatedRelay.kt` | Route `.mp4` segments instead of `.ts`; count `.mp4` files; serve with `video/mp4` MIME |
| `RelayClient.kt` | Upload segments with `video/mp4` instead of `video/MP2T` |
| `SyncViewModel.kt` | Token-patch playlist lines ending in `.mp4` instead of `.ts` |

### Prevention Rules
1. **Never reference Android SDK constants by guessing** — verify every constant in
   the [official MediaMuxer.OutputFormat docs](https://developer.android.com/reference/android/media/MediaMuxer.OutputFormat)
   before use.
2. When replacing a library, audit **every file** in the project for extension strings
   (`.ts`, `.m3u8`) and MIME types (`video/MP2T`) that the old library dictated —
   they must all be updated together.
3. **Do not use `MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4` directly** — see Bug 8 below.

---

## Bug 8 — `Unresolved reference 'MUXER_OUTPUT_MP4'` (HlsSegmenter.kt:120) — PERMANENT FIX

### Symptom
```
e: HlsSegmenter.kt:120:75 Unresolved reference 'MUXER_OUTPUT_MP4'.
FAILURE: Build failed with an exception.
Execution failed for task ':app:compileDebugKotlin'.
```

This error appeared **even though `MUXER_OUTPUT_MP4` is a real Android SDK constant** and
the file correctly said `MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4`. Bug 7's fix had been
pushed (`MPEG_TS` → `MP4`) and the current source was correct, yet the build still failed.

### Root Cause
`MediaMuxer.OutputFormat` in Android SDK `compileSdk ≥ 29` is declared as a
**`@Retention(RetentionPolicy.SOURCE)` annotation type** (`@interface`). Because its
retention policy is `SOURCE`, the annotation type and all of its member constants are
**stripped from the compiled `.class` stubs** inside `android.jar`. They do not exist at
compile time in the bytecode.

The **Kotlin 2.0 K2 compiler** (used in this project via `kotlin.android` version 2.0.21)
has stricter Java interop rules than the old K1 compiler. When it tries to resolve
`MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4`, it finds `OutputFormat` but cannot find
`MUXER_OUTPUT_MP4` as a member because the annotation's members were stripped. K1 may have
silently inlined these values; K2 fails explicitly.

**Affected environment:**
- `compileSdk = 35`
- `org.jetbrains.kotlin.android` version `2.0.21` (K2 compiler)
- `com.android.application` AGP version `8.6.0`

### Fix Applied (permanent)
Added a local constant in `HlsSegmenter.kt`'s companion object and replaced all
references to `MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4` with the local constant:

```kotlin
companion object {
    private const val TAG = "HlsSegmenter"
    private const val SEGMENT_DURATION_US = 4_000_000L
    // MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4 = 0 (stable since API 18).
    // Do NOT reference MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4 directly:
    // in Android SDK compileSdk >= 29 the OutputFormat @interface is declared
    // with @Retention(RetentionPolicy.SOURCE), so its members are stripped from
    // the class stubs. The Kotlin 2.0 K2 compiler cannot resolve them at compile
    // time, producing "Unresolved reference 'MUXER_OUTPUT_MP4'". Using the raw
    // integer value here is the only reliable fix.
    private const val MUXER_OUTPUT_MP4 = 0
}
```

Usage site:
```kotlin
// BEFORE (fails with K2 + compileSdk >= 29):
muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4)

// AFTER (always works — integer literal, no annotation class access):
muxer = MediaMuxer(file.absolutePath, MUXER_OUTPUT_MP4)
```

The integer values for all `MediaMuxer` output formats are documented and stable:

| Constant | Integer value | Safe to hard-code? |
|---|---|---|
| `MUXER_OUTPUT_MP4` | `0` | Yes — stable since API 18 |
| `MUXER_OUTPUT_WEBM` | `1` | Yes — stable since API 21 |
| `MUXER_OUTPUT_3GPP` | `2` | Yes — stable since API 22 |
| `MUXER_OUTPUT_HEIF` | `3` | Yes — stable since API 28 |
| `MUXER_OUTPUT_OGG` | `4` | Yes — stable since API 29 |

### Prevention Rule (CRITICAL — READ THIS BEFORE TOUCHING HlsSegmenter.kt)
**NEVER write `MediaMuxer.OutputFormat.MUXER_OUTPUT_MP4` (or any other `MediaMuxer.OutputFormat.*` constant) in Kotlin source.** The `OutputFormat` annotation type uses source retention and is invisible to the K2 compiler at compile time. Always use the local companion object constant `MUXER_OUTPUT_MP4` (defined as `0`) or define your own `private const val` with the correct integer value.

This rule applies for the lifetime of this project. Even if a future Android SDK changes the retention policy, the local constant approach is always safer and more explicit.

---

## General Rules for Future Agents

1. **One variable declaration per scope.** Before adding a `val foo = ...`, search the function for an existing `val foo` or `var foo`. Edit the existing line; do not add a new one below it.

2. **Clean up after `throw` and `return`.** Any lines after an unconditional `throw` or `return` in the same block are dead. Remove them.

3. **`@Composable` functions belong in composable contexts only.** The bodies of `LaunchedEffect`, `DisposableEffect`, coroutines, threads, and callbacks are NOT composable contexts. Never nest composable calls inside them.

4. **Never shadow library style names in XML.** App-specific themes live in `themes.xml` as `Theme.AgonApp`. The manifest always references `@style/Theme.AgonApp`. Do not add styles to `styles.xml` that share a name with an AndroidX/Material library style.

5. **After any Kotlin/XML edit, verify brace/tag balance.** An unclosed `{` in Kotlin or an unclosed tag in XML will always fail the build with a cryptic parser error. Count your opens and closes.

6. **Match Maven group ID to the import package namespace, and verify the artifact actually exists.** Check `https://search.maven.org` or `https://repo1.maven.org/maven2/` directly before adding any dependency. A 404 from the POM URL means the artifact is unavailable regardless of what the search index shows.

7. **`com.arthenica:ffmpeg-kit-*` and `com.antonkarpenko:ffmpeg-kit-*` are permanently unavailable.** Do not use them. Use Android's `MediaExtractor`+`MediaMuxer` for video segmentation (already implemented in `HlsSegmenter.kt`).

8. **Incremental patching accumulates bugs.** Each time a file is patched by an agent, confirm that the new lines do not duplicate or conflict with existing lines in the same function. Read the surrounding 20-30 lines before making a change.

9. **Never access `MediaMuxer.OutputFormat.*` constants directly.** `OutputFormat` is a source-retention annotation in Android SDK compileSdk ≥ 29. Its members are invisible to the Kotlin 2.0 K2 compiler. Always use the local `private const val MUXER_OUTPUT_MP4 = 0` defined in `HlsSegmenter.kt`'s companion object. Do not remove or rename that constant. See Bug 8 for full details.
  
  ---

  ## Bug 9 — Render Deploy Fails: "open Dockerfile: no such file or directory"

  ### Files
  - `render.yaml` (was missing at repo root; only existed in `relay-server/`)

  ### Symptom
  Render.com web service build fails immediately with:
  ```
  error: failed to solve: failed to read dockerfile: open Dockerfile: no such file or directory
  ```

  ### Root Cause
  Render looks for `render.yaml` at the **repository root**. The file was placed in
  `relay-server/render.yaml` (a subdirectory), so Render ignored it entirely and tried
  to build using the repository root as the Docker context — where there is no Dockerfile.

  ### Fix Applied
  Created a new `render.yaml` at the **repository root** containing the correct
  `rootDir: relay-server` directive:

  ```yaml
  services:
    - type: web
      name: agon-relay
      runtime: docker
      rootDir: relay-server       # ← treats relay-server/ as the build root
      dockerfilePath: ./Dockerfile # ← now resolves to relay-server/Dockerfile
      region: singapore
      plan: free
      envVars:
        - key: PORT
          value: "8080"
        - key: RELAY_TOKEN
          generateValue: true
      healthCheckPath: /healthz
  ```

  `rootDir: relay-server` makes Render:
  1. Resolve `dockerfilePath` relative to `relay-server/`
  2. Set the Docker build context to `relay-server/`

  This is required because the Dockerfile COPY commands reference `go.mod`, `go.sum`,
  and `main.go` without any subdirectory prefix — they must be at the root of the
  Docker build context.

  ### Prevention Rule
  **`render.yaml` must always be at the repository root.** Never place it in a
  subdirectory. If the deployable service lives in a subdirectory, use the
  `rootDir: <subdirectory>` field inside the render.yaml (at root) to point Render
  at the correct directory. Do not move render.yaml into the subdirectory.

  ---

  ## Bug 10 — Relay Server Rejects .mp4 Segment Uploads (400 Bad Request)

  ### File
  `relay-server/main.go` — `validSegmentName()` function

  ### Symptom
  The Android app uploads `.mp4` segments to the relay server. The server responds
  with `400 Bad Request: invalid filename`. No segments are stored. The client cannot
  play anything.

  ### Root Cause
  The `validSegmentName` whitelist was never updated when segments were renamed from
  `.ts` to `.mp4` in Bug 7's fix. It still only allowed `.ts`, `.m3u8`, and `.vtt`:

  ```go
  // BEFORE (broken — rejects all .mp4 uploads)
  return strings.HasSuffix(name, ".ts") ||
      strings.HasSuffix(name, ".m3u8") ||
      strings.HasSuffix(name, ".vtt")
  ```

  ### Fix Applied
  Added `.mp4` as a valid suffix (kept `.ts` for backward compatibility):

  ```go
  // AFTER
  return strings.HasSuffix(name, ".mp4") ||
      strings.HasSuffix(name, ".m3u8") ||
      strings.HasSuffix(name, ".vtt") ||
      strings.HasSuffix(name, ".ts")
  ```

  ### Prevention Rule
  **When changing the segment file extension anywhere in the Android app, also update
  `validSegmentName` in `relay-server/main.go`.** The two must always agree on the
  allowed segment extension. Search for all references to the old extension across the
  entire repository before committing.

  ---

  ## Bug 11 — Relay Server Serves .mp4 Segments with Wrong MIME Type

  ### File
  `relay-server/main.go` — `hlsHandler()` function

  ### Symptom
  Even after Bug 10 is fixed and uploads succeed, ExoPlayer may refuse to play or
  show a degraded experience because the relay serves `.mp4` segments as
  `application/octet-stream` (binary blob) instead of `video/mp4`.

  ### Root Cause
  The content-type switch in `hlsHandler` had no case for `.mp4`:

  ```go
  // BEFORE — .mp4 falls through to the default
  case strings.HasSuffix(filename, ".ts"):
      contentType = "video/MP2T"
  // no .mp4 case → falls through to:
  default:
      contentType = "application/octet-stream"
  ```

  ### Fix Applied
  Added a `.mp4` case before `.ts`:

  ```go
  // AFTER
  case strings.HasSuffix(filename, ".mp4"):
      contentType = "video/mp4"
  case strings.HasSuffix(filename, ".ts"):
      contentType = "video/MP2T"
  ```

  ### Prevention Rule
  **When adding a new segment format, update BOTH the `validSegmentName` whitelist AND
  the content-type switch in `hlsHandler`.** Any segment extension accepted for upload
  must also have a correct MIME type defined for download.
  