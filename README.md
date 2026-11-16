<div align="center">
# ☁️ Cloudyte
 
### All your drives. One secure place.
 
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
![Version](https://img.shields.io/badge/version-1.1.0-2F6FEB?style=for-the-badge)
![Encryption](https://img.shields.io/badge/Encrypted%20copies-AES--256--GCM-52C592?style=for-the-badge)
 
**One app for all your cloud drives.** Link Telegram Cloud, Google Drive and OneDrive, browse and search them together, move files between them, and protect what matters with on-device encryption and an app lock.
 
[Features](#-features) · [Screens](#-screens) · [Design](#-design) · [Architecture](#-architecture) · [Getting started](#-getting-started) · [Roadmap](#-roadmap)
 
</div>
---
 
## ✨ Features
 
### ☁️ Multi-drive management
- Link several accounts from **Telegram Cloud**, **Google Drive** and **OneDrive**
- Rename, refresh, set a default drive, or disconnect any drive
- One home dashboard with total storage, file counts and a breakdown by category (Images, Documents, Media, Other, Zipped, Unknown)
### 📂 Files
- Grid and list views, search, sort and filter
- Create folders, upload, move, copy, rename and delete
- File details and a per-file options menu
- Cached thumbnails and previews for fast browsing
### 🔄 Transfers
- Upload and download queue with **All / Active / Completed / Failed** views
- Resumable transfers, automatic retry and one-tap manual retry
- Pending transfers recover after a reboot
### 🎬 Built-in viewers
- Images, video, audio (with background playback), PDF and a text editor
### 🔐 Security and privacy
- Optional **end-to-end encrypted copies** of files (AES-256-GCM)
- Keys protected by the **Android Keystore**, with passphrase-based key backup (PBKDF2)
- **App lock** screen
- Option to **block screenshots** and screen recording
### 🗂️ Backup and sync
- Pick folders to back up, set a schedule and network conditions
- Exclusion rules and sync logs
### ⚙️ Settings
- Account, Backup, Storage, Permissions, Security, Appearance, Playback, Notifications and Advanced
- Diagnostics screen for troubleshooting
---
 
## 📱 Screens
 
```mermaid
flowchart LR
    A(["🌥️ Splash"]) --> B["🔑 Log in / Register"]
    A --> L["🔒 App lock"]
    B --> H
    L --> H
    subgraph MAIN["Main navigation"]
        H["🏠 Home"] --- F["📂 Files"] --- T["🔄 Transfers"]
    end
    H --> S["⚙️ Settings"]
    H --> D["☁️ Linked drives"]
    D --> AD["➕ Add a drive"]
    F --> FD["📄 File details"]
    F --> V["🎬 Viewers<br/>image · video · audio · PDF · text"]
    S --> SS["Account · Backup · Storage · Permissions<br/>Security · Appearance · Playback<br/>Notifications · Advanced"]
```
 
| Screen | What it shows |
|---|---|
| 🌥️ **Splash** | Animated logo, tagline and a wavy loader |
| 🔑 **Log in** | Large rounded fields, "Remember me" switch, one big Log in button |
| 🔒 **App lock** | "Cloudyte Locked" with a single Unlock button |
| 🏠 **Home** | Storage used, total files, linked drives, category tiles, quick actions |
| 📂 **Files** | Account switcher, search, view and filter buttons, folder and file cards, **New** button |
| 🔄 **Transfers** | Filter chips, status chips (Cancelled, Failed), retry button and error details |
| 📄 **File details** | Preview, name, size and type, details list, Open / Encrypt / Move / Copy |
| ☁️ **Linked drives** | Each drive with its provider and a **Default** marker |
| ➕ **Add a drive** | Bottom sheet: Telegram Cloud, Google Drive, OneDrive |
| ⚙️ **Settings** | Grouped list of nine sections |
 
---
 
## 🎨 Design
 
Cloudyte uses a **Material 3 Expressive** look, dark first: large rounded shapes, bold type, tonal surfaces and a small set of strong colors.
 
The logo is a white cloud on a soft 8-point blue shape. The three small dots under the cloud are your linked drives, in the same colors used for storage categories.
 
**Interface colors**
 
![Primary](https://img.shields.io/badge/Primary-%232F6FEB-2F6FEB?style=for-the-badge&labelColor=1B2538)
![Accent](https://img.shields.io/badge/Accent-%236FA2FF-6FA2FF?style=for-the-badge&labelColor=1B2538)
![Background](https://img.shields.io/badge/Background-%23060918-060918?style=for-the-badge&labelColor=1B2538)
![Surface](https://img.shields.io/badge/Surface-%23121B2E-121B2E?style=for-the-badge&labelColor=1B2538)
![Raised](https://img.shields.io/badge/Raised-%231B2538-1B2538?style=for-the-badge&labelColor=0B1230)
![Tonal](https://img.shields.io/badge/Tonal%20blue-%2318275A-18275A?style=for-the-badge&labelColor=1B2538)
 
**Storage categories**
 
![Images](https://img.shields.io/badge/Images-%234361EE-4361EE?style=for-the-badge&labelColor=1B2538)
![Documents](https://img.shields.io/badge/Documents-%23F5B72E-F5B72E?style=for-the-badge&labelColor=1B2538)
![Media](https://img.shields.io/badge/Media-%23EF4B4B-EF4B4B?style=for-the-badge&labelColor=1B2538)
![Other](https://img.shields.io/badge/Other-%2352C592-52C592?style=for-the-badge&labelColor=1B2538)
![Zipped](https://img.shields.io/badge/Zipped-%239B5CE8-9B5CE8?style=for-the-badge&labelColor=1B2538)
![Unknown](https://img.shields.io/badge/Unknown-%23A7AFC8-A7AFC8?style=for-the-badge&labelColor=1B2538)
 
**Design principles**
 
| Principle | What it means in the app |
|---|---|
| **Expressive shapes** | Large rounded cards, grouped lists with big outer and small inner corners |
| **One clear action** | Each screen has a single primary button in blue |
| **Color with meaning** | Categories and statuses keep the same color everywhere |
| **Readable first** | Bold titles, clear hierarchy, comfortable touch targets (48 px+) |
| **Accessible** | Text contrast of 4.5:1 or better, labels on icon-only buttons, reduced-motion support |
 
---
 
## 🏗️ Architecture
 
```mermaid
flowchart LR
    UI["UI<br/>Compose screens<br/>and components"] --> VM["ViewModels"]
    VM --> REPO["Repositories<br/>files, accounts,<br/>transfers"]
    REPO --> LOCAL[("Room database<br/>and cache")]
    REPO --> REMOTE["Retrofit API<br/>Cloudyte server"]
    REMOTE --> PROV["Telegram Cloud<br/>Google Drive<br/>OneDrive"]
    REPO --> SEC["Security<br/>Keystore, AES-256-GCM,<br/>key backup"]
    WORK["WorkManager<br/>transfers and backup"] --> REPO
```
 
---
 
## 🧰 Tech stack
 
| Area | Library |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose (BOM 2025.02.00), Material 3 1.4.0-alpha05, Navigation Compose |
| Local data | Room 2.7.0, DataStore Preferences |
| Networking | Retrofit 2.12, OkHttp 4.10, Moshi |
| Images | Coil 2.7 |
| Media | AndroidX Media3 (ExoPlayer, Session, UI) 1.3.0 |
| Background work | WorkManager 2.9.0, foreground services |
| Security | AndroidX Security Crypto, Android Keystore |
| Concurrency | Kotlin Coroutines 1.10 |
| Testing | JUnit, Robolectric, Roborazzi, Compose UI tests |
 
| | |
|---|---|
| Package | `com.cloudyte.app` |
| Version | 1.1.0 (versionCode 2) |
| Min SDK | 24 (Android 7.0) |
| Target / Compile SDK | 36 |
 
---
 
## 📁 Project structure
 
```
app/src/main/java/com/cloudyte/app/
├── api/           Retrofit client, API models, file repository
├── core/
│   ├── cache/     Thumbnail and preview cache, quota and cleanup
│   ├── network/   Network policy, request correlation
│   ├── security/  Keystore, content keys, encrypted metadata, key backup
│   └── transfer/  Transfer queue, resume, boot recovery
├── data/          Repositories, providers, local and remote data sources
├── db/            Room entities and DAOs
├── domain/        Models and repository interfaces
├── features/      Feature entry points (files, home, accounts, player, transfers, settings)
├── sync/          Backup worker, auto sync, permissions helper
├── ui/
│   ├── components/  Shared Compose components (nav bar, grouped list, sheets)
│   ├── screens/     Screens and view models
│   ├── navigation/  Transitions
│   └── theme/       Colors, typography, shapes, spacing
└── MainActivity.kt
```
 
---
 
## 🚀 Getting started
 
### Requirements
- Android Studio (latest stable) with Android SDK 36
- JDK 17 or newer
- A device or emulator running Android 7.0 (API 24) or higher
### Build and run
 
```bash
# Debug build
./gradlew assembleDebug
 
# Install on a connected device
./gradlew installDebug
 
# Run unit tests
./gradlew testDebugUnitTest
```
 
The debug APK is created at `app/build/outputs/apk/debug/`. A GitHub Actions workflow (`.github/workflows/build-apk.yml`) is included for CI builds.
 
### Backend
 
The app talks to a REST API. The base URL is set in `app/src/main/java/com/cloudyte/app/api/ApiClient.kt` (`API_BASE_URL`). Change it to point to your own server.
 
### Environment
 
Copy `.env.example` to `.env` if you add local secrets. It is currently empty.
 
---
 
## 🔑 Permissions
 
| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Talk to the server and respect network settings |
| `POST_NOTIFICATIONS` | Transfer and backup notifications |
| `FOREGROUND_SERVICE` (data sync, media playback) | Long transfers and background audio |
| `RECEIVE_BOOT_COMPLETED` | Resume pending transfers after a reboot |
 
---
 
## 🗺️ Roadmap
 
- [x] Multi-drive home, files and transfers
- [x] Encrypted copies and app lock
- [x] Material 3 Expressive design for Home, Files, Transfers, File details, Settings, Splash, Log in, App lock and Drives
- [ ] Apply the new design in the app (Compose theme and components)
- [ ] Light theme
- [ ] Redesign the image, video, audio and PDF viewers
- [ ] Redesign Settings sub-screens and the file options sheet
- [ ] Tune the Files type scale (14 sp names, two-line wrap) and test at the largest system font size
---
 
## 🤝 Contributing
 
1. Fork the repo and create a branch: `git checkout -b feature/my-change`
2. Make your change and add or update tests
3. Run `./gradlew testDebugUnitTest`
4. Open a pull request with a short description and screenshots for any UI change
---
 
## 📄 License
 
Add your license here.
 
<div align="center">
  <sub>Cloudyte · All your drives. One secure place.</sub>
</div>
 
