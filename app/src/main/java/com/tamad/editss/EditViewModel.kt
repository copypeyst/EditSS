package com.tamad.editss

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

// Shared drawing state for Draw, Circle, and Square tools only
data class DrawingState(
    val color: Int = android.graphics.Color.RED,
    val size: Float = 25f, // Default to 25
    val opacity: Int = 255, // Default to 100% opacity (255 out of 255)
    val drawMode: DrawMode = DrawMode.PEN
)

data class AdjustState(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f
)

// Lightweight action representing a single drawn path
data class DrawingAction(
    val path: Path,
    val paint: Paint
)

// Action representing a destructive crop. It must store the state before the crop.
data class CropAction(
    val previousBitmap: Bitmap,
    val newBitmap: Bitmap
)

// Action representing a destructive adjustment. It must store the state before the adjustment.
data class AdjustAction(
    val previousBitmap: Bitmap,
    val newBitmap: Bitmap
)

// Unified action system for the undo/redo stack
sealed class EditAction {
    data class AddPath(val action: DrawingAction) : EditAction()
    data class ApplyAdjustments(val action: AdjustAction) : EditAction()
    data class ApplyCrop(val action: CropAction) : EditAction()
}

// Represents the complete visual state of the canvas to be rendered
data class CanvasState(
    val baseBitmap: Bitmap?,
    val drawnPaths: List<DrawingAction> = emptyList()
)

class EditViewModel : ViewModel() {

    private var initialBitmap: Bitmap? = null
    private val _undoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val undoStack: StateFlow<List<EditAction>> = _undoStack.asStateFlow()

    private val _redoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val redoStack: StateFlow<List<EditAction>> = _redoStack.asStateFlow()

    private val _canvasState = MutableStateFlow(CanvasState(null))
    val canvasState: StateFlow<CanvasState> = _canvasState.asStateFlow()

    // Track the size of the undo stack at the point of the last save
    private val _lastSavedActionCount = MutableStateFlow(0)

    // Shared drawing state for Draw/Circle/Square tools
    private val _drawingState = MutableStateFlow(DrawingState())
    val drawingState: StateFlow<DrawingState> = _drawingState.asStateFlow()

    private val _adjustState = MutableStateFlow(AdjustState())
    val adjustState: StateFlow<AdjustState> = _adjustState.asStateFlow()

    fun setInitialBitmap(bitmap: Bitmap) {
        initialBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        clearAllActions()
    }

    private fun recalculateCanvasState() {
        var currentBitmap = initialBitmap
        val paths = mutableListOf<DrawingAction>()

        for (action in _undoStack.value) {
            when (action) {
                is EditAction.AddPath -> paths.add(action.action)
                is EditAction.ApplyAdjustments -> {
                    currentBitmap = action.action.newBitmap
                    paths.clear() // Destructive action clears previous paths
                }
                is EditAction.ApplyCrop -> {
                    currentBitmap = action.action.newBitmap
                    paths.clear() // Destructive action clears previous paths
                }
            }
        }
        _canvasState.value = CanvasState(currentBitmap, paths)
    }

    fun pushDrawingAction(action: DrawingAction) {
        _undoStack.value += EditAction.AddPath(action)
        _redoStack.value = emptyList()
        recalculateCanvasState()
    }

    fun pushCropAction(action: CropAction) {
        _undoStack.value += EditAction.ApplyCrop(action)
        _redoStack.value = emptyList()
        recalculateCanvasState()
    }

    fun pushAdjustAction(action: AdjustAction) {
        _undoStack.value += EditAction.ApplyAdjustments(action)
        _redoStack.value = emptyList()
        recalculateCanvasState()
    }

    fun undo() {
        if (_undoStack.value.isNotEmpty()) {
            val lastAction = _undoStack.value.last()
            _undoStack.value = _undoStack.value.dropLast(1)
            _redoStack.value = listOf(lastAction) + _redoStack.value
            recalculateCanvasState()
        }
    }

    fun redo() {
        if (_redoStack.value.isNotEmpty()) {
            val actionToRedo = _redoStack.value.first()
            _redoStack.value = _redoStack.value.drop(1)
            _undoStack.value = _undoStack.value + actionToRedo
            recalculateCanvasState()
        }
    }

    // Drawing state management for shared Draw/Circle/Square tools
    fun updateDrawingColor(color: Int) {
        _drawingState.value = _drawingState.value.copy(color = color)
    }

    fun updateDrawingSize(size: Float) {
        _drawingState.value = _drawingState.value.copy(size = size)
    }

    // Convert percentage (1-100) to alpha value (0-255) for Android Paint
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

    fun clearAllActions() {
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        _lastSavedActionCount.value = 0 // Reset saved count when actions are cleared
        recalculateCanvasState()
    }

    // New function to mark current actions as saved
    fun markActionsAsSaved() {
        _lastSavedActionCount.value = _undoStack.value.size
    }

    // hasUnsavedChanges now indicates if there are unsaved changes
    val hasUnsavedChanges: Boolean
        get() = _undoStack.value.size != _lastSavedActionCount.value
}
