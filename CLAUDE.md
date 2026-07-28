# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew build                  # Full build (compile + lint + tests)
./gradlew test                   # JVM unit tests (src/test)
./gradlew connectedAndroidTest   # Instrumented tests (src/androidTest; needs device/emulator)
./gradlew lint                   # Android Lint
```

Run a single unit test:

```bash
./gradlew test --tests "com.example.orbis_optimizedresponsiblebrowsinginterventionsystem.ExampleUnitTest"
```

## Current state

**This repo is an unmodified Android Studio "Empty Activity" (Compose) template.** `MainActivity.kt` is the stock Greeting composable; `AndroidManifest.xml` declares only the launcher Activity — no permissions, services, or receivers. None of the ORBIS functionality below exists yet. Expect greenfield work; do not search for modules that aren't there.

Update this file as real structure lands.

### Build setup

- Single module (`:app`), Kotlin + Jetpack Compose
- Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, Compose BOM 2026.02.01
- compileSdk/targetSdk 36, **minSdk 24**, Java 11 compatibility
- Package root: `com.example.orbis_optimizedresponsiblebrowsinginterventionsystem`
- `gradle/libs.versions.toml` is the source of truth for dependency versions — add deps there, not inline
- Release build type sets `optimization { enable = false }` (minification explicitly off — non-default)

### Known deviations to resolve in Phase 0

- **minSdk must be raised 24 → 26.** API 26 is the required floor for `VpnService`, notification channels, and the modern permission APIs this project depends on.
- The `com.example.` package prefix is template default. Renaming is cheap now and painful once the VPN service and manifest entries exist — decide before Phase 2.

## What ORBIS is

A personal digital-wellbeing Android app: it measures which social apps the user actually overuses, applies proportional network-level friction to those apps, and redirects the reclaimed time toward a positively-framed dashboard and a small real-world good-deed challenge.

MVP scope is five pieces:

1. **Usage tracking** — per-app daily foreground time via `UsageStatsManager`
2. **Usage profile** — rank Instagram / YouTube / Snapchat by time spent; drives both the dashboard and throttle intensity
3. **VPN throttle engine** — local `VpnService` that delays traffic to target domains; everything else passes untouched
4. **Dashboard** — reclaimed time today/this week, simple trend chart, positive framing only
5. **Good deed challenge** — periodic prompt, in-app camera capture, local log with streak

## Hard invariants

These are design intent, not implementation detail. Do not relax them without asking.

- **WhatsApp is never throttled.** It's a communication tool, not passive-scroll content. This is a dedicated negative test case, not an afterthought.
- **Only Instagram, YouTube, and Snapchat are targeted**, at whole-app level. Reels/Shorts-specific targeting is deliberately deferred (domain-level filtering can't see encrypted request paths) and belongs in Future Scope, not v1.
- **Entirely on-device.** All data local; no server component, no cloud storage, no sync.
- **Dashboard copy is encouraging, never shame-based.** Restriction alone gets uninstalled; the positive redirect is the whole thesis.
- **The TUN interface must be released on stop.** A VPN service that leaks its interface throttles the user's phone after the app is closed.
- **Keep the throttle delay modest in development** (a few hundred ms). Cranking it up to make a demo obvious makes the app feel broken instead of intentional.

## Target architecture

```
UsageStatsManager → UsageRepository → UsageProfile
                                          |
                        +-----------------+-----------------+
                        v                                   v
                  ThrottleEngine                    DashboardViewModel
                        |                                   |
                        v                                   v
              OrbisVpnService (VpnService)           Compose UI (stats)

GoodDeedScheduler (WorkManager) → Notification → CameraCapture
    → GoodDeedRepository (Room) → Dashboard
```

- **UsageRepository** — wraps `UsageStatsManager`, exposes daily/weekly per-app time
- **ThrottleEngine** — plain Kotlin; takes a `UsageProfile`, produces throttle rules (which domains, how much delay)
- **OrbisVpnService** — extends `android.net.VpnService`; owns the TUN interface, matches destinations against the domain list, forwards immediately or after a delay
- **GoodDeedScheduler** — `WorkManager` periodic job firing a notification
- All persistence in a single Room database

**Testability constraint:** keep the ranking algorithm and domain-matching logic as pure Kotlin, free of Android framework types, so they're unit-testable in `src/test`. The test plan depends on this.

### Room entities

| Entity | Fields |
|---|---|
| `UsageLog` | `id`, `app`, `date` (ISO), `durationMillis` — one row per app per day |
| `ThrottleRule` | `app` (PK), `domains` (comma-separated), `delayMillis`, `enabled` |
| `GoodDeedEntry` | `id`, `timestamp`, `photoPath`, `note`, `completed` |

### Permissions

| Permission | Notes |
|---|---|
| `PACKAGE_USAGE_STATS` | Special permission — deep-link to `Settings.ACTION_USAGE_ACCESS_SETTINGS`, not a runtime request. Most likely to trip you up; test first. |
| VPN consent | Not a manifest permission — triggered by `VpnService.prepare()`. Declare the service with `BIND_VPN_SERVICE`. |
| `POST_NOTIFICATIONS` | Runtime, Android 13+ |
| `CAMERA` | Standard runtime permission |

Save good-deed photos to app-private storage (`context.filesDir` / `getExternalFilesDir(null)`), never shared storage — this avoids broad storage permissions entirely.

`INTERNET` is likely **not** needed: the VPN service intercepts other apps' traffic, it doesn't make calls of its own.

## Build phases

Build in this order. Verify each "Done when" before moving on. Commit after every phase — if an agent run breaks Phase 3, you want a clean revert to end-of-Phase-2.

| Phase | Focus | Done when |
|---|---|---|
| 0 | Project setup (deps, minSdk bump) | Builds and installs on an emulator with zero errors |
| 1 | Usage tracking + usage-access deep link | A minute of Instagram use is correctly logged |
| 2 | VPN throttle engine | Instagram is measurably slower than WhatsApp, same network, VPN active |
| 3 | Usage profile + adaptive intensity | Highest-usage app gets the strongest throttle, across two usage patterns |
| 4 | Dashboard | Reflects real logged data, not placeholders |
| 5 | Good deed challenge | Full loop — notification → photo → saved entry → dashboard — works end to end |

Phase 2 approach: domain matching **only** first, no delay, confirm connections are attributed to the right app. Add delay logic after. Explicitly test that WhatsApp traffic is never touched.

## Working agreements

- **Phases 2 and 3 are plan-first.** Present a plan and pause for review before writing the VPN service or throttling logic — these are the highest-risk parts and fail at runtime, not compile time.
- **Stop and confirm before modifying** anything touching permissions, `OrbisVpnService`, or `AndroidManifest.xml`.
- Review generated code for Phases 2–3 by hand. Throttling everything instead of just target domains, or failing to clean up the TUN interface, is the kind of bug that works in a demo and then throttles WhatsApp mid-viva.
- Prefer a real device for the usage-stats permission flow and VPN consent dialog — both render inconsistently on emulators.
- If Gradle sync breaks after a change, check `gradle/libs.versions.toml` version alignment first.
