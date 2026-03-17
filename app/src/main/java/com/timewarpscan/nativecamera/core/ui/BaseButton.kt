package com.timewarpscan.nativecamera.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ProgressBar
import com.google.android.material.button.MaterialButton
import android.widget.FrameLayout

/**
 * Material button with a built-in loading state.
 *
 * Call [setLoading(true)] to show a spinner and disable interaction,
 * [setLoading(false)] to restore the original text and re-enable.
 */
class BaseButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val button: MaterialButton
    private val progress: ProgressBar
    private var originalText: CharSequence = ""

    init {
        button = MaterialButton(context, attrs, defStyleAttr).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        progress = ProgressBar(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.gravity = android.view.Gravity.CENTER
            }
            visibility = View.GONE
        }
        addView(button)
        addView(progress)
    }

    var text: CharSequence
        get() = button.text
        set(value) {
            originalText = value
            button.text = value
        }

    fun setLoading(loading: Boolean) {
        if (loading) {
            originalText = button.text
            button.text = ""
            button.isEnabled = false
            progress.visibility = View.VISIBLE
        } else {
            button.text = originalText
            button.isEnabled = true
            progress.visibility = View.GONE
        }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        button.setOnClickListener(l)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        button.isEnabled = enabled
    }
}
