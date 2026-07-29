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
./gradlew test --tests "com.orbis.app.ExampleUnitTest"
```

### CLI builds need JAVA_HOME set

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

`gradle/gradle-daemon-jvm.properties` pins the daemon to `toolchainVersion=21`. With `JAVA_HOME` unset, Gradle matches that against *any* detected Java 21 — including JRE-only runtimes bundled with IDE extensions. Those ship no `jlink`, so AGP's `JdkImageTransform` dies with `jlink executable ... does not exist`. If you see that, set `JAVA_HOME` as above **and** run `./gradlew --stop`, because a daemon that already resolved the bad JVM will keep reusing it. Android Studio is unaffected — it uses its own Gradle JDK setting.

## Current state

**Phases 0, 1 and 2 are done and verified on device (CPH2585, Android 16).** Usage is tracked, the on-screen surface is detected, and Reels/Shorts/Spotlight traffic is genuinely slowed while Stories, DMs and everything else are untouched.

What exists:

- `com.orbis.app.usage` — `TargetApp`, `ForegroundTimeCalculator`, `UsageProfile`, `DurationFormatter`, `UsageProfileHolder`; plus `UsageStatsSource` and `UsageAccess`.
- `com.orbis.app.surface` — `SurfaceDetector` (pure), `OrbisAccessibilityService`, `SurfaceMonitor`, `AccessibilityAccess`, `BrowserPackages`.
- `com.orbis.app.throttle` — `ThrottleEngine` (pure, usage-scaled delay), `ThrottleSettings` (DataStore-backed).
- `com.orbis.app.vpn` — `Ipv4`/`Ipv6` (pure packet parse/build), `OrbisVpnService` (UDP relay).
- `com.orbis.app.data` — `UsageLog`/`UsageLogDao`/`OrbisDatabase`, `DatabaseProvider`, `UsageRepository`.
- `com.orbis.app.ui` — `UsageViewModel`, `UsageScreen`.

Measured behaviour: Reels detected → tunnel up with a usage-scaled delay (400 ms at ~1 h of Instagram), `read 1138 / UDP fwd 1045 / TCP dropped 77`. Browsers are routed too, so `youtube.com/shorts` in Chrome or Edge is throttled.

- `com.orbis.app.dashboard` / `com.orbis.app.deed` — `ReclaimedTime` and `GoodDeedStreak` (both pure and unit-tested), `GoodDeedRepository`, `GoodDeedScheduler`, `DeedPhotoCapture`.

All five phases are built. The database is at **version 2**; `MIGRATION_1_2` adds `good_deed`. There is deliberately **no `fallbackToDestructiveMigration`** — the usage history in this database is what the dashboard's baseline is computed from, so wiping it would silently destroy real data and reset "reclaimed time" to "still learning".

`ThrottleRule` is still not an entity: throttle intensity is derived from usage at runtime by `ThrottleEngine`, so there is nothing to persist until rules become user-editable.

Update this file as real structure lands.

### Build setup

- Single module (`:app`), Kotlin + Jetpack Compose
- Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, Compose BOM 2026.02.01
- compileSdk/targetSdk 36, **minSdk 26**, Java 11 compatibility
- Package root: `com.orbis.app`
- `gradle/libs.versions.toml` is the source of truth for dependency versions — add deps there, not inline
- Release build type sets `optimization { enable = false }` (minification explicitly off — non-default)
- **AGP 9 built-in Kotlin support**: there is no `org.jetbrains.kotlin.android` plugin. Only the Android, Compose-compiler, and KSP plugins are applied. Don't add `kotlin-android` "to fix" something — it isn't missing.
- KSP is pinned to an exact Kotlin build (`2.2.10-2.0.2`). Bump it and `kotlin` together or the build breaks.
- `android.disallowKotlinSourceSets=false` in `gradle.properties` is **load-bearing**. Built-in Kotlin rejects the `kotlin.sourceSets` DSL that KSP uses to register its generated sources, so removing this flag breaks Room with `Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin`. It's a suppression, not a fix — drop it once a KSP release supports AGP 9 natively.

### Don't bump AndroidX versions blindly

Only `android-36.1` is installed locally, so compileSdk is 36. Every AAR declares a `minCompileSdk` and the build hard-fails at `checkDebugAarMetadata` if it exceeds ours. Already hit: **core-ktx 1.19.0** and **lifecycle 2.11.0** require compileSdk 37 — hence the pins at 1.18.0 and 2.10.0.

Note that Google's `maven-metadata.xml` `<release>` field often points at an **alpha** (it did for WorkManager, Navigation, and CameraX), so don't read it as "latest stable". To check a version before committing to it, read `minCompileSdk` out of the AAR directly:

```
https://dl.google.com/dl/android/maven2/<group/path>/<ver>/<artifact>-<ver>.aar
  -> META-INF/com/android/build/gradle/aar-metadata.properties
```

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
- **Only short-form video surfaces are throttled**: Instagram Reels, YouTube Shorts, Snapchat Spotlight, and those same feeds opened in a browser. Instagram **Stories**, DMs, the feed, long-form YouTube and Snapchat chats stay at full speed. *(Revised from the original whole-app scope. A VPN alone cannot do this — HTTPS hides the URL path and Reels shares CDN hosts with Stories — so an AccessibilityService supplies the on-screen context and the VPN acts only while gated on. See the survey section for the measured evidence.)*
- **Detection fails safe to `NORMAL`.** An unrecognised screen is never throttled. A false positive slows something the user asked to keep fast, which is worse than missing a Reel.
- **Match view ids exactly and scope them per package.** Never substring-match: `reel_*` is Stories in Instagram but Shorts in YouTube, and Snapchat's nav bar carries `ngs_spotlight_icon_container` on every screen.
- **Entirely on-device.** All data local; no server component, no cloud storage, no sync.
- **Dashboard copy is encouraging, never shame-based.** Restriction alone gets uninstalled; the positive redirect is the whole thesis.
- **The TUN interface must be released on stop.** A VPN service that leaks its interface throttles the user's phone after the app is closed.
- **Keep the throttle delay modest in development** (a few hundred ms). Cranking it up to make a demo obvious makes the app feel broken instead of intentional.

## Surface-detection survey (measured on CPH2585, Android 16)

Captured from live apps via `uiautomator dump` / `dumpsys activity top`. These are
the signals that distinguish a throttled surface from a normal one.

| App | Throttled surface | Signal | Leave normal |
|---|---|---|---|
| Instagram | Reels | `clips_viewer_view_pager`, `root_clips_layout`, `clips_video_container` | Stories = `reel_viewer_root` |
| YouTube | Shorts | `reel_watch_fragment_root`, `reel_watch_player`, `reel_recycler`; content-desc `Shorts` | long-form watch UI |
| Snapchat | Spotlight | `spotlight_container` | chats, Stories |
| Browser | Shorts/Reels URLs | URL is readable as node text, e.g. `m.youtube.com/shorts/<id>` | any other URL |

**`reel_*` means opposite things in the two apps.** In Instagram it is *Stories*
(leave alone); in YouTube it is *Shorts* (throttle). Never match the substring
`reel` across packages — always scope detection to the foreground package first.
Getting this wrong throttles exactly the surface the user asked to keep normal.

**Only count nodes where `isVisibleToUser` is true.** This is not an optimisation,
it is correctness. Instagram keeps the Reels view pager alive in the tree while
Stories is on screen, so an unfiltered walk finds `clips_*` ids during Stories and
reports `REELS` — throttling Stories. Measured on device: filtering cut 43 ids to
13 and flipped the verdict from `REELS` to the correct `NORMAL`.

Note `uiautomator dump` will *not* reveal this trap, because it lists mostly
visible nodes while `getRootInActiveWindow()` also returns cached offscreen ones.
Verify detection from the service's own logs, never from a dump alone.

Other measured facts:

- `instagram://reels` opens **Stories**, not Reels. Do not trust that deep link as a test fixture.
- `https://www.youtube.com/shorts` opened in the **browser** (Edge), not the YouTube app — browser coverage is required for that case, not optional.
- `uiautomator dump` fails with `could not get idle state` while a video plays. That is a limitation of the *tool*, not of an AccessibilityService, which receives pushed events and can call `getRootInActiveWindow()` at any time. Pause playback to capture.
- Instagram/YouTube class and fragment names are obfuscated by R8; only **resource IDs** and **content-descriptions** are usable.
- These IDs are unversioned app internals and will break when the apps redesign. Re-run this survey when detection stops firing.

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
| `PACKAGE_USAGE_STATS` | Done (Phase 1). See the gotcha below. |
| `BIND_ACCESSIBILITY_SERVICE` | Done (Phase 2a). Enabled by the user in Settings only — adb cannot grant it on ColorOS. `flagReportViewIds` is mandatory or `viewIdResourceName` is always null and every rule silently stops matching. `packageNames` in the config is the privacy boundary. |
| VPN consent | Not a manifest permission — triggered by `VpnService.prepare()`. Declare the service with `BIND_VPN_SERVICE`. |
| `POST_NOTIFICATIONS` | Runtime, Android 13+ |
| `CAMERA` | Standard runtime permission |

Save good-deed photos to app-private storage (`context.filesDir` / `getExternalFilesDir(null)`), never shared storage — this avoids broad storage permissions entirely.

`INTERNET` **is** required — corrected on device. The original reasoning ("the VPN intercepts other apps' traffic, it doesn't make calls of its own") holds for *interception* but not for *relaying*: the relay opens its own sockets to forward packets onward. Without it every `DatagramSocket()` throws `EPERM (Operation not permitted)`, the relay thread dies on its first packet, and the tunnel becomes a black hole that silently eats all traffic from the apps it captures — with no crash and nothing in logcat.

### Usage-access gotchas (learned the hard way)

- **`MODE_DEFAULT` is not "denied".** The app-op check returns `MODE_ALLOWED`, `MODE_IGNORED`, or `MODE_DEFAULT`, and the last means "defer to the permission". Treating it as denied strands the user on the grant prompt forever, even after they grant access. `UsageAccess.isGranted` falls back to `checkPermission` for that case — don't "simplify" it away.
- **Re-check on resume, never cache.** Access is granted in Settings, outside the app. `MainActivity` uses `LifecycleResumeEffect` for this.
- **`connectedAndroidTest` reinstalls the APK, which clears app-ops.** Granting with `adb shell appops set ... allow` beforehand therefore does nothing for instrumented tests. The tests grant it themselves via `uiAutomation.executeShellCommand`.
- Emulators have none of the four target apps installed, so a correct build legitimately shows "nothing tracked". Don't chase that as a bug — verify on a real device.

## Build phases

Build in this order. Verify each "Done when" before moving on. Commit after every phase — if an agent run breaks Phase 3, you want a clean revert to end-of-Phase-2.

| Phase | Focus | Done when |
|---|---|---|
| 0 | ~~Project setup (deps, minSdk bump)~~ | **Done** — builds, installs, launches clean |
| 1 | ~~Usage tracking + usage-access deep link~~ | **Done** on emulator; the literal "a minute of Instagram" check still needs a real device with Instagram installed |
| 2a | ~~Surface detection (AccessibilityService)~~ | **Done** — Reels/Shorts/Spotlight/browser-URL detected; Stories reported as NORMAL |
| 2b | ~~VPN passthrough, no delay~~ | **Done** — UDP/QUIC relay carries real traffic (~1 MB/12 s through `tun0`) |
| 2c | ~~Throttle gated on surface~~ | **Done** — tunnel only up during Reels/Shorts/Spotlight, usage-scaled delay (400 ms observed), WhatsApp absent from the tunnel's `Uids:` set. Side-by-side Reels-vs-Stories timing still worth doing if you want a number for the write-up. |
| 3 | ~~Usage profile + adaptive intensity~~ | **Done** — `UsageProfileHolder` feeds real usage into `ThrottleEngine`; delay scales 120 ms → 400 ms with daily use |
| 3 | Usage profile + adaptive intensity | Highest-usage app gets the strongest throttle, across two usage patterns |
| 4 | ~~Dashboard~~ | **Done** — reclaimed time vs the user's own baseline, 7-day trend |
| 5 | ~~Good deed challenge~~ | **Done** — WorkManager prompt → camera → Room entry → streak. The full loop still wants one manual run-through on a device. |

Phase 2 approach: domain matching **only** first, no delay, confirm connections are attributed to the right app. Add delay logic after. Explicitly test that WhatsApp traffic is never touched.

## Working agreements

- **Phases 2 and 3 are plan-first.** Present a plan and pause for review before writing the VPN service or throttling logic — these are the highest-risk parts and fail at runtime, not compile time.
- **Stop and confirm before modifying** anything touching permissions, `OrbisVpnService`, or `AndroidManifest.xml`.
- Review generated code for Phases 2–3 by hand. Throttling everything instead of just target domains, or failing to clean up the TUN interface, is the kind of bug that works in a demo and then throttles WhatsApp mid-viva.
- Prefer a real device for the usage-stats permission flow and VPN consent dialog — both render inconsistently on emulators.
- If Gradle sync breaks after a change, check `gradle/libs.versions.toml` version alignment first.
