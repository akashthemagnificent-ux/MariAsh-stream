# MariAsh Stream — Production Readiness Roadmap

## Direct answers first

### 1) Can users set their own relay server URL in-app?
Yes. This is already supported through `Settings` and persisted with DataStore (`AppPreferences.relayUrl`).

### 2) Does the app already include an honest host+client continent-gap simulator?
Partially yes. The existing `LocalTestScreen` + `TestViewModel` + `SimulatedRelay` provides split host/client playback on one device with configurable latency, jitter, packet loss, and bandwidth profiles (including India↔USA style profiles). This is the right foundation, but more validation tooling is still needed for production confidence.

---

## Current state assessment

### Already strong
- HLS remuxing path avoids re-encode (`-c:v copy -c:a copy`) preserving source quality.
- WebSocket sync and relay upload path exists.
- Local split-screen test harness exists with network profiles.

### Not production-ready yet
- Relay lifecycle hardening (auth, abuse prevention, room cleanup, quotas) is incomplete.
- Streaming adaptivity and resilient retry/recovery logic need deeper work.
- Observability, crash analytics, and release-grade QA automation are missing.

---

## Phase-by-phase production plan

## Phase 0 — Product & protocol freeze (1 week)
1. Define explicit product SLOs:
   - Join latency target (e.g. <10s after room code entry)
   - Max steady-state A/V drift (e.g. <=250ms p95)
   - Startup failure rate (<1%)
2. Freeze sync protocol schema with versioning (`proto_version`, backward compatibility rules).
3. Define support matrix (Android API levels, codecs, file/container limits).

## Phase 1 — Relay server hardening (2–3 weeks)
1. Persistence + cleanup
   - Replace in-memory-only segment store with object storage (S3/R2/GCS).
   - Add segment TTL + cleanup worker.
   - Add room TTL and orphan cleanup.
2. Security
   - Signed room tokens (host/client role-bound).
   - Upload auth and size/type validation.
   - Rate limiting (per IP, per room, per endpoint).
3. Reliability
   - Multi-instance safe design (shared storage + stateless relay nodes).
   - Health checks, readiness probes, graceful shutdown.
4. Observability
   - Structured logs, request IDs, metrics (upload latency, segment miss, ws disconnects).

## Phase 2 — Streaming quality & sync robustness (2–4 weeks)
1. HLS strategy
   - Segment duration tuning (2s vs 4s A/B)
   - Optionally support CMAF/fMP4 low-latency mode.
2. Buffer strategy
   - Dynamic buffer policy based on measured RTT/jitter and rebuffer count.
3. Sync engine
   - Keep current drift tiers; add hysteresis and anti-oscillation logic.
   - Add monotonic clock use and jitter filtering (EWMA).
4. Recovery paths
   - WS reconnect with exponential backoff + resume state sync.
   - Segment upload retry with idempotency keys.

## Phase 3 — Media track completeness (1–2 weeks)
1. Multi-audio/subtitle correctness
   - Validate track discovery across MKV/MP4 edge cases.
   - Explicit mapping for subtitle tracks during remux when needed.
2. External subtitle support
   - Import sidecar subtitle files (SRT/VTT) from device and sync selection.
3. Next-video handoff
   - Formal playlist/session transition state machine to avoid stale host player state.

## Phase 4 — Honest testing system (2–3 weeks)
1. Strengthen existing Local Test Lab
   - Deterministic network trace playback (record/replay profiles).
   - Scenario presets: India↔USA mobile, Wi-Fi jitter burst, packet-loss storms.
2. Dual-timeline metrics panel
   - Host vs client current position, drift over time, frame drop indicators.
3. Automated soak tests
   - 2-hour and 5-hour playback tests with fault injection.
4. CI instrumentation tests
   - Headless protocol/sync simulation tests for regression prevention.

## Phase 5 — App production polish (1–2 weeks)
1. UX + safety
   - Clear connection status states, recovery prompts, actionable error messages.
2. Settings
   - Confirm relay URL input/validation UX, test-connection button, TLS warning.
3. Privacy/security
   - Remove sensitive logs in release, add network security config.
4. Release ops
   - Staged rollout, crash monitoring, rollback playbook.

---

## Definition of Done (production-ready gate)
- p95 drift <= 250ms in India↔USA profile over 60 min.
- Rebuffer ratio <= 1% on target profile.
- Join success >= 99% on fresh room creation.
- No critical crashes in 48h soak test.
- Relay survives rolling restarts without room corruption.

---

## Immediate next implementation batch (recommended)
1. Relay storage + TTL cleanup + signed room tokens.
2. WS reconnect and segment upload retry with idempotency.
3. Local Test Lab: deterministic profile replay + drift graph.
4. Next-video transition fix with explicit session epoch and player reset handshake.
