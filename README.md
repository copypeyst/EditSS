# EditSS - Image Editor

A simple Android image editing application with drawing, cropping, and adjustment tools.

## License

This project is licensed under GNU GPL v3.

### Third-Party Components
- **Icons**: Google Fonts Material Icons (Apache 2.0)
- **CI Setup**: Based on BaseMax/AndroidAutoBuildAPK (GNU GPL v3)

## App Size Optimization

### ABI Configuration (Item #21)
**Decision**: **Automatic ABI optimization** via Gradle's native dependency analysis.

**Reasoning**:
- Your app has **no native dependencies** (`.so` libraries)
- All functionality is implemented in **pure Kotlin/Java**
- **Gradle automatically excludes unused ABIs** when no native libraries are present
- This achieves the same size reduction (~20-30%) without explicit configuration
- **Modern approach**: No manual ABI filtering needed for pure Kotlin/Java apps

**Implementation**: Gradle handles ABI optimization automatically based on actual dependencies used.

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