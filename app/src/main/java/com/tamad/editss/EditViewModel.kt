package com.tamad.editss

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

class EditViewModel : ViewModel() {

    private val _actions = MutableStateFlow<List<EditAction>>(emptyList())
    val actions: StateFlow<List<EditAction>> = _actions.asStateFlow()

    private val _redoStack = MutableStateFlow<List<EditAction>>(emptyList())

    private val _drawingState = MutableStateFlow(DrawingState())
    val drawingState: StateFlow<DrawingState> = _drawingState.asStateFlow()

    private val _adjustState = MutableStateFlow(AdjustState())
    val adjustState: StateFlow<AdjustState> = _adjustState.asStateFlow()

    private var savedActionCount = 0

    fun pushAction(action: EditAction) {
        _actions.value = _actions.value + action
        _redoStack.value = emptyList()
    }

    fun undo() {
        if (_actions.value.isNotEmpty()) {
            val lastAction = _actions.value.last()
            _actions.value = _actions.value.dropLast(1)
            _redoStack.value = _redoStack.value + lastAction
        }
    }

    fun redo() {
        if (_redoStack.value.isNotEmpty()) {
            val lastAction = _redoStack.value.last()
            _redoStack.value = _redoStack.value.dropLast(1)
            _actions.value = _actions.value + lastAction
        }
    }

    fun canUndo(): Boolean = _actions.value.isNotEmpty()
    fun canRedo(): Boolean = _redoStack.value.isNotEmpty()

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


    fun clearAllActions() {
        _actions.value = emptyList()
        _redoStack.value = emptyList()
        savedActionCount = 0
    }

    fun markActionsAsSaved() {
        savedActionCount = _actions.value.size
    }

    fun hasUnsavedChanges(): Boolean {
        if (savedActionCount > _actions.value.size) {
            savedActionCount = -1
            return true
        }
        return _actions.value.size != savedActionCount
    }
}