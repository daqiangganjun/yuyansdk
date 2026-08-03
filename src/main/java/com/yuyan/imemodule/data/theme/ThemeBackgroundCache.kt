package com.yuyan.imemodule.data.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * 自定义主题背景图的解码缓存。
 *
 * 背景图按键盘尺寸存盘，1080p 下单张约 4.7MB。原实现每次取用都重新解码且不关闭
 * 输入流，而取用点包括主题切换、日夜切换以及主题列表的每次滚动绑定，极易造成 GC
 * 抖动。缓存解码结果即可消除该开销。
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
        val bitmap = file.inputStream().use { BitmapFactory.decodeStream(it) } ?: return null
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
