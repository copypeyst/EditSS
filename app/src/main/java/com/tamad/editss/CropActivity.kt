package com.tamad.editss

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import coil.load
import coil.request.ImageRequest
import coil.size.Scale
import java.io.File
import java.io.FileOutputStream

class CropActivity : AppCompatActivity() {

    companion object {
        const val RESULT_CROP = 1001
        const val EXTRA_CROPPED_URI = "CROPPED_URI"
    }

    private lateinit var imageView: ImageView
    private lateinit var cropButton: Button
    private lateinit var cancelButton: Button
    
    private var cropRect = RectF()
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var aspectRatio = 0f // 0f means free aspect ratio
    
    private var imageWidth = 0
    private var imageHeight = 0
    private var imageMatrix = Matrix()
    private var sourceUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        sourceUri = intent.getParcelableExtra("IMAGE_URI")
        aspectRatio = intent.getFloatExtra("ASPECT_RATIO", 0f)

        imageView = findViewById(R.id.crop_image)
        cropButton = findViewById(R.id.crop_button)
        cancelButton = findViewById(R.id.cancel_button)

        // Load the image
        loadImage()

        cropButton.setOnClickListener {
            applyCrop()
        }

        cancelButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        imageView.setOnTouchListener { view, event ->
            handleTouch(event)
            true
        }
    }

    private fun loadImage() {
        sourceUri?.let { uri ->
            val request = ImageRequest.Builder(this)
                .data(uri)
                .target { drawable ->
                    imageView.setImageDrawable(drawable)
                    imageWidth = drawable.intrinsicWidth
                    imageHeight = drawable.intrinsicHeight
                    setupInitialCropRect()
                }
                .build()
            
            imageView.load(request)
        }
    }

    private fun setupInitialCropRect() {
        val viewWidth = imageView.width
        val viewHeight = imageView.height
        
        // Calculate initial crop area (centered, 80% of image size)
        val cropWidth = viewWidth * 0.8f
        val cropHeight = viewHeight * 0.8f
        
        cropRect.left = (viewWidth - cropWidth) / 2f
        cropRect.top = (viewHeight - cropHeight) / 2f
        cropRect.right = cropRect.left + cropWidth
        cropRect.bottom = cropRect.top + cropHeight
        
        // Apply aspect ratio constraint if specified
        if (aspectRatio > 0f) {
            applyAspectRatioConstraint()
        }
        
        imageView.invalidate()
    }

    private fun applyAspectRatioConstraint() {
        val rectWidth = cropRect.width()
        val rectHeight = cropRect.height()
        
        if (aspectRatio > 1f) { // Landscape
            val newHeight = rectWidth / aspectRatio
            cropRect.top = (imageView.height - newHeight) / 2f
            cropRect.bottom = cropRect.top + newHeight
        } else { // Portrait or Square
            val newWidth = rectHeight * aspectRatio
            cropRect.left = (imageView.width - newWidth) / 2f
            cropRect.right = cropRect.left + newWidth
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                dragStartX = event.x
                dragStartY = event.y
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val deltaX = event.x - dragStartX
                    val deltaY = event.y - dragStartY
                    
                    // Move crop rectangle
                    cropRect.offset(deltaX, deltaY)
                    
                    // Keep crop rectangle within bounds
                    cropRect.left = cropRect.left.coerceIn(0f, imageView.width.toFloat())
                    cropRect.right = cropRect.right.coerceIn(0f, imageView.width.toFloat())
                    cropRect.top = cropRect.top.coerceIn(0f, imageView.height.toFloat())
                    cropRect.bottom = cropRect.bottom.coerceIn(0f, imageView.height.toFloat())
                    
                    // Ensure minimum crop size
                    if (cropRect.width() < 50f) {
                        val centerX = cropRect.centerX()
                        cropRect.left = centerX - 25f
                        cropRect.right = centerX + 25f
                    }
                    if (cropRect.height() < 50f) {
                        val centerY = cropRect.centerY()
                        cropRect.top = centerY - 25f
                        cropRect.bottom = centerY + 25f
                    }
                    
                    // Apply aspect ratio constraint
                    if (aspectRatio > 0f) {
                        applyAspectRatioConstraint()
                    }
                    
                    dragStartX = event.x
                    dragStartY = event.y
                    imageView.invalidate()
                }
                return true
            }
            
            MotionEvent.ACTION_UP -> {
                isDragging = false
                return true
            }
        }
        return false
    }

    private fun applyCrop() {
        sourceUri?.let { uri ->
            try {
                // Get the bitmap from the source URI
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, options)
                
                if (bitmap != null) {
                    // Calculate the crop coordinates relative to the original image
                    val matrix = Matrix()
                    imageView.imageMatrix?.let { imageMatrix ->
                        matrix.set(imageMatrix)
                    }
                    
                    // Invert the matrix to get coordinates from view space to image space
                    val invertedMatrix = Matrix()
                    matrix.invert(invertedMatrix)
                    
                    val cropPoints = floatArrayOf(
                        cropRect.left, cropRect.top,
                        cropRect.right, cropRect.top,
                        cropRect.right, cropRect.bottom,
                        cropRect.left, cropRect.bottom
                    )
                    
                    invertedMatrix.mapPoints(cropPoints)
                    
                    // Create cropped bitmap
                    val croppedBitmap = Bitmap.createBitmap(
                        bitmap,
                        cropPoints[0].toInt(),
                        cropPoints[1].toInt(),
                        (cropPoints[2] - cropPoints[0]).toInt(),
                        (cropPoints[5] - cropPoints[1]).toInt()
                    )
                    
                    // Save cropped bitmap to cache
                    val croppedFile = File(cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(croppedFile).use { out ->
                        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    
                    // Return the cropped URI
                    val croppedUri = Uri.fromFile(croppedFile)
                    
                    val resultIntent = Intent().apply {
                        putExtra(EXTRA_CROPPED_URI, croppedUri)
                    }
                    
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to crop image: ${e.message}", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }
}