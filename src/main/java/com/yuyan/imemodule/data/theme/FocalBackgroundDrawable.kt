package com.yuyan.imemodule.data.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.max

/** 原图上的焦点保持在视口中心，边缘处限制平移；尺寸变化只改变取景范围。 */
class FocalBackgroundDrawable(
    private val bitmap: Bitmap,
    private val focusX: Float = 0.5f,
    private val focusY: Float = 0.5f,
    private val rotation: Int = 0,
    private val flipHorizontal: Boolean = false,
    private val flipVertical: Boolean = false,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    fun imageMatrix(destination: RectF): Matrix {
        val transform = Matrix().apply {
            setRotate(rotation.toFloat())
            postScale(if (flipHorizontal) -1f else 1f, if (flipVertical) -1f else 1f)
        }
        val extent = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        transform.mapRect(extent)
        transform.postTranslate(-extent.left, -extent.top)
        val focus = floatArrayOf(focusX.coerceIn(0f, 1f) * bitmap.width, focusY.coerceIn(0f, 1f) * bitmap.height)
        transform.mapPoints(focus)
        val scale = max(destination.width() / extent.width(), destination.height() / extent.height())
        val left = (destination.centerX() - focus[0] * scale).coerceIn(minOf(destination.left, destination.right - extent.width() * scale), destination.left)
        val top = (destination.centerY() - focus[1] * scale).coerceIn(minOf(destination.top, destination.bottom - extent.height() * scale), destination.top)
        transform.postScale(scale, scale)
        transform.postTranslate(left, top)
        return transform
    }

    override fun draw(canvas: Canvas) {
        if (bitmap.isRecycled || bounds.isEmpty) return
        val area = RectF(bounds)
        (callback as? View)?.takeIf { it.background === this }?.let { owner ->
            area.left += owner.paddingLeft
            area.top += owner.paddingTop
            area.right -= owner.paddingRight
            area.bottom -= owner.paddingBottom
        }
        if (area.isEmpty) return
        matrix.set(imageMatrix(area))
        val saved = canvas.save()
        canvas.clipRect(area)
        canvas.drawBitmap(bitmap, matrix, paint)
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    override fun getColorFilter(): ColorFilter? = paint.colorFilter
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
