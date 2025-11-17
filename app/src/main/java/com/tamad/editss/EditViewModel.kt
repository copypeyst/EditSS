package com.tamad.editss

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Bitmap

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
    val size: Float = 26f, // Default to position 25 on slider (matches (25 + 1))
    val opacity: Int = 255, // Default to 100% opacity (255 out of 255)
    val drawMode: DrawMode = DrawMode.PEN
)

data class AdjustState(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f
)

data class DrawingAction(
    val path: Path,
    val paint: Paint,
    val previousBitmap: Bitmap? = null // For proper undo/redo of bitmap-based drawing
)
data class CropAction(
    val previousBitmap: Bitmap, // The bitmap state before the crop
    val cropRect: android.graphics.RectF, // The crop rectangle that was applied
    val cropMode: CropMode // The crop mode used
    // previousDrawingActions removed - drawings are now immediately merged into bitmap
)

data class AdjustAction(
    val previousBitmap: Bitmap,
    val newBitmap: Bitmap
)

// Unified action system for both drawing and crop operations
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

class EditViewModel : ViewModel() {

    private val _undoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val undoStack: StateFlow<List<EditAction>> = _undoStack.asStateFlow()

    private val _redoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val redoStack: StateFlow<List<EditAction>> = _redoStack.asStateFlow()

    // Track the size of the undo stack at the point of the last save
    private val _lastSavedActionCount = MutableStateFlow(0)

    // Shared drawing state for Draw/Circle/Square tools
    private val _drawingState = MutableStateFlow(DrawingState())
    val drawingState: StateFlow<DrawingState> = _drawingState.asStateFlow()

    private val _adjustState = MutableStateFlow(AdjustState())
    val adjustState: StateFlow<AdjustState> = _adjustState.asStateFlow()

    fun pushDrawingAction(action: DrawingAction) {
        _undoStack.value = _undoStack.value + EditAction.Drawing(action)
        _redoStack.value = emptyList()
    }

    fun pushCropAction(action: CropAction) {
        _undoStack.value = _undoStack.value + EditAction.Crop(action)
        _redoStack.value = emptyList()
    }

    fun pushAdjustAction(action: AdjustAction) {
        _undoStack.value = _undoStack.value + EditAction.Adjust(action)
        _redoStack.value = emptyList()
    }

    fun pushBitmapChangeAction(action: EditAction.BitmapChange) {
        // When a bitmap changes (e.g., after a crop), we add this bitmap change to the undo stack.
        // Drawing actions are now managed by EditAction.Crop when a crop occurs.
        _undoStack.value = _undoStack.value + action
        _redoStack.value = emptyList()
    }

    fun undo() {
        if (_undoStack.value.isNotEmpty()) {
            val lastAction = _undoStack.value.last()
            _undoStack.value = _undoStack.value.dropLast(1)
            _redoStack.value = _redoStack.value + lastAction
            
            // Notify listeners about the undone action
            _lastUndoneAction.value = lastAction
        }
    }

    fun redo() {
        if (_redoStack.value.isNotEmpty()) {
            val lastAction = _redoStack.value.last()
            _redoStack.value = _redoStack.value.dropLast(1)
            _undoStack.value = _undoStack.value + lastAction
            
            // Notify listeners about the redone action
            _lastRedoneAction.value = lastAction
        }
    }
    
    // Flow to notify about undone actions
    private val _lastUndoneAction = MutableStateFlow<EditAction?>(null)
    val lastUndoneAction: StateFlow<EditAction?> = _lastUndoneAction.asStateFlow()
    
    // Flow to notify about redone actions
    private val _lastRedoneAction = MutableStateFlow<EditAction?>(null)
    val lastRedoneAction: StateFlow<EditAction?> = _lastRedoneAction.asStateFlow()
    
    fun clearLastUndoneAction() {
        _lastUndoneAction.value = null
    }
    
    fun clearLastRedoneAction() {
        _lastRedoneAction.value = null
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
    }

    // New function to mark current actions as saved
    fun markActionsAsSaved() {
        _lastSavedActionCount.value = _undoStack.value.size
    }

    // hasUnsavedChanges now indicates if there are unsaved changes
    val hasUnsavedChanges: Boolean
        get() = _undoStack.value.size > _lastSavedActionCount.value

    // Backward compatibility - still available for drawing-only operations
    fun clearDrawings() {
        clearAllActions()
    }

    // Backward compatibility - still available for drawing-only operations
    val hasDrawings: Boolean
        get() = hasUnsavedChanges

    // Backward compatibility - still available for drawing-only operations
    fun markDrawingsAsSaved() {
        markActionsAsSaved()
    }
}