package com.tamad.editss

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class EditAction {
    data class Draw(val path: android.graphics.Path, val paint: android.graphics.Paint) : EditAction()
    data class Crop(val rect: android.graphics.RectF) : EditAction()
    data class Adjust(val brightness: Float, val contrast: Float, val saturation: Float) : EditAction()
}

// Shared drawing state for Draw, Circle, and Square tools only
data class DrawingState(
    val color: Int = android.graphics.Color.BLACK,
    val size: Float = 10f,
    val opacity: Int = 255
)

class EditViewModel : ViewModel() {

    private val _undoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val undoStack: StateFlow<List<EditAction>> = _undoStack

    private val _redoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val redoStack: StateFlow<List<EditAction>> = _redoStack

    // Shared drawing state for Draw/Circle/Square tools
    private val _drawingState = MutableStateFlow(DrawingState())
    val drawingState: StateFlow<DrawingState> = _drawingState.asStateFlow()

    fun pushAction(action: EditAction) {
        _undoStack.value = _undoStack.value + action
        _redoStack.value = emptyList()
    }

    fun undo() {
        if (_undoStack.value.isNotEmpty()) {
            val lastAction = _undoStack.value.last()
            _undoStack.value = _undoStack.value.dropLast(1)
            _redoStack.value = _redoStack.value + lastAction
            // TODO: Apply undo logic in the views
        }
    }

    fun redo() {
        if (_redoStack.value.isNotEmpty()) {
            val lastAction = _redoStack.value.last()
            _redoStack.value = _redoStack.value.dropLast(1)
            _undoStack.value = _undoStack.value + lastAction
            // TODO: Apply redo logic in the views
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
}