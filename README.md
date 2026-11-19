# Coolwalk IPTV — Android Auto & Phone M3U Player

A lightweight, customizable IPTV player built with **Media3 ExoPlayer** and **Android Auto Projection**, designed to run seamlessly on both your Android smartphone and ultrawide Android Auto (Coolwalk) car head units with real-time two-way synchronization.

[![Latest Release](https://img.shields.io/github/v/release/zamankazuya/my-iptv-player?label=Download%20APK&style=for-the-badge&color=38bdf8)](https://github.com/zamankazuya/my-iptv-player/releases/latest)

---

## Key Features

- **Native Android Auto Coolwalk Projection:** Renders full live video and interactive touch controls directly inside the Android Auto split-screen or fullscreen car display (`CarService` + `aauto-sdk`).
- **Real-Time Phone-to-Car Sync (`SharedSyncStore`):** Configure your M3U playlist URL, local `.m3u` file, EPG guide, favourite channels, and player settings on your phone—changes reflect live on your car head unit automatically.
- **Media3 ExoPlayer Engine:** Supports **HLS (`.m3u8`)**, **MPEG-DASH (`.mpd`)**, raw streams, **ClearKey** (hex/JSON/URL), and **Widevine DRM**, along with custom `User-Agent`, `Referer`, `#EXTVLCOPT`, and `#KODIPROP` headers.
- **Fast XMLTV EPG Integration:** Built-in streaming XML/GZIP parser displays current and upcoming program schedules directly in the player overlay and channel deck.
- **Car-Friendly Touch & Remote Controls:**
  - Center **Previous**, **Play/Pause**, and **Next** buttons that cycle through your **Favourites** first (or all channels if no favourites are set).
  - Scrollable bottom Favourites bar with channel logos and live EPG titles.
  - Steering wheel / D-pad Up & Down channel zapping.
  - Auto-hiding HUD overlay with a single-tap wake shield for distraction-free driving.
- **Voice Control (Malay & English):** Built-in speech recognition supporting commands like *"Buka saluran TV1"*, *"Tukar ke Astro Ria"*, *"Switch to..."*, *"Next"*, and *"Pause / Henti"*.
- **Customizable Interface:** Includes 7 color themes, adjustable UI & subtitle font scaling (`85%–140%`), video aspect ratio modes (`Fit`, `Zoom`, `Fill`), stream resolution limiter (`Auto`, `1080p`, `720p`, `480p`, `360p`), and JSON config backup/restore.

---

## Download & Installation

Download the latest APK from the [**Releases**](https://github.com/<your-github-username>/my-iptv-player/releases/latest) page.

### Important: Making the App Appear in Android Auto (Android 14+)
Because Android Auto hides sideloaded projection apps unless they carry Google Play Store installer attribution, install the APK using **one of the two methods below**:

#### Method 1: Install via ADB from a PC (Recommended)
Connect your phone via USB with **USB Debugging** enabled and run:
```bash
adb install -i "com.android.vending" Coolwalk-IPTV.apk
```

#### Method 2: Install Directly on Phone via KingInstaller / Shizuku
1. Install **Shizuku** and start it via Wireless Debugging.
2. Open **KingInstaller** (or **UnknownAuto**) with Shizuku enabled, select the downloaded `.apk`, and install it.

### Enable Unknown Sources in Android Auto
1. On your phone, open **Settings** → **Connected devices** → **Android Auto**.
2. Scroll to the bottom and tap **Version** 10 times to unlock **Developer settings**.
3. Tap the **three-dot menu** (top right) → **Developer settings**.
4. Check **Unknown sources** and set **Application Mode** to **Developer**.
5. Open **Customize launcher** in Android Auto settings and verify **Coolwalk IPTV** is checked.

---

## Quick Start Guide

1. Open **Coolwalk IPTV** on your phone.
2. Tap **Open Settings Now** on the welcome prompt (or tap the **Gear icon** on the right sidebar).
3. Enter your **M3U Playlist URL** (or upload a local `.m3u` / `.m3u8` file) and optional **XMLTV EPG URL**, then tap **Save & Sync**.
4. Tap the **Star icon** (or `+` in the bottom bar) to pin your favourite channels for quick access in the car.
5. Connect your phone to your car's Android Auto head unit—your playlist, favourites, and active channel will sync automatically.

---

## Project Architecture

| Component | Path | Role |
| :--- | :--- | :--- |
| **Phone Activity & Sync Engine** | `app/src/main/java/com/my/iptvplayer/MainActivity.kt` | Hosts `SharedSyncStore`, `IptvSessionController`, ExoPlayer Media3 setup, XMLTV EPG parser, and phone UI. |
| **Android Auto Projection Service** | `app/src/main/java/com/my/iptvplayer/CarService.java` | Bridges `CarActivityService` to `IptvSessionController` for Coolwalk car projection. |
| **Player Overlay & Controls** | `app/src/main/assets/index.html` | Responsive Tailwind CSS HUD, custom glass option pickers, Favourites deck, and voice command router. |
| **Projection Descriptor** | `app/src/main/res/xml/automotive_app_desc.xml` | Declares `<uses name="projection" />` for Android Auto. |

---

## Disclaimer

This application is an open-source media player interface and **does not provide, host, or include any IPTV channels, playlists, or copyrighted streams**. Users must supply their own legally obtained M3U playlists and EPG sources.
# my-iptv-player