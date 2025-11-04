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

Steps: (Only do 1 each time)
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
11. ~~For Import, open the Android 13+ Photo Picker API. On older versions, use ACTION_OPEN_DOCUMENT with intent.addCategory(CATEGORY_OPENABLE) and type="image/*".~~ **[UPDATE] Use ACTION_OPEN_DOCUMENT for all Android versions with proper intent flags and persistable URI permissions for better compatibility.**
12. Reject multi-image imports unless the app explicitly supports multiple ClipData items, to keep behavior predictable.
13. ~~For Camera capture, create a new writable URI in MediaStore.Images.Media.EXTERNAL_CONTENT_URI before launching the ACTION_IMAGE_CAPTURE intent.~~ **[UPDATE] Use FileProvider with private app files directory for camera capture, creating temporary files in getExternalFilesDir() for better compatibility and cleanup.**
14. ~~When decoding or loading large images (from import, camera, or disk), use BitmapFactory.Options.inSampleSize to downsample appropriately to the target display/working resolution to prevent out-of-memory errors.~~ **[UPDATE] Coil automatically handles image downsampling and memory management - no manual BitmapFactory.Options.inSampleSize implementation needed.**
15. [REMOVED — merged into item 56]
16. ~~Implement an LRU in-memory bitmap/thumbnail cache to store decoded thumbnails and frequently-used bitmaps, exposing a simple get/put API so modules can load from cache first.~~ **[UPDATE] Coil automatically provides memory and disk caching - no separate LRU cache implementation needed.**
17. ~~Release and recycle temporary or unused Bitmaps when switching images or tools to avoid memory leaks; ensure references are nulled and Bitmap.recycle() is called where appropriate.~~ **[UPDATE] Coil handles all bitmap memory management and recycling automatically.**
18. ~~Offload heavy image operations (e.g., applyFilter, adjustBrightness/contrast, big-blob encoding) to background threads using coroutines or an executor; synchronize results back to the UI thread for display.~~ **[UPDATE] Coil automatically handles threading for image loading operations with lifecycle-aware coroutines.**
19. Always close InputStreams and OutputStreams in finally blocks (or use try-with-resources) to prevent file descriptor leaks, for both reading imports and writing saves/exports.
20. After image import or capture, call takePersistableUriPermission() if the source URI comes from ACTION_OPEN_DOCUMENT, and store the URI persistently.
21. When saving, create a ContentValues object and insert it using ContentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).
22. Use openOutputStream() on the URI and write the image data. Set IS_PENDING=1 before writing and IS_PENDING=0 afterward to ensure atomic save completion; ensure cleanup sets IS_PENDING=0 on both success and failure branches.
23. When naming saved files, use a timestamp-based name like "IMG_yyyyMMdd_HHmmss" with the correct extension matching MIME type (e.g., .jpg → image/jpeg) and append a short random suffix to avoid collisions.
24. Only show the transparency warning if the user selects JPEG or lossy WEBP and the app cannot auto-switch to a lossless format.
25. If transparency is detected and the user selects WEBP, silently switch to lossless WEBP and suppress the transparency warning.
26. On Android 10 and newer, rely on MediaStore for immediate visibility in gallery apps; on Android 9 and older, call MediaScannerConnection.scanFile() after saving.
27. When sharing a saved image, use its content URI directly. Grant temporary access with FLAG_GRANT_READ_URI_PERMISSION on the share intent.
28. For unsaved edits, export to cache via FileProvider, share that URI, and delete the temporary file after the share intent completes (or schedule a short-lived cleanup).
29. The Save panel should show SaveCopy and Overwrite based on origin and canOverwrite: hide Overwrite for IMPORTED_READONLY; show both for IMPORTED_WRITABLE, CAMERA_CAPTURED, and EDITED_INTERNAL if writable.
30. Use vector drawables for all icons and UI assets to reduce APK size and ensure resolution independence.
31. Make all toolbar buttons toggleable. The active state is indicated by a subtle shade change or highlight color suitable for Dark Mode contrast.
32. Draw, Circle, and Square tools share global Color, Size, and Opacity values that synchronize across these three tools only.
32.1. **[UPDATE] Draw Tool Implementation**: ~~Implement custom drawing with Canvas, Paint, and touch handling for Pen/Circle/Square modes~~ Use Android's native Paint API for drawing operations with proper touch handling, color selection, size/opacity controls, and real-time Canvas rendering.
33. Circle and Square tools are drawn by single-finger dragging, defining both size and position interactively like MS Paint.
34. The Crop tool offers four modes: FreeForm, Square, Portrait, and Landscape. Switching modes resets the current selection.
34.1. **[UPDATE] Crop Tool Implementation**: ~~Implement crop selection and processing with Canvas overlays and bitmap manipulation~~ Use Android's native Matrix API for crop transformations with preview overlays, allowing FreeForm, Square, Portrait, and Landscape crop modes with real-time visual feedback.
35. The Adjust tool provides three sliders for Brightness, Contrast, and Saturation adjustments.
35.1. **[UPDATE] Adjust Tool Implementation**: ~~Implement image adjustments with Canvas filters and pixel manipulation~~ Use Android's native Paint and ColorFilter APIs for Brightness, Contrast, and Saturation adjustments with real-time preview and non-destructive editing capabilities.
36. Implement pinch-to-zoom centered at the midpoint between fingers. Allow simultaneous pan and zoom for fluid control.
37. Implement pinch/gesture precedence rules so gestures do not conflict: define one-finger for drawing/shape placement, two-finger for zoom/pan, and long-press+drag for selection/move. Block one-finger draw when two fingers are detected, and cancel drawing if a second finger is added mid-stroke.
38. ~~Undo and Redo are global across all tools. Use serialized edit commands stored in a circular buffer with a maximum depth of 10 to manage memory.~~ **[UPDATE] Use StateFlow for undo/redo state management to avoid difficulties and provide reactive UI updates across all tools.**
39. Define the edit command structure clearly so that each module (Draw, Crop, Adjust, etc.) can push and revert its own actions independently. Favor delta operations, vector metadata, or region-based diffs over full-bitmap snapshots. Only use downsampled snapshots as a fallback, and cap memory usage aggressively.
40. ~~Use RecyclerView with setHasFixedSize(true) and proper ViewHolder recycling for any lists/panels (tools, format options, recent items) to minimize layout overhead and GC churn.~~ **[UPDATE] UI uses standard Android layouts with minimal memory overhead - RecyclerView not needed for current implementation.**
41. ~~If Import fails (e.g. user cancels or image cannot be read), show "Couldn't load image. Please try again." and return to the main panel safely.~~ **[UPDATE] Coil provides robust error handling with proper user feedback for import failures including image load attempts and loading state management.**
42. ~~If Camera capture fails or returns null, show "Camera error. No image captured." and reset the capture state.~~ **[UPDATE] Camera capture uses FileProvider with proper error handling and cleanup for temporary files and permission management.**
43. If Save fails (e.g. due to write error or low space), show “Save failed. Check storage and try again.” and keep unsaved data in memory.
44. If Share fails (e.g. no compatible app found), show “No apps available to share this image.” and allow user to retry or save instead.
45. If any editing tool crashes (Crop, Adjust, or Draw), catch exceptions and show “Tool error. Please reset tool and try again.”
46. If URI generation for sharing fails, show “Sharing error. File access not available.” and prevent share intent launch.
47. If MediaStore insertion fails during save, show “Save failed. Couldn’t create image entry.” and prevent further write attempts to null URIs.
48. [REMOVED — merged into item 56]
49. Use hardware acceleration selectively: enable hardware-accelerated Canvas transforms and view rendering for performance, but disable hardware acceleration for specific Canvas operations that produce artifacts; make this toggleable per-canvas.
50. [REMOVED — merged into item 56]
51. Limit Undo/Redo memory footprint by storing operation deltas, small region snapshots, or vector metadata when possible; when full-bitmap snapshots are necessary, compress or downsample before storing and keep counts within the defined max depth.
52. Offload image encoding (JPEG/WEBP) and heavy I/O operations to a background worker that can survive short configuration changes (e.g., a retained ViewModel coroutine or WorkManager job) and report completion on the main thread.
53. Always close streams and release file handles in the save/export flows; on failure, remove any partially written MediaStore rows or set IS_PENDING=0 and delete the entry to avoid orphaned entries.
54. ~~Add a lightweight LRU thumbnail cache accessible to UI modules so lists and panels can show small previews without decoding full bitmaps on the main thread.~~ **[UPDATE] Coil's built-in memory and disk caching provides thumbnail functionality automatically.**
55. Verify no third-party native libraries depend on 32-bit ABIs before excluding those ABIs from the AAB packaging; document the ABI decision in the repo README.
56. ~~All bitmap decoding, mutability checks, recycling, and UI-thread synchronization must be handled by a centralized BitmapManager. This manager ensures imported bitmaps are mutable, recycled when unused, and safely rendered. It replaces items 15, 48, and 50.~~ **[UPDATE] Implemented Coil-based image loading system that automatically handles all bitmap decoding, memory management, caching, and UI synchronization. Coil replaces the previous BitmapManager approach with built-in memory/disk caching and robust error handling.**
