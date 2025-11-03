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
import android.graphics.BitmapFactory

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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val IMPORT_REQUEST_CODE = 101
        private const val CAMERA_REQUEST_CODE = 102
    }

    private lateinit var rootLayout: FrameLayout
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
    
    // Step 13: Store camera capture URI temporarily
    private var currentCameraUri: Uri? = null
    
    // Step 14: Target image size for display (to prevent OOM errors)
    private val TARGET_IMAGE_SIZE = 2048 // pixels


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find UI elements
        rootLayout = findViewById(R.id.root_layout)
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
        }

        toolCrop.setOnClickListener {
            cropOptionsLayout.visibility = View.VISIBLE
            drawOptionsLayout.visibility = View.GONE
            adjustOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE // Hide save panel
            currentActiveTool?.isSelected = false
            toolCrop.isSelected = true
            currentActiveTool = toolCrop
        }

        toolAdjust.setOnClickListener {
            adjustOptionsLayout.visibility = View.VISIBLE
            drawOptionsLayout.visibility = View.GONE
            cropOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE // Hide save panel
            currentActiveTool?.isSelected = false
            toolAdjust.isSelected = true
            currentActiveTool = toolAdjust
        }

        // Initialize Save Panel buttons
        val buttonSaveCopy: Button = findViewById(R.id.button_save_copy)
        val buttonOverwrite: Button = findViewById(R.id.button_overwrite)

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
        findViewById<RadioButton>(R.id.radio_jpg)
        findViewById<RadioButton>(R.id.radio_png)
        findViewById<RadioButton>(R.id.radio_webp)

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
            
                    // Step 13: Camera capture with writable MediaStore URI
                    private fun captureImageFromCamera() {
                        try {
                            // Create timestamp-based filename as per plan step 23 (preparation)
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                            val fileName = "IMG_$timestamp"
                            
                            // Create ContentValues for MediaStore
                            val contentValues = android.content.ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures") // Standard Pictures directory
                            }
                            
                            // Create writable URI in MediaStore
                            val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            val insertUri = contentResolver.insert(contentUri, contentValues)
                            
                            if (insertUri != null) {
                                // Launch camera with the writable URI
                                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                                intent.putExtra(MediaStore.EXTRA_OUTPUT, insertUri)
                                intent.putExtra("android.intent.extra.finishAfterCapture", true)
                                
                                // Store the capture URI temporarily for handling result
                                currentCameraUri = insertUri
                                
                                startActivityForResult(intent, CAMERA_REQUEST_CODE)
                            } else {
                                Toast.makeText(this, "Camera error. Could not create image entry.", Toast.LENGTH_SHORT).show()
                            }
                            
                        } catch (e: Exception) {
                            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun loadImageFromUri(uri: android.net.Uri, isEdit: Boolean) {
        try {
            // Step 14: Load image with proper downsampling to prevent OOM errors
            val downsampledBitmap = loadBitmapWithDownsampling(uri, TARGET_IMAGE_SIZE)
            
            if (downsampledBitmap != null) {
                // Step 8: Track image origin and set canOverwrite flag appropriately
                val origin = determineImageOrigin(uri)
                val canOverwrite = determineCanOverwrite(origin)
                
                currentImageInfo = ImageInfo(uri, origin, canOverwrite)
                
                Toast.makeText(this, "Loaded ${origin.name} image with downsampling", Toast.LENGTH_SHORT).show()
                
                // Update UI based on canOverwrite (Step 10 - handle flag changes)
                updateSavePanelUI()
            } else {
                Toast.makeText(this, "Couldn't load image. Please try again.", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Toast.makeText(this, "Image loading error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Step 14: Implement proper image downsampling using BitmapFactory.Options.inSampleSize
    private fun loadBitmapWithDownsampling(uri: Uri, targetSize: Int): android.graphics.Bitmap? {
        return try {
            // First, get image dimensions without loading the full bitmap
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
            
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null
            }
            
            // Calculate inSampleSize to downsample appropriately
            val (width, height) = calculateInSampleSize(options.outWidth, options.outHeight, targetSize)
            
            // Load bitmap with calculated inSampleSize
            val options2 = BitmapFactory.Options().apply {
                inSampleSize = Math.max(width, height)
            }
            
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options2)
            }
            
        } catch (e: Exception) {
            Toast.makeText(this, "Bitmap decoding error: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }
    
    // Step 14: Calculate appropriate inSampleSize for downsampling
    private fun calculateInSampleSize(originalWidth: Int, originalHeight: Int, targetSize: Int): Pair<Int, Int> {
        var inSampleSize = 1
        
        // Calculate the largest inSampleSize value that is a power of 2 and keeps both
        // width and height larger than the target size
        while ((originalWidth / inSampleSize) > targetSize || (originalHeight / inSampleSize) > targetSize) {
            inSampleSize *= 2
        }
        
        return Pair(originalWidth / inSampleSize, originalHeight / inSampleSize)
    }
    
    // Step 8: Helper method to determine image origin
    private fun determineImageOrigin(uri: Uri): ImageOrigin {
        return when {
            uri.toString().contains("media") && !uri.toString().contains("persisted") -> {
                // Imported via MediaStore, may or may not be writable depending on permission
                if (hasImagePermission()) ImageOrigin.IMPORTED_WRITABLE else ImageOrigin.IMPORTED_READONLY
            }
            uri.toString().contains("camera") -> ImageOrigin.CAMERA_CAPTURED
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

    // Photo picker logic - Proper implementation with single selection
    private fun openImagePicker() {
        // Android 13+ Photo Picker API - using proper ACTION_PICK for single image
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Use ACTION_PICK for Android 13+ Photo Picker (single selection enforced)
            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            // Setting EXTRA_ALLOW_MULTIPLE to false ensures single selection
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            startActivityForResult(intent, IMPORT_REQUEST_CODE)
        } else {
            // Android 10-12: Use MediaStore API with ACTION_OPEN_DOCUMENT (single selection)
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) // Single image only
            
            // For Android 10-12, we rely on MediaStore API without storage permissions
            startActivityForResult(intent, IMPORT_REQUEST_CODE)
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
                    // Step 13: Handle camera capture result
                    currentCameraUri?.let { cameraUri ->
                        loadImageFromUri(cameraUri, false)
                        currentCameraUri = null // Clear temporary URI
                    }
                } else {
                    // Camera capture failed or was cancelled
                    Toast.makeText(this, "Camera error. No image captured.", Toast.LENGTH_SHORT).show()
                    currentCameraUri = null // Clean up
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
}
