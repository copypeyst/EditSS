# EditSS - Image Editor

A simple Android image editing application with drawing, cropping, and adjustment tools.

## License

This project is licensed under GNU GPL v3.

### Third-Party Components
- **Icons**: Google Fonts Material Icons (Apache 2.0)
- **CI Setup**: Based on BaseMax/AndroidAutoBuildAPK (GNU GPL v3)

## App Size Optimization

### ABI Configuration (Item #21)
**Decision**: Only supports **ARM64-v8a** (64-bit ARM) architecture.

**Reasoning**:
- Your app has **no native dependencies** (`.so` libraries)
- All functionality is implemented in **pure Kotlin/Java**
- **Modern Android devices** (2018+) all support 64-bit ARM
- Removing 32-bit support reduces app size by approximately **20-30%**

**Safe to implement**: The absence of native libraries means there are no conflicts with third-party dependencies that might require 32-bit support.

## Features

- **Drawing Tools**: Pen, Circle, Square with shared color/size/opacity
- **Crop Tool**: FreeForm, Square, Portrait, Landscape modes
- **Adjust Tool**: Brightness, Contrast, Saturation controls
- **Image Management**: Import from gallery, camera capture, save/share
- **Undo/Redo**: Full history with bitmap-based preservation
- **Format Support**: JPEG, PNG, WEBP with transparency detection

## Build Configuration

- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 15)
- **Architecture**: ARM64-v8a only
- **Language**: Kotlin
- **Build System**: Gradle with Android Gradle Plugin

## Development

This project is based on BaseMax/AndroidAutoBuildAPK for CI automation.

---

**Last Updated**: 2025-11-13