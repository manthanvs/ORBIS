# 🛡️ ORBIS — Optimized Responsible Browsing & Intervention System

> A personal digital-wellbeing Android app that measures which social apps you overuse, applies proportional network-level friction to short-form video feeds, and hands control back through an earn-back loop that buys your feed to full speed.

<p align="center">
  <img src="https://img.shields.io/badge/Android-26%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Java-11-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" />
  <img src="https://img.shields.io/badge/Android_Views-XML_Layouts-4285F4?style=for-the-badge&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Room-Database_v5-FF6F00?style=for-the-badge&logo=sqlite&logoColor=white" />
  <img src="https://img.shields.io/badge/VPN_Service-Local_Tunnel-E91E63?style=for-the-badge&logo=wireguard&logoColor=white" />
</p>

---

## 📌 What Is ORBIS?

ORBIS doesn't block apps — it **slows down only the addictive short-form video feeds** (Instagram Reels, YouTube Shorts, Snapchat Spotlight) while leaving everything else at full speed. Stories, DMs, long-form videos, and chats are completely untouched. The friction scales with your actual daily usage: the more you scroll, the harder it pushes back.

And it never takes the choice away. Fifteen minutes away from the feed buys five minutes at full speed, because losing an option provokes more pushback than the habit does.

Everything runs **entirely on-device**. No servers, no cloud, no data leaves your phone.

---

## ✨ Key Features

### 📊 Usage Tracking
- ⏱️ Per-app daily foreground time via `UsageStatsManager`
- 📈 Usage profiling — ranks Instagram / YouTube / Snapchat by actual time spent
- 🔄 Real usage drives both the dashboard and the friction level
- 🧩 **Finds the build you actually use** — modded clients (InstaPro, Morphe) are discovered at runtime, not hardcoded

### 🔍 Surface Detection
- 🎯 `AccessibilityService` identifies **which screen** is active inside each app
- 🎬 Detects Reels, Shorts & Spotlight by resource ID and content description
- 👁️ Only counts nodes where `isVisibleToUser` is true — Instagram keeps the Reels pager alive *behind* Stories, so an unfiltered read throttles the wrong screen
- ✅ Fails safe — unrecognised screens are **never** throttled
- 🌐 Browser coverage — `youtube.com/shorts` in Chrome/Edge is caught by reading the address bar

### 🌐 VPN Throttle Engine
- 🔒 Local `VpnService` tunnel — no external servers involved
- 🫀 **Friction pulses, it never cuts off** — the feed's downloads are squeezed to a trickle for a moment, then released
- 🎯 **Routes one app at a time** — only the app whose feed is on screen enters the tunnel, so watching Reels never degrades YouTube or your browser
- 🚚 **TCP is carried, not dropped** — a loopback `CONNECT` proxy paces the download so TCP's own flow control does the slowing
- 💬 **WhatsApp is never throttled** — enforced by the OS and observable in the tunnel's `Uids:` set

### 📋 Dashboard
- 📊 Reclaimed time today & this week against **your own** baseline, not anyone else's
- 📉 7-day trend, drawn as seven views rather than a chart dependency
- 🌱 Encouraging copy only — no shame, no guilt, just progress

### 🎟️ Earn-Back Loop
- ⏳ A **verified** focus session: 15 minutes without opening a feed buys 5 minutes of clear time
- 🔍 Verified, not promised — opening a feed ends the session, because detection already knows
- 🧾 Capped at 30 minutes a day and **expires nightly** — a bankable balance turns a daily trade into a savings account
- 🔔 Pays out via WorkManager even if ORBIS was swiped away

---

## 🧠 How It Works

ORBIS uses a **three-layer detection + throttle pipeline**:

1. **Usage Layer** — `UsageStatsManager` tracks how long you spend in each target app
2. **Surface Layer** — `AccessibilityService` asks the active screen for a handful of specific view IDs to tell Reels from Stories, Shorts from long-form, Spotlight from chats
3. **Throttle Layer** — a local `VpnService` tunnel squeezes download bandwidth in a pulse, **only while** a short-form surface is actually on screen, and **only for that one app**

```
UsageStatsManager → UsageRepository → UsageProfile
                                          |
                        +-----------------+-----------------+
                        v                                   v
                  ThrottleEngine                       HomeFragment
                        |                                   |
                        v                                   v
              OrbisVpnService (VpnService)            View UI (stats)

FocusSession → FocusSessionWorker (WorkManager) → Notification
    → EarnRepository (Room) → ClearTimeHolder → the gate
```

### The Pulse

A constant delay could not be felt: added latency barely dents a flowing video stream, and these apps pre-load the next reel. So ORBIS squeezes the **download** instead, in a 3-second cycle whose shape is picked from today's short-form minutes:

| Level | From | Squeezed | Gap ceiling |
|---|---|---|---|
| 1 · Nudge | 0 min | 0.9 s of every 3 s | 600 KB/s |
| 2 · Drag | 20 min | 1.2 s | 400 KB/s |
| 3 · Stutter | 45 min | 1.5 s | 250 KB/s |
| 4 · Grind | 90 min | 1.8 s | 150 KB/s |
| 5 · Immune | 150 min | 2.8 s | 24 KB/s |

Those thresholds are **effective** minutes, not real ones — see the ramp below.

During a squeeze the download is held to a ~8 KB/s trickle — **never zero**, at any level. A feed that feels broken gets the app uninstalled; a feed that stutters gets noticed. The gaps between squeezes are capped too: left at full speed, Instagram pulled a whole reel in a 2-second gap and played straight through the next squeeze.

### The daily buffer, and the weekly ramp

Every day hands the buffer back: at midnight everyone returns to **level 1**, whatever last week looked like. What the week sets is not where you start but **how fast you climb** — its own daily average, run through the same ladder, becomes a multiplier on today's minutes:

| Last 7 days | Ramp | Reaches Immune after |
|---|---|---|
| under 20 min/day | ×1 | 150 min today |
| 20–45 | ×2 | 75 min |
| 45–90 | ×3 | 50 min |
| 90–150 | ×4 | 38 min |
| 150+ | ×5 | 30 min |

So a settled two-hour-a-day habit still gets its quiet morning, then climbs out of it four times as quickly; a light week barely climbs at all. The levels are always walked in order — the friction ramps up gradually rather than landing on Immune at once.

It is not a ratchet. The ramp is recomputed from the trailing week every refresh, so a genuinely lighter week slows the climb back down on its own. Today still counts unaided, so a quiet week can't hold a heavy day down. And the week deliberately **excludes today** — minutes that raised their own ramp would compound, and one long morning would hit Immune by accident rather than by pattern.

---

## 🏗️ Architecture

```
com.orbis.app/
├── usage/      # TargetApp, UsageProfile, ForegroundTimeCalculator, UsageStatsSource, VariantDiscovery
├── surface/    # SurfaceDetector (pure), OrbisAccessibilityService, SurfaceMonitor, BrowserPackages
├── throttle/   # ThrottleEngine, ThrottleLevel, Friction, TokenBucket (all pure), TcpFallback, ThrottleSettings
├── vpn/        # Ipv4/Ipv6 packet build+parse (pure), OrbisVpnService (UDP relay), TcpProxy (TCP relay)
├── data/       # UsageLog, ClearTimeEntry, DAOs, OrbisDatabase (v5), UsageRepository
├── dashboard/  # ReclaimedTime (pure, unit-tested)
├── earn/       # ClearTime, EarnStreak (pure), FocusSession, FocusSessionWorker, EarnRepository
├── ui/         # HomeFragment, EarnFragment, ControlsFragment, AboutFragment, AppUsageAdapter
├── util/       # StateHolder (volatile + LiveData), Background (two-thread IO pool)
└── MainActivity.java   # Entry point, bottom navigation, permission and VPN-consent flows
```

The decision logic — ranking, level selection, surface detection, packet building, the clear-time economics — is **free of Android framework types**, so it runs under plain JUnit on the JVM. That's what the **193 unit tests** cover.

### Room Entities

| Entity | Fields |
|---|---|
| `UsageLog` | `id`, `app`, `date` (ISO), `durationMillis` — one row per app per day |
| `ClearTimeEntry` | `id`, `timestampMillis`, `date` (ISO), `action`, `earnedMillis`, `spentMillis` |

Database is at **version 5** with four hand-written migrations and **no** `fallbackToDestructiveMigration` — the usage history is what the dashboard's baseline is computed from, so wiping it would silently reset "reclaimed time" to "still learning".

---

## 🛠️ Tech Stack

| Category | Technologies |
|---|---|
| **Language** | Java 11 — no Kotlin anywhere, build scripts included |
| **UI** | Android Views + XML layouts, Fragments, `BottomNavigationView`, `RecyclerView` |
| **State** | `StateHolder` (volatile field + `LiveData`) — no ViewModels |
| **Concurrency** | `ExecutorService` (two daemon threads) |
| **Database** | Room + `annotationProcessor` |
| **Preferences** | `SharedPreferences` |
| **Background** | WorkManager |
| **Build** | Gradle 9.4.1 (Groovy DSL), AGP 9.2.1 |
| **Min SDK** | 26 (Android 8.0) |
| **Target SDK** | 36 |

> **Why Views and not Compose?** Jetpack Compose has no Java API. The UI is XML and Views so that the whole project is one language.

---

## 🚀 Getting Started

### Prerequisites
- Android Studio (latest stable)
- Android device running **Android 8.0+** (API 26)
- Java 21 (bundled with Android Studio)

### Build & Install

```bash
# Clone the repository
git clone https://github.com/manthanvs/ORBIS.git
cd ORBIS

# Set JAVA_HOME (Windows PowerShell)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

### First Run

Three things have to be switched on, and ORBIS walks you through them in order — none can be granted from inside the app:

1. **Usage access** — Settings → Special app access → Usage access
2. **Surface detection** — Settings → Accessibility → Downloaded apps → *ORBIS surface detection*
3. **Slowing** — the in-app switch, which raises Android's own VPN consent dialog

> ⚠️ **After reinstalling over an existing copy**, the accessibility service stays listed but is **not re-bound** — ORBIS will look enabled while detecting nothing. Toggle it off and on again in Settings. `adb logcat -d -s OrbisSurface:V` prints `connected; observing=13` once it has actually bound.

### Run Tests

```bash
./gradlew test                   # 193 JVM unit tests
./gradlew connectedAndroidTest   # Instrumented tests (requires device)
./gradlew lint                   # Android Lint
```

---

## 📱 Permissions

| Permission | Purpose |
|---|---|
| `PACKAGE_USAGE_STATS` | Read per-app foreground time (granted in Settings) |
| `BIND_ACCESSIBILITY_SERVICE` | Detect which screen is active inside each app |
| `BIND_VPN_SERVICE` | Local tunnel for network-level throttling |
| `INTERNET` | The relay opens its own sockets to forward packets onward |
| `FOREGROUND_SERVICE` | Keep the tunnel alive when ORBIS is backgrounded |
| `POST_NOTIFICATIONS` | The tunnel's foreground notice and the focus-session payout (Android 13+) |

> **What ORBIS can see:** only the apps named in `accessibility_service_config.xml` — the three short-form apps, their known builds, and seven browsers. **WhatsApp is not among them.** Nothing is uploaded anywhere, because there is nowhere to upload it to.

---

## 🔬 Surface Detection Signals

Measured on CPH2585 (Android 16) via `uiautomator dump` and `dumpsys activity top`:

| App | Throttled Surface | Signal | Left Normal |
|---|---|---|---|
| Instagram | Reels | `clips_viewer_view_pager`, `root_clips_layout` | Stories = `reel_viewer_root` |
| YouTube | Shorts | `reel_watch_fragment_root`, `reel_watch_player` | Long-form watch UI |
| Snapchat | Spotlight | `spotlight_container` | Chats, Stories |
| Browser | Shorts/Reels URLs | URL readable as node text | Any other URL |

> ⚠️ `reel_*` means **opposite things** in Instagram vs YouTube. In Instagram it's Stories (leave alone); in YouTube it's Shorts (throttle). Detection is always scoped to the foreground package first, and IDs are matched exactly — never by substring.

These are unversioned app internals and will break when the apps redesign. The failure is a safe one: detection stops recognising a feed, so ORBIS stops slowing things. It never starts slowing the wrong thing.

---

## 🔒 Design Principles

- 🚫 **WhatsApp is never throttled** — dedicated negative test case, verified in the tunnel's `Uids:` set
- 🎬 **Only short-form video surfaces** are throttled — not the whole app
- 🎯 **One app routed at a time** — a tunnel delays everything it carries
- ✅ **Fails safe to `NORMAL`** — unrecognised screens are never slowed
- 🫀 **Friction pulses, it never cuts off** — never zero bandwidth, never a block
- 🎟️ **The user always holds the lever** — clear time buys the feed back to full speed
- 📱 **Entirely on-device** — all data local, no cloud, no sync
- 🌱 **Positive framing only** — encouraging dashboard copy, never shame-based

---

## 📄 License

This project was built as a university project.

---

## 👤 Author

**Manthan VS**
- GitHub: [@manthanvs](https://github.com/manthanvs)
- LinkedIn: [in/manthanvs](https://www.linkedin.com/in/manthanvs/)
- LeetCode: [manthanvs](https://leetcode.com/u/manthanvs/)
