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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Canvas
import android.os.Environment
import android.content.ContentValues
import android.media.MediaScannerConnection
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.OutputStream
import coil.ImageLoader
import coil.request.ImageRequest
import coil.disk.DiskCache
import coil.memory.MemoryCache

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

// Coil handles all caching, memory management, and bitmap processing automatically

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
    private lateinit var transparencyWarningText: TextView

    private var currentActiveTool: ImageView? = null
    private var currentDrawMode: ImageView? = null
    private var currentCropMode: ImageView? = null
    private var currentSelectedColor: FrameLayout? = null
    
    // Step 8: Track current image information
    private var currentImageInfo: ImageInfo? = null
    
    // Step 23: Track selected save format for proper naming
    private var selectedSaveFormat: String = "image/jpeg" // Default to JPEG
    
    // Track if current image has transparency
    private var currentImageHasTransparency = false
    
    // Step 13: Store camera capture URI temporarily
    private var currentCameraUri: Uri? = null
    
    // Fix: Add loading state to prevent crashes
    private var isImageLoading = false
    
    // Coil-based image loading state
    private var isImageLoadAttempted = false
    private var lastImageLoadFailed = false
    
    // Coil image loader for efficient image handling
    private val imageLoader by lazy {
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir)
                    .maxSizeBytes(50 * 1024 * 1024) // 50MB
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

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
        transparencyWarningText = findViewById(R.id.transparency_warning_text)

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
            // Update transparency warning based on actual image content
            updateTransparencyWarning()
        }
        radioPNG.setOnClickListener {
            selectedSaveFormat = "image/png"
            // PNG supports transparency, so hide warning
            updateTransparencyWarning()
        }
        radioWEBP.setOnClickListener {
            selectedSaveFormat = "image/webp"
            // Update transparency warning based on actual image content
            updateTransparencyWarning()
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
        // Coil handles all memory management automatically
    }

    override fun onPause() {
        super.onPause()
        // Coil handles memory management automatically
        // No need for manual cache clearing
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

    // Simple Coil-based image loading - replaces all complex crash prevention logic
    private fun loadImageFromUri(uri: android.net.Uri, isEdit: Boolean) {
        // Prevent loading while already loading
        if (isImageLoading) {
            Toast.makeText(this, "Image is still loading, please wait...", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Prevent rapid successive attempts after failure
        if (isImageLoadAttempted && lastImageLoadFailed) {
            Toast.makeText(this, "Previous load failed. Please try a different image.", Toast.LENGTH_LONG).show()
            return
        }
        
        isImageLoading = true
        isImageLoadAttempted = true
        lastImageLoadFailed = false
        
        try {
            // Show loading indicator
            Toast.makeText(this, "Loading image...", Toast.LENGTH_SHORT).show()
            
            // Create Coil image request
            val request = ImageRequest.Builder(this)
                .data(uri)
                .target { drawable ->
                    // Success callback
                    runOnUiThread {
                        try {
                            // Step 8: Track image origin and set canOverwrite flag appropriately
                            val origin = determineImageOrigin(uri)
                            val canOverwrite = determineCanOverwrite(origin)
                            
                            currentImageInfo = ImageInfo(uri, origin, canOverwrite)
                            
                            // Display the loaded image
                            canvasImageView.setImageDrawable(drawable)
                            canvasImageView.setScaleType(ImageView.ScaleType.FIT_CENTER)
                            canvasImageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            
                            Toast.makeText(this, "Loaded ${origin.name} image successfully", Toast.LENGTH_SHORT).show()
                            
                            // Update UI based on canOverwrite
                            updateSavePanelUI()
                            
                            // Auto-detect and set the original image format
                            detectAndSetImageFormat(uri)
                            
                            // Detect transparency for warning system (simplified approach)
                            try {
                                val drawable = canvasImageView.drawable
                                if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                                    currentImageHasTransparency = detectImageTransparencyFromDrawable(drawable)
                                    updateTransparencyWarning()
                                }
                            } catch (e: Exception) {
                                // Fallback: assume no transparency if detection fails
                                currentImageHasTransparency = false
                                updateTransparencyWarning()
                            }
                            
                            lastImageLoadFailed = false
                        } catch (e: Exception) {
                            handleImageLoadFailure("Error displaying image: ${e.message}")
                        } finally {
                            isImageLoading = false
                        }
                    }
                }
                .error(R.drawable.ic_launcher_background) // Use existing drawable as error placeholder
                .listener(
                    onStart = {
                        // Loading started
                    },
                    onSuccess = { _, _ ->
                        // Loading successful
                    },
                    onError = { _, result ->
                        // Loading failed
                        handleImageLoadFailure(result.throwable?.message ?: "Unknown error")
                        isImageLoading = false
                    }
                )
                .build()
            
            // Execute the request with Coil
            imageLoader.enqueue(request)
            
        } catch (e: Exception) {
            handleImageLoadFailure("Image loading error: ${e.message}")
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
     * Step 17: Method to be called when switching tools
     * Coil handles all memory management automatically
     */
    private fun onToolSwitch() {
        // No manual memory management needed with Coil
    }

    // All bitmap loading, caching, downsampling, and memory management is now handled automatically by Coil
    
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

    // Photo picker logic - Simplified and reliable implementation
    private fun openImagePicker() {
        try {
            // Simplified approach: Use ACTION_OPEN_DOCUMENT with better error handling
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            
            // Add safety flags
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            
            startActivityForResult(intent, IMPORT_REQUEST_CODE)
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
                        loadImageFromUri(uri, false)
                    }
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
    
    // Step 21, 22, 23: Save image as copy - proper MediaStore implementation using Coil
    private fun saveImageAsCopy() {
        lifecycleScope.launch {
            try {
                // Get the current canvas image (with any edits/drawings) using Coil
                val bitmap = getCanvasBitmap()
                if (bitmap != null) {
                    // Step 21: Create ContentValues and insert into MediaStore
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, generateUniqueFilename())
                        put(MediaStore.Images.Media.MIME_TYPE, selectedSaveFormat)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                        put(MediaStore.Images.Media.IS_PENDING, 1) // Step 22: Mark as pending for atomic save
                    }
                    
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        // Step 22: Write the image data using openOutputStream
                        contentResolver.openOutputStream(uri)?.use { outputStream ->
                            compressBitmapToStream(bitmap, outputStream, selectedSaveFormat)
                        }
                        
                        // Step 22: Mark as complete (atomic save)
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        
                        // Step 26: MediaScannerConnection for Android 9 and older
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                            try {
                                contentResolver.openInputStream(uri)?.use { inputStream ->
                                    val filePath = getRealPathFromUri(uri)
                                    if (filePath != null) {
                                        MediaScannerConnection.scanFile(
                                            this@MainActivity,
                                            arrayOf(filePath),
                                            arrayOf(selectedSaveFormat),
                                            null
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                // MediaScannerConnection is not critical, just log the error
                            }
                        }
                        
                        Toast.makeText(this@MainActivity, "Image saved successfully", Toast.LENGTH_SHORT).show()
                        savePanel.visibility = View.GONE
                        scrim.visibility = View.GONE
                    } else {
                        Toast.makeText(this@MainActivity, "Save failed: Couldn't create image entry", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "No image to save", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // Step 22: Overwrite current image - proper implementation using Coil
    private fun overwriteCurrentImage() {
        currentImageInfo?.let { imageInfo ->
            if (imageInfo.canOverwrite) {
                lifecycleScope.launch {
                    try {
                        // Get the current canvas image (with any edits/drawings) using Coil
                        val bitmap = getCanvasBitmap()
                        if (bitmap != null) {
                            // For writable URIs, we can overwrite the existing file
                            contentResolver.openOutputStream(imageInfo.uri)?.use { outputStream ->
                                compressBitmapToStream(bitmap, outputStream, selectedSaveFormat)
                            }
                            
                            Toast.makeText(this@MainActivity, "Image overwritten successfully", Toast.LENGTH_SHORT).show()
                            savePanel.visibility = View.GONE
                            scrim.visibility = View.GONE
                        } else {
                            Toast.makeText(this@MainActivity, "No image to overwrite", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Overwrite failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Cannot overwrite this image", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(this, "No image to overwrite", Toast.LENGTH_SHORT).show()
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
    
    // Step 24 & 25: Update transparency warning based on actual image content
    private fun updateTransparencyWarning() {
        if (currentImageHasTransparency) {
            when {
                selectedSaveFormat == "image/jpeg" -> {
                    // JPEG doesn't support transparency
                    transparencyWarningText.text = "JPG doesn't support transparency"
                    transparencyWarningText.visibility = View.VISIBLE
                }
                selectedSaveFormat == "image/webp" && isLosslessWebP() -> {
                    // Lossless WEBP supports transparency, so hide warning
                    transparencyWarningText.visibility = View.GONE
                }
                else -> {
                    // PNG and lossless WEBP support transparency, hide warning
                    transparencyWarningText.visibility = View.GONE
                }
            }
        } else {
            // No transparency detected, hide warning
            transparencyWarningText.visibility = View.GONE
        }
    }
    
    // Helper to determine if we should use lossless WEBP
    private fun isLosslessWebP(): Boolean {
        return selectedSaveFormat == "image/webp" && currentImageHasTransparency
    }
    
    // Step 25: Detect if the current image has transparency from bitmap
    private fun detectImageTransparency(bitmap: Bitmap): Boolean {
        try {
            // Sample a few pixels to detect transparency
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(minOf(100, width * height)) // Sample up to 100 pixels
            
            // Sample pixels from different parts of the image
            for (i in pixels.indices) {
                val x = (i * width / pixels.size) % width
                val y = (i * height / pixels.size) / width
                val pixel = bitmap.getPixel(x, y)
                
                // Check if pixel is fully transparent (alpha = 0)
                if (android.graphics.Color.alpha(pixel) < 255) {
                    return true
                }
            }
        } catch (e: Exception) {
            // If transparency detection fails, assume no transparency for safety
            return false
        }
        return false
    }
    
    // Helper method to detect transparency from drawable (improved approach)
    private fun detectImageTransparencyFromDrawable(drawable: android.graphics.drawable.Drawable): Boolean {
        try {
            // Get the current image info and detect format from the actual image
            val currentImageInfo = this.currentImageInfo
            if (currentImageInfo != null) {
                // Try to get the MIME type from the actual image
                val mimeType = contentResolver.getType(currentImageInfo.uri)
                
                return when (mimeType) {
                    "image/png", "image/webp" -> {
                        // PNG and WEBP can have transparency
                        // For better detection, we'd need bitmap sampling, but this is safer
                        true
                    }
                    else -> {
                        // JPEG doesn't support transparency
                        false
                    }
                }
            }
        } catch (e: Exception) {
            // If detection fails, assume no transparency for safety
        }
        
        // Default fallback based on current selected format
        return when (selectedSaveFormat) {
            "image/png", "image/webp" -> true
            else -> false
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
    
    // Helper to get the current canvas bitmap for saving using current ImageView
    private suspend fun getCanvasBitmap(): Bitmap? {
        return try {
            // Get the current drawable from the ImageView (which contains any edits)
            val drawable = canvasImageView.drawable
            
            if (drawable != null) {
                // Get the intrinsic dimensions of the actual image
                val imageWidth = drawable.intrinsicWidth
                val imageHeight = drawable.intrinsicHeight
                
                if (imageWidth > 0 && imageHeight > 0) {
                    // Create bitmap with actual image dimensions
                    val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    
                    // Set the drawable bounds to the full bitmap area
                    drawable.setBounds(0, 0, imageWidth, imageHeight)
                    
                    // Draw the content
                    drawable.draw(canvas)
                    bitmap
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    // Helper to compress bitmap to output stream in selected format
    private fun compressBitmapToStream(bitmap: Bitmap, outputStream: OutputStream, mimeType: String) {
        try {
            val compressFormat = when (mimeType) {
                "image/jpeg" -> CompressFormat.JPEG
                "image/png" -> CompressFormat.PNG
                "image/webp" -> CompressFormat.WEBP
                else -> CompressFormat.JPEG
            }
            
            val quality = when (mimeType) {
                "image/jpeg", "image/webp" -> 95 // High quality for lossy formats
                else -> 100 // Lossless for PNG
            }
            
            bitmap.compress(compressFormat, quality, outputStream)
        } catch (e: Exception) {
            throw Exception("Failed to compress image: ${e.message}")
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
    
    // Helper to get real file path from content URI for MediaScannerConnection
    private fun getRealPathFromUri(uri: Uri): String? {
        return try {
            val projection = arrayOf(android.provider.MediaStore.Images.Media.DATA)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DATA)
                if (cursor.moveToFirst() && columnIndex != -1) {
                    cursor.getString(columnIndex)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
