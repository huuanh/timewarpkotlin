package com.timewarpscan.nativecamera.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Centered loading indicator with an optional message.
 *
 * Usage:
 *   <LoadingView android:visibility="gone" />
 *   loadingView.show("Loading...")
 *   loadingView.hide()
 */
class LoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val progressBar: ProgressBar
    private val messageText: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(32, 32, 32, 32)

        progressBar = ProgressBar(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        addView(progressBar)

        messageText = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.topMargin = 16
            }
            textSize = 14f
            setTextColor(0xB0FFFFFF.toInt())
            gravity = Gravity.CENTER
            visibility = GONE
        }
        addView(messageText)
    }

    fun show(message: String? = null) {
        visibility = VISIBLE
        if (message != null) {
            messageText.text = message
            messageText.visibility = VISIBLE
        } else {
            messageText.visibility = GONE
        }
    }

    fun hide() {
        visibility = GONE
    }
}
