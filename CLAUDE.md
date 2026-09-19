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
- `com.orbis.app.vpn` — `Ipv4`/`Ipv6` (pure packet parse/build), `OrbisVpnService` (UDP relay), `TunnelStats`.
- `com.orbis.app.data` — `UsageLog`/`UsageLogDao`/`OrbisDatabase`, `DatabaseProvider`, `UsageRepository`.
- `com.orbis.app.dashboard` / `com.orbis.app.deed` — `ReclaimedTime` and `GoodDeedStreak` (both pure and unit-tested), `GoodDeedRepository`, `GoodDeedScheduler`, `DeedPhotoCapture`.
- `com.orbis.app.earn` — `EarnAction`, `ClearTime` (pure, unit-tested), `ClearTimeHolder`, `EarnRepository`.
- `com.orbis.app.ui` — `HomeScreen`/`HomeViewModel`, `ControlsScreen`, `EarnScreen`/`EarnViewModel`, `CaptureSheet`.

Measured behaviour: Reels detected → tunnel up with a usage-scaled delay (400 ms at ~1 h of Instagram), `read 1138 / UDP fwd 1045 / TCP dropped 77`. Browsers are routed too, so `youtube.com/shorts` in Chrome or Edge is throttled.

Re-verified on CPH2585 after the selector rewrite, one Reels session:
`read 486 / UDP fwd 416 / TCP dropped 60 / shed 0 / open flows 0`, delay 126 ms.
`shed 0` means the bounded delay queue never saturated at Reels bitrate, and
`open flows 0` after teardown means every channel was closed. Tunnel comes up
~6 s after Reels appears and drops **3 s** after leaving for an app ORBIS does
not observe — the watchdog, which is the case the old hysteresis never handled.

The WhatsApp invariant is enforced by the OS and observable: while the tunnel is
up, `dumpsys connectivity` shows the ORBIS network's `Uids:` set. WhatsApp's uid
is absent. That is the check to re-run if the routing logic is ever touched.

**The tunnel now routes one app at a time**, not all of them. `Uids:` should show
a *single* uid during automatic throttling — Instagram's while Reels is on
screen, YouTube's during Shorts. Seeing the old five-uid set means something has
re-broadened the routing; see the routing invariant below.

All five phases are built. The database is at **version 4**: `MIGRATION_1_2` adds `good_deed`, `MIGRATION_2_3` reorders `usage_log`'s unique index to lead with `date` and indexes `good_deed`, `MIGRATION_3_4` adds `clear_time` for the earn-back loop. There is deliberately **no `fallbackToDestructiveMigration`** — the usage history in this database is what the dashboard's baseline is computed from, so wiping it would silently destroy real data and reset "reclaimed time" to "still learning".

`ThrottleRule` is still not an entity: throttle intensity is derived from usage at runtime by `ThrottleEngine`, so there is nothing to persist until rules become user-editable.

Update this file as real structure lands.

### UI structure

Four destinations behind a bottom `NavigationBar`, and only the selected one is
composed:

- **Home** (`HomeScreen` / `SimpleHomeScreen`) — live protection status,
  reclaimed-time hero, 7-day chart, today's per-app split, streak teaser.
- **Earn** (`EarnScreen`) — the clear-time balance and the actions that top it
  up. A `LazyColumn`, because the action list grows.
- **Controls** (`ControlsScreen`) — detection state, the auto-throttle switch,
  the bounded tunnel test, and the packet diagnostics.
- **About** (`AboutScreen`) — the explainer.

### The earn-back loop

`ClearTime` is pure and holds the economics; `ClearTimeHolder` is a process-wide
object for the same reason `SurfaceMonitor` is — the accessibility service cannot
be bound to, but it is the only thing that knows a feed is on screen, so it has
to read the balance and charge against it without a repository.

**Spending is metered in memory and flushed every `SPEND_FLUSH_MILLIS` (10 s).**
The gate re-evaluates several times a second while a feed is up, and a row per
tick would be hundreds of writes a minute. Up to 10 s of spending is therefore
lost if the process dies — which *under*-charges the user, the harmless direction
to be wrong in. Do not "fix" this by writing per tick.

`ClearTimeHolder.charge` clamps each tick to `MAX_TICK_MILLIS` (2 s): a longer gap
means the user put the phone down or the service was not scheduled, and billing
the real elapsed time would charge them for time they were not scrolling.

The focus session is verified, not promised: `EarnViewModel` watches
`SurfaceMonitor` and ends the session if a throttled surface appears. That check
is free because detection already runs.

`HomeViewModel` owns both halves of the home screen: `state` for the slow-moving
numbers and `protection` for the live ones. They are separate because the tunnel
publishes counters several times a second, and `protection` maps those away
before `distinctUntilChanged` so the home screen never wakes for traffic it does
not display. **The packet counters are collected in `ControlsRoute` and nowhere
else** — collecting them at the top of the composition, as the old tab layout
did, recomposed every screen once a second.

`OrbisTheme` uses a fixed ORBIS palette with `dynamicColor = false`. Material You
would repaint the app in wallpaper colours, which can land on reds that make an
encouraging dashboard read as a warning.

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

- **Route only the app whose feed is on screen.** A tunnel delays everything it carries and cannot tell one app's packets from another's, so routing every target app at once meant watching Reels also degraded YouTube, Snapchat and all seven browsers — and dropped their TCP. `ThrottleEngine.routeFor` names the single package; `OrbisVpnService` rebuilds the interface when it changes, because Android fixes the allow-list at `establish()` time. The full set in `routedPackages()` is the upper bound ORBIS will *ever* route, and only the manual diagnostic uses it.

- **A tunnel nothing will take down must not exist.** The automatic path is torn down by the accessibility watchdog. The manual diagnostic on Controls has no watchdog — auto-throttle is off by default, so the gate returns early and never arms one — so it carries a hard `EXTRA_AUTO_STOP_MILLIS` stop instead. It used to stay up until the user remembered it, dropping every routed app's TCP the whole time.
- **Detection fails safe to `NORMAL`.** An unrecognised screen is never throttled. A false positive slows something the user asked to keep fast, which is worse than missing a Reel.
- **Match view ids exactly and scope them per package.** Never substring-match: `reel_*` is Stories in Instagram but Shorts in YouTube, and Snapchat's nav bar carries `ngs_spotlight_icon_container` on every screen.
- **Entirely on-device.** All data local; no server component, no cloud storage, no sync.
- **Dashboard copy is encouraging, never shame-based.** Restriction alone gets uninstalled; the positive redirect is the whole thesis.

- **The user always holds the lever.** ORBIS slows a feed; it never blocks one, and clear time earned through `EarnAction` buys it back to full speed. Anything that removes the option — a hard block, a lockout, a penalty that cannot be worked off — breaks the thesis, because losing an option provokes more pushback than the habit does. Credit **expires nightly** and is **capped daily**: a bankable balance turns a daily trade into a savings account, and an uncapped one rewards whoever grinds hardest.
- **The TUN interface must be released on stop.** A VPN service that leaks its interface throttles the user's phone after the app is closed.
- **Keep the throttle delay modest in development** (a few hundred ms). Cranking it up to make a demo obvious makes the app feel broken instead of intentional.

## Performance invariants

Both hot paths were rewritten because the original shapes degraded the whole
device, not just ORBIS. Don't reintroduce either.

### The relay is three threads, not one per flow

`OrbisVpnService` runs exactly **orbis-tun** (reads the TUN), **orbis-select**
(one `Selector` over every flow's `DatagramChannel`; the only thread that writes
back into the tunnel, so no lock is needed) and **orbis-delay** (drains the
`DelayQueue`). It previously ran a thread and a blocking `DatagramSocket` per
5-tuple with no eviction — and browsers are routed, so every DNS lookup and CDN
connection opened another. Flows are now evicted after 30 s idle and capped at
`MAX_FLOWS`.

Other things in there that look removable and are not:

- **Packet counters are `AtomicLong`.** They are incremented from all three
  threads; `@Volatile var Long` gave visibility without atomicity and silently
  undercounted.
- **Nothing closes the tunnel's `FileInputStream`/`FileOutputStream`.** Both wrap
  the *same* fd, which `ParcelFileDescriptor.close()` owns. Closing it three
  times can yank an fd number another thread has already been given.
- **The delay queue is a fixed pool, and a full pool drops the packet.** That is
  the back-pressure and the memory ceiling. An unbounded `ScheduledThreadPool`
  queue holds `bitrate × delay` packets alive.
- **`buildUdpInto`/`parseUdpInto` write into reused buffers**, so every header
  byte including the zeroed ones and the checksum field must be written
  explicitly — the buffer still holds the previous packet on entry. `PacketBufferTest`
  covers exactly this.

### Detection asks for ids; it does not walk the tree

`collectSignals` calls `findAccessibilityNodeInfosByViewId` for the handful of
ids `SurfaceDetector.candidateIdsFor` names, and returns at the first visible
hit. Every node accessor is a binder call into the observed app, and the old
breadth-first walk of up to 600 nodes made roughly **ten thousand of them per
second** during playback, on the main thread. Browsers read the address bar by
id (`BrowserPackages.URL_BAR_IDS`), falling back to a bounded walk only when the
toolbar is hidden.

`isVisibleToUser` is still the load-bearing check — see the survey below.

Nodes are recycled below API 33 (`recycleCompat`); the old walk leaked all of
them.

### Tunnel lifecycle runs on its own thread

`establish()` is a binder round trip and `shutdown()` joins three threads, so
neither belongs on the main thread — and re-pointing the tunnel at a different
app is a shutdown immediately followed by a start, which must not interleave with
another request to do the same. Every start and stop is posted to
**orbis-vpn-lifecycle** and runs one at a time; `start`/`shutdown` are
`@Synchronized` because `onDestroy` and `onRevoke` still arrive on the main
thread.

`onStartCommand` reads its extras *before* posting: the `Intent` is recycled once
it returns, so the lifecycle thread must never be handed the object itself.

Changing only the delay does **not** rebuild the tunnel — it is adopted in place.
That is what lets the throttle re-scale across a long session instead of staying
frozen at whatever usage said when the feed first appeared.

### The tunnel comes down on a watchdog, not on the next event

`STOP_GRACE_MILLIS` hysteresis alone never expires once the user leaves the
observed apps, because no further events arrive — and that is exactly when the
tunnel must drop. `scheduleWatchdog()` keeps a teardown check pending for as long
as the tunnel is up, and `onDestroy` stops the tunnel outright: with detection
gone, nothing else would ever take it down.

**The watchdog re-reads the screen before it decides.** A video playing steadily
sends *no* accessibility events. Measured on CPH2585: a YouTube Short left
playing lost its throttle ~9 s in, because the last event aged past the grace
period with the Short still on screen. So `watchdog.run()` calls `evaluate()`
first. It keeps running while the tunnel is up *or* a feed was seen within the
grace period — the latter is what meters clear time through a long video. It is
keyed on `lastFeedSeenMillis`, not `SurfaceMonitor`, because an unreadable
screen leaves the monitor stale and the loop would never end.

**Screen-off stands everything down.** Locking the phone on Reels leaves Reels as
the foreground surface, so without the `ACTION_SCREEN_OFF` receiver the tunnel
stayed up all night, dropping the app's TCP. The unlock's own window events
raise it again within a second.

### One repository, one query

`UsageRepository.shared(context)` is process-wide and caches today's profile for
`CACHE_TTL_MILLIS`. Each ViewModel used to construct its own, so a single resume
ran two full-day `queryEvents` scans concurrently and wrote the same four rows
twice. `UsageStatsSource.eventsBetween` also takes a package filter — the raw
stream carries every app on the device.

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

### Modded clients are the real apps on real phones

On the test phone the user's Instagram was **InstaPro** (`com.instapro2.android`,
28 h in a month) and their YouTube was **Morphe** (`app.morphe.android.youtube`,
6 h 47 m in a week). The official apps were barely opened, so an ORBIS that knew
only official package names did almost nothing. Both carry the official view ids
**under their own package name** — `app.morphe.android.youtube:id/reel_watch_player`
— so they are `TargetApp.variants`, qualified with their own package everywhere.

To check a new one before adding it, pull the APK and read its resource table:

```bash
adb pull $(adb shell pm path <pkg> | grep base | cut -d: -f2) v.apk
aapt2 dump resources v.apk | grep -E "^Package name|id/(clips_viewer_view_pager|reel_watch_player)"
```

Find which build a user actually uses from `dumpsys usagestats` weekly totals,
not from `pm list packages` — see the hidden-app note below.

**ColorOS "hidden apps" are invisible to `pm list packages`** (`hidden=true` in
`dumpsys package`) and cannot be launched by `monkey`/`am start`, but everything
ORBIS needs still works for them, measured: usage stats record them,
accessibility events arrive, and `addAllowedApplication` routes them — InstaPro's
appId 10423 appeared in the tunnel's `Uids:`. Testing one needs the user to open
it by hand.

The `Uids:` set shows each routed app twice, e.g. `10460-10460, 20460-20460`: the
second is the app's **SDK sandbox** (appId + 10000), which Android routes with it.
That is still one app.

The tunnel's network has **no DNS servers** (`DnsAddresses: [ ]`) and the phone
runs **strict Private DNS** (DNS-over-TLS, i.e. TCP, which the relay drops). It
works anyway — new reels and Shorts, ads included, loaded under the tunnel —
but it is the first place to look if a routed app ever fails to resolve.

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
| `BIND_ACCESSIBILITY_SERVICE` | Done (Phase 2a). `flagReportViewIds` is mandatory or `viewIdResourceName` is always null and every rule silently stops matching. `packageNames` in the config is the privacy boundary. **adb can enable it** on this build — see the note below. |
| VPN consent | Not a manifest permission — triggered by `VpnService.prepare()`. Declare the service with `BIND_VPN_SERVICE`. |
| `POST_NOTIFICATIONS` | Runtime, Android 13+ |
| `CAMERA` | Standard runtime permission |

Save good-deed photos to app-private storage (`context.filesDir` / `getExternalFilesDir(null)`), never shared storage — this avoids broad storage permissions entirely.

`INTERNET` **is** required — corrected on device. The original reasoning ("the VPN intercepts other apps' traffic, it doesn't make calls of its own") holds for *interception* but not for *relaying*: the relay opens its own sockets to forward packets onward. Without it every `DatagramSocket()` throws `EPERM (Operation not permitted)`, the relay thread dies on its first packet, and the tunnel becomes a black hole that silently eats all traffic from the apps it captures — with no crash and nothing in logcat.

### What adb can and cannot do on CPH2585 (Android 16, ColorOS)

Measured, because the earlier note here was wrong in one direction and right in
another:

- **Enabling the accessibility service from adb works.** Append — never
  overwrite, or you silently disable whatever the user already had on:

  ```bash
  EXISTING=$(adb shell settings get secure enabled_accessibility_services)
  adb shell settings put secure enabled_accessibility_services \
    "$EXISTING:com.orbis.app/com.orbis.app.surface.OrbisAccessibilityService"
  adb shell settings put secure accessibility_enabled 1
  ```

  A reinstall drops ORBIS back out of that list, so re-apply it after
  `connectedAndroidTest`.

- **`appops set` and `pm clear` are blocked** — both throw `SecurityException`
  (`MANAGE_APP_OPS_MODES` / `CLEAR_APP_USER_DATA`). So usage access cannot be
  granted from the shell here, and app data cannot be wiped that way. To reset
  the database, pull it with `run-as`, edit it, and push it back.

- **There is no `sqlite3` binary on the device.** Same workaround:
  `adb exec-out run-as com.orbis.app cat databases/orbis.db > local.db`.

- **This app's own logcat tags are dropped.** `Log.i(TAG, …)` from `OrbisVpn`
  and `OrbisSurface` mostly never appears, which is exactly why the packet
  counters are surfaced on the Controls screen instead. Verify the tunnel from
  `dumpsys connectivity` (`grep "InterfaceName: tun0"`, then its `Uids:`), not
  from logcat. `ip link show tun0` returns nothing to the shell user here.

- **Do not run `connectedAndroidTest` on a phone someone uses.** It uninstalls
  ORBIS afterwards, taking the database and every permission grant with it.
  Compile the source set instead: `./gradlew compileDebugAndroidTestKotlin`.

- **`install -r` keeps everything**: usage access, VPN consent, the enabled
  accessibility service and the database all survived updates in testing.
  `allowBackup=true` also means a *fresh* install restores the last Google
  backup — the first install on this phone came up with rows from July.

- **A deep link straight to ORBIS's accessibility page is impossible.**
  `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` needs
  `OPEN_ACCESSIBILITY_DETAILS_SETTINGS`, which is signature-only. ColorOS files
  the service under *Accessibility → Downloaded apps*, and the onboarding copy
  says so.

- **ColorOS switches on a floating accessibility shortcut button** for ORBIS
  when the service is enabled (`accessibility_button_targets`), although the
  config never requests one. It is the user's setting to turn off, under the
  service's own page.

- **Swiping ORBIS from recents does not kill it**: the bound accessibility
  service keeps the process alive, `stopped=false`, and a running focus session
  survives.

### Usage-access gotchas (learned the hard way)

- **`MODE_DEFAULT` is not "denied".** The app-op check returns `MODE_ALLOWED`, `MODE_IGNORED`, or `MODE_DEFAULT`, and the last means "defer to the permission". Treating it as denied strands the user on the grant prompt forever, even after they grant access. `UsageAccess.isGranted` falls back to `checkPermission` for that case — don't "simplify" it away.
- **Re-check on resume, never cache.** Access is granted in Settings, outside the app. `MainActivity` uses `LifecycleResumeEffect` for this.
- **`connectedAndroidTest` reinstalls the APK, which clears app-ops.** Granting with `adb shell appops set ... allow` beforehand therefore does nothing for instrumented tests. The tests grant it themselves via `uiAutomation.executeShellCommand`.
- Emulators have none of the four target apps installed, so a correct build legitimately shows "nothing tracked". Don't chase that as a bug — verify on a real device.
- **Pair resumes and pauses per activity, never per package.** Real apps resume two activities at once and pause them out of order (InstaPro's `LauncherActivity` + `PinLockActivity`, Morphe's link trampoline). One session per package closed on the first pause and credited the second "from the window start" — midnight — so the dashboard read *19 h of YouTube* at 19:30, and the throttle sat at maximum all day. An unmatched pause counts from the window start only as a package's *first* event, and no package is credited more than the window.
- **`dumpsys usagestats` daily totals are not a midnight-to-now reference.** The in-memory "daily" bucket on this phone covered 1:35–7:35 pm. Read its `timeRange` before comparing it with ORBIS, which counts from local midnight.

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
| 6 | ~~Surface-scoped routing~~ | **Done** — one app routed at a time, delay re-scales in place, manual tunnel self-stops after 30 s, browser short-video scales with the heaviest short-form app instead of being pinned at 120 ms |
| 4 | ~~Dashboard~~ | **Done** — reclaimed time vs the user's own baseline, 7-day trend |
| 5 | ~~Good deed challenge~~ | **Done** — WorkManager prompt → camera → Room entry → streak. The full loop still wants one manual run-through on a device. |

Phase 2 approach: domain matching **only** first, no delay, confirm connections are attributed to the right app. Add delay logic after. Explicitly test that WhatsApp traffic is never touched.

## Working agreements

- **Phases 2 and 3 are plan-first.** Present a plan and pause for review before writing the VPN service or throttling logic — these are the highest-risk parts and fail at runtime, not compile time.
- **Stop and confirm before modifying** anything touching permissions, `OrbisVpnService`, or `AndroidManifest.xml`.
- Review generated code for Phases 2–3 by hand. Throttling everything instead of just target domains, or failing to clean up the TUN interface, is the kind of bug that works in a demo and then throttles WhatsApp mid-viva.
- Prefer a real device for the usage-stats permission flow and VPN consent dialog — both render inconsistently on emulators.
- If Gradle sync breaks after a change, check `gradle/libs.versions.toml` version alignment first.
