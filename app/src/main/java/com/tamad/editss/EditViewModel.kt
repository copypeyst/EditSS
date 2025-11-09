package com.tamad.editss

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Bitmap // Import Bitmap

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
    val opacity: Int = 252, // Default to position 100 on slider (matches ((100 - 1) * 2.55))
    val drawMode: DrawMode = DrawMode.PEN
)

// Sealed class to represent any action that can be undone/redone
sealed class Action {
    data class Drawing(val path: Path, val paint: Paint) : Action()
    data class Crop(val previousBitmap: Bitmap, val croppedBitmap: Bitmap) : Action()
}

class EditViewModel : ViewModel() {

    private val _undoStack = MutableStateFlow<List<Action>>(emptyList())
    val undoStack: StateFlow<List<Action>> = _undoStack.asStateFlow()

    private val _redoStack = MutableStateFlow<List<Action>>(emptyList())
    val redoStack: StateFlow<List<Action>> = _redoStack.asStateFlow()

    // Track the size of the undo stack at the point of the last save
    private val _lastSavedDrawingCount = MutableStateFlow(0)

    // Shared drawing state for Draw/Circle/Square tools
    private val _drawingState = MutableStateFlow(DrawingState())
    val drawingState: StateFlow<DrawingState> = _drawingState.asStateFlow()

    // New StateFlow to communicate bitmap changes to CanvasView for crop undo/redo
    private val _bitmapForCanvas = MutableStateFlow<Bitmap?>(null)
    val bitmapForCanvas: StateFlow<Bitmap?> = _bitmapForCanvas.asStateFlow()

    fun pushAction(action: Action) { // Changed parameter type to Action
        _undoStack.value = _undoStack.value + action
        _redoStack.value = emptyList()
    }

    fun undo() {
        if (_undoStack.value.isNotEmpty()) {
            val lastAction = _undoStack.value.last()
            _undoStack.value = _undoStack.value.dropLast(1)
            _redoStack.value = _redoStack.value + lastAction

            when (lastAction) {
                is Action.Drawing -> {
                    // No direct bitmap change for drawing undo, CanvasView will re-draw paths
                }
                is Action.Crop -> {
                    _bitmapForCanvas.value = lastAction.previousBitmap // Restore previous bitmap
                }
            }
        }
    }

    fun redo() {
        if (_redoStack.value.isNotEmpty()) {
            val lastAction = _redoStack.value.last()
            _redoStack.value = _redoStack.value.dropLast(1)
            _undoStack.value = _undoStack.value + lastAction

            when (lastAction) {
                is Action.Drawing -> {
                    // No direct bitmap change for drawing redo, CanvasView will re-draw paths
                }
                is Action.Crop -> {
                    _bitmapForCanvas.value = lastAction.croppedBitmap // Re-apply cropped bitmap
                }
            }
        }
    }

    // Drawing state management for shared Draw/Circle/Square tools
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

    fun clearDrawings() {
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        _lastSavedDrawingCount.value = 0 // Reset saved count when drawings are cleared
        _bitmapForCanvas.value = null // Clear any pending bitmap changes
    }

    // New function to mark current drawings as saved
    fun markDrawingsAsSaved() {
        _lastSavedDrawingCount.value = _undoStack.value.size
    }

    // hasDrawings now indicates if there are unsaved changes
    val hasDrawings: Boolean
        get() = _undoStack.value.size > _lastSavedDrawingCount.value
}