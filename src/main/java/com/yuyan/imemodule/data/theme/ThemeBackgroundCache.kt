package com.yuyan.imemodule.data.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import androidx.exifinterface.media.ExifInterface

/**
 * 自定义主题背景图的解码缓存。
 *
 * 缓存原图的受限采样结果，避免主题切换与列表绑定时重复解码。单张最长边不超过
 * 4096 像素、总像素不超过 8M；焦点与裁切范围由实际视口确定。
 *
 * 保持 ARGB_8888 解码：裁剪结果以 PNG 无损保存（见 CustomThemeActivity），用户选用
 * 带透明通道的图片时 alpha 有效，降为 RGB_565 会让透明区域变黑，渐变也会出现色带。
 */
object ThemeBackgroundCache {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(path: String): Bitmap? {
        cache.get(path)?.let { if (!it.isRecycled) return it }
        val file = File(path)
        if (!file.exists()) return null
        val bounds = dimensions(path)
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (bounds.first / options.inSampleSize > 4096 || bounds.second / options.inSampleSize > 4096 ||
            bounds.first.toLong() * bounds.second / options.inSampleSize / options.inSampleSize > 8 * 1024 * 1024) options.inSampleSize *= 2
        val bitmap = file.inputStream().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        cache.put(path, bitmap)
        return bitmap
    }

    fun dimensions(path: String): Pair<Int, Int> = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, this)
    }.let { it.outWidth to it.outHeight }

    data class Orientation(val rotation: Int = 0, val flipHorizontal: Boolean = false, val flipVertical: Boolean = false)
    fun orientation(path: String): Orientation = runCatching {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Orientation(flipHorizontal = true)
            ExifInterface.ORIENTATION_ROTATE_180 -> Orientation(180)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Orientation(flipVertical = true)
            ExifInterface.ORIENTATION_TRANSPOSE -> Orientation(90, flipHorizontal = true)
            ExifInterface.ORIENTATION_ROTATE_90 -> Orientation(90)
            ExifInterface.ORIENTATION_TRANSVERSE -> Orientation(270, flipHorizontal = true)
            ExifInterface.ORIENTATION_ROTATE_270 -> Orientation(270)
            else -> Orientation()
        }
    }.getOrDefault(Orientation())

    /** 主题被删除或重新裁剪后调用，避免继续使用过期图像。 */
    fun invalidate(path: String) {
        cache.remove(path)
    }

    fun clear() {
        cache.evictAll()
    }
}
