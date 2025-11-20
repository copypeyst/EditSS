package com.tamad.editss

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Bitmap

// --- Restored Data Classes for Compatibility ---

enum class DrawMode {
    PEN,
    SQUARE,
    CIRCLE
}

enum class CropMode {
    FREEFORM,
    SQUARE,
    PORTRAIT,
    LANDSCAPE
}

data class DrawingState(
    val color: Int = android.graphics.Color.RED,
    val size: Float = 25f,
    val opacity: Int = 255,
    val drawMode: DrawMode = DrawMode.PEN
)

data class AdjustState(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f
)

// These classes are required by your Tools and MainActivity
data class DrawingAction(
    val path: Path,
    val paint: Paint,
    val previousBitmap: Bitmap? = null
)

data class CropAction(
    val previousBitmap: Bitmap,
    val cropRect: android.graphics.RectF,
    val cropMode: CropMode
)

data class AdjustAction(
    val previousBitmap: Bitmap,
    val newBitmap: Bitmap
)

sealed class EditAction {
    data class Drawing(val action: DrawingAction) : EditAction()
    data class Crop(val action: CropAction) : EditAction()
    data class Adjust(val action: AdjustAction) : EditAction()
    data class BitmapChange(
        val previousBitmap: Bitmap,
        val newBitmap: Bitmap,
        val associatedStroke: DrawingAction? = null,
        val cropAction: CropAction? = null
    ) : EditAction()
}

// --- ViewModel ---

class EditViewModel : ViewModel() {

    // Kept for compatibility with MainActivity, but disconnected from logic to prevent bugs
    private val _undoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val undoStack: StateFlow<List<EditAction>> = _undoStack.asStateFlow()

    private val _redoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val redoStack: StateFlow<List<EditAction>> = _redoStack.asStateFlow()

    private val _drawingState = MutableStateFlow(DrawingState())
    val drawingState: StateFlow<DrawingState> = _drawingState.asStateFlow()

    private val _adjustState = MutableStateFlow(AdjustState())
    val adjustState: StateFlow<AdjustState> = _adjustState.asStateFlow()

    // These are left empty or dummy to satisfy MainActivity compilation.
    // The actual History logic is now exclusively in CanvasView to ensure efficiency.
    fun pushDrawingAction(action: DrawingAction) { /* Handled by CanvasView */ }
    fun pushCropAction(action: CropAction) { /* Handled by CanvasView */ }
    fun pushAdjustAction(action: AdjustAction) { /* Handled by CanvasView */ }
    fun pushBitmapChangeAction(action: EditAction.BitmapChange) { /* Handled by CanvasView */ }

    fun undo() { 
        // NOTE: MainActivity calls this. You should change MainActivity to call canvasView.undo()
    }
    
    fun redo() {
        // NOTE: MainActivity calls this. You should change MainActivity to call canvasView.redo()
    }
    
    fun clearAllActions() {
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
    }

    fun updateDrawingColor(color: Int) {
        _drawingState.value = _drawingState.value.copy(color = color)
    }

    fun updateDrawingSize(size: Float) {
        _drawingState.value = _drawingState.value.copy(size = size)
    }

    private fun percentageToAlpha(percentage: Int): Int {
        return ((percentage / 100f) * 255).toInt().coerceIn(0, 255)
    }

    fun updateDrawingOpacity(opacityPercentage: Int) {
        val alphaValue = percentageToAlpha(opacityPercentage)
        _drawingState.value = _drawingState.value.copy(opacity = alphaValue)
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
}