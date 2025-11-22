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
import androidx.activity.OnBackPressedCallback
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.viewModels

enum class ImageOrigin {
    IMPORTED_READONLY,
    IMPORTED_WRITABLE,
    CAMERA_CAPTURED,
    EDITED_INTERNAL
}

data class ImageInfo(
    val uri: Uri,
    val origin: ImageOrigin,
    var canOverwrite: Boolean,
    val originalMimeType: String
)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    // UI Variables
    private lateinit var rootLayout: FrameLayout
    private lateinit var savePanel: View
    private lateinit var toolOptionsLayout: LinearLayout
    private lateinit var drawOptionsLayout: LinearLayout
    private lateinit var cropOptionsLayout: LinearLayout
    private lateinit var adjustOptionsLayout: LinearLayout
    private lateinit var scrim: View
    private lateinit var transparencyWarningText: TextView
    private lateinit var overlayContainer: FrameLayout
    private lateinit var buttonUndo: ImageView
    private lateinit var buttonRedo: ImageView

    // Tool State
    private var currentActiveTool: ImageView? = null
    private var currentCropMode: View? = null
    private var currentDrawMode: ImageView? = null
    
    private lateinit var cropModeFreeform: View
    private lateinit var cropModeSquare: View
    private lateinit var cropModePortrait: View
    private lateinit var cropModeLandscape: View
    
    // Image State
    private var currentImageInfo: ImageInfo? = null
    private var selectedSaveFormat: String = "image/jpeg"
    private var currentImageHasTransparency = false
    private var currentCameraUri: Uri? = null
    private var isImageLoading = false
    private var isSketchMode = false
    private var isSaving = false
    
    private lateinit var deleteRequestLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>
    private var pendingOverwriteUri: Uri? = null

    // ViewModel
    private val editViewModel: EditViewModel by viewModels()
    
    // Drawing View & Controls
    private lateinit var drawingView: CanvasView
    private lateinit var drawSizeSlider: SeekBar
    private lateinit var drawOpacitySlider: SeekBar
    
    // Overlays
    private lateinit var drawSizeOverlay: SliderValueOverlay
    private lateinit var drawOpacityOverlay: SliderValueOverlay
    private lateinit var brightnessOverlay: SliderValueOverlay
    private lateinit var contrastOverlay: SliderValueOverlay
    private lateinit var saturationOverlay: SliderValueOverlay

    // Launchers

    private val oldImagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
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
                    .maxSizeBytes(50 * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ads Setup
        MobileAds.initialize(this) {}
        val adView = findViewById<AdView>(R.id.ad_banner)
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)

        // Bind UI
        rootLayout = findViewById(R.id.root_layout)
        buttonUndo = findViewById(R.id.button_undo)
        buttonRedo = findViewById(R.id.button_redo)
        val buttonSave: ImageView = findViewById(R.id.button_save)
        val buttonImport: ImageView = findViewById(R.id.button_import)
        val buttonCamera: ImageView = findViewById(R.id.button_camera)
        val buttonShare: ImageView = findViewById(R.id.button_share)
        val toolDraw: ImageView = findViewById(R.id.tool_draw)
        val toolCrop: ImageView = findViewById(R.id.tool_crop)
        val toolAdjust: ImageView = findViewById(R.id.tool_adjust)

        overlayContainer = findViewById(R.id.overlay_container)
        savePanel = findViewById(R.id.save_panel)
        toolOptionsLayout = findViewById(R.id.tool_options)
        scrim = findViewById(R.id.scrim)
        transparencyWarningText = findViewById(R.id.transparency_warning_text)

        drawSizeSlider = findViewById(R.id.draw_size_slider)
        drawOpacitySlider = findViewById(R.id.draw_opacity_slider)
        
        drawSizeOverlay = findViewById(R.id.draw_size_overlay)
        drawOpacityOverlay = findViewById(R.id.draw_opacity_overlay)
        brightnessOverlay = findViewById(R.id.brightness_overlay)
        contrastOverlay = findViewById(R.id.contrast_overlay)
        saturationOverlay = findViewById(R.id.saturation_overlay)
        
        drawOptionsLayout = findViewById(R.id.draw_options)
        cropOptionsLayout = findViewById(R.id.crop_options)
        adjustOptionsLayout = findViewById(R.id.adjust_options)
        
        drawingView = findViewById(R.id.drawing_view)
        
        drawingView.undoRedoListener = object : CanvasView.OnUndoRedoStateChangedListener {
            override fun onStateChanged(canUndo: Boolean, canRedo: Boolean) {
                updateUndoRedoButtonState()
            }
        }
        
        updateUndoRedoButtonState()

        // Back Button Handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (savePanel.visibility == View.VISIBLE) {
                    savePanel.visibility = View.GONE
                    scrim.visibility = View.GONE
                } else if (drawingView.hasUnsavedChanges()) {
                    AlertDialog.Builder(this@MainActivity, R.style.AlertDialog_EditSS)
                        .setTitle(getString(R.string.discard_changes_title))
                        .setMessage(getString(R.string.discard_changes_message))
                        .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                            finish()
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Initialize Sliders
        val defaultSize = 24
        val defaultOpacity = 99
        drawSizeSlider.max = 99
        drawOpacitySlider.max = 99
        drawSizeSlider.progress = defaultSize
        drawOpacitySlider.progress = defaultOpacity

        // Save Button Logic
        buttonSave.setOnClickListener {
            if (savePanel.visibility == View.VISIBLE) {
                savePanel.visibility = View.GONE
                scrim.visibility = View.GONE
            } else {
                val currentAdjustState = editViewModel.adjustState.value
                val hasUnappliedAdjustments = currentAdjustState.brightness != 0f ||
                                               currentAdjustState.contrast != 1f ||
                                               currentAdjustState.saturation != 1f
                
                if (hasUnappliedAdjustments) {
                    AlertDialog.Builder(this, R.style.AlertDialog_EditSS)
                        .setTitle(getString(R.string.apply_adjustments_title))
                        .setMessage(getString(R.string.apply_adjustments_message))
                        .setPositiveButton(getString(R.string.apply_adjustments)) { dialog, _ ->
                            applyAdjustmentsAndShowSavePanel()
                            dialog.dismiss()
                        }
                        .setNegativeButton(getString(R.string.ignore)) { dialog, _ ->
                            savePanel.visibility = View.VISIBLE
                            scrim.visibility = View.VISIBLE
                            dialog.dismiss()
                        }
                        .show()
                } else {
                    savePanel.visibility = View.VISIBLE
                    scrim.visibility = View.VISIBLE
                }
            }
        }

        scrim.setOnClickListener {
            savePanel.visibility = View.GONE
            scrim.visibility = View.GONE
        }

        // Import/Camera Buttons
        buttonImport.setOnClickListener {
            if (drawingView.hasUnsavedChanges()) {
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

        buttonCamera.setOnClickListener {
            if (drawingView.hasUnsavedChanges()) {
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

        buttonShare.setOnClickListener {
            shareCurrentImage()
        }

        // Tool Switching Logic
        toolDraw.setOnClickListener {
            drawOptionsLayout.visibility = View.VISIBLE
            cropOptionsLayout.visibility = View.GONE
            adjustOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE
            drawingView.visibility = View.VISIBLE
            drawingView.setToolType(CanvasView.ToolType.DRAW)
            drawingView.setCropModeInactive()
            
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
            savePanel.visibility = View.GONE
            drawingView.visibility = View.VISIBLE
            drawingView.setToolType(CanvasView.ToolType.CROP)
            
            if (currentActiveTool == toolAdjust) {
                drawingView.clearAdjustments()
            }
            
            currentActiveTool?.isSelected = false
            toolCrop.isSelected = true
            currentActiveTool = toolCrop
            
            drawingView.post {
                if (currentCropMode != null) {
                    when (currentCropMode?.id) {
                        R.id.crop_mode_freeform -> drawingView.setCropMode(CropMode.FREEFORM)
                        R.id.crop_mode_square -> drawingView.setCropMode(CropMode.SQUARE)
                        R.id.crop_mode_portrait -> drawingView.setCropMode(CropMode.PORTRAIT)
                        R.id.crop_mode_landscape -> drawingView.setCropMode(CropMode.LANDSCAPE)
                    }
                } else {
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
            savePanel.visibility = View.GONE
            drawingView.visibility = View.VISIBLE
            drawingView.setToolType(CanvasView.ToolType.ADJUST)
            drawingView.setCropModeInactive()
            currentActiveTool?.isSelected = false
            toolAdjust.isSelected = true
            currentActiveTool = toolAdjust
            
            val currentAdjustState = editViewModel.adjustState.value
            drawingView.setAdjustments(currentAdjustState.brightness, currentAdjustState.contrast, currentAdjustState.saturation)
        }

        // Save Panel Actions
        val buttonSaveCopy: Button = findViewById(R.id.button_save_copy)
        val buttonOverwrite: Button = findViewById(R.id.button_overwrite)
        
        buttonSaveCopy.setOnClickListener { saveImageAsCopy() }
        buttonOverwrite.setOnClickListener { overwriteCurrentImage() }

        val touchListener = View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.alpha = 0.5f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1.0f
            }
            false
        }

        buttonSaveCopy.setOnTouchListener(touchListener)
        buttonOverwrite.setOnTouchListener(touchListener)
        
        // Format Selection
        val radioJPG: RadioButton = findViewById(R.id.radio_jpg)
        val radioPNG: RadioButton = findViewById(R.id.radio_png)
        val radioWEBP: RadioButton = findViewById(R.id.radio_webp)
        
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

        // Draw Mode Options
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
        
        drawSizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val size = (progress + 1).toFloat()
                    editViewModel.updateDrawingSize(size)
                    drawSizeOverlay.updateFromSlider(seekBar!!, progress, "${progress + 1}")
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (seekBar != null) {
                    drawSizeOverlay.onSliderTouched(seekBar, seekBar.progress, "${seekBar.progress + 1}")
                }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                drawSizeOverlay.onSliderReleased()
            }
        })

        drawOpacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val opacity = progress + 1
                    editViewModel.updateDrawingOpacity(opacity)
                    drawOpacityOverlay.updateFromSlider(seekBar!!, progress, "${opacity}%")
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (seekBar != null) {
                    val opacity = seekBar.progress + 1
                    drawOpacityOverlay.onSliderTouched(seekBar, seekBar.progress, "${opacity}%")
                }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                drawOpacityOverlay.onSliderReleased()
            }
        })

        // Crop Mode Options
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

        drawingView.setCropMode(CropMode.FREEFORM)

        val buttonCropApply: Button = findViewById(R.id.button_crop_apply)
        val buttonCropCancel: Button = findViewById(R.id.button_crop_cancel)

        buttonCropApply.setOnClickListener {
            val croppedBitmap = drawingView.applyCrop()
            if (croppedBitmap != null) {
                currentCropMode?.isSelected = false
                currentCropMode = null
                drawingView.setCropModeInactive()
                showCustomToast(getString(R.string.crop_applied))
            }
        }

        buttonCropCancel.setOnClickListener {
            drawingView.cancelCrop()
            currentCropMode?.isSelected = false
            currentCropMode = null
            drawingView.setCropModeInactive()
        }

        drawingView.onCropApplied = { _ ->
            currentCropMode?.isSelected = false
            currentCropMode = null
        }

        drawingView.onCropCanceled = {
            currentCropMode?.isSelected = false
            currentCropMode = null
        }

        // Adjustment Controls
        val brightnessSlider: SeekBar = findViewById(R.id.adjust_brightness_slider)
        val contrastSlider: SeekBar = findViewById(R.id.adjust_contrast_slider)
        val saturationSlider: SeekBar = findViewById(R.id.adjust_saturation_slider)
        val buttonAdjustApply: Button = findViewById(R.id.button_adjust_apply)
        val buttonAdjustCancel: Button = findViewById(R.id.button_adjust_cancel)
        
        brightnessSlider.max = 200
        contrastSlider.max = 200
        saturationSlider.max = 200
        
        brightnessSlider.progress = 100
        contrastSlider.progress = 100
        saturationSlider.progress = 100

        brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = progress - 100
                    editViewModel.updateBrightness(value.toFloat())
                    val displayValue = if (value > 0) "+$value" else "$value"
                    brightnessOverlay.updateFromSlider(seekBar!!, progress, displayValue)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (seekBar != null) {
                    val value = seekBar.progress - 100
                    val displayValue = if (value > 0) "+$value" else "$value"
                    brightnessOverlay.onSliderTouched(seekBar, seekBar.progress, displayValue)
                }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                brightnessOverlay.onSliderReleased()
            }
        })

        contrastSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val rawValue = progress - 100
                    val normalizedValue = (rawValue + 100) / 100f
                    editViewModel.updateContrast(normalizedValue)
                    val displayValue = if (rawValue > 0) "+$rawValue" else "$rawValue"
                    contrastOverlay.updateFromSlider(seekBar!!, progress, displayValue)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (seekBar != null) {
                    val rawValue = seekBar.progress - 100
                    val displayValue = if (rawValue > 0) "+$rawValue" else "$rawValue"
                    contrastOverlay.onSliderTouched(seekBar, seekBar.progress, displayValue)
                }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                contrastOverlay.onSliderReleased()
            }
        })

        saturationSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val rawValue = progress - 100
                    val normalizedValue = (rawValue + 100) / 100f
                    editViewModel.updateSaturation(normalizedValue)
                    val displayValue = if (rawValue > 0) "+$rawValue" else "$rawValue"
                    saturationOverlay.updateFromSlider(seekBar!!, progress, displayValue)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (seekBar != null) {
                    val rawValue = seekBar.progress - 100
                    val displayValue = if (rawValue > 0) "+$rawValue" else "$rawValue"
                    saturationOverlay.onSliderTouched(seekBar, seekBar.progress, displayValue)
                }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saturationOverlay.onSliderReleased()
            }
        })

        buttonAdjustApply.setOnClickListener {
            drawingView.applyAdjustmentsToBitmap()
            showCustomToast(getString(R.string.adjustment_applied))

            editViewModel.resetAdjustments()
            brightnessSlider.progress = 100
            contrastSlider.progress = 100
            saturationSlider.progress = 100
        }

        buttonAdjustCancel.setOnClickListener {
            drawingView.resetAdjustments()
            editViewModel.resetAdjustments()
            brightnessSlider.progress = 100
            contrastSlider.progress = 100
            saturationSlider.progress = 100
        }

        // Color Swatches
        val colorBlackContainer: FrameLayout = findViewById(R.id.color_black_container)
        val colorWhiteContainer: FrameLayout = findViewById(R.id.color_white_container)
        val colorRedContainer: FrameLayout = findViewById(R.id.color_red_container)
        val colorGreenContainer: FrameLayout = findViewById(R.id.color_green_container)
        val colorBlueContainer: FrameLayout = findViewById(R.id.color_blue_container)
        val colorYellowContainer: FrameLayout = findViewById(R.id.color_yellow_container)
        val colorOrangeContainer: FrameLayout = findViewById(R.id.color_orange_container)
        val colorPinkContainer: FrameLayout = findViewById(R.id.color_pink_container)

        val colorSwatchMap = mapOf(
            android.graphics.Color.BLACK to colorBlackContainer,
            android.graphics.Color.WHITE to colorWhiteContainer,
            android.graphics.Color.RED to colorRedContainer,
            android.graphics.Color.GREEN to colorGreenContainer,
            android.graphics.Color.BLUE to colorBlueContainer,
            android.graphics.Color.YELLOW to colorYellowContainer,
            android.graphics.Color.rgb(255, 165, 0) to colorOrangeContainer,
            android.graphics.Color.rgb(255, 192, 203) to colorPinkContainer
        )

        val colorClickListener = View.OnClickListener { v ->
            val clickedColor = colorSwatchMap.entries.find { it.value == v }?.key
            clickedColor?.let { editViewModel.updateDrawingColor(it) }
        }
        colorSwatchMap.values.forEach { it.setOnClickListener(colorClickListener) }

        updateDrawModeSelection(drawModePen)
        
        // ViewModel Observation
        lifecycleScope.launch {
            editViewModel.drawingState.collect { state ->
                drawingView.setDrawingState(state)
                colorSwatchMap.forEach { (color, container) ->
                    val border = container.findViewWithTag<View>("border")
                    border?.visibility = if (color == state.color) View.VISIBLE else View.GONE
                }
            }
        }

        lifecycleScope.launch {
            editViewModel.undoStack.collect { actions ->
            }
        }

        lifecycleScope.launch {
            editViewModel.adjustState.collect { state ->
                drawingView.setAdjustments(state.brightness, state.contrast, state.saturation)
            }
        }

        // Undo/Redo Buttons
        buttonUndo.setOnClickListener {
            drawingView.undo()
        }

        buttonRedo.setOnClickListener {
            drawingView.redo()
        }

        cropModeFreeform.isSelected = true
        currentCropMode = cropModeFreeform

        toolDraw.isSelected = true
        currentActiveTool = toolDraw
        drawOptionsLayout.visibility = View.VISIBLE
        
        handleIntent(intent)

        // Setup Initial Sketch Mode
        drawingView.doOnLayout { view ->
            if (currentImageInfo == null) {
                isSketchMode = true
                drawingView.setSketchMode(true)
                
                val width = view.width
                val height = view.height
                
                val transparentBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                
                drawingView.setBitmap(transparentBitmap)
                
                drawingView.post {
                    drawingView.setSketchMode(true)
                }
                
                currentImageInfo = ImageInfo(
                    uri = Uri.EMPTY,
                    origin = ImageOrigin.EDITED_INTERNAL,
                    canOverwrite = false,
                    originalMimeType = "image/jpeg" 
                )
                
                selectedSaveFormat = "image/jpeg"
                updateFormatSelectionUI()
                updateSavePanelUI()
            }
        }

        // Overwrite Request Launcher
        deleteRequestLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pendingOverwriteUri?.let {
                    currentImageInfo?.uri?.let { oldUri ->
                        imageLoader.memoryCache?.remove(MemoryCache.Key(oldUri.toString()))
                    }
                    currentImageInfo = currentImageInfo?.copy(uri = it)
                    pendingOverwriteUri = null
                }
            } else {
                pendingOverwriteUri?.let {
                    currentImageInfo = currentImageInfo?.copy(uri = it)
                    pendingOverwriteUri = null
                }
            }
        }
    }
    
    private fun updateUndoRedoButtonState() {
        val canUndo = drawingView.canUndo()
        val canRedo = drawingView.canRedo()
        buttonUndo.isEnabled = canUndo
        buttonRedo.isEnabled = canRedo
        buttonUndo.alpha = if (canUndo) 1.0f else 0.5f
        buttonRedo.alpha = if (canRedo) 1.0f else 0.5f
    }
    
    // Helper Methods
    private fun shareCurrentImage() {
        if (isSaving) return
        isSaving = true

        lifecycleScope.launch {
            try {
                showLoadingSpinner()
                val shareUri = withContext(Dispatchers.IO) {
                    val bitmapToShare = createBitmapToSave()

                    cacheDir.listFiles { file -> file.name.startsWith("share_temp_") }
                        ?.forEach { it.delete() }

                    val cacheDir = cacheDir
                    val fileName = "share_temp_${System.currentTimeMillis()}.${getExtensionFromMimeType(selectedSaveFormat)}"
                    val tempFile = File(cacheDir, fileName)

                    contentResolver.openOutputStream(Uri.fromFile(tempFile))?.use { outputStream ->
                        compressBitmapToStream(bitmapToShare, outputStream, selectedSaveFormat)
                    }

                    androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        tempFile
                    )
                }

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = selectedSaveFormat
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = Intent.createChooser(shareIntent, getString(R.string.share_image))
                startActivity(chooser)

            } catch (e: Exception) {
                showCustomToast(getString(R.string.share_failed, e.message ?: "Unknown error"))
            } finally {
                hideLoadingSpinner()
                isSaving = false
            }
        }
    }

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
        checkPermissionRevocation()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> {
                val clipData = intent.clipData
                if (clipData != null) {
                    val itemCount = clipData.itemCount
                    if (itemCount > 1) {
                        return
                    }
                    val item = clipData.getItemAt(0)
                    item?.uri?.let { uri ->
                        loadImageFromUri(uri, Intent.ACTION_EDIT == intent.action)
                    }
                } else {
                    intent.data?.let { uri ->
                        loadImageFromUri(uri, Intent.ACTION_EDIT == intent.action)
                    }
                }
            }
        }
    }

    private fun captureImageFromCamera() {
        try {
            val imageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            if (imageDir == null) {
                showCustomToast(getString(R.string.cannot_access_storage))
                return
            }
            imageDir.mkdirs()
            
            val fileName = "camera_temp_${System.currentTimeMillis()}.jpg"
            val privateFile = File(imageDir, fileName)
            
            if (!privateFile.exists()) {
                if (!privateFile.createNewFile()) {
                    showCustomToast(getString(R.string.failed_to_create_temp_file))
                    return
                }
            }
            
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val photoURI = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                privateFile
            )
            
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            
            currentCameraUri = photoURI
            
            cameraCaptureLauncher.launch(intent)
            
        } catch (e: Exception) {
            showCustomToast(getString(R.string.camera_error, e.message ?: "Unknown error"))
        }
    }

    private fun loadImageFromUri(uri: android.net.Uri, isEdit: Boolean) {
        if (isImageLoading) return
        isImageLoading = true

        drawingView.setSketchMode(false)
        editViewModel.clearAllActions()

        lifecycleScope.launch {
            showLoadingSpinner()
            try {
                val request = ImageRequest.Builder(this@MainActivity)
                    .data(uri)
                    .size(coil.size.Size.ORIGINAL)
                    .lifecycle(this@MainActivity) 
                    .build()

                val result = imageLoader.execute(request)

                if (result is coil.request.SuccessResult) {
                    val drawable = result.drawable
                    val bitmap = (drawable as android.graphics.drawable.BitmapDrawable).bitmap

                    val origin = determineImageOrigin(uri)
                    val canOverwrite = determineCanOverwrite(origin)
                    val originalMimeType = contentResolver.getType(uri) ?: "image/jpeg"
                    
                    currentImageInfo = ImageInfo(uri, origin, canOverwrite, originalMimeType)
                    
                    drawingView.setBitmap(bitmap.copy(Bitmap.Config.ARGB_8888, true))
                    
                    val displayName = getDisplayNameFromUri(uri) ?: "Image"
                    showCustomToast(getString(R.string.loaded_image_successfully, displayName))
                    
                    updateSavePanelUI()
                    detectAndSetImageFormat(uri)
                    
                    currentImageHasTransparency = bitmap.hasAlpha()
                    updateTransparencyWarning()
                    
                    updateUndoRedoButtonState()

                } else {
                    val error = (result as coil.request.ErrorResult).throwable
                    handleImageLoadFailure(error.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                handleImageLoadFailure("Image loading error: ${e.message ?: "Unknown error"}")
            } finally {
                hideLoadingSpinner()
                isImageLoading = false
            }
        }
    }
    
    private fun handleImageLoadFailure(errorMessage: String) {
        runOnUiThread {
            try {
                drawingView.setBitmap(null)
                drawingView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                showCustomToast(getString(R.string.could_not_load_image, errorMessage))
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in handleImageLoadFailure: ${e.message ?: "Unknown error"}")
            }
        }
    }

    private fun cleanupCameraFile(uri: Uri) {
        try {
            val path = uri.path
            if (path != null && path.contains("camera_temp_")) {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            } else {
                try {
                    contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
    }
    
    private fun determineImageOrigin(uri: Uri): ImageOrigin {
        val isPersistedWritable = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }

        return when {
            isPersistedWritable -> ImageOrigin.IMPORTED_WRITABLE
            uri.path?.contains("camera_temp_") == true -> ImageOrigin.CAMERA_CAPTURED
            uri.authority == "${packageName}.fileprovider" -> ImageOrigin.EDITED_INTERNAL
            uri.toString().contains("media") -> {
                if (hasImagePermission()) ImageOrigin.IMPORTED_WRITABLE else ImageOrigin.IMPORTED_READONLY
            }
            uri.toString().contains("camera") -> ImageOrigin.CAMERA_CAPTURED 
            else -> ImageOrigin.IMPORTED_READONLY
        }
    }
    
    private fun determineCanOverwrite(origin: ImageOrigin): Boolean {
        return when (origin) {
            ImageOrigin.CAMERA_CAPTURED, ImageOrigin.EDITED_INTERNAL -> true
            ImageOrigin.IMPORTED_WRITABLE -> hasImagePermission()
            ImageOrigin.IMPORTED_READONLY -> false
        }
    }

    private fun showLoadingSpinner() {
        overlayContainer.visibility = View.VISIBLE
        disableAllInteractiveElements(true)
    }

    private fun hideLoadingSpinner() {
        overlayContainer.visibility = View.GONE
        disableAllInteractiveElements(false)
    }

    private fun disableAllInteractiveElements(disabled: Boolean) {
        findViewById<ImageView>(R.id.button_import)?.isEnabled = !disabled
        findViewById<ImageView>(R.id.button_camera)?.isEnabled = !disabled
        
        val canUndo = drawingView.canUndo()
        val canRedo = drawingView.canRedo()
        findViewById<ImageView>(R.id.button_undo)?.isEnabled = !disabled && canUndo
        findViewById<ImageView>(R.id.button_redo)?.isEnabled = !disabled && canRedo

        findViewById<ImageView>(R.id.button_share)?.isEnabled = !disabled
        findViewById<ImageView>(R.id.button_save)?.isEnabled = !disabled
        
        findViewById<ImageView>(R.id.tool_draw)?.isEnabled = !disabled
        findViewById<ImageView>(R.id.tool_crop)?.isEnabled = !disabled
        findViewById<ImageView>(R.id.tool_adjust)?.isEnabled = !disabled
        
        if (savePanel.visibility == View.VISIBLE) {
            savePanel.isEnabled = !disabled
        }
        
        if (toolOptionsLayout.visibility == View.VISIBLE) {
            toolOptionsLayout.isEnabled = !disabled
        }
    }

    private fun hasImagePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestImagePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            openImagePicker()
        }
    }

    private fun openImagePicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            oldImagePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.select_picture)))
        } catch (e: Exception) {
            showCustomToast(getString(R.string.could_not_open_photo_picker, e.message ?: "Unknown error"))
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun checkPermissionRevocation() {
        val wasOverwriteAvailable = currentImageInfo?.canOverwrite ?: false
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasImagePermission()) {
                currentImageInfo?.canOverwrite = false
            }
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (currentImageInfo?.origin == ImageOrigin.IMPORTED_WRITABLE && !hasImagePermission()) {
                currentImageInfo?.canOverwrite = false
            }
        }
        
        if (wasOverwriteAvailable && (currentImageInfo?.canOverwrite == false)) {
            updateSavePanelUI()
        }
    }

    private fun updateSavePanelUI() {
        updateSaveButtonsState() 
        updateSaveButtonText()
    }

    private fun updateDrawModeSelection(selectedMode: ImageView) {
        currentDrawMode?.isSelected = false
        selectedMode.isSelected = true
        currentDrawMode = selectedMode
    }
    
    private fun updateSaveButtonText() {
        val buttonSaveCopy: Button = findViewById(R.id.button_save_copy)
        
        val info = currentImageInfo
        if (info == null) {
            buttonSaveCopy.text = getString(R.string.save_copy)
            return
        }

        if (info.origin == ImageOrigin.CAMERA_CAPTURED) {
            buttonSaveCopy.text = getString(R.string.save)
        } else {
            buttonSaveCopy.text = getString(R.string.save_copy)
        }
    }
    
    private fun updateSaveButtonsState() {
        val buttonOverwrite: Button = findViewById(R.id.button_overwrite)
        val warningIcon: ImageView = findViewById(R.id.warning_icon)

        val info = currentImageInfo
        if (info == null) {
            buttonOverwrite.visibility = View.GONE
            warningIcon.visibility = View.GONE
            return
        }

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
    
    private fun saveImageAsCopy() {
        if (currentImageInfo == null || isSaving) return
        isSaving = true

        lifecycleScope.launch {
            showLoadingSpinner()
            try {
                val bitmapToSave = createBitmapToSave()
                val savedUri = saveBitmapToMediaStore(bitmapToSave)
                
                val displayName = getDisplayNameFromUri(savedUri) ?: "Unknown file"
                showCustomToast(getString(R.string.image_saved_to_editss_folder, displayName))
                
                savePanel.visibility = View.GONE
                scrim.visibility = View.GONE
                drawingView.markAsSaved()

            } catch (e: Exception) {
                showCustomToast(e.message ?: "Unknown error")
            } finally {
                hideLoadingSpinner()
                isSaving = false
            }
        }
    }

    private suspend fun createBitmapToSave(): Bitmap = withContext(Dispatchers.IO) {
        val bitmap = if (isSketchMode) {
            when (selectedSaveFormat) {
                "image/png", "image/webp" -> drawingView.getDrawing()
                "image/jpeg" -> drawingView.getDrawing()?.let {
                    drawingView.convertTransparentToWhite(it)
                }
                else -> drawingView.getDrawing()
            }
        } else {
            drawingView.getDrawing()?.let {
                if (selectedSaveFormat == "image/jpeg" && currentImageHasTransparency) {
                    drawingView.convertTransparentToWhite(it)
                } else {
                    it
                }
            }
        }
        bitmap ?: throw Exception("No image to save")
    }

    private suspend fun saveBitmapToMediaStore(bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val imageInfo = currentImageInfo ?: throw Exception("Image info is missing")
        
        val uniqueDisplayName = when (imageInfo.origin) {
            ImageOrigin.CAMERA_CAPTURED, ImageOrigin.EDITED_INTERNAL -> {
                generateTimestampFileName()
            }
            else -> {
                val originalDisplayName = getDisplayNameFromUri(imageInfo.uri) ?: "Image"
                generateUniqueCopyName(originalDisplayName)
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, uniqueDisplayName)
            put(MediaStore.Images.Media.MIME_TYPE, selectedSaveFormat)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EditSS")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception(getString(R.string.save_failed))

        contentResolver.openOutputStream(uri)?.use { outputStream ->
            compressBitmapToStream(bitmap, outputStream, selectedSaveFormat)
        }
        
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        
        uri
    }
    
    private fun overwriteCurrentImage() {
        val imageInfo = currentImageInfo ?: return
        if (!imageInfo.canOverwrite || selectedSaveFormat != imageInfo.originalMimeType) {
            return
        }
        
        if (isSaving) return
        isSaving = true

        AlertDialog.Builder(this, R.style.AlertDialog_EditSS)
            .setTitle(getString(R.string.overwrite_changes_title))
            .setMessage(getString(R.string.overwrite_changes_message))
            .setPositiveButton(getString(R.string.confirm)) { dialog, _ ->
                lifecycleScope.launch {
                    showLoadingSpinner()
                    try {
                        val displayName = withContext(Dispatchers.IO) {
                            val bitmapToSave = drawingView.getDrawing()
                                ?: throw Exception("Could not get image to overwrite")
                            
                            contentResolver.openOutputStream(imageInfo.uri, "wt")?.use { outputStream ->
                                compressBitmapToStream(bitmapToSave, outputStream, selectedSaveFormat)
                            }
                            
                            getDisplayNameFromUri(imageInfo.uri)
                        }

                        val pathToShow = displayName ?: "Unknown file"
                        showCustomToast(getString(R.string.image_overwritten_successfully, pathToShow))
                        
                        savePanel.visibility = View.GONE
                        scrim.visibility = View.GONE
                        drawingView.markAsSaved()

                        imageLoader.memoryCache?.remove(MemoryCache.Key(imageInfo.uri.toString()))
                        imageLoader.diskCache?.remove(imageInfo.uri.toString())

                    } catch (e: Exception) {
                        showCustomToast(getString(R.string.overwrite_failed, e.message ?: "Unknown error"))
                    } finally {
                        hideLoadingSpinner()
                        isSaving = false
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                isSaving = false
                dialog.dismiss()
            }
            .setOnCancelListener {
                isSaving = false
            }
            .show()
    }
    
    private fun generateUniqueCopyName(originalDisplayName: String): String {
        val newExtension = when (selectedSaveFormat) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }

        val nameWithoutExt = originalDisplayName.substringBeforeLast('.')
        val copyPattern = Pattern.compile("\\s-\\sCopy(\\s\\(\\d+\\))?$")
        val matcher = copyPattern.matcher(nameWithoutExt)
        val baseName = if (matcher.find()) {
            nameWithoutExt.substring(0, matcher.start())
        } else {
            nameWithoutExt
        }

        var newName = "$baseName - Copy.$newExtension"
        if (!fileNameExistsInEditSS(newName)) {
            return newName
        }

        var counter = 2
        while (true) {
            newName = "$baseName - Copy ($counter).$newExtension"
            if (!fileNameExistsInEditSS(newName)) {
                return newName
            }
            counter++
        }
    }

    private fun fileNameExistsInEditSS(fileName: String): Boolean {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(fileName, "%Pictures/EditSS%")

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            return cursor.count > 0
        }
        return false
    }

    private fun generateTimestampFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val extension = when (selectedSaveFormat) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        return "IMG_${timestamp}.$extension"
    }
    
    private fun updateTransparencyWarning() {
        if (currentImageHasTransparency) {
            when {
                selectedSaveFormat == "image/jpeg" -> {
                    transparencyWarningText.text = getString(R.string.jpg_does_not_support_transparency)
                    transparencyWarningText.visibility = View.VISIBLE
                }
                selectedSaveFormat == "image/webp" && currentImageHasTransparency -> {
                    transparencyWarningText.visibility = View.GONE
                }
                else -> {
                    transparencyWarningText.visibility = View.GONE
                }
            }
        } else {
            transparencyWarningText.visibility = View.GONE
        }
    }
            
    private fun updateFormatSelectionUI() {
        val radioJPG: RadioButton = findViewById(R.id.radio_jpg)
        val radioPNG: RadioButton = findViewById(R.id.radio_png)
        val radioWEBP: RadioButton = findViewById(R.id.radio_webp)
        
        when (selectedSaveFormat) {
            "image/jpeg" -> radioJPG.isChecked = true
            "image/png" -> radioPNG.isChecked = true
            "image/webp" -> radioWEBP.isChecked = true
        }
        updateSaveButtonsState()
    }
    
    private fun detectAndSetImageFormat(uri: Uri) {
        try {
            val mimeType = contentResolver.getType(uri)
            
            val detectedFormat = when {
                mimeType == "image/jpeg" -> "image/jpeg"
                mimeType == "image/png" -> "image/png"
                mimeType == "image/webp" -> "image/webp"
                else -> {
                    val displayName = getDisplayNameFromUri(uri) ?: ""
                    when {
                        displayName.lowercase().endsWith(".jpg") || displayName.lowercase().endsWith(".jpeg") -> "image/jpeg"
                        displayName.lowercase().endsWith(".png") -> "image/png"
                        displayName.lowercase().endsWith(".webp") -> "image/webp"
                        else -> "image/jpeg"
                    }
                }
            }
            
            selectedSaveFormat = detectedFormat
            updateFormatSelectionUI()
            
        } catch (e: Exception) {
            selectedSaveFormat = "image/jpeg"
            updateFormatSelectionUI()
        }
    }
    
    private fun compressBitmapToStream(bitmap: Bitmap, outputStream: OutputStream, mimeType: String) {
        try {
            val compressFormat = when (mimeType) {
                "image/jpeg" -> CompressFormat.JPEG
                "image/png" -> CompressFormat.PNG
                "image/webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) CompressFormat.WEBP_LOSSY else CompressFormat.WEBP
                else -> CompressFormat.JPEG
            }
            
            val quality = when (mimeType) {
                "image/jpeg", "image/webp" -> 100
                else -> 100
            }
            
            bitmap.compress(compressFormat, quality, outputStream)
        } catch (e: Exception) {
            throw Exception("Failed to compress image: ${e.message ?: "Unknown error"}")
        }
    }

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

    private fun showCustomToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        val layout = layoutInflater.inflate(R.layout.custom_toast, null)
        val textView = layout.findViewById<TextView>(R.id.toast_message)
        textView.text = message
        
        val toast = Toast(applicationContext)
        toast.view = layout
        toast.duration = duration
        
        toolOptionsLayout?.let { toolPanel ->
            val location = IntArray(2)
            toolPanel.getLocationOnScreen(location)
            val offsetInPixels = (toolPanel.height + 35 * resources.displayMetrics.density).toInt()
            toast.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, offsetInPixels)
        } ?: run {
            toast.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, 100)
        }
        toast.show()
    }

    private fun applyAdjustmentsAndShowSavePanel() {
        val brightnessSlider: SeekBar = findViewById(R.id.adjust_brightness_slider)
        val contrastSlider: SeekBar = findViewById(R.id.adjust_contrast_slider)
        val saturationSlider: SeekBar = findViewById(R.id.adjust_saturation_slider)
        
        drawingView.applyAdjustmentsToBitmap()
        showCustomToast(getString(R.string.adjustment_applied))

        editViewModel.resetAdjustments()
        brightnessSlider.progress = 100
        contrastSlider.progress = 100
        saturationSlider.progress = 100
        
        savePanel.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
    }
}