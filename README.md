# AR VoltCore — Android Battery Monitor & Ampere Charging Meter (Free APK, No Root)

[![Download APK](https://img.shields.io/github/v/release/ABDURREHMAN629/voltcore?label=Download%20APK&style=for-the-badge)](https://github.com/ABDURREHMAN629/voltcore/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/ABDURREHMAN629/voltcore/total?style=for-the-badge)](https://github.com/ABDURREHMAN629/voltcore/releases)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#installing-the-apk)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#architecture)

**AR VoltCore is a free, open-source battery monitor and charging meter for Android.** It
shows real charging speed in **amperes (mA), watts and volts**, battery temperature and
health, **per-app battery usage**, and a **day-by-day and hour-by-hour battery history**
drawn as charts — so you can see exactly where your battery went.

It is an **ampere meter**, a **charging speed test**, a **battery drain analyzer** and a
**background app freezer** in one app, for phones running **Android 8.0 through Android 17**.

**No root. No ads. No trackers. No internet permission** — nothing it reads ever leaves the
phone. Your bootloader stays locked, Play Integrity stays intact, and banking apps keep
working.

## Download the APK

**[⬇ Download the latest AR VoltCore APK](https://github.com/ABDURREHMAN629/voltcore/releases/latest)**
— free, no sign-up, built from the source in this repository.

### Installing the APK

1. Download the `AR-VoltCore-<version>.apk` file from the [latest release](https://github.com/ABDURREHMAN629/voltcore/releases/latest).
2. Open it on your phone and allow installing from this source when Android asks.
3. Open **AR VoltCore** and plug in a charger — the live readout starts immediately.

Nothing else is required to use the app. Two optional one-time USB commands unlock the full
battery history and per-app figures; see [Setup without Shizuku](#setup-without-shizuku-recommended).

## Features

- **Live charging meter** — charging current in mA, charging power in watts, battery voltage
  and temperature, updated about once a second while plugged in.
- **Charging speed test** — tells you whether the charger is running slow, normal or fast,
  and shows the USB PD / Quick Charge profile the phone negotiated.
- **Battery percentage with decimals** — 78.1% instead of a flat 78%, so a slow drain is
  visible long before the whole point ticks over.
- **Battery history by day and by hour** — bar charts of how much battery each day used and
  charged, tap any day, then tap any hour to see what happened in it. Older dates included.
- **Per-app battery usage** — which apps drained the battery, system apps included, taken
  from Android's own battery accounting.
- **Battery health and capacity** — estimated full-charge capacity against the factory
  rating, plus cycle count where the phone exposes it.
- **Boost** — stops the background apps you pick, then measures the charging current before
  and after so the gain is a real measurement rather than an animation.
- **App freezer** — force stop the apps you choose, or pause and disable them if you add the
  optional Shizuku helper.
- **Charge alarm and alerts** — full charge, custom charge limit (e.g. stop at 80%),
  overheating and low battery.
- **Home screen widget and quick settings tile** — charge level and current at a glance.
- **Material 3 interface** with light and dark themes, built in Jetpack Compose.

## Who it is for

Anyone who wants to know whether a charger, cable or power bank is actually fast; whether a
battery is ageing; which app is draining the phone overnight; or how much battery a
particular hour of the day costs. If you have used apps like Ampere, AccuBattery, Ampere
Flow, Electron or Fast Charge, this covers the same ground as a single open-source app.

## Building

Requirements are already satisfied by a standard Android Studio install:

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.4.0 |
| Gradle | 9.7.1 |
| Kotlin | 2.2.10 (AGP 9's built-in Kotlin) |
| KSP | 2.2.10-2.0.2 |
| Compose BOM | 2026.08.00 |
| JDK | 17+ (Android Studio's bundled JBR is fine) |
| compileSdk / targetSdk | 37 (Android 17) |
| minSdk | 26 (Android 8.0) |

```bash
./gradlew :app:assembleDebug
```

Or, to get a properly named APK in a findable place:

```bash
./gradlew :app:voltcoreApk
```

That writes **`dist/VoltCore.apk`** in the project root. (Plain `assembleDebug` leaves it at
`app/build/outputs/apk/debug/app-debug.apk`, which is neither easy to find nor obvious what
it is once it is sitting in a Downloads folder.)

To install straight to a connected phone:

```bash
./gradlew :app:installDebug
```

> **First build is slow.** Gradle compiles and fingerprints the Kotlin DSL build scripts
> before it runs a single task, which on Windows can take several minutes. Later builds
> reuse that cache and are dramatically faster. If your antivirus scans the Gradle cache,
> excluding `~/.gradle` and the project directory makes a large difference.

### Notes on the toolchain

AGP 9 has **built-in Kotlin support**, so `org.jetbrains.kotlin.android` must *not* be
applied — the build fails if it is. The Kotlin version is therefore whatever AGP bundles
(2.2.10), and the Compose and serialization compiler plugins are pinned to match. KSP still
registers its generated sources through `kotlin.sourceSets`, which AGP 9 rejects by
default, hence `android.disallowKotlinSourceSets=false` in `gradle.properties`.

---

## Setup without Shizuku (recommended)

Everything except pausing and disabling apps works with AR VoltCore alone. Some data needs
permissions Android only hands out over USB. They are **not root** and install nothing
else on the phone. They stay granted after a reboot and are removed if you uninstall the app.

With USB debugging on and the phone plugged into a computer, run these once:

```bash
adb shell pm grant com.voltcore.app android.permission.DUMP
adb shell pm grant com.voltcore.app android.permission.PACKAGE_USAGE_STATS
adb shell pm grant com.voltcore.app android.permission.WRITE_SECURE_SETTINGS
```

| Grant | Unlocks |
|---|---|
| `DUMP` + `PACKAGE_USAGE_STATS` | The daily and hourly battery history, including the ~2 weeks Android kept before install, and per-app battery estimates. The battery service needs **both** grants. The Usage access switch in Settings does not count as the second one. |
| `WRITE_SECURE_SETTINGS` | Boost turning Battery Saver on |

To force-stop apps, turn on **AR VoltCore** under Settings → Accessibility. If Android calls
it a *restricted setting*, open App info → ⋮ → **Allow restricted settings** first.

Android keeps **Pause**, **Disable** and **Restrict App Standby bucket** for system apps
(`SUSPEND_APPS`, `CHANGE_COMPONENT_ENABLED_STATE` and `CHANGE_APP_IDLE_STATE` cannot be
granted over USB), so those three alone need Shizuku.

## Setting up Shizuku (optional: only for Pause / Disable)

Android deliberately gives no app the ability to stop another app —
`killBackgroundProcesses()` has been a no-op for other packages since Android 8. So app
freezing needs borrowed authority. [Shizuku](https://shizuku.rikka.app/) provides it by
running a service with the same privileges `adb shell` has, and letting apps you authorise
call through it.

**This is not root.** Shizuku changes nothing on the system partition, needs no unlocked
bootloader, and does not affect SafetyNet / Play Integrity.

### On Android 11 and newer — no PC needed

1. Install Shizuku from Play Store or GitHub.
2. On your phone: **Settings → About phone → tap Build number 7 times** to enable
   Developer options.
3. **Settings → System → Developer options → Wireless debugging** → turn it on.
4. Open Shizuku → **Start via Wireless debugging** → follow its pairing prompt.
5. Open VoltCore → **Apps** tab → **Grant permission**.

### On Android 10 and older — one command from a PC

```bash
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

### After a reboot

Shizuku stops when the phone restarts and has to be started again. That is a limitation of
the approach, not a bug — nothing that survives a reboot automatically is available without
root or device-owner enrolment.

---

## What works at each level of access

| Capability | No setup | USB grants + Accessibility | Shizuku |
|---|---|---|---|
| Live current / voltage / watts / temperature | ✅ | ✅ | ✅ |
| Charger voltage + USB PD / QC detection | ✅ * | ✅ * | ✅ * |
| Charging history and session logging | ✅ | ✅ | ✅ |
| Battery by day / hour, logged from install | ✅ | ✅ | ✅ |
| Android's older history + per-app estimates | ❌ | ✅ | ✅ |
| Alerts (full, charge limit, overheat, low) | ✅ | ✅ | ✅ |
| Force stop an app | Manual only | ✅ (slow) | ✅ |
| **Pause** an app so it stays stopped | ❌ | ❌ | ✅ |
| Disable an app | ❌ | ❌ | ✅ |
| Restrict App Standby bucket | ❌ | ❌ | ✅ |
| Toggle Battery Saver from Boost | ❌ | ✅ | ✅ |

\* Depends on the kernel exposing `/sys/class/power_supply`. Where it is unavailable
VoltCore shows a dash rather than inventing a number.

> **Verified on Xiaomi / HyperOS (Android 17):** this device denies `/sys/class/power_supply`
> to *every* app — even the `shell` user over ADB gets `Permission denied`. That removes
> charger voltage, USB PD / Quick Charge detection, cycle count and design capacity. Everything
> sourced from the framework API (current, voltage, temperature, level, charge counter)
> still works. Settings → **Device data sources** shows exactly which sources your phone
> permits, so unexplained dashes are never a mystery.

### Per-app battery usage

Two sources, and the app labels which one it used, because they measure different things:

- **"Android estimate":** VoltCore runs `dumpsys batterystats --usage` in its own process
  (DUMP + PACKAGE_USAGE_STATS granted over USB, or through Shizuku) and reports Android's
  **mAh per app**, system components included.
- **Without those grants — "Screen time":** UsageStats foreground time, computed from the raw
  event stream so it can be scoped to an arbitrary window. It is an honest proxy and
  clearly labelled as one: a sync adapter with no UI can drain the battery hard and still
  score zero here.

The accessibility fallback works by opening the system App info page and pressing **Force
stop** for you. It is slow, it takes over the screen while it runs, and OEMs rename that
button — it exists so the feature degrades rather than disappears.

---

## Things this app will not pretend to do

Apps in this category routinely imply capabilities that Android does not grant. VoltCore
states its limits in the UI itself:

- **Boost charging cannot make your charger deliver more power.** Charging current is
  negotiated between the charger and the phone's charging IC; no SDK API changes it. What
  boost *can* do is cut what the phone spends on itself, so more of the incoming current
  reaches the battery. It measures the real current before and after and reports the
  difference — **including when the difference is nothing**.
- **The charge limit is an alarm, not a cut-off.** Stopping charging at 80% requires
  writing to kernel charging nodes, which needs root. VoltCore tells you when to unplug.
- **Some devices do not report current at all.** Those show a dash. A fake zero would be
  worse than an honest gap.
- **Sign and units vary by manufacturer.** Android documents the current sensor as
  microamps, positive while charging; a meaningful number of OEM kernels invert the sign or
  report milliamps, which is why other amperage apps show a negative number on a phone that
  is plainly charging. VoltCore learns the convention by watching readings whose direction
  it already knows, and you can override it in Settings.

---

## What VoltCore costs you

A battery monitor that drains the battery is a joke at its own expense, so the sampling
loop is built to be cheap:

| Measure | Effect |
|---|---|
| **Screen-off sampling drops to 30 s** | With the screen off nobody can read the notification. Sampling every second there would wake the CPU 3,600 times an hour for something invisible — this is the single biggest saving. |
| **Notification posted only when it changes** | Re-posting identical text still costs a binder round trip and a shade relayout. Idle or trickle-charging, almost every update is skipped. |
| **Sysfs availability cached once** | On phones that deny `/sys/class/power_supply`, the app used to retry ~20 failed file opens *every second, forever*. It now probes once at startup and short-circuits. |
| **Ring animation only exists while charging** | An infinite Compose transition keeps requesting frames for as long as it is composed, so the animated layer is composed only when current is flowing — not merely ignored. |
| **Sampling moved off the main thread** | `read()` opens files and registers a receiver; it now runs on the IO dispatcher instead of janking the UI once a second. |
| **Duplicate reads removed** | Each sample was fetching the charge counter and capacity twice. |
| **Widget refreshes every 2 min** | Down from 30 s. A home-screen readout is glanced at, not watched. |
| **No wake locks, ever** | The loop uses plain `delay`, so when the CPU sleeps, sampling sleeps with it. Doze is left to do its job. |
| **Service stops when you unplug** | On by default (*Only while charging*), so on battery the app costs nothing at all. |

The refresh rate is yours to set in Settings; 1 s is the default while you are looking at
the screen, and it makes no difference to standby drain because of the first row above.

---

## Architecture

Single Gradle module, hand-rolled dependency graph (`core/AppGraph.kt`) — a DI framework
would add build cost and version coupling for a handful of collaborators.

```
core/
  battery/    BatteryTelemetry  — merges framework + sysfs into one snapshot
              SysfsProbe        — /sys/class/power_supply reader: charger volts, PD/QC
              CurrentCalibrator — learns each device's sign and unit convention
  privileged/ PrivilegeManager  — picks the best available tier
              ShizukuBackend    — shell-UID access via a Shizuku UserService (AIDL)
              ForceStopAccessibilityService — last-resort Settings automation
  boost/      BoostEngine       — measure, act, measure again
  alerts/     AlertManager      — edge-triggered, latched notifications
  data/       VoltPrefs (DataStore), Room history, installed-app listing
feature/      dashboard · history · boost · freezer · settings
ui/           theme (validated palette) · reusable components · charts
service/      MonitorService (foreground) · widget · QS tile · receivers
```

### On the colour palette

Charging speed is an **ordinal** scale, so it uses a sequential single-hue ramp (dim to
brilliant cyan) rather than a rainbow. An earlier draft ran blue → cyan → green → yellow;
a palette validator showed "Slow" and "Normal" sat 6.7 ΔE apart — indistinguishable even
with full colour vision, let alone colour blindness. Brightness now carries speed, which
frees green/amber/red to mean *good/warning/critical* and nothing else. Every speed and
status is rendered with its text label, so colour is never the only channel.

---

## FAQ

### Is AR VoltCore free?

Yes. It is free and open source, with no ads, no in-app purchases and no accounts.

### Does it need root?

No. Nothing in the app needs root, an unlocked bootloader or a modified system partition.

### Which Android versions are supported?

Android 8.0 (API 26) through Android 17 (API 37), phones and tablets.

### Why does my phone show no charger voltage or cycle count?

Those come from kernel files some manufacturers keep private — Xiaomi/HyperOS is one. Where
a value is unavailable the app shows a dash instead of inventing one. **Settings → Device
data sources** lists exactly what your phone allows.

### Can an app make charging faster?

Not by raising the charger's output: the current is negotiated between the charger and the
phone's charging driver, and no Android app can override it. What an app can do is lower the
phone's own consumption so more of that power reaches the battery, which is what Boost does
— and it reports the measured difference, including when there is none.

### Can it stop charging at 80%?

It can alarm at any level you set, but no Android app can cut the charger off. Phones that
offer a real charge limit do it in the system settings.

### Is my data sent anywhere?

No. The app has no internet permission at all, so readings and app names cannot leave the
phone.

### Why does it need the Usage access and DUMP permissions?

Only to read what Android already records about battery history and app usage. Both are
optional, granted by you over USB, and the app works without them — see
[Setup without Shizuku](#setup-without-shizuku-recommended).

---

## Permissions, and why each is needed

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE_SPECIAL_USE` | One-second sampling; a deferrable worker would coalesce readings into uselessness |
| `POST_NOTIFICATIONS` | The live readout and every alert |
| `QUERY_ALL_PACKAGES` | Listing apps you can choose to freeze |
| `PACKAGE_USAGE_STATS` | Screen time per hour; with DUMP, reading battery history (granted over USB) |
| `DUMP` | Android's battery history and per-app estimates (granted over USB) |
| `WRITE_SECURE_SETTINGS` | Boost turning Battery Saver on (granted over USB) |
| `moe.shizuku.manager.permission.API_V23` | Talking to Shizuku, if you set it up |
| `WRITE_SETTINGS` | Boost toggling system settings (optional) |

VoltCore has no network permission. Nothing it reads leaves the device.

---

**Keywords:** android battery monitor app, ampere meter android, charging current mA tester,
watt meter charging, battery health checker, battery usage by app, battery drain analyzer,
hourly battery usage history, fast charging test, USB PD Quick Charge detector, battery
saver, background app freezer, no root battery app, open source android battery app, free
battery monitor APK download.
