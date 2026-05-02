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

## Summary Table

| # | File | Category | Error Type | Fix |
|---|------|----------|-----------|-----|
| 1 | `RelayClient.kt` | Duplicate variable | Kotlin compile error | Removed the second `val tokenQuery` declaration |
| 2 | `RelayClient.kt` | Dead code after `throw` | Unreachable code / stale patch | Removed code lines after the unconditional `throw` |
| 3 | `RoomScreen.kt` | Composable in coroutine + unbalanced braces | Compose plugin error + Kotlin syntax error | Removed the wrapping outer `LaunchedEffect`, fixed keys |
| 4 | `styles.xml` + `AndroidManifest.xml` | Circular style inheritance | AAPT resource link failure | Removed broken style, pointed manifest to `Theme.AgonApp` |

---

## General Rules for Future Agents

1. **One variable declaration per scope.** Before adding a `val foo = ...`, search the function for an existing `val foo` or `var foo`. Edit the existing line; do not add a new one below it.

2. **Clean up after `throw` and `return`.** Any lines after an unconditional `throw` or `return` in the same block are dead. Remove them.

3. **`@Composable` functions belong in composable contexts only.** The bodies of `LaunchedEffect`, `DisposableEffect`, coroutines, threads, and callbacks are NOT composable contexts. Never nest composable calls inside them.

4. **Never shadow library style names in XML.** App-specific themes live in `themes.xml` as `Theme.AgonApp`. The manifest always references `@style/Theme.AgonApp`. Do not add styles to `styles.xml` that share a name with an AndroidX/Material library style.

5. **After any Kotlin/XML edit, verify brace/tag balance.** An unclosed `{` in Kotlin or an unclosed tag in XML will always fail the build with a cryptic parser error. Count your opens and closes.

6. **Incremental patching accumulates bugs.** Each time a file is patched by an agent, confirm that the new lines do not duplicate or conflict with existing lines in the same function. Read the surrounding 20-30 lines before making a change.
