        buttonCropApply.setOnClickListener {
            drawingView.applyCrop()
            Toast.makeText(this, "Image cropped successfully", Toast.LENGTH_SHORT).show()
        }

        buttonCropCancel.setOnClickListener {
            drawingView.cancelCrop()
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
                    val value = progress / 50f
                    editViewModel.updateSaturation(value)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        buttonAdjustApply.setOnClickListener {
            val currentState = editViewModel.adjustState.value
            val action = AdjustAction(currentState.brightness, currentState.contrast, currentState.saturation)
            editViewModel.pushAdjustAction(action)
            Toast.makeText(this, "Adjustments applied", Toast.LENGTH_SHORT).show()
            
            editViewModel.resetAdjustments()
            brightnessSlider.progress = 50
            contrastSlider.progress = 50
            saturationSlider.progress = 50
        }

        buttonAdjustCancel.setOnClickListener {
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
            
            val selectedColor = when (v.id) {
                R.id.color_black_container -> android.graphics.Color.BLACK
                R.id.color_white_container -> android.graphics.Color.WHITE
                R.id.color_red_container -> android.graphics.Color.RED
                R.id.color_green_container -> android.graphics.Color.GREEN
                R.id.color_blue_container -> android.graphics.Color.BLUE
                R.id.color_yellow_container -> android.graphics.Color.YELLOW
                R.id.color_orange_container -> android.graphics.Color.rgb(255, 165, 0)
                R.id.color_pink_container -> android.graphics.Color.rgb(255, 192, 203)
                else -> android.graphics.Color.RED
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

        updateDrawModeSelection(drawModePen)

        // --- REFACTORED: Connect View to ViewModel ---

        drawingView.onDrawingAction = { action ->
            editViewModel.pushDrawingAction(action)
        }

        drawingView.onCropAction = { action ->
            editViewModel.pushCropAction(action)
        }

        lifecycleScope.launch {
            editViewModel.drawingState.collect {
                drawingView.setDrawingState(it)
            }
        }

        lifecycleScope.launch {
            editViewModel.processedBitmap.collect { bitmap ->
                drawingView.setBitmap(bitmap)
            }
        }
        
        // Temporary adjustments are now handled differently.
        // The view model recomputes the whole stack.
        // For real-time feedback, we might need a different approach,
        // but for now, we apply adjustments atomically.
        lifecycleScope.launch {
            editViewModel.adjustState.collect { state ->
                // This is now for temporary visual feedback only if we wanted it.
                // The actual action is applied on button click.
            }
        }

        buttonUndo.setOnClickListener {
            editViewModel.undo()
        }

        buttonRedo.setOnClickListener {
            editViewModel.redo()
        }

        cropModeFreeform.isSelected = true
        currentCropMode = cropModeFreeform

        colorRedContainer.performClick()

        toolDraw.isSelected = true
        currentActiveTool = toolDraw
        drawOptionsLayout.visibility = View.VISIBLE
        
        handleIntent(intent)

        drawingView.doOnLayout { view ->
            if (currentImageInfo == null) {
                isSketchMode = true
                val width = view.width
                val height = view.height
                
                val whiteBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(whiteBitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                
                editViewModel.setSourceBitmap(whiteBitmap)
                
                currentImageInfo = ImageInfo(
                    uri = Uri.EMPTY,
                    origin = ImageOrigin.EDITED_INTERNAL,
                    canOverwrite = false,
                    originalMimeType = "image/png"
                )
                updateSavePanelUI()
            }
        }

        deleteRequestLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, getString(R.string.original_file_deleted), Toast.LENGTH_SHORT).show()
                pendingOverwriteUri?.let {
                    currentImageInfo?.uri?.let { oldUri ->
                        imageLoader.memoryCache?.remove(MemoryCache.Key(oldUri.toString()))
                    }
                    currentImageInfo = currentImageInfo?.copy(uri = it)
                    pendingOverwriteUri = null
                }
            } else {
                Toast.makeText(this, getString(R.string.original_file_was_not_deleted), Toast.LENGTH_SHORT).show()
                pendingOverwriteUri?.let {
                    currentImageInfo = currentImageInfo?.copy(uri = it)
                    pendingOverwriteUri = null
                }
            }
        }
    }

    private fun shareCurrentImage() {
        val imageInfo = currentImageInfo ?: run {
            Toast.makeText(this, getString(R.string.no_image_to_share), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmapToShare = editViewModel.processedBitmap.value

                if (bitmapToShare != null) {
                    val cacheDir = cacheDir
                    val fileName = "share_temp_${System.currentTimeMillis()}.${getExtensionFromMimeType(selectedSaveFormat)}"
                    val tempFile = File(cacheDir, fileName)

                    contentResolver.openOutputStream(Uri.fromFile(tempFile))?.use { outputStream ->
                        compressBitmapToStream(bitmapToShare, outputStream, selectedSaveFormat)
                    }

                    val shareUri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        tempFile
                    )

                    lifecycleScope.launch {
                        delay(300000) // 5 minutes
                        if (tempFile.exists()) {
                            tempFile.delete()
                        }
                    }

                    withContext(Dispatchers.Main) {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = selectedSaveFormat
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_image)))
                    }
                } else {
                    throw Exception("No image to share")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.share_failed, e.message), Toast.LENGTH_SHORT).show()
                }
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
                val uri = intent.clipData?.getItemAt(0)?.uri ?: intent.data
                uri?.let {
                    if (intent.clipData?.itemCount ?: 1 > 1) {
                        Toast.makeText(this, getString(R.string.multiple_images_not_supported), Toast.LENGTH_LONG).show()
                        return
                    }
                    loadImageFromUri(it, Intent.ACTION_EDIT == intent.action)
                }
            }
        }
    }

    private fun captureImageFromCamera() {
        try {
            val imageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return
            imageDir.mkdirs()
            val fileName = "camera_temp_${System.currentTimeMillis()}.jpg"
            val privateFile = File(imageDir, fileName)
            privateFile.createNewFile()
            
            val photoURI = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", privateFile)
            
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            
            currentCameraUri = photoURI
            cameraCaptureLauncher.launch(intent)
            
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.camera_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImageFromUri(uri: android.net.Uri, isEdit: Boolean) {
        isSketchMode = false
        if (isImageLoading) {
            Toast.makeText(this, getString(R.string.image_is_still_loading), Toast.LENGTH_SHORT).show()
            return
        }
        if (isImageLoadAttempted && lastImageLoadFailed) {
            Toast.makeText(this, getString(R.string.previous_load_failed), Toast.LENGTH_LONG).show()
            return
        }
        
        isImageLoading = true
        isImageLoadAttempted = true
        lastImageLoadFailed = false
        
        Toast.makeText(this, getString(R.string.loading_image), Toast.LENGTH_SHORT).show()
        
        val request = ImageRequest.Builder(this)
            .data(uri)
            .size(coil.size.Size.ORIGINAL)
            .target { drawable ->
                runOnUiThread {
                    try {
                        val bitmap = (drawable as android.graphics.drawable.BitmapDrawable).bitmap
                        editViewModel.setSourceBitmap(bitmap)
                        
                        val origin = determineImageOrigin(uri)
                        val canOverwrite = determineCanOverwrite(origin)
                        val originalMimeType = contentResolver.getType(uri) ?: "image/jpeg"
                        currentImageInfo = ImageInfo(uri, origin, canOverwrite, originalMimeType)
                        
                        Toast.makeText(this, getString(R.string.loaded_image_successfully, origin.name), Toast.LENGTH_SHORT).show()
                        updateSavePanelUI()
                        detectAndSetImageFormat(uri)
                        currentImageHasTransparency = bitmap.hasAlpha()
                        updateTransparencyWarning()
                        lastImageLoadFailed = false
                    } catch (e: Exception) {
                        handleImageLoadFailure(getString(R.string.error_displaying_image, e.message))
                    } finally {
                        isImageLoading = false
                    }
                }
            }
            .error(R.drawable.ic_launcher_background)
            .listener(onError = { _, result ->
                handleImageLoadFailure(result.throwable?.message ?: "Unknown error")
                isImageLoading = false
            })
            .build()
        
        imageLoader.enqueue(request)
    }
    
    private fun handleImageLoadFailure(errorMessage: String) {
        lastImageLoadFailed = true
        runOnUiThread {
            editViewModel.setSourceBitmap(null)
            Toast.makeText(this, getString(R.string.could_not_load_image, errorMessage), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanupCameraFile(uri: Uri) {
        try {
            uri.path?.let {
                if (it.contains("camera_temp_")) {
                    File(it).delete()
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun determineImageOrigin(uri: Uri): ImageOrigin {
        val isPersistedWritable = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return when {
            isPersistedWritable -> ImageOrigin.IMPORTED_WRITABLE
            uri.path?.contains("camera_temp_") == true -> ImageOrigin.CAMERA_CAPTURED
            uri.authority == "${packageName}.fileprovider" -> ImageOrigin.EDITED_INTERNAL
            else -> ImageOrigin.IMPORTED_READONLY
        }
    }
    
    private fun determineCanOverwrite(origin: ImageOrigin): Boolean {
        return origin == ImageOrigin.IMPORTED_WRITABLE
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
            Toast.makeText(this, getString(R.string.could_not_open_photo_picker, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun checkPermissionRevocation() {
        val wasOverwriteAvailable = currentImageInfo?.canOverwrite ?: false
        if (!hasImagePermission()) {
            currentImageInfo?.canOverwrite = false
        }
        if (wasOverwriteAvailable && currentImageInfo?.canOverwrite == false) {
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
        buttonSaveCopy.text = if (currentImageInfo?.origin == ImageOrigin.CAMERA_CAPTURED) {
            getString(R.string.save)
        } else {
            getString(R.string.save_copy)
        }
    }
    
    private fun updateSaveButtonsState() {
        val buttonOverwrite: Button = findViewById(R.id.button_overwrite)
        val warningIcon: ImageView = findViewById(R.id.warning_icon)
        val info = currentImageInfo
        val shouldShowOverwrite = info != null &&
                                  info.origin == ImageOrigin.IMPORTED_WRITABLE &&
                                  info.canOverwrite &&
                                  selectedSaveFormat == info.originalMimeType
        buttonOverwrite.visibility = if (shouldShowOverwrite) View.VISIBLE else View.GONE
        warningIcon.visibility = if (shouldShowOverwrite) View.VISIBLE else View.GONE
    }
    
    private fun saveImageAsCopy() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmapToSave = editViewModel.processedBitmap.value ?: throw Exception("No image to save")
                
                val picturesDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).path + "/EditSS"
                val originalDisplayName = currentImageInfo?.let { getDisplayNameFromUri(it.uri) } ?: "Image"
                val uniqueDisplayName = generateUniqueCopyName(originalDisplayName, picturesDirectory)

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, uniqueDisplayName)
                    put(MediaStore.Images.Media.MIME_TYPE, selectedSaveFormat)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/EditSS")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)?.use {
                    compressBitmapToStream(bitmapToSave, it, selectedSaveFormat)
                }
                
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.image_saved_to_editss_folder), Toast.LENGTH_SHORT).show()
                    savePanel.visibility = View.GONE
                    scrim.visibility = View.GONE
                    editViewModel.markActionsAsSaved()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun overwriteCurrentImage() {
        val info = currentImageInfo ?: return
        if (!info.canOverwrite || selectedSaveFormat != info.originalMimeType) {
            Toast.makeText(this, getString(R.string.cannot_overwrite_this_image), Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this, R.style.AlertDialog_EditSS)
            .setTitle(getString(R.string.overwrite_changes_title))
            .setMessage(getString(R.string.overwrite_changes_message))
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val bitmapToSave = editViewModel.processedBitmap.value ?: throw Exception("Could not get image to overwrite")
                        contentResolver.openOutputStream(info.uri, "wt")?.use {
                            compressBitmapToStream(bitmapToSave, it, selectedSaveFormat)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, getString(R.string.image_overwritten_successfully), Toast.LENGTH_SHORT).show()
                            savePanel.visibility = View.GONE
                            scrim.visibility = View.GONE
                            editViewModel.markActionsAsSaved()
                            imageLoader.memoryCache?.remove(MemoryCache.Key(info.uri.toString()))
                            imageLoader.diskCache?.remove(info.uri.toString())
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, getString(R.string.overwrite_failed, e.message), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun generateUniqueCopyName(originalDisplayName: String, directory: String): String {
        val newExtension = getExtensionFromMimeType(selectedSaveFormat)
        val nameWithoutExt = originalDisplayName.substringBeforeLast('.')
        val copyPattern = Pattern.compile("\\s-\\sCopy(\\s\\(\\d+\\))?$")
        val matcher = copyPattern.matcher(nameWithoutExt)
        val baseName = if (matcher.find()) nameWithoutExt.substring(0, matcher.start()) else nameWithoutExt

        var newName = "$baseName - Copy.$newExtension"
        if (!File(directory, newName).exists()) return newName

        var counter = 2
        while (true) {
            newName = "$baseName - Copy ($counter).$newExtension"
            if (!File(directory, newName).exists()) return newName
            counter++
        }
    }

    private fun generateUniqueCameraName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "IMG_${timestamp}.${getExtensionFromMimeType(selectedSaveFormat)}"
    }
    
    private fun updateTransparencyWarning() {
        transparencyWarningText.visibility = if (currentImageHasTransparency && selectedSaveFormat == "image/jpeg") {
            transparencyWarningText.text = getString(R.string.jpg_does_not_support_transparency)
            View.VISIBLE
        } else {
            View.GONE
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
        val mimeType = contentResolver.getType(uri)
        selectedSaveFormat = when {
            mimeType == "image/png" -> "image/png"
            mimeType == "image/webp" -> "image/webp"
            else -> "image/jpeg"
        }
        updateFormatSelectionUI()
    }
    
    private fun compressBitmapToStream(bitmap: Bitmap, outputStream: OutputStream, mimeType: String) {
        val format = when (mimeType) {
            "image/png" -> CompressFormat.PNG
            "image/webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) CompressFormat.WEBP_LOSSY else CompressFormat.WEBP
            else -> CompressFormat.JPEG
        }
        val quality = if (format == CompressFormat.PNG) 100 else 95
        bitmap.compress(format, quality, outputStream)
    }

    private fun getDisplayNameFromUri(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { 
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }
    
    private fun getRealPathFromUri(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }
}