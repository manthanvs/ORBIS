# 🛡️ ORBIS — Optimized Responsible Browsing & Intervention System

> A personal digital-wellbeing Android app that measures which social apps you overuse, applies proportional network-level friction to short-form video feeds, and redirects the reclaimed time toward a positively-framed dashboard and real-world good-deed challenges.

<p align="center">
  <img src="https://img.shields.io/badge/Android-26%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-BOM_2026.02-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" />
  <img src="https://img.shields.io/badge/Room-Database-FF6F00?style=for-the-badge&logo=sqlite&logoColor=white" />
  <img src="https://img.shields.io/badge/VPN_Service-Local_Tunnel-E91E63?style=for-the-badge&logo=wireguard&logoColor=white" />
</p>

---

## 📌 What Is ORBIS?

ORBIS doesn't block apps — it **slows down only the addictive short-form video feeds** (Instagram Reels, YouTube Shorts, Snapchat Spotlight) while leaving everything else at full speed. Stories, DMs, long-form videos, and chats are completely untouched. The throttle intensity scales proportionally with your actual daily usage — the more you scroll, the stronger the friction.

Everything runs **entirely on-device**. No servers, no cloud, no data leaves your phone.

---

## ✨ Key Features

### 📊 Usage Tracking
- ⏱️ Per-app daily foreground time via `UsageStatsManager`
- 📈 Usage profiling — ranks Instagram / YouTube / Snapchat by actual time spent
- 🔄 Real-time usage data drives both dashboard insights and throttle intensity

### 🔍 Surface Detection
- 🎯 AccessibilityService identifies **which screen** is active inside each app
- 🎬 Detects Reels, Shorts & Spotlight via resource IDs and content descriptions
- ✅ Fails safe — unrecognised screens are **never** throttled
- 🌐 Browser coverage — `youtube.com/shorts` in Chrome/Edge is also caught

### 🌐 VPN Throttle Engine
- 🔒 Local `VpnService` tunnel — no external servers involved
- ⏳ Usage-scaled delay (120 ms → 400 ms+) applied only during active short-form surfaces
- 🎯 Per-app targeting via `addAllowedApplication` — only monitored apps enter the tunnel
- 💬 **WhatsApp is never throttled** — it's a communication tool, not passive-scroll content

### 📋 Dashboard
- 📊 Reclaimed time today & this week against your own baseline
- 📉 7-day trend visualisation with positive framing
- 🌱 Encouraging copy only — no shame, no guilt, just progress

### 🤝 Good Deed Challenge
- 🔔 WorkManager periodic prompts to do something positive
- 📸 In-app camera capture with local-only photo storage
- 🔥 Streak tracking to build positive habits

---

## 🧠 How It Works

ORBIS uses a **three-layer detection + throttle pipeline**:

1. **Usage Layer** — `UsageStatsManager` tracks how long you spend in each target app
2. **Surface Layer** — `AccessibilityService` inspects the active screen's view hierarchy to distinguish Reels from Stories, Shorts from long-form, Spotlight from chats
3. **Throttle Layer** — a local `VpnService` tunnel introduces a proportional delay to network traffic **only when** a short-form video surface is actively on screen

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

---

## 🏗️ Architecture

```
com.orbis.app/
├── usage/           # UsageStatsManager wrappers, UsageProfile, ForegroundTimeCalculator
├── surface/         # SurfaceDetector (pure), OrbisAccessibilityService, SurfaceMonitor
├── throttle/        # ThrottleEngine (pure, usage-scaled), ThrottleSettings (DataStore)
├── vpn/             # Ipv4/Ipv6 packet parse/build, OrbisVpnService (UDP relay)
├── data/            # UsageLog/UsageLogDao/OrbisDatabase, DatabaseProvider, UsageRepository
├── dashboard/       # ReclaimedTime, GoodDeedStreak (both pure & unit-tested)
├── deed/            # GoodDeedRepository, GoodDeedScheduler, DeedPhotoCapture
├── ui/              # UsageViewModel, UsageScreen (Compose)
└── MainActivity.kt  # Entry point, permission flows, navigation
```

### Room Entities

| Entity | Fields |
|---|---|
| `UsageLog` | `id`, `app`, `date` (ISO), `durationMillis` — one row per app per day |
| `GoodDeedEntry` | `id`, `timestamp`, `photoPath`, `note`, `completed` |

---

## 🛠️ Tech Stack

| Category | Technologies |
|---|---|
| **Language** | Kotlin 2.2 |
| **UI** | Jetpack Compose (BOM 2026.02) |
| **Database** | Room + KSP |
| **Preferences** | DataStore Preferences |
| **Background** | WorkManager |
| **Camera** | CameraX (camera2) |
| **Build** | Gradle 9.4, AGP 9.2 |
| **Min SDK** | 26 (Android 8.0) |
| **Target SDK** | 36 |

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

### Run Tests

```bash
./gradlew test                   # JVM unit tests
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
| `INTERNET` | VPN relay needs sockets to forward packets |
| `FOREGROUND_SERVICE` | Keep the tunnel alive when ORBIS is backgrounded |
| `POST_NOTIFICATIONS` | Good-deed challenge prompts (Android 13+) |
| `CAMERA` | In-app photo capture for good-deed log |

> **Note:** Photos are saved to app-private storage — no broad storage permissions needed.

---

## 🔬 Surface Detection Signals

Measured on CPH2585 (Android 16) via `uiautomator dump` and `dumpsys activity top`:

| App | Throttled Surface | Signal | Left Normal |
|---|---|---|---|
| Instagram | Reels | `clips_viewer_view_pager`, `root_clips_layout` | Stories = `reel_viewer_root` |
| YouTube | Shorts | `reel_watch_fragment_root`, `reel_watch_player` | Long-form watch UI |
| Snapchat | Spotlight | `spotlight_container` | Chats, Stories |
| Browser | Shorts/Reels URLs | URL readable as node text | Any other URL |

> ⚠️ `reel_*` means **opposite things** in Instagram vs YouTube. In Instagram it's Stories (leave alone); in YouTube it's Shorts (throttle). Detection is always scoped to the foreground package first.

---

## 🔒 Design Principles

- 🚫 **WhatsApp is never throttled** — dedicated negative test case
- 🎬 **Only short-form video surfaces** are throttled — not the whole app
- ✅ **Fails safe to `NORMAL`** — unrecognised screens are never slowed
- 📱 **Entirely on-device** — all data local, no cloud, no sync
- 🌱 **Positive framing only** — encouraging dashboard copy, never shame-based
- 🔧 **Modest throttle delays** — intentional friction, not app breakage

---

## 📄 License

This project was built as a university project.

---

## 👤 Author

**Manthan VS**
- GitHub: [@manthanvs](https://github.com/manthanvs)
- LinkedIn: [in/manthanvs](https://www.linkedin.com/in/manthanvs/)
- LeetCode: [manthanvs](https://leetcode.com/u/manthanvs/)
