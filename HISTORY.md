## UI Implementation & Refinement (Phase 1)

*   **Layout:** `activity_main.xml` structure (Google Ad Banner, ActionBar, Canvas, Tools, ToolOptions, Save Panel) + responsiveness.
*   **Icons:** All ActionBar icons integrated with vector drawables and ripple effects.
*   **Save Panel:** Centered modal with scrim overlay, show/hide functionality, rounded corners. Buttons: "Save Copy", "Overwrite" using theme colors (`colorSecondary`/`colorOnSecondary`). File types: JPG, PNG, WEBP with RadioButton selection.
*   **ToolOptions:** Contextual show/hide functionality, properly structured for each tool.
    *   **Draw:** Modes (Pen/Circle/Square) with button backgrounds. Colors: 8-color palette with circular ring selection indicators using secondary theme color. Sliders (Size/Opacity): Properly labeled and positioned.
    *   **Crop:** Four crop modes (FreeForm, Square, Portrait, Landscape) with mode-specific icons.
    *   **Adjust:** Text labels + Brightness/Contrast/Saturation sliders.
*   **Color Selection:** Implemented circular ring indicators using `faded_round_border.xml` with secondary theme color (#FF03DAC5) for selected state visual feedback.
*   **Refinements:** Margins, padding, sizes optimized for dark theme consistency.
*   **Dependencies:** `androidx.constraintlayout` correctly set to version 2.1.4 in `libs.versions.toml`.

## Implementation Steps (Phase 2)

### Step 1: Intent Filters & ClipData Safety
*   **AndroidManifest.xml:** Added ACTION_VIEW and ACTION_EDIT intent filters with `image/*` mime type.
*   **MainActivity.kt:** Implemented `handleIntent()` with multi-image rejection using toast notifications.
*   **Safety:** Rejects multiple images and processes single images from both ClipData and direct URIs.

### Step 2: Android 12+ Compliance
*   **AndroidManifest.xml:** MainActivity already has `android:exported="true"` attribute set.
*   **Compliance:** Ensures activities with intent filters are properly exported for Android 12+ requirements.

### Step 3: Smart Permission Requests
*   **AndroidManifest.xml:** Added READ_MEDIA_IMAGES permission for Android 13+, no legacy permissions for Android 10-12.
*   **MainActivity.kt:** Implemented `hasImagePermission()` and `requestImagePermission()` with version-specific logic.
*   **Conditional Logic:** Only requests permissions when import button is clicked, not at app startup.

### Step 4: MediaStore API for Android 10-12
*   **MainActivity.kt:** No storage permissions needed for Android 10-12, uses MediaStore API exclusively.
*   **Photo Picker:** Uses ACTION_OPEN_DOCUMENT with MediaStore API for older versions without legacy permissions.

### Step 5: Permission Denial Dialog
*   **MainActivity.kt:** Implemented `showPermissionDeniedDialog()` with non-blocking AlertDialog.
*   **Settings Link:** Provides "Settings" button using ACTION_APPLICATION_DETAILS_SETTINGS to open system settings.
*   **User Experience:** Graceful handling with cancel option and clear messaging.

### Step 6: Permission Revocation Detection
*   **MainActivity.kt:** Implemented `checkPermissionRevocation()` called in `onResume()`.
*   **Revocation Detection:** Monitors permission state changes mid-session and updates UI accordingly.
*   **Dialog:** Shows `showPermissionRevokedDialog()` when permissions are revoked.

### Step 7: Intent Handling on Launch
*   **MainActivity.kt:** Enhanced `handleIntent()` method in `onCreate()` and `onNewIntent()`.
*   **Intent Processing:** Handles ACTION_VIEW and ACTION_EDIT intents on app launch and resume.

### Step 8: Image Origin Tracking
*   **MainActivity.kt:** Defined `ImageOrigin` enum with four states (IMPORTED_READONLY, IMPORTED_WRITABLE, CAMERA_CAPTURED, EDITED_INTERNAL).
*   **ImageInfo Class:** Created data class to track URI, origin, and canOverwrite flag.
*   **Origin Detection:** Implemented `determineImageOrigin()` and `determineCanOverwrite()` methods.

### Step 9: EDITED_INTERNAL Definition & Overwrite Logic
*   **MainActivity.kt:** Implemented `updateSavePanelUI()` with image origin-based logic.
*   **Overwrite Visibility:** Shows/hides Overwrite button based on image origin and canOverwrite flag.
*   **EDITED_INTERNAL:** App-created images get writable URI handling and overwrite capabilities.

### Step 10: CanOverwrite Flag Handling
*   **MainActivity.kt:** Updates UI when canOverwrite flag changes mid-session.
*   **Dynamic UI:** Save panel adapts visibility based on permission state and image origin changes.

### Step 11: Photo Picker API Implementation
*   **MainActivity.kt:** Implemented `openImagePicker()` with Android 13+ Photo Picker (ACTION_PICK).
*   **Fallback:** Uses ACTION_OPEN_DOCUMENT for Android 10-12 with MediaStore API.
*   **Single Selection:** Both APIs enforce single image selection with appropriate intent flags.

### Step 12: Multi-Image Import Rejection
*   **MainActivity.kt:** Added safety checks in `handleIntent()` and `onActivityResult()`.
*   **Rejection Logic:** Shows toast messages for multiple image selections and uses first image only.

### Step 13: Camera Capture with MediaStore URI
*   **MainActivity.kt:** Implemented `captureImageFromCamera()` with writable MediaStore URI creation.
*   **Timestamp Files:** Creates filename with format "IMG_yyyyMMdd_HHmmss" for camera captures.
*   **MediaStore Integration:** Uses ContentResolver.insert() to create writable URI before launching camera.

### Step 14: Bitmap Downsampling Implementation
*   **MainActivity.kt:** Implemented `loadBitmapWithDownsampling()` using BitmapFactory.Options.inSampleSize.
*   **Target Size:** Uses TARGET_IMAGE_SIZE = 2048 pixels to prevent OutOfMemory errors.
*   **Memory Management:** First gets dimensions without loading full bitmap, then calculates appropriate inSampleSize.

### Step 20: Persistable URI Permission
*   **MainActivity.kt:** Added `takePersistableUriPermission()` call in `onActivityResult()`.
*   **Android 10-12:** Requests persistable read URI permission for MediaStore imported images.
*   **Error Handling:** Gracefully handles SecurityException when permission persistence fails.

### Step 16: LRU In-Memory Bitmap/Thumbnail Cache
*   **MainActivity.kt:** Created `BitmapLRUCache` class using LinkedHashMap with maxSize=50.
*   **Cache Implementation:** Simple get/put API with cache key generation from URI and target size.
*   **Integration:** Modified `loadBitmapWithDownsampling()` to check cache first, load from disk only on cache miss.
*   **Memory Management:** Added `onDestroy()` lifecycle hook to clear and recycle cache to prevent leaks.
*   **Performance:** Cache hit/miss logging through toast messages for monitoring.

### Step 17: Bitmap Recycling and Memory Leak Prevention
*   **MainActivity.kt:** Added `currentBitmap` variable to track active bitmap for proper recycling.
*   **Recycling Methods:** Implemented `recycleCurrentBitmap()` and `onToolSwitch()` for memory management.
*   **Load Integration:** Modified `loadImageFromUri()` to recycle current bitmap before loading new one.
*   **Tool Switching:** Added optional bitmap recycling in all tool switch listeners (Draw/Crop/Adjust).
*   **Memory Safety:** Ensures references are nulled and `Bitmap.recycle()` called appropriately.
*   **Lifecycle Integration:** Combined with cache clearing in `onDestroy()` for complete memory cleanup.

### Step 18: Background Thread Offloading with Coroutines
*   **MainActivity.kt:** Added `import kotlinx.coroutines.*` and `import androidx.lifecycle.lifecycleScope`.
*   **Coroutine Implementation:** Made `decodeBitmapFromUri()` a suspend function with `withContext(Dispatchers.IO)`.
*   **Thread Management:** Heavy bitmap decoding moved to background thread, UI updates synchronized to main thread.
*   **LifecycleScope:** Used `lifecycleScope.launch` for proper coroutine management and automatic cancellation.
*   **Async Loading:** `loadBitmapWithDownsampling()` now uses callback pattern to prevent UI blocking.
*   **User Feedback:** Shows "Loading image..." while background processing happens, then "Loaded asynchronously".
*   **Error Handling:** Ensures all UI operations happen on main thread using `withContext(Dispatchers.Main)`.
*   **Performance:** UI remains responsive during image loading, eliminates main thread blocking.
*   **Memory Management:** Proper coroutine cancellation prevents memory leaks during activity lifecycle changes.

### Step 19: Stream Closure and File Descriptor Leak Prevention
*   **InputStream Management:** Enhanced `decodeBitmapFromUri()` with explicit InputStream resource management.
*   **Try-with-Resources:** Used `inputStream.use { }` for automatic stream closure in happy path scenarios.
*   **Finally Blocks:** Added explicit finally blocks in `decodeBitmapFromUri()` and `captureImageFromCamera()`.
*   **OutputStream Management:** Enhanced `captureImageFromCamera()` with proper OutputStream handling.
*   **Error Safety:** Stream closure failures are caught and logged but don't crash the app.
*   **File Descriptor Safety:** Prevents file descriptor leaks by ensuring streams are always closed.
*   **Resource Cleanup:** Both InputStreams and OutputStreams are properly closed in all scenarios.
*   **Memory Leak Prevention:** Eliminates potential file descriptor leaks during image loading and camera operations.
*   **Background Thread Safety:** Stream closure works properly even when called from coroutine background threads.
