package com.tamad.editss

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF

sealed class EditAction {
    data class Draw(
        val path: Path,
        val paint: Paint,
        val mode: DrawMode
    ) : EditAction()
    data class Crop(
        val rect: RectF,
        val mode: CropMode
    ) : EditAction()
    data class Adjust(
        val brightness: Float,
        val contrast: Float,
        val saturation: Float
    ) : EditAction()
}

class EditViewModel : ViewModel() {

    private val _undoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val undoStack: StateFlow<List<EditAction>> = _undoStack

    private val _redoStack = MutableStateFlow<List<EditAction>>(emptyList())
    val redoStack: StateFlow<List<EditAction>> = _redoStack

    fun pushAction(action: EditAction) {
        _undoStack.value = _undoStack.value + action
        _redoStack.value = emptyList()
    }

    fun canUndo(): Boolean = _undoStack.value.isNotEmpty()
    fun canRedo(): Boolean = _redoStack.value.isNotEmpty()

    fun undo(): EditAction? {
        if (_undoStack.value.isNotEmpty()) {
            val lastAction = _undoStack.value.last()
            _undoStack.value = _undoStack.value.dropLast(1)
            _redoStack.value = _redoStack.value + lastAction
            return lastAction
        }
        return null
    }

    fun redo(): EditAction? {
        if (_redoStack.value.isNotEmpty()) {
            val lastAction = _redoStack.value.last()
            _redoStack.value = _redoStack.value.dropLast(1)
            _undoStack.value = _undoStack.value + lastAction
            return lastAction
        }
        return null
    }

    fun clearHistory() {
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
    }
}