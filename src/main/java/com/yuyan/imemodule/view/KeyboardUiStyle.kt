package com.yuyan.imemodule.view

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils
import com.yuyan.imemodule.data.theme.Theme

object KeyboardUiStyle {
    fun surface(theme: Theme): Int = ColorUtils.compositeColors(
        theme.popupBackgroundColor,
        ColorUtils.compositeColors(theme.keyboardColor, if (theme.isDark) Color.BLACK else Color.WHITE)
    )

    fun secondaryText(theme: Theme): Int = ColorUtils.blendARGB(theme.keyTextColor, surface(theme), 0.28f)

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    fun buttonBackground(theme: Theme, radius: Float, normal: Int = theme.keyBackgroundColor): StateListDrawable {
        val selected = theme.accentKeyBackgroundColor
        val pressed = theme.functionKeyPressHighlightColor
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected, android.R.attr.state_pressed), rounded(ColorUtils.compositeColors(pressed, selected), radius))
            addState(intArrayOf(android.R.attr.state_selected), rounded(selected, radius))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(ColorUtils.compositeColors(pressed, normal), radius))
            addState(intArrayOf(android.R.attr.state_focused), rounded(ColorUtils.compositeColors(pressed, normal), radius))
            addState(intArrayOf(), rounded(normal, radius))
        }
    }

    fun buttonText(theme: Theme) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(theme.accentKeyTextColor, theme.keyTextColor)
    )
}
