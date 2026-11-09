package com.yuyan.imemodule.data.theme

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils

object ThemeControls {
    fun contrastingText(background: Int): Int =
        if (ColorUtils.calculateContrast(Color.WHITE, ColorUtils.setAlphaComponent(background, 255)) >= 4.5) Color.WHITE else Color.BLACK

    fun surface(theme: Theme, selected: Boolean): Int {
        return if (selected) theme.accentKeyBackgroundColor else theme.keyBackgroundColor
    }

    fun foreground(theme: Theme, selected: Boolean): Int = when {
        selected -> theme.accentKeyTextColor
        else -> theme.keyTextColor
    }

    fun background(theme: Theme, selected: Boolean, radius: Float, oval: Boolean = false) = GradientDrawable().apply {
        shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(surface(theme, selected))
    }
}
