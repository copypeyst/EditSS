package com.tamad.editss

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.content.Intent
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Toast
import android.provider.Settings
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.os.Environment
import android.content.ContentValues
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.OutputStream
import coil.ImageLoader
import coil.request.ImageRequest
import coil.disk.DiskCache
import coil.memory.MemoryCache

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

    private lateinit var canvasImageView: ImageView
    private lateinit var savePanel: View
    private lateinit var drawOptionsLayout: LinearLayout
    private lateinit var cropOptionsLayout: LinearLayout
    private lateinit var adjustOptionsLayout: LinearLayout
    private lateinit var scrim: View
    private lateinit var transparencyWarningText: TextView
    private var currentActiveTool: ImageView? = null
    private var currentDrawMode: ImageView? = null
    private var currentCropMode: ImageView? = null
    private var currentSelectedColor: FrameLayout? = null
    private var currentImageInfo: ImageInfo? = null
    private var selectedSaveFormat: String = "image/jpeg"
    private var currentImageHasTransparency = false
    private var currentCameraUri: Uri? = null
    private var isImageLoading = false
    private var isImageLoadAttempted = false
    private var lastImageLoadFailed = false
    
    // Handles the result of the delete confirmation dialog
    private lateinit var deleteRequestLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>

    // Temporarily stores the URI of the new file while we wait for user confirmation
    private var pendingOverwriteUri: Uri? = null

    private val imageLoader by lazy {
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir).maxSizeBytes(50 * 1024 * 1024).build() }
            .respectCacheHeaders(false)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        canvasImageView = findViewById(R.id.canvas)
        val buttonSave: ImageView = findViewById(R.id.button_save)
        val buttonImport: ImageView = findViewById(R.id.button_import)
        val buttonCamera: ImageView = findViewById(R.id.button_camera)
        val toolDraw: ImageView = findViewById(R.id.tool_draw)
        val toolCrop: ImageView = findViewById(R.id.tool_crop)
        val toolAdjust: ImageView = findViewById(R.id.tool_adjust)
        savePanel = findViewById(R.id.save_panel)
        drawOptionsLayout = findViewById(R.id.draw_options)
        cropOptionsLayout = findViewById(R.id.crop_options)
        adjustOptionsLayout = findViewById(R.id.adjust_options)
        scrim = findViewById(R.id.scrim)
        transparencyWarningText = findViewById(R.id.transparency_warning_text)

        buttonSave.setOnClickListener {
            savePanel.visibility = if (savePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            scrim.visibility = if (scrim.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        scrim.setOnClickListener {
            savePanel.visibility = View.GONE
            scrim.visibility = View.GONE
        }

        buttonImport.setOnClickListener {
            if (hasImagePermission()) openImagePicker() else requestImagePermission()
        }

        buttonCamera.setOnClickListener { captureImageFromCamera() }

        toolDraw.setOnClickListener {
            showToolOptions(drawOptionsLayout)
            currentActiveTool?.isSelected = false
            toolDraw.isSelected = true
            currentActiveTool = toolDraw
        }

        toolCrop.setOnClickListener {
            showToolOptions(cropOptionsLayout)
            currentActiveTool?.isSelected = false
            toolCrop.isSelected = true
            currentActiveTool = toolCrop
        }

        toolAdjust.setOnClickListener {
            showToolOptions(adjustOptionsLayout)
            currentActiveTool?.isSelected = false
            toolAdjust.isSelected = true
            currentActiveTool = toolAdjust
        }

        val buttonSaveCopy: Button = findViewById(R.id.button_save_copy)
        val buttonOverwrite: Button = findViewById(R.id.button_overwrite)
        buttonSaveCopy.setOnClickListener { saveImageAsCopy() }
        buttonOverwrite.setOnClickListener { overwriteCurrentImage() }

        val touchListener = View.OnTouchListener { v, event ->
            v.alpha = when (event.action) {
                MotionEvent.ACTION_DOWN -> 0.5f
                else -> 1.0f
            }
            false
        }
        buttonSaveCopy.setOnTouchListener(touchListener)
        buttonOverwrite.setOnTouchListener(touchListener)
        
        findViewById<RadioButton>(R.id.radio_jpg).setOnClickListener { setSaveFormat("image/jpeg") }
        findViewById<RadioButton>(R.id.radio_png).setOnClickListener { setSaveFormat("image/png") }
        findViewById<RadioButton>(R.id.radio_webp).setOnClickListener { setSaveFormat("image/webp") }

        setupToolSelectors()
        setupColorSwatches()

        toolDraw.performClick()
        colorRedContainer.performClick()
        
        handleIntent(intent)
        checkPermissionRevocation()

        // Initialize the launcher that will handle the result of the delete request.
        deleteRequestLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, "Original file deleted.", Toast.LENGTH_SHORT).show()
                pendingOverwriteUri?.let {
                    currentImageInfo?.uri?.let { oldUri -> imageLoader.memoryCache?.remove(MemoryCache.Key(oldUri.toString())) }
                    currentImageInfo = currentImageInfo?.copy(uri = it)
                    pendingOverwriteUri = null
                }
            } else {
                Toast.makeText(this, "Original file was not deleted.", Toast.LENGTH_SHORT).show()
                pendingOverwriteUri?.let {
                    currentImageInfo = currentImageInfo?.copy(uri = it)
                    pendingOverwriteUri = null
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        checkPermissionRevocation()
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) {
            val uri = intent.clipData?.getItemAt(0)?.uri ?: intent.data
            uri?.let { loadImageFromUri(it, intent.action == Intent.ACTION_EDIT) }
        }
    }

    private fun captureImageFromCamera() {
        try {
            val imageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: run {
                Toast.makeText(this, "Cannot access storage", Toast.LENGTH_SHORT).show()
                return
            }
            imageDir.mkdirs()
            val privateFile = File(imageDir, "camera_temp_${System.currentTimeMillis()}.jpg")
            val photoURI = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", privateFile)
            
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            currentCameraUri = photoURI
            startActivityForResult(intent, CAMERA_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImageFromUri(uri: Uri, isEdit: Boolean) {
        if (isImageLoading) return
        isImageLoading = true
        Toast.makeText(this, "Loading image...", Toast.LENGTH_SHORT).show()

        val request = ImageRequest.Builder(this)
            .data(uri)
            .target(
                onSuccess = { drawable ->
                    isImageLoading = false
                    val origin = determineImageOrigin(uri)
                    currentImageInfo = ImageInfo(uri, origin, determineCanOverwrite(origin))
                    canvasImageView.setImageDrawable(drawable)
                    Toast.makeText(this, "Loaded ${origin.name} image successfully", Toast.LENGTH_SHORT).show()
                    updateSavePanelUI()
                    detectAndSetImageFormat(uri)
                    currentImageHasTransparency = detectImageTransparencyFromDrawable()
                    updateTransparencyWarning()
                },
                onError = {
                    isImageLoading = false
                    Toast.makeText(this, "Couldn't load image.", Toast.LENGTH_SHORT).show()
                }
            )
            .build()
        imageLoader.enqueue(request)
    }

    private fun determineImageOrigin(uri: Uri): ImageOrigin {
        return when {
            uri.authority == "${packageName}.fileprovider" -> ImageOrigin.EDITED_INTERNAL
            uri.toString().startsWith("content://media/") -> ImageOrigin.IMPORTED_WRITABLE
            else -> ImageOrigin.IMPORTED_READONLY
        }
    }

    private fun determineCanOverwrite(origin: ImageOrigin): Boolean {
        return origin != ImageOrigin.IMPORTED_READONLY && hasImagePermission()
    }

    private fun hasImagePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestImagePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), PERMISSION_REQUEST_CODE)
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, IMPORT_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == CAMERA_REQUEST_CODE) currentCameraUri?.let { cleanupCameraFile(it) }
            return
        }
        when (requestCode) {
            IMPORT_REQUEST_CODE -> data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                loadImageFromUri(uri, false)
            }
            CAMERA_REQUEST_CODE -> currentCameraUri?.let { loadImageFromUri(it, false) }
        }
    }
    
    private fun cleanupCameraFile(uri: Uri) {
        uri.path?.let { File(it).delete() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openImagePicker()
        } else {
            showPermissionDeniedDialog()
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Permission denied. Please allow access in Settings.")
            .setPositiveButton("Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun checkPermissionRevocation() {
        if (currentImageInfo?.canOverwrite == true && !hasImagePermission()) {
            currentImageInfo?.canOverwrite = false
            updateSavePanelUI()
        }
    }

    private fun updateSavePanelUI() {
        findViewById<Button>(R.id.button_overwrite).visibility = if (currentImageInfo?.canOverwrite == true) View.VISIBLE else View.GONE
    }
    
    private fun saveImageAsCopy() {
        val imageUriToSave = currentImageInfo?.uri ?: run {
            Toast.makeText(this, "No image to save", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(this@MainActivity).data(imageUriToSave).allowHardware(false).build()
                val bitmapToSave = (imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (bitmapToSave != null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, generateUniqueFilename())
                        put(MediaStore.Images.Media.MIME_TYPE, selectedSaveFormat)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val newUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("Failed to create new image entry.")
                    contentResolver.openOutputStream(newUri)?.use { compressBitmapToStream(bitmapToSave, it, selectedSaveFormat) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(newUri, values, null, null)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Image saved successfully", Toast.LENGTH_SHORT).show()
                        savePanel.visibility = View.GONE
                        scrim.visibility = View.GONE
                    }
                } else throw Exception("Could not get image data to save.")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun overwriteCurrentImage() {
        val imageInfo = currentImageInfo ?: run { Toast.makeText(this, "No image to overwrite", Toast.LENGTH_SHORT).show(); return }
        if (!imageInfo.canOverwrite) { Toast.makeText(this, "Cannot overwrite this image", Toast.LENGTH_SHORT).show(); return }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(this@MainActivity).data(imageInfo.uri).allowHardware(false).build()
                val bitmapToSave = (imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap 
                    ?: throw Exception("Could not get image to overwrite")
                
                val isFormatChanging = contentResolver.getType(imageInfo.uri) != selectedSaveFormat
                if (isFormatChanging) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, generateUniqueFilename())
                        put(MediaStore.Images.Media.MIME_TYPE, selectedSaveFormat)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val newUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("Failed to create new image entry.")
                    contentResolver.openOutputStream(newUri)?.use { compressBitmapToStream(bitmapToSave, it, selectedSaveFormat) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(newUri, values, null, null)
                    
                    pendingOverwriteUri = newUri
                    val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(imageInfo.uri))
                    val intentSenderRequest = androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Confirm deletion of original.", Toast.LENGTH_LONG).show()
                        savePanel.visibility = View.GONE
                        scrim.visibility = View.GONE
                        deleteRequestLauncher.launch(intentSenderRequest)
                    }
                } else {
                    contentResolver.openOutputStream(imageInfo.uri)?.use { compressBitmapToStream(bitmapToSave, it, selectedSaveFormat) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Image overwritten successfully", Toast.LENGTH_SHORT).show()
                        savePanel.visibility = View.GONE
                        scrim.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Overwrite failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
    
    private fun generateUniqueFilename(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "IMG_${timestamp}_${(100..999).random()}.${selectedSaveFormat.substringAfter('/')}"
    }
    
    private fun setSaveFormat(format: String) {
        selectedSaveFormat = format
        updateTransparencyWarning()
    }

    private fun updateTransparencyWarning() {
        val showWarning = currentImageHasTransparency && selectedSaveFormat == "image/jpeg"
        transparencyWarningText.visibility = if (showWarning) View.VISIBLE else View.GONE
    }
    
    private fun detectImageTransparencyFromDrawable(): Boolean {
        return currentImageInfo?.uri?.let { contentResolver.getType(it) in listOf("image/png", "image/webp") } ?: false
    }
    
    private fun detectAndSetImageFormat(uri: Uri) {
        val mimeType = contentResolver.getType(uri)
        val format = when {
            mimeType in listOf("image/jpeg", "image/png", "image/webp") -> mimeType
            else -> getDisplayNameFromUri(uri)?.substringAfterLast('.')?.lowercase()?.let { "image/$it" } ?: "image/jpeg"
        }
        setSaveFormat(if (format in listOf("image/jpeg", "image/png", "image/webp")) format else "image/jpeg")
        updateFormatSelectionUI()
    }

    private fun updateFormatSelectionUI() {
        val radioGroup = findViewById<android.widget.RadioGroup>(R.id.format_radio_group)
        val id = when(selectedSaveFormat) {
            "image/png" -> R.id.radio_png
            "image/webp" -> R.id.radio_webp
            else -> R.id.radio_jpg
        }
        radioGroup.check(id)
    }
    
    private fun compressBitmapToStream(bitmap: Bitmap, outputStream: OutputStream, mimeType: String) {
        val format = when(mimeType) {
            "image/png" -> CompressFormat.PNG
            "image/webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) CompressFormat.WEBP_LOSSY else CompressFormat.WEBP
            else -> CompressFormat.JPEG
        }
        bitmap.compress(format, 95, outputStream)
    }

    private fun getDisplayNameFromUri(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { 
            if (it.moveToFirst()) return it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
        }
        return null
    }

    private fun showToolOptions(layoutToShow: View) {
        drawOptionsLayout.visibility = View.GONE
        cropOptionsLayout.visibility = View.GONE
        adjustOptionsLayout.visibility = View.GONE
        layoutToShow.visibility = View.VISIBLE
        savePanel.visibility = View.GONE
    }

    private fun setupToolSelectors() {
        val pen: ImageView = findViewById(R.id.draw_mode_pen)
        val circle: ImageView = findViewById(R.id.draw_mode_circle)
        val square: ImageView = findViewById(R.id.draw_mode_square)
        pen.setOnClickListener { selectDrawMode(it as ImageView) }
        circle.setOnClickListener { selectDrawMode(it as ImageView) }
        square.setOnClickListener { selectDrawMode(it as ImageView) }
        pen.performClick()

        val freeform: ImageView = findViewById(R.id.crop_mode_freeform)
        val cropSquare: ImageView = findViewById(R.id.crop_mode_square)
        val portrait: ImageView = findViewById(R.id.crop_mode_portrait)
        val landscape: ImageView = findViewById(R.id.crop_mode_landscape)
        freeform.setOnClickListener { selectCropMode(it as ImageView) }
        cropSquare.setOnClickListener { selectCropMode(it as ImageView) }
        portrait.setOnClickListener { selectCropMode(it as ImageView) }
        landscape.setOnClickListener { selectCropMode(it as ImageView) }
        freeform.performClick()
    }

    private fun selectDrawMode(view: ImageView) {
        currentDrawMode?.isSelected = false
        view.isSelected = true
        currentDrawMode = view
    }

    private fun selectCropMode(view: ImageView) {
        currentCropMode?.isSelected = false
        view.isSelected = true
        currentCropMode = view
    }

    private fun setupColorSwatches() {
        val colorClickListener = View.OnClickListener { v ->
            currentSelectedColor?.findViewWithTag<View>("border")?.visibility = View.GONE
            v.findViewWithTag<View>("border")?.visibility = View.VISIBLE
            currentSelectedColor = v as FrameLayout
        }
        findViewById<FrameLayout>(R.id.color_black_container).setOnClickListener(colorClickListener)
        findViewById<FrameLayout>(R.id.color_white_container).setOnClickListener(colorClickListener)
        findViewById<FrameLayout>(R.id.color_red_container).setOnClickListener(colorClickListener)
        findViewById<FrameLayout>(R.id.color_green_container).setOnClickListener(colorClickListener)
        findViewById<FrameLayout>(R.id.color_blue_container).setOnClickListener(colorClickListener)
        findViewById<FrameLayout>(R.id.color_yellow_container).setOnClickListener(colorClickListener)
        findViewById<FrameLayout>(R.id.color_orange_container).setOnClickListener(colorClickListener)
        findViewById<FrameLayout>(R.id.color_pink_container).setOnClickListener(colorClickListener)
    }
}