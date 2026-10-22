# Zeidex Convert for Android ⚡📱

[![License: MIT](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg?logo=android)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline-success.svg)](#privacy-first)

**Zeidex Convert** brings the power and privacy of the Zeidex ecosystem to Android. Built from scratch with **Kotlin 2.0** and **Jetpack Compose (Material 3)**, it is an offline-first file conversion utility designed for speed, beauty, and absolute privacy.

---

## ✨ Features

- **🛡️ 100% Offline & Private**: Zero internet permissions requested (`android.permission.INTERNET` is omitted from `AndroidManifest.xml`). All conversions happen entirely on-device. No telemetry, no cloud uploads.
- **🔄 Interactive Radial Wheel**: Touch-driven circular selector to smoothly flick and choose target conversion formats with spring animations.
- **📤 System Share Target**: Send files directly from Google Photos, Gallery, WhatsApp, Files, or any app using Android's native share sheet (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`).
- **📸 Motion Photo & Live Photo Video Extractor**:
  - Automatically parses XMP metadata and extracts full 1080p/4K 60fps MP4 motion video from Google Pixel & Samsung Motion Photos.
  - Supports Apple Live Photos.
- **📄 PDF Studio**:
  - Convert multi-page PDF documents to high-resolution PNG/JPG pages with hardware-accelerated `PdfRenderer`.
  - Combine multiple images into a clean single PDF document.
- **🎨 Image Processing**:
  - Convert between PNG, JPEG, WEBP, and ICO.
  - Instant Black & White (Grayscale) filter with color matrix acceleration.
  - 90° / 180° / 270° lossless rotation and intelligent compression.
- **📦 ZIP Archive Creator & Extractor**:
  - Pack multiple files into standard ZIP archives.
  - Extract archives directly to internal storage.
- **🔲 High-Speed QR Engine**:
  - Instant offline QR Code generation from text, links, and credentials via ZXing.
  - Direct sharing to external apps.

---

## 🏗️ Architecture

```
com.zeidex.convert/
├── ZeidexApplication.kt        # Application lifecycle
├── MainActivity.kt             # Navigation host & system intent routing
├── model/
│   ├── Category.kt             # Media classification (Image, PDF, Archive, etc.)
│   ├── Format.kt               # Target format mappings & extension detection
│   ├── FileItem.kt             # Selected file descriptor (URI, size, MIME)
│   └── ConversionTask.kt       # State, progress, and output file model
├── engine/
│   ├── ConversionEngine.kt     # Background dispatcher & task runner
│   ├── ImageConverter.kt       # Bitmap transforms, compress, ICO packaging
│   ├── MotionPhotoExtractor.kt # XMP parsing & MP4 byte extractor
│   ├── PdfConverter.kt         # PdfRenderer & PdfDocument routines
│   ├── ArchiveConverter.kt     # ZipInputStream / ZipOutputStream routines
│   └── QrEngine.kt             # ZXing QR matrix generator
└── ui/
    ├── theme/                  # Zeidex Dark/Light Material 3 theme & colors
    ├── components/
    │   ├── RadialWheel.kt      # Custom Canvas-based interactive radial wheel
    │   └── FileCard.kt         # Selected file item card with swipe/remove
    ├── screens/
    │   ├── HomeScreen.kt       # Drop zone, target format chips, task history
    │   ├── WheelScreen.kt      # Touch-centric radial conversion wheel
    │   ├── ToolsScreen.kt      # QR, Motion Photo, PDF, and Image tools
    │   └── SettingsScreen.kt   # Cache management & system info
    └── viewmodel/
        └── MainViewModel.kt    # StateFlow reactive state management
```

---

## 🛠️ Building & Requirements

- **Android SDK**: `compileSdk = 35`, `minSdk = 26` (Android 8.0+)
- **JDK**: Java 17+
- **Gradle**: 8.10.2

To assemble the debug APK:
```bash
./gradlew assembleDebug
```
The output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License

MIT License. Built with ❤️ by [zeiad6](https://github.com/zeiad6).
