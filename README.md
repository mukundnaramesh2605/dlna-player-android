# DLNA Player for Android

A native Android application built with **Kotlin**, **Jetpack Compose (Material 3)**, and **Coroutines / Flow** that lets you browse the device's real storage hierarchy folder-by-folder and stream media files (video, audio, photos) directly to any UPnP / DLNA MediaRenderer on your Wi-Fi network (Smart TVs, AV receivers, network soundbars, and speakers).

---

## Key Features

1. **Folder-Wise File Browsing**:
   - Walks the real filesystem starting from external storage root (`/storage/emulated/0`) using Java `File` APIs.
   - Shows the actual directory tree with breadcrumb navigation.
   - Real-time search query filtering and sorting by Name, Date, or Size.
   - Media filter toggle to show only castable media or all files.
   - Async thumbnail loading for images and video frames using Coil.
   - Clear visual indicators and badges for non-castable files (documents, APKs, archives).

2. **DLNA / UPnP Device Discovery (SSDP)**:
   - Discovers Smart TVs and MediaRenderers using SSDP `M-SEARCH` UDP multicasting (`239.255.255.250:1900`).
   - Automatically acquires Android `WifiManager.MulticastLock` to prevent Wi-Fi chipset multicast packet filtering.
   - Fetches and parses UPnP device XML descriptors to discover `AVTransport` and `RenderingControl` service endpoints.

3. **High-Performance Local Streaming Server**:
   - Embedded lightweight HTTP server powered by **NanoHTTPD**.
   - **Byte-Range Support (`RFC 7233`)**: Responds with `206 Partial Content` and `Content-Range` headers, enabling instant seeking on DLNA Smart TVs.
   - Compliant with DLNA headers (`transferMode.dlna.org: Streaming`, `contentFeatures.dlna.org`).

4. **Comprehensive Playback Controls**:
   - UPnP SOAP actions: `SetAVTransportURI` (with DIDL-Lite metadata XML), `Play`, `Pause`, `Stop`, `Seek` (`REL_TIME`), `GetPositionInfo`, and `GetTransportInfo`.
   - Volume control and mute toggle via `RenderingControl` service.
   - Automatic 1-second background poller syncing the scrubbable seek bar and playback state.
   - Folder playlist queue with Next and Previous track controls.

5. **Foreground Service & Media Notification**:
   - Android 14+ compliant `DlnaCastingService` with `foregroundServiceType="mediaPlayback"`.
   - Media-style notification with Play, Pause, and Stop actions to keep playback alive even when the app is backgrounded or the screen turns off.
   - `WakeLock` to prevent the device from entering deep sleep during an active cast session.

6. **Full Storage Access & Graceful Permissions**:
   - Declares `MANAGE_EXTERNAL_STORAGE` for Android 11+ (API 30+) with a clear rationale screen directing the user to system settings.
   - Fallback runtime permission flow for `READ_EXTERNAL_STORAGE` on Android 10 and below, and granular media permissions (`READ_MEDIA_*`) on Android 13+.
   - Never crashes on permission denial; provides instant retry mechanisms.

---

## Architecture

The project follows a clean, decoupled **MVVM (Model-View-ViewModel)** architecture:

```
├── app/src/main/java/com/dlnaplayer/android/
│   ├── DlnaApplication.kt              # Configures Coil with VideoFrameDecoder
│   ├── dlna/
│   │   ├── SsdpDiscovery.kt            # Multicast UDP SSDP discovery & XML parser
│   │   ├── UPnPSoapClient.kt           # AVTransport & RenderingControl SOAP client
│   │   └── DlnaController.kt           # Playback coordinator, polling, & playlists
│   ├── model/
│   │   ├── FileItem.kt                 # Filesystem model & MIME categorization
│   │   ├── DlnaDevice.kt               # Discovered UPnP MediaRenderer device
│   │   └── PlaybackState.kt            # Real-time casting state & time helpers
│   ├── repository/
│   │   └── FileRepository.kt           # File system traversal, sorting, & filtering
│   ├── server/
│   │   └── DlnaMediaServer.kt          # NanoHTTPD byte-range streaming server
│   ├── service/
│   │   └── DlnaCastingService.kt       # Foreground media playback service
│   ├── util/
│   │   └── NetworkUtils.kt             # LAN IP resolver & MulticastLock manager
│   └── ui/
│       ├── MainActivity.kt             # TopAppBar, Cast button, & Scaffold
│       ├── components/
│       │   ├── FolderBrowserScreen.kt  # File list, breadcrumbs, search, & sort
│       │   ├── DevicePickerBottomSheet.kt # Discovered renderers sheet
│       │   ├── MiniPlayerBar.kt        # Persistent bottom player bar
│       │   ├── NowPlayingSheet.kt      # Full controls, seekbar, & volume
│       │   └── PermissionScreen.kt     # Storage permission rationale screen
│       ├── theme/                      # Material 3 colors, typography, theme
│       └── viewmodel/
│           ├── FileBrowserViewModel.kt # State for folder navigation & search
│           └── CastViewModel.kt        # State for discovery, casting, & playback
```

---

## Build & Installation

### Requirements
- **JDK 17**
- **Android SDK Platform 34**
- **Android Studio Iguana / Jellyfish / Koala or newer** (or Gradle CLI)

### Building the Project
From the repository root:

```bash
# Clean and assemble the debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test
```

The resulting APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

### Installing on Device (Sideloading)
Connect your Android phone via USB with USB Debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Permissions & First Launch Guide

1. **All Files Access (`MANAGE_EXTERNAL_STORAGE`)**:
   - On first launch, the app displays a permission screen explaining why All Files Access is needed.
   - Tap **"Grant All Files Access"**.
   - In Android's system settings screen, toggle the switch for **DLNA Player** to **Allow**.
   - Return to the app. The app automatically detects the permission and lists the root external storage folder (`/storage/emulated/0`).

2. **Wi-Fi Requirement**:
   - Your Android phone and your Smart TV / DLNA receiver must be connected to the **same Wi-Fi network**.
   - Some home routers have "AP Isolation" or "Guest Wi-Fi" enabled which blocks device-to-device communication; ensure AP isolation is disabled.

---

## Supported Media Formats
DLNA MediaRenderers can stream:
- **Video**: MP4, MKV, MOV, AVI, WebM, 3GP, TS
- **Audio**: MP3, FLAC, AAC, WAV, M4A, OGG, Opus
- **Images**: JPEG, PNG, WebP, GIF, BMP

*Note: Non-media files (PDFs, ZIPs, docs, APKs) are browsable in the file tree but cannot be streamed over DLNA. The app marks them as "Non-media" and allows you to toggle "Playable media only" to hide them.*
