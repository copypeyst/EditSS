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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val IMPORT_REQUEST_CODE = 101
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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find UI elements
        rootLayout = findViewById(R.id.root_layout)
        val buttonSave: ImageView = findViewById(R.id.button_save)
        val buttonImport: ImageView = findViewById(R.id.button_import)
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

    private fun loadImageFromUri(uri: android.net.Uri, isEdit: Boolean) {
        // TODO: Implement actual image loading logic
        // This is a placeholder for the actual implementation
        Toast.makeText(this, "Loading image from: $uri", Toast.LENGTH_SHORT).show()
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

    // Photo picker logic - Step 4: Use MediaStore API for Android 10-12
    private fun openImagePicker() {
        // Android 13+ Photo Picker API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) // Single image only
            startActivityForResult(intent, IMPORT_REQUEST_CODE)
        } else {
            // Android 10-12: Use MediaStore API with ACTION_OPEN_DOCUMENT
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
        
        if (requestCode == IMPORT_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
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
}
