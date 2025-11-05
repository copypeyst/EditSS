package com.tamad.editss

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

data class AdjustSettings(
    var brightness: Float = 0f,     // -100 to +100
    var contrast: Float = 0f,       // -100 to +100  
    var saturation: Float = 0f      // -100 to +100
)

class AdjustView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    private val paint = Paint()
    private val originalBitmap = Paint()
    private val adjustBitmap = Paint()
    private var adjustedBitmap: Bitmap? = null
    private var currentSettings = AdjustSettings()
    private var isAdjusting = false
    private var editViewModel: EditViewModel? = null
    private var lifecycleScope: CoroutineScope? = null
    
    fun setupAdjustState(viewModel: EditViewModel) {
        this.editViewModel = viewModel
        // The lifecycle scope will be set when the view is attached
        if (lifecycleScope != null) {
            observeAdjustState()
        }
    }
    
    private fun observeAdjustState() {
        editViewModel?.let { viewModel ->
            lifecycleScope?.launch {
                viewModel.adjustState.collect { adjustState ->
                    updateBrightness(adjustState.brightness)
                    updateContrast(adjustState.contrast)
                    updateSaturation(adjustState.saturation)
                }
            }
        }
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Get lifecycle scope from activity
        val activity = context as? androidx.appcompat.app.AppCompatActivity
        lifecycleScope = activity?.lifecycleScope
        observeAdjustState()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lifecycleScope = null
    }
    
    private fun applyAdjustments() {
        val bitmap = adjustedBitmap ?: return

        // Create a new bitmap for adjustments to preserve original
        val adjusted = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(adjusted)
        val imagePaint = Paint()

        // Apply ColorFilter for combined adjustments
        imagePaint.colorFilter = createColorFilter(
            currentSettings.brightness,
            currentSettings.contrast,
            currentSettings.saturation
        )

        // Apply adjustments
        canvas.drawBitmap(bitmap, 0f, 0f, imagePaint)

        adjustedBitmap = adjusted
    }
    
    private fun createColorFilter(brightness: Float, contrast: Float, saturation: Float): ColorFilter {
        return when {
            brightness != 0f || contrast != 0f -> {
                // Use ColorMatrix for brightness and contrast
                val matrix = ColorMatrix()
                
                // Brightness adjustment - use postTranslate instead of set
                val brightnessScale = brightness / 100f
                if (brightnessScale != 0f) {
                    val brightnessMatrix = ColorMatrix()
                    brightnessMatrix.postTranslate(
                        255f * brightnessScale,
                        255f * brightnessScale,
                        255f * brightnessScale,
                        0f
                    )
                    matrix.postConcat(brightnessMatrix)
                }
                
                // Contrast adjustment - use postConcat with scale matrix
                val contrastScale = (contrast / 100f) + 1f
                if (contrastScale != 1f) {
                    val contrastMatrix = ColorMatrix()
                    contrastMatrix.setScale(contrastScale, contrastScale, contrastScale, 1f)
                    matrix.postConcat(contrastMatrix)
                }
                
                ColorMatrixColorFilter(matrix)
            }
            saturation != 0f -> {
                // Use PorterDuffColorFilter for saturation
                val colorMatrix = ColorMatrix()
                colorMatrix.setSaturation(saturation / 100f + 1f)
                ColorMatrixColorFilter(colorMatrix)
            }
            else -> null
        } ?: NoOpColorFilter()
    }

    init {
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        adjustBitmap.isAntiAlias = true
        adjustBitmap.isFilterBitmap = true
    }

    fun setOriginalImage(bitmap: Bitmap) {
        // Store original for non-destructive editing
        adjustedBitmap = bitmap.copy(bitmap.config, true)
        applyAdjustments()
        invalidate()
    }

    fun updateBrightness(value: Float) {
        currentSettings.brightness = value
        applyAdjustments()
        invalidate()
    }

    fun updateContrast(value: Float) {
        currentSettings.contrast = value
        applyAdjustments()
        invalidate()
    }

    fun updateSaturation(value: Float) {
        currentSettings.saturation = value
        applyAdjustments()
        invalidate()
    }

    fun resetAdjustments() {
        currentSettings = AdjustSettings()
        adjustedBitmap?.let { 
            applyAdjustments()
            invalidate()
        }
    }

    private fun applyAdjustments() {
        val bitmap = adjustedBitmap ?: return

        // Create a new bitmap for adjustments to preserve original
        val adjusted = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(adjusted)
        val imagePaint = Paint()

        // Apply ColorFilter for combined adjustments
        imagePaint.colorFilter = createColorFilter(
            currentSettings.brightness,
            currentSettings.contrast,
            currentSettings.saturation
        )

        // Apply adjustments
        canvas.drawBitmap(bitmap, 0f, 0f, imagePaint)

        adjustedBitmap = adjusted
    }
    
    private fun createColorFilter(brightness: Float, contrast: Float, saturation: Float): ColorFilter {
        return when {
            brightness != 0f || contrast != 0f -> {
                // Use ColorMatrix for brightness and contrast
                val matrix = ColorMatrix()
                
                // Brightness adjustment - use postTranslate instead of set
                val brightnessScale = brightness / 100f
                if (brightnessScale != 0f) {
                    val brightnessMatrix = ColorMatrix()
                    brightnessMatrix.postTranslate(
                        255f * brightnessScale,
                        255f * brightnessScale,
                        255f * brightnessScale,
                        0f
                    )
                    matrix.postConcat(brightnessMatrix)
                }
                
                // Contrast adjustment - use postConcat with scale matrix
                val contrastScale = (contrast / 100f) + 1f
                if (contrastScale != 1f) {
                    val contrastMatrix = ColorMatrix()
                    contrastMatrix.setScale(contrastScale, contrastScale, contrastScale, 1f)
                    matrix.postConcat(contrastMatrix)
                }
                
                ColorMatrixColorFilter(matrix)
            }
            saturation != 0f -> {
                // Use PorterDuffColorFilter for saturation
                val colorMatrix = ColorMatrix()
                colorMatrix.setSaturation(saturation / 100f + 1f)
                ColorMatrixColorFilter(colorMatrix)
            }
            else -> null
        } ?: NoOpColorFilter()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        adjustedBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        }
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        if (bm != null) {
            setOriginalImage(bm)
        }
    }

    override fun setImageURI(uri: android.net.Uri?) {
        super.setImageURI(uri)
        // This will be handled when the bitmap is set
    }

    // Helper class for no-op color filter
    private class NoOpColorFilter : ColorFilter()
}