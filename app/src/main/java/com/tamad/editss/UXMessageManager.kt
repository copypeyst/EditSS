package com.tamad.editss

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import kotlinx.coroutines.*

class UXMessageManager(
    private val messageContainer: FrameLayout,
    private val messageBox: LinearLayout,
    private val messageText: TextView,
    private val loadingOverlay: FrameLayout,
    private val loadingSpinner: ProgressBar
) {

    private var messageJob: Job? = null

    fun showMessage(message: String, durationMs: Long = 2000) {
        messageJob?.cancel()
        messageText.text = message
        messageBox.visibility = View.VISIBLE

        messageJob = GlobalScope.launch {
            delay(durationMs)
            withContext(Dispatchers.Main) {
                messageBox.visibility = View.GONE
            }
        }
    }

    fun showLoading() {
        loadingOverlay.visibility = View.VISIBLE
        loadingSpinner.visibility = View.VISIBLE
    }

    fun hideLoading() {
        loadingOverlay.visibility = View.GONE
        loadingSpinner.visibility = View.GONE
    }

    fun hideMessage() {
        messageJob?.cancel()
        messageBox.visibility = View.GONE
    }
}
