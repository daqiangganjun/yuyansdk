package com.yuyan.imemodule.data.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * 自定义主题背景图的解码缓存。
 *
 * 背景图按键盘尺寸存盘，1080p 下单张 ARGB_8888 约 4.7MB。原实现每次取用都重新
 * 解码且不关闭输入流，而取用点包括主题切换、日夜切换以及主题列表的每次滚动绑定，
 * 极易造成 GC 抖动。键盘背景不需要透明通道，统一按 RGB_565 解码，内存再减半。
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
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = file.inputStream().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        cache.put(path, bitmap)
        return bitmap
    }

    /** 主题被删除或重新裁剪后调用，避免继续使用过期图像。 */
    fun invalidate(path: String) {
        cache.remove(path)
    }

    fun clear() {
        cache.evictAll()
    }
}
