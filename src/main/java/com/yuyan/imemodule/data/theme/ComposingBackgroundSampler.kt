package com.yuyan.imemodule.data.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import androidx.core.graphics.ColorUtils

object ComposingBackgroundSampler {
    fun sample(owner: View, textColor: Int, fallback: Int): Int {
        val drawable = owner.background ?: return readable(fallback, textColor)
        val width = owner.width - owner.paddingLeft - owner.paddingRight
        val height = owner.height - owner.paddingTop - owner.paddingBottom
        if (width <= 0 || height <= 0) return readable(fallback, textColor)
        val area = (32 * owner.resources.displayMetrics.density).coerceAtLeast(1f)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val previous = Rect(drawable.bounds)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(ColorUtils.setAlphaComponent(fallback, 255))
            canvas.scale(16f / minOf(width.toFloat(), area), 16f / minOf(height.toFloat(), area))
            canvas.translate(-owner.paddingLeft.toFloat(), -owner.paddingTop.toFloat())
            drawable.setBounds(0, 0, owner.width, owner.height)
            drawable.draw(canvas)
            var red = 0; var green = 0; var blue = 0
            for (y in 0 until 16) for (x in 0 until 16) {
                val color = bitmap.getPixel(x, y)
                red += Color.red(color); green += Color.green(color); blue += Color.blue(color)
            }
            return readable(Color.rgb(red / 256, green / 256, blue / 256), textColor)
        } finally { drawable.bounds = previous; bitmap.recycle() }
    }

    fun readable(background: Int, text: Int): Int {
        val opaque = ColorUtils.setAlphaComponent(background, 255)
        if (ColorUtils.calculateContrast(text, opaque) >= 4.5) return opaque
        val target = if (ColorUtils.calculateContrast(text, Color.BLACK) > ColorUtils.calculateContrast(text, Color.WHITE)) Color.BLACK else Color.WHITE
        for (step in 1..100) {
            val adjusted = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(opaque, target, step / 100f), 255)
            if (ColorUtils.calculateContrast(text, adjusted) >= 4.5) return adjusted
        }
        return target
    }
}
