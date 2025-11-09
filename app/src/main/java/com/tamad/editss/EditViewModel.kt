package com.tamad.editss

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.graphics.Path
import android.graphics.Paint

// Drawing modes enum
enum class DrawMode {
    PEN,
    SQUARE,
    CIRCLE
// Crop modes enum
enum class CropMode {
    FREEFORM,
    SQUARE,
    PORTRAIT,
    LANDSCAPE
}
}

// Shared drawing state for Draw, Circle, and Square tools only
data class DrawingState(
    val color: Int = android.graphics.Color.RED,
    val size: Float = 26f, // Default to position 25 on slider (matches (25 + 1))
    val opacity: Int = 252, // Default to position 100 on slider (matches ((100 - 1) * 2.55))
    val drawMode: DrawMode = DrawMode.PEN
)

data class DrawingAction(
    val path: Path,
    val paint: Paint
)

class EditViewModel : ViewModel() {

    private val _undoStack = MutableStateFlow<List<DrawingAction>>(emptyList())
    val undoStack: StateFlow<List<DrawingAction>> = _undoStack.asStateFlow()

    private val _redoStack = MutableStateFlow<List<DrawingAction>>(emptyList())
    val redoStack: StateFlow<List<DrawingAction>> = _redoStack.asStateFlow()

    // Track the size of the undo stack at the point of the last save
    private val _lastSavedDrawingCount = MutableStateFlow(0)

    // Shared drawing state for Draw/Circle/Square tools
    private val _drawingState = MutableStateFlow(DrawingState())
    val drawingState: StateFlow<DrawingState> = _drawingState.asStateFlow()

    fun pushAction(action: DrawingAction) {
        _undoStack.value = _undoStack.value + action
        _redoStack.value = emptyList()
    }

    fun undo() {
        if (_undoStack.value.isNotEmpty()) {
            val lastAction = _undoStack.value.last()
            _undoStack.value = _undoStack.value.dropLast(1)
            _redoStack.value = _redoStack.value + lastAction
        }
    }

    fun redo() {
        if (_redoStack.value.isNotEmpty()) {
            val lastAction = _redoStack.value.last()
            _redoStack.value = _redoStack.value.dropLast(1)
            _undoStack.value = _undoStack.value + lastAction
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
    }

    // New function to mark current drawings as saved
    fun markDrawingsAsSaved() {
        _lastSavedDrawingCount.value = _undoStack.value.size
    }

    // hasDrawings now indicates if there are unsaved changes
    val hasDrawings: Boolean
        get() = _undoStack.value.size > _lastSavedDrawingCount.value
}