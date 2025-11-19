package com.tamad.editss

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path

sealed interface EditAction

data class DrawAction(val path: Path, val paint: Paint) : EditAction
data class StateChangeAction(val bitmap: Bitmap) : EditAction
