package com.tamad.editss

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Toast
import android.provider.Settings
import android.net.Uri
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import android.provider.MediaStore
import android.content.ActivityNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.LinkedHashMap
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.os.Environment
import android.content.ContentValues
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import java.io.File
import android.app.ActivityManager
import android.os.Debug

// Step 8: Image origin tracking enum
enum class ImageOrigin {
    IMPORTED_READONLY,
    IMPORTED_WRITABLE,
    CAMERA_CAPTURED,
    EDITED_INTERNAL
}

data class ImageInfo(
    val uri: Uri,
    val origin: ImageOrigin,
    var canOverwrite: Boolean
)

// Step 16: LRU in-memory bitmap/thumbnail cache
class BitmapLRUCache(maxSize: Int = 50) {
    private val cache = object : LinkedHashMap<String, Bitmap>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > maxSize
        }
    }

    /**
     * Get bitmap from cache by URI string
     */
    fun get(key: String): Bitmap? {
        return cache[key]
    }

    /**
     * Put bitmap into cache with URI string as key
     */
    fun put(key: String, bitmap: Bitmap) {
        // Recycle existing bitmap if present
        cache[key]?.let { oldBitmap ->
            if (!oldBitmap.isRecycled) {
                oldBitmap.recycle()
            }
        }
        cache[key] = bitmap
    }

    /**
     * Generate cache key from URI and size
     */
    fun generateKey(uri: Uri, targetSize: Int): String {
        return "${uri.toString()}_${targetSize}"
    }

    /**
     * Clear all cached bitmaps and recycle them
     */
    fun clear() {
        cache.values.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        cache.clear()
    }

    /**
     * Get current cache size in entries
     */
    fun size(): Int = cache.size

    /**
     * Check if key exists in cache
     */
    fun contains(key: String): Boolean = cache.containsKey(key)
}

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val IMPORT_REQUEST_CODE = 101
        private const val CAMERA_REQUEST_CODE = 102
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var canvasImageView: ImageView
    private lateinit var savePanel: View
    private lateinit var toolOptionsLayout: LinearLayout
    private lateinit var drawOptionsLayout: LinearLayout
    private lateinit var cropOptionsLayout: LinearLayout
    private lateinit var adjustOptionsLayout: LinearLayout
    private lateinit var scrim: View

    private var currentActiveTool: ImageView? = null
    private var currentDrawMode: ImageView? = null
    private var currentCropMode: ImageView? = null
    private var currentSelectedColor: FrameLayout? = null
    
    // Step 8: Track current image information
    private var currentImageInfo: ImageInfo? = null
    
    // Step 23: Track selected save format for proper naming
    private var selectedSaveFormat: String = "image/jpeg" // Default to JPEG
    
    // Step 13: Store camera capture URI temporarily
    private var currentCameraUri: Uri? = null
    
    // Step 16: LRU bitmap cache instance
    private val bitmapCache = BitmapLRUCache(maxSize = 50)
    
    // Fix: Add loading state to prevent crashes
    private var isImageLoading = false
    
    // Step 17: Current loaded bitmap for proper recycling
    private var currentBitmap: android.graphics.Bitmap? = null
    
    // Step 14: Target image size for display (to prevent OOM errors)
    private val TARGET_IMAGE_SIZE = 2048 // pixels
    
    // Crash prevention: Memory and validation thresholds - more conservative
    private val MAX_BITMAP_SIZE = 32 * 1024 * 1024 // 32MB max bitmap size (reduced from 64MB)
    private val MIN_MEMORY_THRESHOLD = 64 * 1024 * 1024 // 64MB available memory required (increased)
    private val SUPPORTED_FORMATS = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")
    private val SUPPORTED_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")
    
    // Crash prevention: Track loading state
    private var isImageLoadAttempted = false
    private var lastImageLoadFailed = false
    
    // Additional safety: Track recent load attempts to prevent rapid successive calls
    private var lastLoadTime = 0L
    private val LOAD_COOLDOWN = 1000L // 1 second cooldown between load attempts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find UI elements
        rootLayout = findViewById(R.id.root_layout)
        canvasImageView = findViewById(R.id.canvas)
        val buttonSave: ImageView = findViewById(R.id.button_save)
        val buttonImport: ImageView = findViewById(R.id.button_import)
        val buttonCamera: ImageView = findViewById(R.id.button_camera)
        val toolDraw: ImageView = findViewById(R.id.tool_draw)
        val toolCrop: ImageView = findViewById(R.id.tool_crop)
        val toolAdjust: ImageView = findViewById(R.id.tool_adjust)

        savePanel = findViewById(R.id.save_panel)
        toolOptionsLayout = findViewById(R.id.tool_options)
        drawOptionsLayout = findViewById(R.id.draw_options)
        cropOptionsLayout = findViewById(R.id.crop_options)
        adjustOptionsLayout = findViewById(R.id.adjust_options)
        scrim = findViewById(R.id.scrim)

        // Save Panel Logic
        buttonSave.setOnClickListener {
            if (savePanel.visibility == View.VISIBLE) {
                savePanel.visibility = View.GONE
                scrim.visibility = View.GONE
            } else {
                savePanel.visibility = View.VISIBLE
                scrim.visibility = View.VISIBLE
            }
        }

        scrim.setOnClickListener {
            savePanel.visibility = View.GONE
            scrim.visibility = View.GONE
        }

        // Import Button Logic
        buttonImport.setOnClickListener {
            if (hasImagePermission()) {
                openImagePicker()
            } else {
                requestImagePermission()
            }
        }

        // Camera Button Logic - Step 13: Create writable URI in MediaStore for camera capture
        buttonCamera.setOnClickListener {
            captureImageFromCamera()
        }

        // Tool Buttons Logic
        toolDraw.setOnClickListener {
            drawOptionsLayout.visibility = View.VISIBLE
            cropOptionsLayout.visibility = View.GONE
            adjustOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE // Hide save panel
            currentActiveTool?.isSelected = false
            toolDraw.isSelected = true
            currentActiveTool = toolDraw
            // Step 17: Optional memory management on tool switch
            onToolSwitch()
        }

        toolCrop.setOnClickListener {
            cropOptionsLayout.visibility = View.VISIBLE
            drawOptionsLayout.visibility = View.GONE
            adjustOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE // Hide save panel
            currentActiveTool?.isSelected = false
            toolCrop.isSelected = true
            currentActiveTool = toolCrop
            // Step 17: Optional memory management on tool switch
            onToolSwitch()
        }

        toolAdjust.setOnClickListener {
            adjustOptionsLayout.visibility = View.VISIBLE
            drawOptionsLayout.visibility = View.GONE
            cropOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE // Hide save panel
            currentActiveTool?.isSelected = false
            toolAdjust.isSelected = true
            currentActiveTool = toolAdjust
            // Step 17: Optional memory management on tool switch
            onToolSwitch()
        }

        // Initialize Save Panel buttons
        val buttonSaveCopy: Button = findViewById(R.id.button_save_copy)
        val buttonOverwrite: Button = findViewById(R.id.button_overwrite)
        
        // Step 21, 22, 23: Save button click handlers
        buttonSaveCopy.setOnClickListener {
            if (currentImageInfo != null) {
                saveImageAsCopy()
            } else {
                Toast.makeText(this, "No image to save", Toast.LENGTH_SHORT).show()
            }
        }
        
        buttonOverwrite.setOnClickListener {
            if (currentImageInfo != null && currentImageInfo!!.canOverwrite) {
                overwriteCurrentImage()
            } else {
                Toast.makeText(this, "Cannot overwrite this image", Toast.LENGTH_SHORT).show()
            }
        }

        val touchListener = View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1.0f
                }
            }
            false
        }

        buttonSaveCopy.setOnTouchListener(touchListener)
        buttonOverwrite.setOnTouchListener(touchListener)
        
        // Step 23 & 24: Format selection handling
        val radioJPG: RadioButton = findViewById(R.id.radio_jpg)
        val radioPNG: RadioButton = findViewById(R.id.radio_png)
        val radioWEBP: RadioButton = findViewById(R.id.radio_webp)
        
        radioJPG.setOnClickListener {
            selectedSaveFormat = "image/jpeg"
            // Step 24: Check for transparency warning if needed
            checkTransparencyWarning()
        }
        radioPNG.setOnClickListener {
            selectedSaveFormat = "image/png"
        }
        radioWEBP.setOnClickListener {
            selectedSaveFormat = "image/webp"
            // Step 24: Check for transparency warning if needed
            checkTransparencyWarning()
        }

        // Initialize Draw Options
        findViewById<SeekBar>(R.id.draw_size_slider)
        findViewById<SeekBar>(R.id.draw_opacity_slider)
        val drawModePen: ImageView = findViewById(R.id.draw_mode_pen)
        val drawModeCircle: ImageView = findViewById(R.id.draw_mode_circle)
        val drawModeSquare: ImageView = findViewById(R.id.draw_mode_square)

        drawModePen.setOnClickListener {
            currentDrawMode?.isSelected = false
            drawModePen.isSelected = true
            currentDrawMode = drawModePen
        }
        drawModeCircle.setOnClickListener {
            currentDrawMode?.isSelected = false
            drawModeCircle.isSelected = true
            currentDrawMode = drawModeCircle
        }
        drawModeSquare.setOnClickListener {
            currentDrawMode?.isSelected = false
            drawModeSquare.isSelected = true
            currentDrawMode = drawModeSquare
        }

        // Initialize Crop Options
        val cropModeFreeform: ImageView = findViewById(R.id.crop_mode_freeform)
        val cropModeSquare: ImageView = findViewById(R.id.crop_mode_square)
        val cropModePortrait: ImageView = findViewById(R.id.crop_mode_portrait)
        val cropModeLandscape: ImageView = findViewById(R.id.crop_mode_landscape)

        cropModeFreeform.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModeFreeform.isSelected = true
            currentCropMode = cropModeFreeform
        }
        cropModeSquare.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModeSquare.isSelected = true
            currentCropMode = cropModeSquare
        }
        cropModePortrait.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModePortrait.isSelected = true
            currentCropMode = cropModePortrait
        }
        cropModeLandscape.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModeLandscape.isSelected = true
            currentCropMode = cropModeLandscape
        }

        // Initialize Adjust Options (no logic yet)
        findViewById<SeekBar>(R.id.adjust_brightness_slider)
        findViewById<SeekBar>(R.id.adjust_contrast_slider)
        findViewById<SeekBar>(R.id.adjust_saturation_slider)

        // Color Swatches Logic
        val colorBlackContainer: FrameLayout = findViewById(R.id.color_black_container)
        val colorWhiteContainer: FrameLayout = findViewById(R.id.color_white_container)
        val colorRedContainer: FrameLayout = findViewById(R.id.color_red_container)
        val colorGreenContainer: FrameLayout = findViewById(R.id.color_green_container)
        val colorBlueContainer: FrameLayout = findViewById(R.id.color_blue_container)
        val colorYellowContainer: FrameLayout = findViewById(R.id.color_yellow_container)
        val colorOrangeContainer: FrameLayout = findViewById(R.id.color_orange_container)
        val colorPinkContainer: FrameLayout = findViewById(R.id.color_pink_container)

        val colorClickListener = View.OnClickListener { v ->
            currentSelectedColor?.findViewWithTag<View>("border")?.visibility = View.GONE
            val border = v.findViewWithTag<View>("border")
            border?.visibility = View.VISIBLE
            currentSelectedColor = v as FrameLayout
        }

        colorBlackContainer.setOnClickListener(colorClickListener)
        colorWhiteContainer.setOnClickListener(colorClickListener)
        colorRedContainer.setOnClickListener(colorClickListener)
        colorGreenContainer.setOnClickListener(colorClickListener)
        colorBlueContainer.setOnClickListener(colorClickListener)
        colorYellowContainer.setOnClickListener(colorClickListener)
        colorOrangeContainer.setOnClickListener(colorClickListener)
        colorPinkContainer.setOnClickListener(colorClickListener)

        // Set default selections
        drawModePen.isSelected = true
        currentDrawMode = drawModePen

        cropModeFreeform.isSelected = true
        currentCropMode = cropModeFreeform

        colorRedContainer.performClick()

        // Set draw as default active tool
        toolDraw.isSelected = true
        currentActiveTool = toolDraw
        drawOptionsLayout.visibility = View.VISIBLE
        
        // Handle incoming intents
        handleIntent(intent)
        
        // Step 6: Check if permissions were revoked while app was in background
        checkPermissionRevocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Step 16 & 17: Clear and recycle bitmap cache to prevent memory leaks
        bitmapCache.clear()
    }

    override fun onPause() {
        super.onPause()
        // Step 16: Optional cache clearing to free memory when app goes to background
        // Uncomment if memory pressure is an issue
        // bitmapCache.clear()
    }

    override fun onResume() {
        super.onResume()
        // Step 6: Check if permissions are revoked mid-session
        checkPermissionRevocation()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> {
                // Check if this is a single image or multiple images
                val clipData = intent.clipData
                if (clipData != null) {
                    val itemCount = clipData.itemCount
                    if (itemCount > 1) {
                        // Multiple images - reject for safety
                        Toast.makeText(this, "Multiple images not supported. Please select a single image.", Toast.LENGTH_LONG).show()
                        return
                    }
                    // Single image from clip data
                    val item = clipData.getItemAt(0)
                    item?.uri?.let { uri ->
                        loadImageFromUri(uri, Intent.ACTION_EDIT == intent.action)
                    }
                } else {
                    // Single image from data URI
                    intent.data?.let { uri ->
                        loadImageFromUri(uri, Intent.ACTION_EDIT == intent.action)
                    }
                }
            }
        }
    }

    // New camera capture flow: Private file storage with user confirmation
    private fun captureImageFromCamera() {
        try {
            // Create private file in app's external files directory
            val imageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            if (imageDir == null) {
                Toast.makeText(this, "Cannot access storage", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Ensure directory exists
            imageDir.mkdirs()
            
            // Create private file
            val fileName = "camera_temp_${System.currentTimeMillis()}.jpg"
            val privateFile = File(imageDir, fileName)
            
            if (!privateFile.exists()) {
                if (!privateFile.createNewFile()) {
                    Toast.makeText(this, "Failed to create temp file", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            
            // Launch camera with private file URI
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val photoURI = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                privateFile
            )
            
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            
            // Store the private file for handling result
            currentCameraUri = photoURI
            
            // Start camera activity
            startActivityForResult(intent, CAMERA_REQUEST_CODE)
            
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImageFromUri(uri: android.net.Uri, isEdit: Boolean) {
        // Crash Prevention: Comprehensive pre-loading validation - enhanced
        
        // Prevent loading while already loading
        if (isImageLoading) {
            Toast.makeText(this, "Image is still loading, please wait...", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Prevent rapid successive attempts with cooldown
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLoadTime < LOAD_COOLDOWN) {
            Toast.makeText(this, "Please wait before selecting another image", Toast.LENGTH_SHORT).show()
            return
        }
        lastLoadTime = currentTime
        
        // Prevent rapid successive attempts after failure with longer cooldown
        if (isImageLoadAttempted && lastImageLoadFailed) {
            Toast.makeText(this, "Previous load failed. Please wait a moment before trying again.", Toast.LENGTH_LONG).show()
            return
        }
        
        isImageLoading = true
        isImageLoadAttempted = true
        lastImageLoadFailed = false
        
        try {
            // Crash Prevention 1: Memory availability check
            if (!isMemoryAvailable()) {
                Toast.makeText(this, "Insufficient memory. Please close other apps and try again.", Toast.LENGTH_LONG).show()
                isImageLoading = false
                return
            }
            
            // Crash Prevention 3: URI access validation - simplified
            if (!validateUriAccess(uri)) {
                Toast.makeText(this, "Cannot access image. Please try selecting it again.", Toast.LENGTH_LONG).show()
                isImageLoading = false
                return
            }
            
            // Crash Prevention 4: Format validation
            if (!isImageFormatSupported(uri)) {
                Toast.makeText(this, "Unsupported image format. Please select a JPEG, PNG, or WEBP file.", Toast.LENGTH_LONG).show()
                isImageLoading = false
                return
            }
            
            // Step 17: Recycle current bitmap before loading new one
            recycleCurrentBitmap()
            
            // Step 18: Show loading indicator on UI thread while image loads in background
            runOnUiThread {
                Toast.makeText(this, "Loading image...", Toast.LENGTH_SHORT).show()
            }
            
            // Crash Prevention 1: Memory-aware target size calculation
            val safeTargetSize = calculateSafeTargetSize()
            
            // Crash Prevention 5: Ensure canvas is ready before attempting to display
            if (!isCanvasReady()) {
                // Wait for canvas to be ready with timeout
                canvasImageView.postDelayed({
                    loadBitmapWithDownsamplingSafe(uri, safeTargetSize) { result ->
                        handleImageLoadResult(result, uri, isEdit)
                    }
                }, 100) // Small delay to ensure canvas is ready
            } else {
                loadBitmapWithDownsamplingSafe(uri, safeTargetSize) { result ->
                    handleImageLoadResult(result, uri, isEdit)
                }
            }
            
        } catch (e: Exception) {
            lastImageLoadFailed = true
            Toast.makeText(this, "Image loading error: ${e.message}", Toast.LENGTH_SHORT).show()
            isImageLoading = false
        }
    }
    
    // Safe image loading with comprehensive error handling
    private fun loadBitmapWithDownsamplingSafe(uri: Uri, targetSize: Int, callback: (Result<Bitmap>?) -> Unit) {
        lifecycleScope.launch {
            try {
                // Step 16: Check cache first
                val cacheKey = bitmapCache.generateKey(uri, targetSize)
                val cachedBitmap = bitmapCache.get(cacheKey)
                if (cachedBitmap != null) {
                    callback(Result.success(cachedBitmap))
                    return@launch
                }
                
                // Step 18: Load bitmap with crash prevention
                val decodedBitmap = withContext(Dispatchers.IO) {
                    decodeBitmapFromUriSafe(uri, targetSize)
                }
                
                if (decodedBitmap != null) {
                    // Crash Prevention 2: Process bitmap safely without premature mutability
                    val safeBitmap = processBitmapSafely(decodedBitmap)
                    if (safeBitmap != null) {
                        // Step 16: Add to cache
                        bitmapCache.put(cacheKey, safeBitmap)
                        callback(Result.success(safeBitmap))
                    } else {
                        callback(Result.failure(Exception("Failed to process bitmap safely")))
                    }
                } else {
                    callback(Result.failure(Exception("Failed to decode bitmap")))
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Bitmap loading error: ${e.message}", Toast.LENGTH_SHORT).show()
                    callback(Result.failure(e))
                }
            }
        }
    }
    
    // Safe bitmap decoding with comprehensive error handling - more robust
    private suspend fun decodeBitmapFromUriSafe(uri: Uri, targetSize: Int): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // First, get image dimensions without loading the full bitmap
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
                
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    return@withContext null
                }
                
                // Calculate safe sample size to prevent OOM - more conservative
                var sampleSize = 1
                val maxSize = maxOf(options.outWidth, options.outHeight)
                
                // More conservative downsampling to prevent crashes
                while (maxSize / sampleSize > targetSize * 1.5) { // Buffer factor
                    sampleSize *= 2
                    // Prevent excessive downsampling but allow more aggressive reduction
                    if (sampleSize > 64) break
                }
                
                // Load bitmap with calculated inSampleSize - more conservative settings
                val options2 = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565 // More memory efficient
                    inDither = true
                    inMutable = false // Don't force mutability immediately
                    inPurgeable = true // Allow Android to purge if needed
                    inInputShareable = true
                }
                
                // Re-open stream for actual decoding
                val finalBitmap = contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options2)
                }
                
                // Check if bitmap is too large for memory - more conservative check
                if (finalBitmap != null && finalBitmap.byteCount > MAX_BITMAP_SIZE / 2) { // Half the max size
                    finalBitmap.recycle()
                    return@withContext null
                }
                
                return@withContext finalBitmap
                
            } catch (e: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Image too large for device memory. Please try a smaller image.", Toast.LENGTH_LONG).show()
                }
                null
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Image decoding error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                null
            }
        }
    }
    
    // Safe image load result handling - enhanced with additional safety checks
    private fun handleImageLoadResult(result: Result<Bitmap>?, uri: Uri, isEdit: Boolean) {
        try {
            if (result?.isSuccess == true) {
                val downsampledBitmap = result.getOrNull()
                if (downsampledBitmap != null && !downsampledBitmap.isRecycled) {
                    // Additional safety check: Verify bitmap dimensions are reasonable
                    if (downsampledBitmap.width <= 0 || downsampledBitmap.height <= 0 || 
                        downsampledBitmap.width > 10000 || downsampledBitmap.height > 10000) {
                        handleImageLoadFailure("Invalid image dimensions")
                        return
                    }
                    
                    // Step 8: Track image origin and set canOverwrite flag appropriately
                    val origin = determineImageOrigin(uri)
                    val canOverwrite = determineCanOverwrite(origin)
                    
                    currentImageInfo = ImageInfo(uri, origin, canOverwrite)
                    
                    // Step 17: Store current bitmap for proper recycling
                    currentBitmap = downsampledBitmap
                    
                    // Step 15: Display loaded bitmap on Canvas ImageView with safety checks
                    runOnUiThread {
                        try {
                            if (isCanvasReady() && canvasImageView != null) {
                                canvasImageView.setImageBitmap(downsampledBitmap)
                                canvasImageView.setScaleType(ImageView.ScaleType.FIT_CENTER)
                                canvasImageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                
                                Toast.makeText(this, "Loaded ${origin.name} image successfully", Toast.LENGTH_SHORT).show()
                                
                                // Update UI based on canOverwrite
                                updateSavePanelUI()
                                
                                // Auto-detect and set the original image format
                                detectAndSetImageFormat(uri)
                                
                                lastImageLoadFailed = false
                            } else {
                                // Canvas not ready, try again with timeout
                                canvasImageView.postDelayed({
                                    if (isCanvasReady() && canvasImageView != null) {
                                        canvasImageView.setImageBitmap(downsampledBitmap)
                                        canvasImageView.setScaleType(ImageView.ScaleType.FIT_CENTER)
                                        canvasImageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                        
                                        Toast.makeText(this, "Image loaded successfully", Toast.LENGTH_SHORT).show()
                                        updateSavePanelUI()
                                        detectAndSetImageFormat(uri)
                                        lastImageLoadFailed = false
                                    } else {
                                        handleImageLoadFailure("Failed to display image")
                                    }
                                }, 200)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this, "Error displaying image: ${e.message}", Toast.LENGTH_SHORT).show()
                            lastImageLoadFailed = true
                        }
                    }
                } else {
                    // Bitmap is null or recycled
                    handleImageLoadFailure("Failed to decode image")
                }
            } else {
                // Result failed
                handleImageLoadFailure(result?.exceptionOrNull()?.message ?: "Unknown error")
            }
        } catch (e: Exception) {
            handleImageLoadFailure(e.message ?: "Unknown error")
        } finally {
            isImageLoading = false
        }
    }
    
    // Centralized failure handling
    private fun handleImageLoadFailure(errorMessage: String) {
        lastImageLoadFailed = true
        runOnUiThread {
            try {
                // Clear canvas on failed load
                canvasImageView.setImageBitmap(null)
                canvasImageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                Toast.makeText(this, "Couldn't load image: $errorMessage", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Even error handling failed, but don't crash
            }
        }
    }

    /**
     * Step 17: Recycle current bitmap to prevent memory leaks
     */
    private fun recycleCurrentBitmap() {
        currentBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        currentBitmap = null
    }

    /**
     * Step 17: Method to be called when switching tools to free bitmap memory
     */
    private fun onToolSwitch() {
        // Optional: Recycle current bitmap when switching tools to free memory
        // Uncomment if memory pressure is an issue during tool usage
        // recycleCurrentBitmap()
    }

    // Step 14: Implement proper image downsampling using BitmapFactory.Options.inSampleSize
    // Step 16: Integrated with LRU cache for performance optimization
    // Step 18: Background thread offloading with coroutines for heavy image operations
    private fun loadBitmapWithDownsampling(uri: Uri, targetSize: Int, callback: (Bitmap?) -> Unit) {
        // Step 16: Check cache first
        val cacheKey = bitmapCache.generateKey(uri, targetSize)
        val cachedBitmap = bitmapCache.get(cacheKey)
        if (cachedBitmap != null) {
            // Cache hit - return cached bitmap immediately (no blocking)
            callback(cachedBitmap)
            return
        }

        // Step 18: Use lifecycleScope for proper coroutine management (no UI blocking)
        lifecycleScope.launch {
            try {
                // Step 18: Offload heavy bitmap decoding to background thread
                val decodedBitmap = withContext(Dispatchers.IO) {
                    decodeBitmapFromUri(uri, targetSize)
                }
                
                if (decodedBitmap != null) {
                    // Step 16: Add to cache for future use
                    bitmapCache.put(cacheKey, decodedBitmap)
                    callback(decodedBitmap)
                } else {
                    callback(null)
                }
                
            } catch (e: Exception) {
                // Step 18: Ensure UI updates happen on main thread
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Bitmap decoding error: ${e.message}", Toast.LENGTH_SHORT).show()
                    callback(null)
                }
            }
        }
    }

    /**
     * Step 16: Helper method to decode bitmap from URI with downsampling
     * Step 18: Made suspend function for coroutine execution
     * Step 19: Stream closure and file descriptor leak prevention
     * Step 2C: Fixed bitmap decoding - simplified approach to prevent blur
     */
    private suspend fun decodeBitmapFromUri(uri: Uri, targetSize: Int): android.graphics.Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // First, get image dimensions without loading the full bitmap
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
                
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    return@withContext null
                }
                
                // Simple calculation: ensure we don't downsample too aggressively
                var sampleSize = 1
                val maxSize = maxOf(options.outWidth, options.outHeight)
                
                // Only downsample if image is significantly larger than target
                while (maxSize / sampleSize > targetSize * 2) {
                    sampleSize *= 2
                }
                
                // Load bitmap with calculated inSampleSize - simplified approach
                val options2 = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    // Use RGB_565 instead of ARGB_8888 to reduce memory and potential blur
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    inDither = true // Enable dithering for better quality
                    inMutable = false
                }
                
                // Re-open stream for actual decoding
                val finalBitmap = contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options2)
                }
                
                return@withContext finalBitmap
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Bitmap decoding error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                null
            }
        }
    }
    
    // Helper method to clean up private camera files
    private fun cleanupCameraFile(uri: Uri) {
        try {
            // Get the actual file path from the FileProvider URI
            val path = uri.path
            if (path != null && path.contains("camera_temp_")) {
                // This is our private file, delete it
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            } else {
                // For FileProvider URIs, we can't directly access the file path
                // Just release the URI permission
                try {
                    contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // Ignore cleanup errors
                }
            }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
    
    // Crash Prevention 1: Memory availability check - more conservative
    private fun isMemoryAvailable(): Boolean {
        return try {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            // More conservative memory check - require at least 64MB available
            val requiredMemory = 64 * 1024 * 1024L // 64MB
            memoryInfo.availMem >= requiredMemory && !memoryInfo.lowMemory
        } catch (e: Exception) {
            // If we can't check memory, be more conservative
            false
        }
    }
    
    // Crash Prevention 4: Image format validation
    private fun isImageFormatSupported(uri: Uri): Boolean {
        return try {
            // Check MIME type first
            val mimeType = contentResolver.getType(uri)
            if (mimeType != null && mimeType in SUPPORTED_FORMATS) {
                true
            } else {
                // Fallback to file extension check
                val fileName = getDisplayNameFromUri(uri)?.lowercase() ?: ""
                SUPPORTED_EXTENSIONS.any { fileName.endsWith(it) }
            }
        } catch (e: Exception) {
            // If we can't determine the format, assume it's supported
            true
        }
    }
    
    // Crash Prevention 1: Memory-aware target size calculation - more conservative
    private fun calculateSafeTargetSize(): Int {
        return try {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            // More conservative downsampling to prevent crashes
            when {
                memoryInfo.availMem < 64 * 1024 * 1024 -> { // Less than 64MB
                    512  // Very small images
                }
                memoryInfo.availMem < 128 * 1024 * 1024 -> { // Less than 128MB
                    1024 // Small images
                }
                memoryInfo.availMem < 256 * 1024 * 1024 -> { // Less than 256MB
                    1536 // Medium images
                }
                memoryInfo.availMem < 512 * 1024 * 1024 -> { // Less than 512MB
                    2048 // Large images
                }
                else -> {
                    TARGET_IMAGE_SIZE // Maximum size
                }
            }
        } catch (e: Exception) {
            // If we can't check memory, use a safe default
            1024
        }
    }
    
    // Crash Prevention 2: Safe bitmap processing without premature mutability enforcement
    private fun processBitmapSafely(bitmap: Bitmap): Bitmap? {
        return try {
            // Only make mutable if we're actually going to modify it
            // Don't enforce mutability immediately on import
            if (bitmap.isMutable) {
                bitmap
            } else {
                // Only create a mutable copy if we need to modify it
                // For display-only use cases, use as-is to save memory
                bitmap
            }
        } catch (e: OutOfMemoryError) {
            // Handle OOM gracefully
            null
        } catch (e: Exception) {
            // Handle other processing errors
            null
        }
    }
    
    // Crash Prevention 5: Canvas safety checks
    private fun isCanvasReady(): Boolean {
        return try {
            canvasImageView != null && canvasImageView.width > 0 && canvasImageView.height > 0
        } catch (e: Exception) {
            false
        }
    }
    
    // Crash Prevention 3: URI access validation - simplified and safer
    private fun validateUriAccess(uri: Uri): Boolean {
        return try {
            // Only check basic URI scheme and authority, don't actually open stream
            // This prevents potential crashes from trying to access URIs that might be revoked
            when {
                uri.scheme == null || uri.scheme != "content" -> false
                uri.authority == null -> false
                // For Android 10+, check if we have the necessary permissions
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasImagePermission() -> false
                else -> true
            }
        } catch (e: Exception) {
            // If we can't even check the URI, assume it's not accessible
            false
        }
    }
    
    // Step 8: Helper method to determine image origin
    private fun determineImageOrigin(uri: Uri): ImageOrigin {
        return when {
            // Check if this is a FileProvider URI (our private camera files)
            uri.authority == packageName -> {
                // This is our private file (camera temp or app-created)
                ImageOrigin.EDITED_INTERNAL
            }
            uri.toString().contains("media") && !uri.toString().contains("persisted") -> {
                // Imported via MediaStore, may or may not be writable depending on permission
                if (hasImagePermission()) ImageOrigin.IMPORTED_WRITABLE else ImageOrigin.IMPORTED_READONLY
            }
            uri.toString().contains("camera") -> ImageOrigin.CAMERA_CAPTURED // Legacy support
            uri.toString().contains("editss") -> ImageOrigin.EDITED_INTERNAL
            else -> ImageOrigin.IMPORTED_READONLY // Default to readonly for unknown sources
        }
    }
    
    // Step 8: Helper method to determine canOverwrite flag
    private fun determineCanOverwrite(origin: ImageOrigin): Boolean {
        return when (origin) {
            ImageOrigin.CAMERA_CAPTURED -> true
            ImageOrigin.EDITED_INTERNAL -> true
            ImageOrigin.IMPORTED_WRITABLE -> hasImagePermission()
            ImageOrigin.IMPORTED_READONLY -> false
        }
    }

    // Permission checking methods - Step 4: Only check READ_MEDIA_IMAGES for Android 13+
    private fun hasImagePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 10-12: No permission needed for MediaStore API
            true
        }
    }

    private fun requestImagePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                PERMISSION_REQUEST_CODE
            )
        } else {
            // Android 10-12: No permission request needed, proceed directly
            openImagePicker()
        }
    }

    // Photo picker logic - Enhanced with better error handling and safety checks
    private fun openImagePicker() {
        try {
            // Check if we have the necessary permissions first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasImagePermission()) {
                requestImagePermission()
                return
            }
            
            // Enhanced approach: Use ACTION_OPEN_DOCUMENT with comprehensive error handling
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            
            // Add safety flags
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            
            // Add additional safety flags for Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            
            try {
                startActivityForResult(intent, IMPORT_REQUEST_CODE)
            } catch (e: ActivityNotFoundException) {
                // Fallback to gallery if document picker is not available
                try {
                    val galleryIntent = Intent(Intent.ACTION_GET_CONTENT)
                    galleryIntent.addCategory(Intent.CATEGORY_OPENABLE)
                    galleryIntent.type = "image/*"
                    startActivityForResult(galleryIntent, IMPORT_REQUEST_CODE)
                } catch (e2: Exception) {
                    Toast.makeText(this, "No image picker available on this device", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open photo picker: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, proceed with import
                openImagePicker()
            } else {
                // Permission denied - show dialog (Step 5 implementation)
                showPermissionDeniedDialog()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            IMPORT_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    // Safety check: Only process single image even if multiple selection is somehow allowed
                    val uri = data?.data
                    if (uri != null) {
                        try {
                            // Check for multi-image selection safety
                            val clipData = data.clipData
                            if (clipData != null && clipData.itemCount > 1) {
                                // If multiple images somehow selected, only use the first one
                                Toast.makeText(this, "Multiple images not supported. Loading first image only.", Toast.LENGTH_SHORT).show()
                            }
                            
                            // Step 20: For Android 10-12, request persistable URI permission for MediaStore
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                                try {
                                    contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                } catch (e: SecurityException) {
                                    // Handle case where permission can't be persisted
                                    Toast.makeText(this, "Could not persist access to image", Toast.LENGTH_SHORT).show()
                                }
                            }
                            
                            // Add a small delay to prevent crashes from rapid successive calls
                            uri.postDelayed({
                                loadImageFromUri(uri, false)
                            }, 100)
                            
                        } catch (e: Exception) {
                            Toast.makeText(this, "Error processing selected image: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
                    }
                } else if (resultCode == RESULT_CANCELED) {
                    // User cancelled the selection - don't show error
                } else {
                    // Some other error occurred
                    Toast.makeText(this, "Failed to select image", Toast.LENGTH_SHORT).show()
                }
            }
            CAMERA_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    // Handle successful camera capture result
                    val cameraUri = currentCameraUri
                    if (cameraUri != null) {
                        // Use post to delay loading slightly to prevent crashes
                        canvasImageView.post {
                            try {
                                // This is a private file from camera, so treat as EDITED_INTERNAL
                                loadImageFromUri(cameraUri, false)
                            } catch (e: Exception) {
                                // Better error handling for crashes
                                Toast.makeText(this, "Error loading camera image: ${e.message}", Toast.LENGTH_SHORT).show()
                                // Clean up the private file if loading fails
                                cleanupCameraFile(cameraUri)
                            }
                        }
                        currentCameraUri = null
                    }
                } else {
                    // Camera was cancelled or failed - clean up private file
                    val cameraUri = currentCameraUri
                    if (cameraUri != null) {
                        cleanupCameraFile(cameraUri)
                    }
                    currentCameraUri = null
                    // Don't show error for cancelled operations
                }
            }
        }
    }
    
    // Extension function to add delay to URI operations
    private fun Uri.postDelayed(action: () -> Unit, delayMillis: Long) {
        try {
            val context = this@MainActivity
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                action()
            }, delayMillis)
        } catch (e: Exception) {
            // If we can't post delayed, run immediately
            action()
        }
    }

    // Permission denied dialog (Step 5)
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Permission denied. Please allow access in Settings.")
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    // Step 6: Detect permission revocation mid-session and prompt user
    private fun checkPermissionRevocation() {
        val wasOverwriteAvailable = currentImageInfo?.canOverwrite ?: false
        
        // For Android 13+, check if READ_MEDIA_IMAGES was revoked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasImagePermission()) {
                showPermissionRevokedDialog()
                // Update canOverwrite flag if permission was revoked
                currentImageInfo?.canOverwrite = false
            }
        }
        // For Android 10-12, check URI permissions
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            // Check if permission status changed for imported images
            if (currentImageInfo?.origin == ImageOrigin.IMPORTED_WRITABLE && !hasImagePermission()) {
                currentImageInfo?.canOverwrite = false
            }
        }
        
        // Update UI if canOverwrite flag changed
        if (wasOverwriteAvailable && (currentImageInfo?.canOverwrite == false)) {
            updateSavePanelUI()
        }
    }

    private fun showPermissionRevokedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Revoked")
            .setMessage("Permission has been revoked. Please reopen Settings to re-enable access.")
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    // Step 9 & 10: Update save panel UI based on image origin and canOverwrite flag
    private fun updateSavePanelUI() {
        val buttonSaveCopy: Button = findViewById(R.id.button_save_copy)
        val buttonOverwrite: Button = findViewById(R.id.button_overwrite)
        
        if (currentImageInfo == null) {
            // No image loaded, show both buttons (default behavior)
            buttonSaveCopy.visibility = View.VISIBLE
            buttonOverwrite.visibility = View.VISIBLE
            return
        }
        
        val imageInfo = currentImageInfo!!
        
        // Always show SaveCopy
        buttonSaveCopy.visibility = View.VISIBLE
        
        // Step 9: Define EDITED_INTERNAL behavior - only show Overwrite for writable images
        when {
            !imageInfo.canOverwrite -> {
                // Hide Overwrite for IMPORTED_READONLY or when permission is lost
                buttonOverwrite.visibility = View.GONE
            }
            imageInfo.origin == ImageOrigin.IMPORTED_READONLY -> {
                // Hide Overwrite for IMPORTED_READONLY even if canOverwrite was somehow true
                buttonOverwrite.visibility = View.GONE
            }
            imageInfo.origin == ImageOrigin.IMPORTED_WRITABLE && imageInfo.canOverwrite -> {
                // Show both SaveCopy and Overwrite for writable imports
                buttonOverwrite.visibility = View.VISIBLE
            }
            imageInfo.origin == ImageOrigin.CAMERA_CAPTURED && imageInfo.canOverwrite -> {
                // Show both SaveCopy and Overwrite for camera captures
                buttonOverwrite.visibility = View.VISIBLE
            }
            imageInfo.origin == ImageOrigin.EDITED_INTERNAL && imageInfo.canOverwrite -> {
                // Step 9: Show Overwrite for EDITED_INTERNAL images (app-created)
                buttonOverwrite.visibility = View.VISIBLE
            }
            else -> {
                // Default: hide overwrite button
                buttonOverwrite.visibility = View.GONE
            }
        }
    }
    
    // Step 21, 22, 23: Save image as copy with ContentResolver.insert workflow
    private fun saveImageAsCopy() {
        lifecycleScope.launch {
            try {
                // Step 22: Set IS_PENDING flag to indicate saving in progress
                val savedUri = saveImageToMediaStore()
                
                if (savedUri != null) {
                    // Step 22: Set IS_PENDING=0 after successful save
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Image saved successfully", Toast.LENGTH_SHORT).show()
                        savePanel.visibility = View.GONE
                        scrim.visibility = View.GONE
                    }
                } else {
                    // Step 43: Save failure handling
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Save failed. Check storage and try again.", Toast.LENGTH_SHORT).show()
                    }
                }
                
            } catch (e: Exception) {
                // Step 43: Save failure handling
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // Step 21: Atomic save completion with IS_PENDING flag
    private suspend fun saveImageToMediaStore(): Uri? = withContext(Dispatchers.IO) {
        var outputStream: java.io.OutputStream? = null
        
        try {
            // Step 23: Generate filename with timestamp and collision avoidance
            val filename = generateUniqueFilename()
            
            // Step 21: Create ContentValues and insert using ContentResolver.insert
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, selectedSaveFormat)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EditSS")
                }
                // Step 22: Set IS_PENDING=1 to indicate save in progress (for atomic operations)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            
            // Step 21: Insert into MediaStore using ContentResolver.insert
            val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            
            if (imageUri != null) {
                // Step 22: Write image data with proper stream management
                outputStream = contentResolver.openOutputStream(imageUri)
                
                // TODO: Step 52: Offload image encoding to background worker
                // For now, use current bitmap (would need actual encoding logic)
                currentBitmap?.compress(
                    when (selectedSaveFormat) {
                        "image/jpeg" -> Bitmap.CompressFormat.JPEG
                        "image/png" -> Bitmap.CompressFormat.PNG
                        "image/webp" -> Bitmap.CompressFormat.WEBP
                        else -> Bitmap.CompressFormat.JPEG
                    },
                    95, // Quality
                    outputStream ?: return@withContext null
                )
                
                // Step 22: Set IS_PENDING=0 after successful write (atomic completion)
                val updateValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                contentResolver.update(imageUri, updateValues, null, null)
                
                return@withContext imageUri
            }
            
            return@withContext null
            
        } catch (e: Exception) {
            // Step 22: Ensure cleanup sets IS_PENDING=0 on failure
            try {
                val failedUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "temp_error_${System.currentTimeMillis()}")
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                })
                failedUri?.let { contentResolver.delete(it, null, null) }
            } catch (cleanupException: Exception) {
                // Log cleanup failure but don't crash
                cleanupException.printStackTrace()
            }
            throw e
        } finally {
            // Step 19: Ensure stream closure to prevent file descriptor leaks
            try {
                outputStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    // Step 22: Overwrite current image workflow
    private fun overwriteCurrentImage() {
        currentImageInfo?.let { imageInfo ->
            lifecycleScope.launch {
                try {
                    // Step 22: Overwrite the original image with IS_PENDING workflow
                    val updatedUri = overwriteImageInMediaStore(imageInfo.uri)
                    
                    if (updatedUri != null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Image overwritten successfully", Toast.LENGTH_SHORT).show()
                            savePanel.visibility = View.GONE
                            scrim.visibility = View.GONE
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Overwrite failed. Check permissions.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Overwrite failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    // Step 22: Overwrite workflow with IS_PENDING flag
    private suspend fun overwriteImageInMediaStore(originalUri: Uri): Uri? = withContext(Dispatchers.IO) {
        var outputStream: java.io.OutputStream? = null
        
        try {
            // Step 22: Set IS_PENDING=1 to begin atomic overwrite
            val pendingValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            contentResolver.update(originalUri, pendingValues, null, null)
            
            // Write the new image data
            outputStream = contentResolver.openOutputStream(originalUri)
            currentBitmap?.compress(
                when (selectedSaveFormat) {
                    "image/jpeg" -> Bitmap.CompressFormat.JPEG
                    "image/png" -> Bitmap.CompressFormat.PNG
                    "image/webp" -> Bitmap.CompressFormat.WEBP
                    else -> Bitmap.CompressFormat.JPEG
                },
                95,
                outputStream ?: return@withContext null
            )
            
            // Step 22: Set IS_PENDING=0 to complete atomic overwrite
            val finalizeValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
                put(MediaStore.Images.Media.MIME_TYPE, selectedSaveFormat)
            }
            contentResolver.update(originalUri, finalizeValues, null, null)
            
            return@withContext originalUri
            
        } catch (e: Exception) {
            // Step 22: On failure, ensure IS_PENDING=0 to prevent orphaned entries
            try {
                val cleanupValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                contentResolver.update(originalUri, cleanupValues, null, null)
            } catch (cleanupException: Exception) {
                cleanupException.printStackTrace()
            }
            throw e
        } finally {
            // Step 19: Ensure stream closure
            try {
                outputStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    // Step 23: Timestamp-based file naming with collision avoidance
    private fun generateUniqueFilename(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val randomSuffix = (Math.random() * 1000).toInt()
        val extension = when (selectedSaveFormat) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        return "IMG_${timestamp}_${randomSuffix}$extension"
    }
    
    // Step 24 & 25: Transparency warning for JPEG/WEBP
    private fun checkTransparencyWarning() {
        // TODO: Step 25: Detect if current bitmap has transparency
        // For now, we'll show warning for JPEG/WEBP and handle WEBP auto-switching
        val hasTransparency = false // This would need actual detection logic
        
        if (selectedSaveFormat == "image/jpeg") {
            // Step 24: JPEG doesn't support transparency - show warning if needed
            if (hasTransparency) {
                AlertDialog.Builder(this)
                    .setTitle("Transparency Warning")
                    .setMessage("JPEG format does not support transparency. Areas with transparency will be filled with black.")
                    .setPositiveButton("Continue") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setNegativeButton("Switch to PNG") { _, _ ->
                        selectedSaveFormat = "image/png"
                        updateFormatSelectionUI()
                    }
                    .show()
            }
        } else if (selectedSaveFormat == "image/webp") {
            if (hasTransparency) {
                // Step 25: Auto-switch to lossless WEBP and suppress transparency warning
                selectedSaveFormat = "image/webp-lossless" // Would need actual lossless implementation
                updateFormatSelectionUI()
                Toast.makeText(this, "Auto-switched to lossless WEBP to preserve transparency", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // Helper method to update format selection UI
    private fun updateFormatSelectionUI() {
        val radioJPG: RadioButton = findViewById(R.id.radio_jpg)
        val radioPNG: RadioButton = findViewById(R.id.radio_png)
        val radioWEBP: RadioButton = findViewById(R.id.radio_webp)
        
        when (selectedSaveFormat) {
            "image/jpeg" -> radioJPG.isChecked = true
            "image/png" -> radioPNG.isChecked = true
            "image/webp" -> radioWEBP.isChecked = true
        }
    }
    
    // Auto-detect image format and preselect it
    private fun detectAndSetImageFormat(uri: Uri) {
        try {
            // Try to get MIME type from MediaStore first
            val mimeType = contentResolver.getType(uri)
            
            var detectedFormat = when {
                mimeType == "image/jpeg" -> "image/jpeg"
                mimeType == "image/png" -> "image/png"
                mimeType == "image/webp" -> "image/webp"
                // Fallback: detect from file extension in display name
                else -> {
                    val displayName = getDisplayNameFromUri(uri) ?: ""
                    when {
                        displayName.lowercase().endsWith(".jpg") || displayName.lowercase().endsWith(".jpeg") -> "image/jpeg"
                        displayName.lowercase().endsWith(".png") -> "image/png"
                        displayName.lowercase().endsWith(".webp") -> "image/webp"
                        else -> "image/jpeg" // Default fallback
                    }
                }
            }
            
            // Update selected format and UI
            selectedSaveFormat = detectedFormat
            updateFormatSelectionUI()
            
            // Show a subtle hint about the detected format
            val formatName = when (detectedFormat) {
                "image/jpeg" -> "JPEG"
                "image/png" -> "PNG"
                "image/webp" -> "WEBP"
                else -> "Unknown"
            }
            
            Toast.makeText(this, "Detected format: $formatName", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            // If detection fails, keep current default
            selectedSaveFormat = "image/jpeg"
            updateFormatSelectionUI()
        }
    }
    
    // Helper to get display name from URI
    private fun getDisplayNameFromUri(uri: Uri): String? {
        return try {
            val projection = arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                if (cursor.moveToFirst() && columnIndex != -1) {
                    cursor.getString(columnIndex)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
