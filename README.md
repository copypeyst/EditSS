SS Edit

1. Core Features:
   - Crop
   - Rotate
   - Draw (freehand on image)
   - Undo/Redo for draw and edits
   - Brightness and contrast sliders
   - Resize (no compression slider)
   - Format conversion: JPEG, PNG, WEBP

2. Offline-first Architecture:
   - No cloud sync or account system
   - All processing done locally

3. Conditional Ad Support:
   - Banner ad at the top only if internet is available
   - Use ConnectivityManager to check network status
   - Load AdMob banner dynamically

4. Save Logic:
   - Save as copy to avoid overwriting originals
   - Format picker for JPEG/PNG/WEBP
   - Handle MIME type and file naming correctly

5. Size Optimization:
   - Target only ARM64 via AAB
   - Use vector drawables for UI assets
   - Strip unused resources and shrink dependencies
   - Enable R8/ProGuard for code minification

5. Locked task: (Do not do)
   - App signing
   - Obfuscation (R8)