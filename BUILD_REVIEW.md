# Build Review

This repository is **Agon**, an Android watch-party app intended for long-distance co-watching with synchronized playback.

## What this project is for
- Host a local movie file on one phone and stream it as HLS segments to another user via a lightweight relay server.
- Keep both devices in sync with WebSocket control messages and ping/pong drift correction.
- Support a local simulation lab to test playback under global-network-like conditions (latency, jitter, packet loss, and bandwidth limits).

## Build rating

**8.4 / 10**

### Why this score

#### Strengths
1. Clear product focus and architecture split (Android client + tiny Go relay).
2. Good resilience strategy for poor networks (deep buffering + soft/hard drift correction).
3. Practical testability via built-in simulation profiles.
4. Deployment path is simple and inexpensive (Render free tier).

#### Gaps / risks
1. Relay stores media segments in memory only (no persistence or eviction strategy).
2. WebSocket origin policy is fully open.
3. Segment upload size and room lifecycle controls are basic, so abuse/scale handling is limited.
4. Hardcoded fallback relay URL may not fit private/self-hosted environments by default.

## Recommendation
For a prototype / v1 indie build, this is strong and thoughtfully engineered. For production-scale use, prioritize relay hardening (auth, storage strategy, cleanup, and traffic protection).
