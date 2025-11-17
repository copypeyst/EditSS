package com.tamad.editss

import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import android.widget.Toast
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import android.provider.MediaStore
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.os.Environment
import android.content.ContentValues
import android.media.MediaScannerConnection
import kotlinx.coroutines.*
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.OutputStream
import coil.ImageLoader
import coil.request.ImageRequest
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.util.regex.Pattern
import java.text.SimpleDateFormat
import java.util.Date
import com.tamad.editss.DrawMode
import com.tamad.editss.EditAction
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

// Step 8: Image origin tracking enum
enum class ImageOrigin {
    IMPORTED_READONLY,
    IMPORTED_WRITABLE,
    CAMERA_CAPTURED,
    EDITED_INTERNAL
}

// MODIFIED: Added originalMimeType to track the image's starting format
data class ImageInfo(
    val uri: Uri,
    val origin: ImageOrigin,
    var canOverwrite: Boolean,
    val originalMimeType: String // Added to track original format
)

// Coil handles all caching, memory management, and bitmap processing automatically

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var rootLayout: FrameLayout

    private lateinit var savePanel: View
    private lateinit var toolOptionsLayout: LinearLayout
    private lateinit var drawOptionsLayout: LinearLayout
    private lateinit var cropOptionsLayout: LinearLayout
    private lateinit var adjustOptionsLayout: LinearLayout
    private lateinit var scrim: View
    private lateinit var transparencyWarningText: TextView

    // Loading overlay elements
    private lateinit var overlayContainer: FrameLayout

    private var currentActiveTool: ImageView? = null

    private var currentCropMode: View? = null
    private var currentSelectedColor: FrameLayout? = null
    private var currentDrawMode: ImageView? = null
    
    // Crop mode option buttons
    private lateinit var cropModeFreeform: View
    private lateinit var cropModeSquare: View
    private lateinit var cropModePortrait: View
    private lateinit var cropModeLandscape: View
    
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
    private var isSketchMode = false
    
    // --- START: ADDED FOR OVERWRITE FIX ---
    // Handles the result of the delete confirmation dialog
    private lateinit var deleteRequestLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>

    // Temporarily stores the URI of the new file while we wait for user confirmation
    private var pendingOverwriteUri: Uri? = null
    // --- END: ADDED FOR OVERWRITE FIX ---

    // Drawing tools ViewModel for shared state
    private lateinit var editViewModel: EditViewModel
    
    // Drawing-related UI elements
    private lateinit var drawingView: CanvasView
    private lateinit var drawSizeSlider: SeekBar
    private lateinit var drawOpacitySlider: SeekBar
    // --- END: ADDED FOR OVERWRITE FIX ---

    private val oldImagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                // Persist URI permissions for long-term access, crucial for overwriting.
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                loadImageFromUri(uri, false)
            }
        }
    }

    private val cameraCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val cameraUri = currentCameraUri
            if (cameraUri != null) {
                try {
                    loadImageFromUri(cameraUri, false)
                } catch (e: Exception) {
                    showCustomToast(getString(R.string.error_loading_camera_image, e.message ?: "Unknown error"))
                    cleanupCameraFile(cameraUri)
                }
                currentCameraUri = null
            }
        } else {
            val cameraUri = currentCameraUri
            if (cameraUri != null) {
                cleanupCameraFile(cameraUri)
            }
            currentCameraUri = null
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            openImagePicker()
        } else {
            showPermissionDeniedDialog()
        }
    }


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
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize the Mobile Ads SDK
        MobileAds.initialize(this) {}

        // Load Ad Banner
        val adView = findViewById<AdView>(R.id.ad_banner)
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)

        // Initialize ViewModel for shared drawing state
        editViewModel = EditViewModel()

        // Find UI elements
        rootLayout = findViewById(R.id.root_layout)
        val buttonUndo: ImageView = findViewById(R.id.button_undo)
        val buttonRedo: ImageView = findViewById(R.id.button_redo)
        val buttonSave: ImageView = findViewById(R.id.button_save)
        val buttonImport: ImageView = findViewById(R.id.button_import)
        val buttonCamera: ImageView = findViewById(R.id.button_camera)
        val buttonShare: ImageView = findViewById(R.id.button_share)
        val toolDraw: ImageView = findViewById(R.id.tool_draw)
        val toolCrop: ImageView = findViewById(R.id.tool_crop)
        val toolAdjust: ImageView = findViewById(R.id.tool_adjust)

        // Initialize loading overlay elements
        overlayContainer = findViewById(R.id.overlay_container)

        savePanel = findViewById(R.id.save_panel)
        toolOptionsLayout = findViewById(R.id.tool_options)
        scrim = findViewById(R.id.scrim)
        transparencyWarningText = findViewById(R.id.transparency_warning_text)

        // Initialize drawing controls
        drawSizeSlider = findViewById(R.id.draw_size_slider)
        drawOpacitySlider = findViewById(R.id.draw_opacity_slider)
        drawOptionsLayout = findViewById(R.id.draw_options)
        cropOptionsLayout = findViewById(R.id.crop_options)
        adjustOptionsLayout = findViewById(R.id.adjust_options)
        scrim = findViewById(R.id.scrim)
        transparencyWarningText = findViewById(R.id.transparency_warning_text)
        
        // Initialize DrawingView and connect to ViewModel
        drawingView = findViewById(R.id.drawing_view)


        // Initialize sliders with default values (25% size, 100% opacity)
        val defaultSize = 25 // 25% of slider range
        val defaultOpacity = 100 // 100% of slider range
        drawSizeSlider.progress = defaultSize
        drawOpacitySlider.progress = defaultOpacity

        // Save Panel Logic
        buttonSave.setOnClickListener {
            if (savePanel.visibility == View.VISIBLE) {
                savePanel.visibility = View.GONE
                scrim.visibility = View.GONE
            } else {
                // Check if there are unapplied adjustments
                val currentAdjustState = editViewModel.adjustState.value
                val hasUnappliedAdjustments = currentAdjustState.brightness != 0f ||
                                               currentAdjustState.contrast != 1f ||
                                               currentAdjustState.saturation != 1f
                
                if (hasUnappliedAdjustments) {
                    // Show dialog asking to apply adjustments
                    AlertDialog.Builder(this, R.style.AlertDialog_EditSS)
                        .setTitle(getString(R.string.apply_adjustments_title))
                        .setMessage(getString(R.string.apply_adjustments_message))
                        .setPositiveButton(getString(R.string.apply_adjustments)) { dialog, _ ->
                            // Apply adjustments first
                            applyAdjustmentsAndShowSavePanel()
                            dialog.dismiss()
                        }
                        .setNegativeButton(getString(R.string.ignore)) { dialog, _ ->
                            // Just show the save panel
                            savePanel.visibility = View.VISIBLE
                            scrim.visibility = View.VISIBLE
                            dialog.dismiss()
                        }
                        .show()
                } else {
                    // No unapplied adjustments, show save panel directly
                    savePanel.visibility = View.VISIBLE
                    scrim.visibility = View.VISIBLE
                }
            }
        }

        scrim.setOnClickListener {
            savePanel.visibility = View.GONE
            scrim.visibility = View.GONE
        }

        // Import Button Logic
        buttonImport.setOnClickListener {
            if (editViewModel.hasUnsavedChanges) {
                AlertDialog.Builder(this, R.style.AlertDialog_EditSS)
                    .setTitle(getString(R.string.discard_changes_title))
                    .setMessage(getString(R.string.discard_changes_message))
                    .setPositiveButton(getString(R.string.confirm)) { dialog, _ ->
                        editViewModel.clearAllActions()
                        if (hasImagePermission()) {
                            openImagePicker()
                        } else {
                            requestImagePermission()
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            } else {
                if (hasImagePermission()) {
                    openImagePicker()
                } else {
                    requestImagePermission()
                }
            }
        }

        // Camera Button Logic - Step 13: Create writable URI in MediaStore for camera capture
        buttonCamera.setOnClickListener {
            if (editViewModel.hasUnsavedChanges) {
                AlertDialog.Builder(this, R.style.AlertDialog_EditSS)
                    .setTitle(getString(R.string.discard_changes_title))
                    .setMessage(getString(R.string.discard_changes_message))
                    .setPositiveButton(getString(R.string.confirm)) { dialog, _ ->
                        editViewModel.clearAllActions()
                        captureImageFromCamera()
                        dialog.dismiss()
                    }
                    .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            } else {
                captureImageFromCamera()
            }
        }

        // Step 1 & 2: Share Button Logic - Content URI sharing for saved images, cache-based for unsaved edits
        buttonShare.setOnClickListener {
            shareCurrentImage()
        }

        // Tool Buttons Logic
        toolDraw.setOnClickListener {
            drawOptionsLayout.visibility = View.VISIBLE
            cropOptionsLayout.visibility = View.GONE
            adjustOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE // Hide save panel
            drawingView.visibility = View.VISIBLE // Show drawing view
            drawingView.setToolType(CanvasView.ToolType.DRAW) // Set draw mode
            drawingView.setCropModeInactive() // Clear crop mode active state
            
            // Clear adjustments when switching away from Adjust mode
            if (currentActiveTool == toolAdjust) {
                drawingView.clearAdjustments()
            }
            
            currentActiveTool?.isSelected = false
            toolDraw.isSelected = true
            currentActiveTool = toolDraw
        }

        toolCrop.setOnClickListener {
            cropOptionsLayout.visibility = View.VISIBLE
            drawOptionsLayout.visibility = View.GONE
            adjustOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE // Hide save panel
            drawingView.visibility = View.VISIBLE // Keep drawing view visible for cropping
            drawingView.setToolType(CanvasView.ToolType.CROP) // Set crop mode
            
            // Clear adjustments when switching away from Adjust mode
            if (currentActiveTool == toolAdjust) {
                drawingView.clearAdjustments()
            }
            
            currentActiveTool?.isSelected = false
            toolCrop.isSelected = true
            currentActiveTool = toolCrop
            
            // Re-initialize crop rectangle when crop mode is activated
            // This ensures the marquee appears and adjusts to fit the current image
            // Similar to the post block in your snippet - happens whenever crop mode button is pressed
            drawingView.post {
                // Always re-apply the current crop mode to adjust the marquee to fit the current image
                if (currentCropMode != null) {
                    // If a crop mode option is already selected, re-apply it
                    when (currentCropMode?.id) {
                        R.id.crop_mode_freeform -> drawingView.setCropMode(CropMode.FREEFORM)
                        R.id.crop_mode_square -> drawingView.setCropMode(CropMode.SQUARE)
                        R.id.crop_mode_portrait -> drawingView.setCropMode(CropMode.PORTRAIT)
                        R.id.crop_mode_landscape -> drawingView.setCropMode(CropMode.LANDSCAPE)
                    }
                } else {
                    // If no crop mode option is selected, default to freeform and select it
                    drawingView.setCropMode(CropMode.FREEFORM)
                    cropModeFreeform.isSelected = true
                    currentCropMode = cropModeFreeform
                }
            }
        }

        toolAdjust.setOnClickListener {
            adjustOptionsLayout.visibility = View.VISIBLE
            drawOptionsLayout.visibility = View.GONE
            cropOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE // Hide save panel
            drawingView.visibility = View.VISIBLE // Show drawing view
            drawingView.setToolType(CanvasView.ToolType.ADJUST) // Set tool type
            drawingView.setCropModeInactive() // Clear crop mode active state
            currentActiveTool?.isSelected = false
            toolAdjust.isSelected = true
            currentActiveTool = toolAdjust
            
            // Re-apply adjustments when switching back to Adjust mode
            val currentAdjustState = editViewModel.adjustState.value
            drawingView.setAdjustments(currentAdjustState.brightness, currentAdjustState.contrast, currentAdjustState.saturation)
        }

        // Initialize Save Panel buttons
        val buttonSaveCopy: Button = findViewById(R.id.button_save_copy)
        val buttonOverwrite: Button = findViewById(R.id.button_overwrite)
        
        // Step 21, 22, 23: Save button click handlers
        buttonSaveCopy.setOnClickListener {
            saveImageAsCopy()
        }
        
        buttonOverwrite.setOnClickListener {
            overwriteCurrentImage()
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
        
        // MODIFIED: Added call to updateSaveButtonsState() in each listener
        radioJPG.setOnClickListener {
            selectedSaveFormat = "image/jpeg"
            updateTransparencyWarning()
            updateSaveButtonsState()
        }
        radioPNG.setOnClickListener {
            selectedSaveFormat = "image/png"
            updateTransparencyWarning()
            updateSaveButtonsState()
        }
        radioWEBP.setOnClickListener {
            selectedSaveFormat = "image/webp"
            updateTransparencyWarning()
            updateSaveButtonsState()
        }

        // Initialize Draw Options
        val drawModePen: ImageView = findViewById(R.id.draw_mode_pen)
        val drawModeCircle: ImageView = findViewById(R.id.draw_mode_circle)
        val drawModeSquare: ImageView = findViewById(R.id.draw_mode_square)

        drawModePen.setOnClickListener {
            editViewModel.updateDrawMode(DrawMode.PEN)
            updateDrawModeSelection(drawModePen)
        }
        drawModeCircle.setOnClickListener {
            editViewModel.updateDrawMode(DrawMode.CIRCLE)
            updateDrawModeSelection(drawModeCircle)
        }
        drawModeSquare.setOnClickListener {
            editViewModel.updateDrawMode(DrawMode.SQUARE)
            updateDrawModeSelection(drawModeSquare)
        }
        
        // Initialize slider listeners for shared drawing state
        drawSizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val size = (progress + 1).toFloat() // Convert to 1-101 range as Float
                    editViewModel.updateDrawingSize(size)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        drawOpacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Convert progress (1-101) to opacity (0-255) for proper alpha mapping
                    val opacity = ((progress - 1) * 2.55).toInt().coerceIn(0, 255)
                    editViewModel.updateDrawingOpacity(opacity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Initialize Crop Options
        cropModeFreeform = findViewById(R.id.crop_mode_freeform)
        cropModeSquare = findViewById(R.id.crop_mode_square)
        cropModePortrait = findViewById(R.id.crop_mode_portrait)
        cropModeLandscape = findViewById(R.id.crop_mode_landscape)

        cropModeFreeform.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModeFreeform.isSelected = true
            currentCropMode = cropModeFreeform
            drawingView.setCropMode(CropMode.FREEFORM)
        }
        cropModeSquare.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModeSquare.isSelected = true
            currentCropMode = cropModeSquare
            drawingView.setCropMode(CropMode.SQUARE)
        }
        cropModePortrait.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModePortrait.isSelected = true
            currentCropMode = cropModePortrait
            drawingView.setCropMode(CropMode.PORTRAIT)
        }
        cropModeLandscape.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModeLandscape.isSelected = true
            currentCropMode = cropModeLandscape
            drawingView.setCropMode(CropMode.LANDSCAPE)
        }

        // Set default crop mode
        drawingView.setCropMode(CropMode.FREEFORM)

        // Setup crop apply/cancel button handlers
        val buttonCropApply: Button = findViewById(R.id.button_crop_apply)
        val buttonCropCancel: Button = findViewById(R.id.button_crop_cancel)

        buttonCropApply.setOnClickListener {
            val croppedBitmap = drawingView.applyCrop()
            if (croppedBitmap != null) {
                // Image was cropped successfully - clear crop mode selection
                currentCropMode?.isSelected = false
                currentCropMode = null
                drawingView.setCropModeInactive()
                showCustomToast(getString(R.string.crop_applied))
            } else {
                // Toast removed: "No crop area selected" - UX improvement
            }
        }

        buttonCropCancel.setOnClickListener {
            drawingView.cancelCrop()
            currentCropMode?.isSelected = false
            currentCropMode = null
            drawingView.setCropModeInactive()
        }

        // Set callbacks for crop operations to update UI
        drawingView.onCropApplied = { _ ->
            currentCropMode?.isSelected = false
            currentCropMode = null
        }

        drawingView.onCropCanceled = {
            currentCropMode?.isSelected = false
            currentCropMode = null
        }

        // Initialize Adjust Options
        val brightnessSlider: SeekBar = findViewById(R.id.adjust_brightness_slider)
        val contrastSlider: SeekBar = findViewById(R.id.adjust_contrast_slider)
        val saturationSlider: SeekBar = findViewById(R.id.adjust_saturation_slider)
        val buttonAdjustApply: Button = findViewById(R.id.button_adjust_apply)
        val buttonAdjustCancel: Button = findViewById(R.id.button_adjust_cancel)

        // Set sliders to the middle (50) by default
        brightnessSlider.progress = 50
        contrastSlider.progress = 50
        saturationSlider.progress = 50

        brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Map progress (0-100) to brightness value (-100 to 100)
                    val value = (progress - 50) * 2f
                    editViewModel.updateBrightness(value)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        contrastSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Map progress (0-100) to contrast value (0.0 to 2.0)
                    val value = progress / 50f
                    editViewModel.updateContrast(value)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        saturationSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Map progress (0-100) to saturation value (0.0 to 2.0)
                    val value = progress / 50f
                    editViewModel.updateSaturation(value)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        buttonAdjustApply.setOnClickListener {
            val previousBitmap = drawingView.getBaseBitmap()
            val newBitmap = drawingView.applyAdjustmentsToBitmap()

            if (previousBitmap != null && newBitmap != null) {
                val action = AdjustAction(previousBitmap, newBitmap)
                editViewModel.pushAdjustAction(action)
                drawingView.setBitmap(newBitmap)
                showCustomToast(getString(R.string.adjustment_applied))
            }

            editViewModel.resetAdjustments()
            brightnessSlider.progress = 50
            contrastSlider.progress = 50
            saturationSlider.progress = 50
        }

        buttonAdjustCancel.setOnClickListener {
            drawingView.resetAdjustments()
            editViewModel.resetAdjustments()
            brightnessSlider.progress = 50
            contrastSlider.progress = 50
            saturationSlider.progress = 50
        }

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
            
            // Update shared drawing state in ViewModel
            val selectedColor = when (v.id) {
                R.id.color_black_container -> android.graphics.Color.BLACK
                R.id.color_white_container -> android.graphics.Color.WHITE
                R.id.color_red_container -> android.graphics.Color.RED
                R.id.color_green_container -> android.graphics.Color.GREEN
                R.id.color_blue_container -> android.graphics.Color.BLUE
                R.id.color_yellow_container -> android.graphics.Color.YELLOW
                R.id.color_orange_container -> android.graphics.Color.rgb(255, 165, 0) // Orange
                R.id.color_pink_container -> android.graphics.Color.rgb(255, 192, 203) // Pink
                else -> android.graphics.Color.RED // Default fallback
            }
            
            editViewModel.updateDrawingColor(selectedColor)
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
        updateDrawModeSelection(drawModePen)

        // Connect bitmap change actions to ViewModel (for both drawing strokes and crops)
        drawingView.onBitmapChanged = { bitmapChangeAction ->
            editViewModel.pushBitmapChangeAction(bitmapChangeAction)
        }

        // Connect undo/redo action handlers to CanvasView
        drawingView.onUndoAction = { action ->
            when (action) {
                is EditAction.BitmapChange -> drawingView.handleBitmapChangeUndo(action)
                is EditAction.Adjust -> drawingView.handleAdjustUndo(action.action)
                is EditAction.Crop -> drawingView.handleCropUndo(action.action)
                else -> { /* Handle other action types if needed */ }
            }
        }

        drawingView.onRedoAction = { action ->
            when (action) {
                is EditAction.BitmapChange -> drawingView.handleBitmapChangeRedo(action)
                is EditAction.Adjust -> drawingView.handleAdjustRedo(action.action)
                is EditAction.Crop -> drawingView.handleCropRedo(action.action)
                else -> { /* Handle other action types if needed */ }
            }
        }

        lifecycleScope.launch {
            editViewModel.drawingState.collect {
                drawingView.setDrawingState(it)
            }
        }

        lifecycleScope.launch {
            editViewModel.undoStack.collect { actions ->
                // Drawing actions are now handled immediately as bitmap changes
                // No need to set paths anymore since drawings are merged into bitmap
                // The CanvasView handles drawing actions via the onNewPath callback
            }
        }

        lifecycleScope.launch {
            editViewModel.adjustState.collect { state ->
                drawingView.setAdjustments(state.brightness, state.contrast, state.saturation)
            }
        }

        // Handle undone actions
        lifecycleScope.launch {
            editViewModel.lastUndoneAction.collect { action ->
                action?.let {
                    drawingView.onUndoAction?.invoke(it)
                    // Clear the action to prevent duplicate processing
                    editViewModel.clearLastUndoneAction()
                }
            }
        }

        // Handle redone actions
        lifecycleScope.launch {
            editViewModel.lastRedoneAction.collect { action ->
                action?.let {
                    drawingView.onRedoAction?.invoke(it)
                    // Clear the action to prevent duplicate processing
                    editViewModel.clearLastRedoneAction()
                }
            }
        }

        buttonUndo.setOnClickListener {
            editViewModel.undo()
            // The lastUndoneAction collector will handle updating the CanvasView
        }

        buttonRedo.setOnClickListener {
            editViewModel.redo()
            // The lastRedoneAction collector will handle updating the CanvasView
        }

        cropModeFreeform.isSelected = true
        currentCropMode = cropModeFreeform

        colorRedContainer.performClick()

        // Set draw as default active tool
        toolDraw.isSelected = true
        currentActiveTool = toolDraw
        drawOptionsLayout.visibility = View.VISIBLE
        
        // Handle incoming intents
        handleIntent(intent)

        drawingView.doOnLayout { view ->
            // This block runs once the view has been laid out and has dimensions.
            // We only want to do this if no image has been loaded from an intent.
            if (currentImageInfo == null) {
                isSketchMode = true
                val width = view.width
                val height = view.height
                
                // Create a mutable white bitmap
                val whiteBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(whiteBitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                
                // Set it as the base for the drawing view
                drawingView.setBitmap(whiteBitmap)
                drawingView.setSketchMode(true) // Enable sketch mode for transparency
                
                // Create a dummy ImageInfo for sketch mode
                currentImageInfo = ImageInfo(
                    uri = Uri.EMPTY,
                    origin = ImageOrigin.EDITED_INTERNAL,
                    canOverwrite = false,
                    originalMimeType = "image/jpeg" // Default to JPEG for sketch mode
                )
                
                // Set JPEG as the selected format for sketch mode
                selectedSaveFormat = "image/jpeg"
                updateFormatSelectionUI()
                updateSavePanelUI()
            }
        }

        // --- START: ADDED FOR OVERWRITE FIX ---
        // Initialize the launcher that will handle the result of the delete request.
        deleteRequestLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { result ->
            // This code runs AFTER the user responds to the delete confirmation dialog.
            if (result.resultCode == RESULT_OK) {
                // User confirmed the deletion.
                // Toast removed: "Original file deleted" - UX improvement
                
                // Now, safely update our app's reference to point to the new file.
                pendingOverwriteUri?.let {
                    // Invalidate Coil's cache for the old URI to prevent showing a stale image.
                    currentImageInfo?.uri?.let { oldUri ->
                        imageLoader.memoryCache?.remove(MemoryCache.Key(oldUri.toString()))
                    }
                    currentImageInfo = currentImageInfo?.copy(uri = it)
                    pendingOverwriteUri = null // Clean up the temporary variable
                }
            } else {
                // User cancelled the deletion. The original file remains.
                // Toast removed: "Original file was not deleted" - UX improvement
                // We still need to update our app's reference to the new file that was created.
                pendingOverwriteUri?.let {
                    currentImageInfo = currentImageInfo?.copy(uri = it)
                    pendingOverwriteUri = null // Clean up
                }
            }
        }
        // --- END: ADDED FOR OVERWRITE FIX ---
    }

    // Step 1 & 2: Implement sharing functionality
    // Item 1: Content URI sharing for saved images
    // Item 2: Cache-based sharing for unsaved edits
    private fun shareCurrentImage() {
        val imageInfo = currentImageInfo ?: run {
            // Toast removed: "No image to share" - UX improvement
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Get bitmap from Coil
                val bitmapToShare = drawingView.getDrawing()

                if (bitmapToShare != null) {
                    var shareUri: Uri? = null

                    // Determine sharing strategy based on image origin
                    when (imageInfo.origin) {
                        ImageOrigin.EDITED_INTERNAL, ImageOrigin.CAMERA_CAPTURED -> {
                            // Item 2: Cache-based sharing for unsaved edits
                            // Create temporary file in cache directory
                            val cacheDir = cacheDir
                            val fileName = "share_temp_${System.currentTimeMillis()}.${getExtensionFromMimeType(selectedSaveFormat)}"
                            val tempFile = File(cacheDir, fileName)

                            contentResolver.openOutputStream(Uri.fromFile(tempFile))?.use { outputStream ->
                                compressBitmapToStream(bitmapToShare, outputStream, selectedSaveFormat)
                            }

                            shareUri = androidx.core.content.FileProvider.getUriForFile(
                                this@MainActivity,
                                "${packageName}.fileprovider",
                                tempFile
                            )

                            // Schedule cleanup after sharing (in 5 minutes to be safe)
                            lifecycleScope.launch {
                                delay(5 * 60 * 1000) // 5 minutes
                                if (tempFile.exists()) {
                                    tempFile.delete()
                                }
                            }
                        }
                        else -> {
                            // Item 1: Content URI sharing for saved images
                            // Use the original content URI with read permission
                            shareUri = imageInfo.uri
                        }
                    }

                    // Create and launch share intent
                    if (shareUri != null) {
                        withContext(Dispatchers.Main) {
                            try {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = selectedSaveFormat
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                
                                val chooser = Intent.createChooser(shareIntent, getString(R.string.share_image))
                                startActivity(chooser)

                                // Toast removed: "Sharing image" - UX improvement
                            } catch (e: Exception) {
                                showCustomToast(getString(R.string.share_failed, e.message ?: "Unknown error"))
                            }
                        }
                    } else {
                        throw Exception("Failed to create share URI")
                    }
                } else {
                    throw Exception("No image to share")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showCustomToast(getString(R.string.share_failed, e.message ?: "Unknown error"))
                }
            }
        }
    }

    // Helper function to get file extension from MIME type
    private fun getExtensionFromMimeType(mimeType: String): String {
        return when (mimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
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
                    // Toast removed: "Multiple images not supported" - UX improvement
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
                showCustomToast(getString(R.string.cannot_access_storage))
                return
            }
            
            // Ensure directory exists
            imageDir.mkdirs()
            
            // Create private file
            val fileName = "camera_temp_${System.currentTimeMillis()}.jpg"
            val privateFile = File(imageDir, fileName)
            
            if (!privateFile.exists()) {
                if (!privateFile.createNewFile()) {
                    showCustomToast(getString(R.string.failed_to_create_temp_file))
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
            cameraCaptureLauncher.launch(intent)
            
        } catch (e: Exception) {
            showCustomToast(getString(R.string.camera_error, e.message ?: "Unknown error"))
        }
    }

    // Simple Coil-based image loading - replaces all complex crash prevention logic
    private fun loadImageFromUri(uri: android.net.Uri, isEdit: Boolean) {
        isSketchMode = false
        drawingView.setSketchMode(false) // Disable sketch mode for imported/captured images
        // Clear any existing actions when a new image is loaded
        editViewModel.clearAllActions()

        // Prevent loading while already loading
        if (isImageLoading) {
            // Toast removed: "Image is still loading" - UX improvement
            return
        }
        
        // Prevent rapid successive attempts after failure
        if (isImageLoadAttempted && lastImageLoadFailed) {
            // Toast removed: "Previous load failed" - UX improvement
            return
        }
        
        isImageLoading = true
        isImageLoadAttempted = true
        lastImageLoadFailed = false
        
        try {
            // Show loading overlay
            showLoadingSpinner()
            
            // Create Coil image request
            val request = ImageRequest.Builder(this)
                .data(uri)
                .size(coil.size.Size.ORIGINAL) // Request original size to prevent downsampling
                .target { drawable ->
                    // Success callback
                    runOnUiThread {
                        try {
                            // Hide loading overlay
                            hideLoadingSpinner()
                            
                            // Step 8: Track image origin and set canOverwrite flag appropriately
                            val origin = determineImageOrigin(uri)
                            val canOverwrite = determineCanOverwrite(origin)

                            // MODIFIED: Get and store original MIME type
                            val originalMimeType = contentResolver.getType(uri) ?: "image/jpeg"
                            
                            currentImageInfo = ImageInfo(uri, origin, canOverwrite, originalMimeType)
                            
                            // Display the loaded image
                            val bitmap = (drawable as android.graphics.drawable.BitmapDrawable).bitmap
                            drawingView.setBitmap(bitmap)
                            drawingView.requestLayout()
                            drawingView.invalidate()
                            
                            val filePath = getRealPathFromUri(uri)
                            val displayName = getDisplayNameFromUri(uri)
                            val pathToShow = displayName ?: "Image"
                            showCustomToast(getString(R.string.loaded_image_successfully, pathToShow))
                            
                            // Update UI based on canOverwrite
                            updateSavePanelUI()
                            
                            // Auto-detect and set the original image format
                            detectAndSetImageFormat(uri) // This will also call updateSaveButtonsState() via the radio button click
                            
                            // Detect transparency for warning system (simplified approach)
                            try {
                                currentImageHasTransparency = bitmap.hasAlpha()
                                updateTransparencyWarning()
                            } catch (e: Exception) {
                                // Fallback: assume no transparency if detection fails
                                currentImageHasTransparency = false
                                updateTransparencyWarning()
                            }
                            
                            lastImageLoadFailed = false
                        } catch (e: Exception) {
                            hideLoadingSpinner()
                            handleImageLoadFailure(getString(R.string.error_displaying_image, e.message ?: "Unknown error"))
                        } finally {
                            isImageLoading = false
                        }
                    }
                }
                .error(R.drawable.ic_launcher_background) // Use existing drawable as error placeholder
                .listener(
                    onStart = {
                        // Loading started - overlay already shown above
                    },
                    onSuccess = { _, _ ->
                        // Loading successful - handled in target callback above
                    },
                    onError = { _, result ->
                        // Loading failed
                        runOnUiThread {
                            hideLoadingSpinner()
                            handleImageLoadFailure(result.throwable?.message ?: "Unknown error")
                            isImageLoading = false
                        }
                    }
                )
                .build()
            
            // Execute the request with Coil
            imageLoader.enqueue(request)
            
        } catch (e: Exception) {
            runOnUiThread {
                hideLoadingSpinner()
                handleImageLoadFailure("Image loading error: ${e.message ?: "Unknown error"}")
                isImageLoading = false
            }
        }
    }
    
    // Centralized failure handling
    private fun handleImageLoadFailure(errorMessage: String) {
        lastImageLoadFailed = true
        runOnUiThread {
            try {
                // Clear canvas on failed load
                drawingView.setBitmap(null)
                drawingView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                showCustomToast(getString(R.string.could_not_load_image, errorMessage))
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in handleImageLoadFailure: ${e.message ?: "Unknown error"}")
            }
        }
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
        // Check if we have persisted write permission for this URI
        val isPersistedWritable = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }

        return when {
            isPersistedWritable -> ImageOrigin.IMPORTED_WRITABLE
            // Check if this is a camera temp file (temporary cache file)
            uri.path?.contains("camera_temp_") == true -> ImageOrigin.CAMERA_CAPTURED
            // Check if this is a FileProvider URI from our app (but not camera temp)
            uri.authority == "${packageName}.fileprovider" -> ImageOrigin.EDITED_INTERNAL
            uri.toString().contains("media") -> {
                // Imported via MediaStore, may or may not be writable depending on permission
                // For MediaStore URIs, we assume writability if we have general image permissions
                if (hasImagePermission()) ImageOrigin.IMPORTED_WRITABLE else ImageOrigin.IMPORTED_READONLY
            }
            uri.toString().contains("camera") -> ImageOrigin.CAMERA_CAPTURED // Legacy support
            else -> ImageOrigin.IMPORTED_READONLY // Default to readonly for unknown sources
        }
    }
    
    // Step 8: Helper method to determine canOverwrite flag
    private fun determineCanOverwrite(origin: ImageOrigin): Boolean {
        return when (origin) {
            ImageOrigin.CAMERA_CAPTURED, ImageOrigin.EDITED_INTERNAL -> true
            ImageOrigin.IMPORTED_WRITABLE -> hasImagePermission()
            ImageOrigin.IMPORTED_READONLY -> false
        }
    }

    // Loading overlay functions
    private fun showLoadingSpinner() {
        overlayContainer.visibility = View.VISIBLE
        
        // Disable all interactive elements while loading
        disableAllInteractiveElements(true)
    }

    private fun hideLoadingSpinner() {
        overlayContainer.visibility = View.GONE
        
        // Re-enable all interactive elements
        disableAllInteractiveElements(false)
    }

    private fun disableAllInteractiveElements(disabled: Boolean) {
        // Disable main action buttons
        findViewById<ImageView>(R.id.button_import)?.isEnabled = !disabled
        findViewById<ImageView>(R.id.button_camera)?.isEnabled = !disabled
        findViewById<ImageView>(R.id.button_undo)?.isEnabled = !disabled
        findViewById<ImageView>(R.id.button_redo)?.isEnabled = !disabled
        findViewById<ImageView>(R.id.button_share)?.isEnabled = !disabled
        findViewById<ImageView>(R.id.button_save)?.isEnabled = !disabled
        
        // Disable tool buttons
        findViewById<ImageView>(R.id.tool_draw)?.isEnabled = !disabled
        findViewById<ImageView>(R.id.tool_crop)?.isEnabled = !disabled
        findViewById<ImageView>(R.id.tool_adjust)?.isEnabled = !disabled
        
        // Disable save panel if visible
        if (savePanel.visibility == View.VISIBLE) {
            savePanel.isEnabled = !disabled
        }
        
        // Disable tool options if visible
        if (toolOptionsLayout.visibility == View.VISIBLE) {
            toolOptionsLayout.isEnabled = !disabled
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
            requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            // Android 10-12: No permission request needed, proceed directly
            openImagePicker()
        }
    }

    // Photo picker logic - Modern implementation using Photo Picker
    private fun openImagePicker() {
        try {
            // Use ACTION_OPEN_DOCUMENT as specified in the old notes. This intent is part of the
            // Storage Access Framework and is designed to provide persistent access to documents,
            // which is crucial for the overwrite functionality.
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            oldImagePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.select_picture)))
        } catch (e: Exception) {
            showCustomToast(getString(R.string.could_not_open_photo_picker, e.message ?: "Unknown error"))
        }
    }

    // Permission denied dialog (Step 5)
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
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

    // Step 9 & 10: Update save panel UI based on image origin and canOverwrite flag
    private fun updateSavePanelUI() {
        updateSaveButtonsState() // Consolidate logic into one function
        updateSaveButtonText() // Update button text based on image origin
    }

    private fun updateDrawModeSelection(selectedMode: ImageView) {
        currentDrawMode?.isSelected = false
        selectedMode.isSelected = true
        currentDrawMode = selectedMode
    }
    
    // Update save button text based on image origin (camera vs import)
    private fun updateSaveButtonText() {
        val buttonSaveCopy: Button = findViewById(R.id.button_save_copy)
        
        val info = currentImageInfo
        if (info == null) {
            // Default to "Save Copy" when no image loaded
            buttonSaveCopy.text = getString(R.string.save_copy)
            return
        }

        // For camera images, change button text to "Save" instead of "Save Copy"
        if (info.origin == ImageOrigin.CAMERA_CAPTURED) {
            buttonSaveCopy.text = getString(R.string.save)
        } else {
            // For imported images, keep the original "Save Copy" text
            buttonSaveCopy.text = getString(R.string.save_copy)
        }
    }
    
    // MODIFIED: Central function to control visibility of Overwrite button and warning icon.
    private fun updateSaveButtonsState() {
        val buttonOverwrite: Button = findViewById(R.id.button_overwrite)
        val warningIcon: ImageView = findViewById(R.id.warning_icon)

        val info = currentImageInfo
        if (info == null) {
            // Default state: no image loaded, hide both.
            buttonOverwrite.visibility = View.GONE
            warningIcon.visibility = View.GONE
            return
        }

        // Show overwrite ONLY if:
        // 1. It's not a camera-captured image (camera images are temporary cache files)
        // 2. It's allowed (canOverwrite)
        // 3. The save format matches the original format
        val shouldShowOverwrite = info.origin != ImageOrigin.CAMERA_CAPTURED &&
                                  info.canOverwrite &&
                                  selectedSaveFormat == info.originalMimeType
        if (shouldShowOverwrite) {
            buttonOverwrite.visibility = View.VISIBLE
            warningIcon.visibility = View.VISIBLE
        } else {
            buttonOverwrite.visibility = View.GONE
            warningIcon.visibility = View.GONE
        }
    }
    
    // Updated function to save a copy using Coil to fetch the bitmap reliably.
    private fun saveImageAsCopy() {
        val imageInfo = currentImageInfo ?: run {
            // Toast removed: "No image to save" - UX improvement
            return
        }

        lifecycleScope.launch(Dispatchers.Main) {
            // Show loading overlay to prevent spamming
            showLoadingSpinner()
            
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val bitmapToSave: Bitmap?
                    if (isSketchMode) {
                        when (selectedSaveFormat) {
                            "image/png", "image/webp" -> {
                                // For transparent formats in sketch mode:
                                // Use the new method that converts white bg to transparent
                                // while preserving strokes + adjustments
                                bitmapToSave = drawingView.getTransparentDrawingWithAdjustments()
                            }
                            "image/jpeg" -> {
                                // For JPEG, render strokes on white background
                                bitmapToSave = drawingView.getSketchDrawingOnWhite()
                            }
                            else -> {
                                bitmapToSave = drawingView.getDrawing()
                            }
                        }
                    } else {
                        bitmapToSave = drawingView.getDrawing()?.let { bitmap ->
                            // For imported/captured images: if saving as JPEG and image has transparency,
                            // convert transparent areas to white instead of letting them turn black
                            if (selectedSaveFormat == "image/jpeg" && currentImageHasTransparency) {
                                drawingView.convertTransparentToWhite(bitmap)
                            } else {
                                bitmap
                            }
                        }
                    }

                    if (bitmapToSave != null) {
                        // Generate filename based on image origin
                        val picturesDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).path + "/EditSS"
                        val uniqueDisplayName = if (imageInfo.origin == ImageOrigin.CAMERA_CAPTURED) {
                            // For camera images, use proper naming format without "- Copy" suffix
                            generateUniqueCameraName()
                        } else {
                            // For imported images, use copy naming logic
                            val originalDisplayName = getDisplayNameFromUri(imageInfo.uri) ?: "Image"
                            generateUniqueCopyName(originalDisplayName, picturesDirectory)
                        }

                        // MODIFIED: Save to dedicated app folder "EditSS"
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, uniqueDisplayName)
                            put(MediaStore.Images.Media.MIME_TYPE, selectedSaveFormat)
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EditSS")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { outputStream ->
                                compressBitmapToStream(bitmapToSave, outputStream, selectedSaveFormat)
                            }
                            
                            values.clear()
                            values.put(MediaStore.Images.Media.IS_PENDING, 0)
                            contentResolver.update(uri, values, null, null)
                            
                            // Step 26: MediaScannerConnection for Android 9 and older
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                try {
                                    val filePath = getRealPathFromUri(uri)
                                    if (filePath != null) {
                                        MediaScannerConnection.scanFile(
                                            this@MainActivity,
                                            arrayOf(filePath),
                                            arrayOf(selectedSaveFormat),
                                            null
                                        )
                                    }
                                } catch (e: Exception) {
                                    // MediaScannerConnection is not critical, just log the error
                                }
                            }
                            
                            // Return success to main thread
                            val filePath = getRealPathFromUri(uri)
                            val displayName = getDisplayNameFromUri(uri)
                            val pathToShow = filePath ?: displayName ?: "Unknown file"
                            
                            withContext(Dispatchers.Main) {
                                hideLoadingSpinner()
                                showCustomToast(getString(R.string.image_saved_to_editss_folder, pathToShow))
                                savePanel.visibility = View.GONE
                                scrim.visibility = View.GONE
                                editViewModel.markActionsAsSaved()
                            }
                        } else {
                            throw Exception(getString(R.string.save_failed))
                        }
                    } else {
                        throw Exception("No image to save")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        hideLoadingSpinner()
                        showCustomToast(e.message ?: "Unknown error")
                    }
                }
            }
        }
    }
    
    // FINAL, CORRECTED VERSION - This one avoids deletion and uses MediaStore update instead.
    private fun overwriteCurrentImage() {
        val imageInfo = currentImageInfo ?: run {
            // Toast removed: "No image to overwrite" - UX improvement
            return
        }

        if (!imageInfo.canOverwrite) {
            // Toast removed: "Cannot overwrite this image" - UX improvement
            return
        }
        
        // Double-check that format hasn't changed, as a safeguard.
        if (selectedSaveFormat != imageInfo.originalMimeType) {
            // Toast removed: "Format changed please save a copy" - UX improvement
            return
        }

        // Show confirmation dialog
        AlertDialog.Builder(this, R.style.AlertDialog_EditSS)
            .setTitle(getString(R.string.overwrite_changes_title))
            .setMessage(getString(R.string.overwrite_changes_message))
            .setPositiveButton(getString(R.string.confirm)) { dialog, _ ->
                // User confirmed, proceed with overwrite
                lifecycleScope.launch {
                    try {
                        // Show loading overlay on main thread before background work
                        showLoadingSpinner()
                        
                        withContext(Dispatchers.IO) {
                            val bitmapToSave = drawingView.getDrawing()
                                ?: throw Exception("Could not get image to overwrite")
                            
                            // Since format is the same, simple overwrite is fine. "w" for write, "t" for truncate.
                            contentResolver.openOutputStream(imageInfo.uri, "wt")?.use { outputStream ->
                                compressBitmapToStream(bitmapToSave, outputStream, selectedSaveFormat)
                            }
                            
                            // Return file info for main thread
                            Pair(
                                getRealPathFromUri(imageInfo.uri),
                                getDisplayNameFromUri(imageInfo.uri)
                            )
                        }.let { (filePath, displayName) ->
                            // Back on main thread
                            hideLoadingSpinner()
                            val pathToShow = displayName ?: "Unknown file"
                            showCustomToast(getString(R.string.image_overwritten_successfully, pathToShow))
                            savePanel.visibility = View.GONE
                            scrim.visibility = View.GONE
                            editViewModel.markActionsAsSaved()

                            // Invalidate Coil's cache for the overwritten URI to ensure a fresh load next time.
                            imageLoader.memoryCache?.remove(MemoryCache.Key(imageInfo.uri.toString()))
                            imageLoader.diskCache?.remove(imageInfo.uri.toString())
                        }
                    } catch (e: Exception) {
                        hideLoadingSpinner()
                        showCustomToast(getString(R.string.overwrite_failed, e.message ?: "Unknown error"))
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                // User cancelled, do nothing
                dialog.dismiss()
            }
            .show()
    }
    
    // NEW: Robust function to generate Windows-style copy names.
    private fun generateUniqueCopyName(originalDisplayName: String, directory: String): String {
        val newExtension = when (selectedSaveFormat) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }

        // 1. Get the name without the original extension
        val nameWithoutExt = originalDisplayName.substringBeforeLast('.')

        // 2. Find the true base name by stripping any existing " - Copy" or " - Copy (n)" suffixes
        // This regex finds " - Copy" optionally followed by " (n)" at the end of the string.
        val copyPattern = Pattern.compile("\\s-\\sCopy(\\s\\(\\d+\\))?$")
        val matcher = copyPattern.matcher(nameWithoutExt)
        val baseName = if (matcher.find()) {
            nameWithoutExt.substring(0, matcher.start())
        } else {
            nameWithoutExt
        }

        // 3. Check for "baseName - Copy.ext"
        var newName = "$baseName - Copy$newExtension"
        var file = File(directory, newName)
        if (!file.exists()) {
            return newName
        }

        // 4. If it exists, start incrementing with "baseName - Copy (n).ext"
        var counter = 2
        while (true) {
            newName = "$baseName - Copy ($counter)$newExtension"
            file = File(directory, newName)
            if (!file.exists()) {
                return newName
            }
            counter++
        }
    }

    // NEW: Generate proper display name for camera-captured images
    private fun generateCameraDisplayName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val extension = when (selectedSaveFormat) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        return "IMG_${timestamp}$extension"
    }
    
    // NEW: Generate unique camera name without "- Copy" suffixes for camera captures
    private fun generateUniqueCameraName(): String {
        val baseName = generateCameraDisplayName()
        val picturesDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).path + "/EditSS"
        
        // Check if the base name already exists
        var file = File(picturesDirectory, baseName)
        if (!file.exists()) {
            return baseName
        }
        
        // If it exists, append timestamp to make it unique (camera originals shouldn't have - Copy)
        val uniqueSuffix = SimpleDateFormat("_yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val extension = baseName.substringAfterLast('.')
        val nameWithoutExt = baseName.substringBeforeLast('.')
        
        return "${nameWithoutExt}${uniqueSuffix}.${extension}"
    }
    
    // Step 24 & 25: Update transparency warning based on actual image content
    private fun updateTransparencyWarning() {
        if (currentImageHasTransparency) {
            when {
                selectedSaveFormat == "image/jpeg" -> {
                    // JPEG doesn't support transparency
                    transparencyWarningText.text = getString(R.string.jpg_does_not_support_transparency)
                    transparencyWarningText.visibility = View.VISIBLE
                }
                selectedSaveFormat == "image/webp" && currentImageHasTransparency -> {
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
        updateSaveButtonsState() // Also update the button visibility
    }
    
    // Auto-detect image format and preselect it
    private fun detectAndSetImageFormat(uri: Uri) {
        try {
            // Try to get MIME type from MediaStore first
            val mimeType = contentResolver.getType(uri)
            
            val detectedFormat = when {
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
            
            // Toast removed: "Detected format" - too technical, UX improvement
            
        } catch (e: Exception) {
            // If detection fails, keep current default
            selectedSaveFormat = "image/jpeg"
            updateFormatSelectionUI()
        }
    }
    
    // NOTE: The getCanvasBitmap() function has been removed as it is no longer needed.
    
    // Helper to compress bitmap to output stream in selected format
    private fun compressBitmapToStream(bitmap: Bitmap, outputStream: OutputStream, mimeType: String) {
        try {
            val compressFormat = when (mimeType) {
                "image/jpeg" -> CompressFormat.JPEG
                "image/png" -> CompressFormat.PNG
                "image/webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) CompressFormat.WEBP_LOSSY else CompressFormat.WEBP
                else -> CompressFormat.JPEG
            }
            
            val quality = when (mimeType) {
                "image/jpeg", "image/webp" -> 95 // High quality for lossy formats
                else -> 100 // Lossless for PNG
            }
            
            bitmap.compress(compressFormat, quality, outputStream)
        } catch (e: Exception) {
            throw Exception("Failed to compress image: ${e.message ?: "Unknown error"}")
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

    // Helper to show custom toast without app icon
    private fun showCustomToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        val layout = layoutInflater.inflate(R.layout.custom_toast, null)
        val textView = layout.findViewById<TextView>(R.id.toast_message)
        textView.text = message
        
        val toast = Toast(applicationContext)
        toast.view = layout
        toast.duration = duration
        
        // Position toast at bottom of canvas (just above tool_options panel)
        toolOptionsLayout?.let { toolPanel ->
            val location = IntArray(2)
            toolPanel.getLocationOnScreen(location)
            // Position 35dp above the tool panel (converted to pixels)
            val offsetInPixels = (toolPanel.height + 35 * resources.displayMetrics.density).toInt()
            toast.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, offsetInPixels)
        } ?: run {
            // Fallback to screen bottom if tool_options not available
            toast.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, 100)
        }
        toast.show()
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

    // Helper function to apply adjustments and show save panel (used from save button dialog)
    private fun applyAdjustmentsAndShowSavePanel() {
        val brightnessSlider: SeekBar = findViewById(R.id.adjust_brightness_slider)
        val contrastSlider: SeekBar = findViewById(R.id.adjust_contrast_slider)
        val saturationSlider: SeekBar = findViewById(R.id.adjust_saturation_slider)
        
        val previousBitmap = drawingView.getBaseBitmap()
        val newBitmap = drawingView.applyAdjustmentsToBitmap()

        if (previousBitmap != null && newBitmap != null) {
            val action = AdjustAction(previousBitmap, newBitmap)
            editViewModel.pushAdjustAction(action)
            drawingView.setBitmap(newBitmap)
            showCustomToast(getString(R.string.adjustment_applied))
        }

        editViewModel.resetAdjustments()
        brightnessSlider.progress = 50
        contrastSlider.progress = 50
        saturationSlider.progress = 50
        
        // Now show the save panel
        savePanel.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
    }

    // --- END: ADDED FOR OVERWRITE FIX ---
}