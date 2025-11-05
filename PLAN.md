Notes:
- This project is a modified implementation of BaseMax/AndroidAutoBuildAPK. It automates CI for a native Android Gradle project. Do not modify the core workflow logic.
- Use Model Context Protocol from .kilocode to stay updated with updated info.
- preview.html is a visualizer for the user to visualize how the app looks like as close as possible and is meant to be coded manually.
- If plan in conflict with existing project, prioritize project.

Edit SS

UI (top to bottom)
├─ Theme = Dark (fixed)
├─ Google Ad Banner
├─ ActionBar (icons)
│  ├─ Import
│  ├─ Camera
│  ├─ Undo / Redo
│  ├─ Share
│  └─ Save → Panel: SaveCopy and Overwrite (separate Confirm/Cancel), Format (JPG/PNG/WEBP), Transparency warning if needed
├─ Canvas
├─ ToolOptions (contextual)
│  ├─ Draw → Modes: Pen/Circle/Square; Options: Colors, Size/Opacity (sliders); Color/Size/Opacity are shared only among drawing tools
│  ├─ Crop → Mode icons (FreeForm, Square, Portrait, Landscape); selection resets when switching modes
│  └─ Adjust → Sliders for Brightness, Contrast, Saturation
├─ Tools
│  ├─ Draw → See ToolOptions
│  ├─ Crop → See ToolOptions
│  └─ Adjust → See ToolOptions

[DONE]
1. In AndroidManifest.xml, declare intent filters for ACTION_VIEW and ACTION_EDIT with mimeType="image/*", and ensure the activity handles incoming ClipData for multi-image safety.
2. Set android:exported="true" for all activities that include intent filters, to comply with Android 12 and newer manifest requirements.
3. Request READ_MEDIA_IMAGES only when importing images via Photo Picker or ACTION_OPEN_DOCUMENT. Do not request it for camera capture or internal editing workflows. Avoid requesting POST_NOTIFICATIONS unless the app explicitly sends notifications.
4. For Android 10–12, rely on the MediaStore API without requesting legacy storage permissions. Never request WRITE_EXTERNAL_STORAGE or READ_EXTERNAL_STORAGE.
5. When permissions are denied, display a non-blocking dialog: “Permission denied. Please allow access in Settings.” with a button linking to system settings via ACTION_APPLICATION_DETAILS_SETTINGS.
6. Detect if permissions are revoked mid-session (e.g. via onResume) and prompt the user to reopen system settings to re-enable access before proceeding.
7. Handle ACTION_VIEW and ACTION_EDIT intents on launch by checking the incoming Intent data URI and setting the editor state accordingly.
8. Track each image’s origin using an enum with the following members: IMPORTED_READONLY, IMPORTED_WRITABLE, CAMERA_CAPTURED, and EDITED_INTERNAL. Each origin must store a boolean flag canOverwrite. This flag is true if the app has confirmed write access to the image’s URI—either by owning the URI (e.g., CAMERA_CAPTURED, EDITED_INTERNAL) or by holding persistable URI permission (e.g., IMPORTED_WRITABLE).
9. Define EDITED_INTERNAL to represent images created or saved by the app itself. Only show an Overwrite option if the image has a writable URI.
10. If canOverwrite becomes false due to permission loss or URI revocation, disable Overwrite in the UI and default to SaveCopy to preserve data integrity.
11. Use ACTION_OPEN_DOCUMENT for all Android versions with proper intent flags and persistable URI permissions for better compatibility.
12. Reject multi-image imports unless the app explicitly supports multiple ClipData items, to keep behavior predictable.
13. Use FileProvider with private app files directory for camera capture, creating temporary files in getExternalFilesDir() for better compatibility and cleanup.
14. Coil automatically handles image downsampling and memory management - no manual BitmapFactory.Options.inSampleSize implementation needed.
15. Coil automatically provides memory and disk caching - no separate LRU cache implementation needed.
16. Coil handles all bitmap memory management and recycling automatically.
17. Coil automatically handles threading for image loading operations with lifecycle-aware coroutines.
18. Always close InputStreams and OutputStreams in finally blocks (or use try-with-resources) to prevent file descriptor leaks, for both reading imports and writing saves/exports.
19. After image import or capture, call takePersistableUriPermission() if the source URI comes from ACTION_OPEN_DOCUMENT, and store the URI persistently.
20. Implemented complete save system using Coil's ImageRequest API with proper bitmap extraction via imageLoader.execute(request).drawable, ContentValues with IS_PENDING flags, atomic save operations with lifecycleScope.launch(Dispatchers.IO), enhanced MediaStore URI handling with getMediaStoreUriWithId() for format-changing overwrites, and save directory changed to EditSS folder with RELATIVE_PATH = "EditSS/".
21. Implemented both saveImageAsCopy() and overwriteCurrentImage() with proper openOutputStream() usage, IS_PENDING workflow, ActivityResultLauncher for delete confirmations, Coil cache invalidation, and comprehensive error handling with proper UI context switching (IO/Main).
22. When naming saved files, use MS Windows-style duplication naming like "IMG_20241105_093019_copy_2.jpg" with the correct extension matching MIME type (e.g., .jpg → image/jpeg). Use copy number suffix to avoid collisions.
23. Only show the transparency warning if the user selects JPEG or lossy WEBP and the app cannot auto-switch to a lossless format.
24. If transparency is detected and the user selects WEBP, silently switch to lossless WEBP and suppress the transparency warning.
25. On Android 10 and newer, rely on MediaStore for immediate visibility in gallery apps; on Android 9 and older, call MediaScannerConnection.scanFile() after saving.
26. The Save panel shows SaveCopy and Overwrite based on origin and canOverwrite: hide Overwrite for IMPORTED_READONLY; show both for IMPORTED_WRITABLE, CAMERA_CAPTURED, and EDITED_INTERNAL if writable. Additionally, hide Overwrite button when selected format differs from original image format to prevent MediaStore MIME type update conflicts.
27. Use vector drawables for all icons and UI assets to reduce APK size and ensure resolution independence.
28. Make all toolbar buttons toggleable. The active state is indicated by a subtle shade change or highlight color suitable for Dark Mode contrast.
29. Coil provides robust error handling with proper user feedback for import failures including image load attempts and loading state management.
30. Camera capture uses FileProvider with proper error handling and cleanup for temporary files and permission management.
31. If Save fails (e.g. due to write error or low space), show “Save failed. Check storage and try again.” and keep unsaved data in memory.
32. If MediaStore insertion fails during save, show “Save failed. Couldn’t create image entry.” and prevent further write attempts to null URIs.
33. Implemented Coil-based image loading system that automatically handles all bitmap decoding, memory management, caching, and UI synchronization. Coil replaces the previous BitmapManager approach with built-in memory/disk caching and robust error handling.
34. Hide the Overwrite button when the selected format (JPG/PNG/WEBP) differs from the original image's format to prevent MediaStore MIME type update failures. This preserves the working same-format overwrite functionality while avoiding format change conflicts.

35. Fixed a critical save bug by using Coil's API to reliably extract bitmaps for saving, instead of the previously broken method.
36. Added visual icons to the save panel to distinguish between "Save Copy" and "Overwrite" actions.
37. Implemented a bright orange warning text to appear below the format selection when a format that doesn't support transparency (like JPEG) is chosen for a transparent image.
38. The app now automatically detects and pre-selects the original format of an image (JPEG, PNG, or WEBP) when it's loaded.
39. Added loading state tracking to prevent crashes and provide better user feedback during image loading.

[TODO]
1. When sharing a saved image, use its content URI directly. Grant temporary access with FLAG_GRANT_READ_URI_PERMISSION on the share intent.
2. For unsaved edits, export to cache via FileProvider, share that URI, and delete the temporary file after the share intent completes (or schedule a short-lived cleanup).
3. Draw, Circle, and Square tools share global Color, Size, and Opacity values that synchronize across these three tools only.
4. Use Android's native Paint API for drawing operations with proper touch handling, color selection, size/opacity controls, and real-time Canvas rendering.
5. Circle and Square tools are drawn by single-finger dragging, defining both size and position interactively like MS Paint.
6. The Crop tool offers four modes: FreeForm, Square, Portrait, and Landscape. Switching modes resets the current selection.
7. Use Android's native Matrix API for crop transformations with preview overlays, allowing FreeForm, Square, Portrait, and Landscape crop modes with real-time visual feedback.
8. The Adjust tool provides three sliders for Brightness, Contrast, and Saturation adjustments.
9. Use Android's native Paint and ColorFilter APIs for Brightness, Contrast, and Saturation adjustments with real-time preview and non-destructive editing capabilities.
10. Implement pinch-to-zoom centered at the midpoint between fingers. Allow simultaneous pan and zoom for fluid control.
11. Implement pinch/gesture precedence rules so gestures do not conflict: define one-finger for drawing/shape placement, two-finger for zoom/pan, and long-press+drag for selection/move. Block one-finger draw when two fingers are detected, and cancel drawing if a second finger is added mid-stroke.
12. Use StateFlow for undo/redo state management to avoid difficulties and provide reactive UI updates across all tools.
13. Define the edit command structure clearly so that each module (Draw, Crop, Adjust, etc.) can push and revert its own actions independently. Favor delta operations, vector metadata, or region-based diffs over full-bitmap snapshots. Only use downsampled snapshots as a fallback, and cap memory usage aggressively.
14. If Share fails (e.g. no compatible app found), show “No apps available to share this image.” and allow user to retry or save instead.
15. If any editing tool crashes (Crop, Adjust, or Draw), catch exceptions and show “Tool error. Please reset tool and try again.”
16. If URI generation for sharing fails, show “Sharing error. File access not available.” and prevent share intent launch.
17. Use hardware acceleration selectively: enable hardware-accelerated Canvas transforms and view rendering for performance, but disable hardware acceleration for specific Canvas operations that produce artifacts; make this toggleable per-canvas.
18. Limit Undo/Redo memory footprint by storing operation deltas, small region snapshots, or vector metadata when possible; when full-bitmap snapshots are necessary, compress or downsample before storing and keep counts within the defined max depth.
19. Offload image encoding (JPEG/WEBP) and heavy I/O operations to a background worker that can survive short configuration changes (e.g., a retained ViewModel coroutine or WorkManager job) and report completion on the main thread.
20. Always close streams and release file handles in the save/export flows; on failure, remove any partially written MediaStore rows or set IS_PENDING=0 and delete the entry to avoid orphaned entries.
21. Verify no third-party native libraries depend on 32-bit ABIs before excluding those ABIs from the AAB packaging; document the ABI decision in the repo README.