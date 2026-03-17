package com.timewarpscan.nativecamera.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * Error view with icon, message, and retry button.
 *
 * Usage:
 *   errorView.show("Something went wrong") { retryAction() }
 *   errorView.hide()
 */
class ErrorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView: ImageView
    private val messageText: TextView
    private val retryButton: MaterialButton

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(48, 48, 48, 48)
        visibility = GONE

        iconView = ImageView(context).apply {
            layoutParams = LayoutParams(64.dp, 64.dp)
            setImageResource(android.R.drawable.ic_dialog_alert)
            setColorFilter(0xFFFF6B6B.toInt())
        }
        addView(iconView)

        messageText = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.topMargin = 16
            }
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        addView(messageText)

        retryButton = MaterialButton(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.topMargin = 20
            }
            text = "Retry"
        }
        addView(retryButton)
    }

    fun show(message: String, onRetry: (() -> Unit)? = null) {
        visibility = VISIBLE
        messageText.text = message
        if (onRetry != null) {
            retryButton.visibility = VISIBLE
            retryButton.setOnClickListener { onRetry() }
        } else {
            retryButton.visibility = GONE
        }
    }

    fun hide() {
        visibility = GONE
    }

    /** dp → px helper */
    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()
}
