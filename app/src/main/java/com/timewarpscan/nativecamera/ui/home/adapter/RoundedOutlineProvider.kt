package com.timewarpscan.nativecamera.ui.home.adapter

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

class RoundedOutlineProvider(private val radiusDp: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        val radius = radiusDp * view.resources.displayMetrics.density
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}
