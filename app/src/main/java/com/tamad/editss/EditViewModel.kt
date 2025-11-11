package com.tamad.editss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.graphics.*

// Drawing modes enum
enum class DrawMode {
    PEN,
    SQUARE,
    CIRCLE
}

// Crop modes enum
enum class CropMode {
    FREEFORM,
    SQUARE,
    PORTRAIT,
    LANDSCAPE
}

// --- DATA CLASSES FOR STATE AND ACTIONS ---

// Shared drawing state for Draw, Circle, and Square tools only
data class DrawingState(
    val color: Int = android.graphics.Color.RED,
    val size: Float = 26f,
    val opacity: Int = 252,
    val drawMode: DrawMode = DrawMode.PEN
)

data class AdjustState(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f
)

// --- ACTION DEFINITIONS ---
// Actions now store parameters, not bitmaps.

data class DrawingAction(
    val path: Path,
    val paint: Paint
)

data class CropAction(
    val cropRect: RectF, // The rectangle used for the crop
    val imageMatrix: Matrix // The matrix to map screen coords to image coords
)

data class AdjustAction(
    val brightness: Float,
    val contrast: Float,
    val saturation: Float
)

// Unified action system
sealed class EditAction {
    data class Drawing(val action: DrawingAction) : EditAction()
    data class Crop(val action: CropAction) : EditAction()
    data class Adjust(val action: AdjustAction) : EditAction()
}

class EditViewModel : ViewModel() {

    // --- STATEFLOWS ---

    private var _sourceBitmap: Bitmap? = null

    private val _processedBitmap = MutableStateFlow<Bitmap?>(null)
    val processedBitmap: StateFlow<Bitmap?> = _processedBitmap.asStateFlow()

    private val _undoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val undoStack: StateFlow<List<EditAction>> = _undoStack.asStateFlow()

    private val _redoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val redoStack: StateFlow<List<EditAction>> = _redoStack.asStateFlow()

    private val _lastSavedActionCount = MutableStateFlow(0)

    private val _drawingState = MutableStateFlow(DrawingState())
    val drawingState: StateFlow<DrawingState> = _drawingState.asStateFlow()

    private val _adjustState = MutableStateFlow(AdjustState())
    val adjustState: StateFlow<AdjustState> = _adjustState.asStateFlow()

    init {
        // Automatically recompute the bitmap whenever the undo stack changes.
        viewModelScope.launch {
            _undoStack.collect {
                recomputeBitmap()
            }
        }
    }

    fun setSourceBitmap(bitmap: Bitmap?) {
        _sourceBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        _processedBitmap.value = _sourceBitmap
        clearAllActions()
    }

    private fun recomputeBitmap() {
        viewModelScope.launch {
            val source = _sourceBitmap ?: return@launch
            var currentBitmap = source.copy(Bitmap.Config.ARGB_8888, true)

            for (editAction in _undoStack.value) {
                currentBitmap = when (editAction) {
                    is EditAction.Drawing -> {
                        val canvas = Canvas(currentBitmap)
                        canvas.drawPath(editAction.action.path, editAction.action.paint)
                        currentBitmap
                    }
                    is EditAction.Crop -> {
                        val inverseMatrix = Matrix()
                        editAction.action.imageMatrix.invert(inverseMatrix)
                        val imageCropRect = RectF()
                        inverseMatrix.mapRect(imageCropRect, editAction.action.cropRect)

                        val left = imageCropRect.left.coerceIn(0f, currentBitmap.width.toFloat())
                        val top = imageCropRect.top.coerceIn(0f, currentBitmap.height.toFloat())
                        val right = imageCropRect.right.coerceIn(0f, currentBitmap.width.toFloat())
                        val bottom = imageCropRect.bottom.coerceIn(0f, currentBitmap.height.toFloat())

                        if (right > left && bottom > top) {
                            Bitmap.createBitmap(
                                currentBitmap,
                                left.toInt(),
                                top.toInt(),
                                (right - left).toInt(),
                                (bottom - top).toInt()
                            )
                        } else {
                            currentBitmap
                        }
                    }
                    is EditAction.Adjust -> {
                        val adjustedBitmap = Bitmap.createBitmap(currentBitmap.width, currentBitmap.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(adjustedBitmap)
                        val paint = Paint()
                        val colorMatrix = ColorMatrix()
                        colorMatrix.set(floatArrayOf(
                            editAction.action.contrast, 0f, 0f, 0f, editAction.action.brightness,
                            0f, editAction.action.contrast, 0f, 0f, editAction.action.brightness,
                            0f, 0f, editAction.action.contrast, 0f, editAction.action.brightness,
                            0f, 0f, 0f, 1f, 0f
                        ))
                        val saturationMatrix = ColorMatrix()
                        saturationMatrix.setSaturation(editAction.action.saturation)
                        colorMatrix.postConcat(saturationMatrix)
                        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                        canvas.drawBitmap(currentBitmap, 0f, 0f, paint)
                        adjustedBitmap
                    }
                }
            }
            _processedBitmap.value = currentBitmap
        }
    }

    // --- ACTION MANAGEMENT ---

    fun pushDrawingAction(action: DrawingAction) {
        _undoStack.value += EditAction.Drawing(action)
        _redoStack.value = emptyList()
    }

    fun pushCropAction(action: CropAction) {
        _undoStack.value += EditAction.Crop(action)
        _redoStack.value = emptyList()
    }

    fun pushAdjustAction(action: AdjustAction) {
        _undoStack.value += EditAction.Adjust(action)
        _redoStack.value = emptyList()
    }

    fun undo() {
        if (_undoStack.value.isNotEmpty()) {
            val lastAction = _undoStack.value.last()
            _redoStack.value += lastAction
            _undoStack.value = _undoStack.value.dropLast(1)
        }
    }

    fun redo() {
        if (_redoStack.value.isNotEmpty()) {
            val lastAction = _redoStack.value.last()
            _undoStack.value += lastAction
            _redoStack.value = _redoStack.value.dropLast(1)
        }
    }

    fun clearAllActions() {
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        _lastSavedActionCount.value = 0
    }

    // --- UI STATE MANAGEMENT ---

    fun updateDrawingColor(color: Int) {
        _drawingState.value = _drawingState.value.copy(color = color)
    }

    fun updateDrawingSize(size: Float) {
        _drawingState.value = _drawingState.value.copy(size = size)
    }

    fun updateDrawingOpacity(opacity: Int) {
        _drawingState.value = _drawingState.value.copy(opacity = opacity)
    }

    fun updateDrawMode(drawMode: DrawMode) {
        _drawingState.value = _drawingState.value.copy(drawMode = drawMode)
    }

    fun updateBrightness(brightness: Float) {
        _adjustState.value = _adjustState.value.copy(brightness = brightness)
    }

    fun updateContrast(contrast: Float) {
        _adjustState.value = _adjustState.value.copy(contrast = contrast)
    }

    fun updateSaturation(saturation: Float) {
        _adjustState.value = _adjustState.value.copy(saturation = saturation)
    }

    fun resetAdjustments() {
        _adjustState.value = AdjustState()
    }

    // --- SAVE STATE ---

    fun markActionsAsSaved() {
        _lastSavedActionCount.value = _undoStack.value.size
    }

    val hasUnsavedChanges: Boolean
        get() = _undoStack.value.size != _lastSavedActionCount.value
}