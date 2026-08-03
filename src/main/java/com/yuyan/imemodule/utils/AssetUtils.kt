package com.yuyan.imemodule.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object AssetUtils {
    @JvmStatic
    fun copyFileOrDir(context: Context, parent: String, path: String, destParent: String, overwrite: Boolean) {
        val assetManager = context.assets
        try {
            val assetPath = File(parent, path).path
            val assets = assetManager.list(assetPath)
            if (assets.isNullOrEmpty()) {
                // Files
                copyFile(context, parent, path, destParent, overwrite)
            } else {
                // Dirs
                val dir = File(destParent, path)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                for (asset in assets) {
                    val subPath = File(path, asset).path
                    copyFileOrDir(context, parent, subPath, destParent, overwrite)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 词库总量超过 120MB，1KB 缓冲意味着十余万次读写系统调用
    private const val BUFFER_SIZE = 64 * 1024

    private fun copyFile(context: Context, parentAssetPath: String, filename: String, destParent: String, overwrite: Boolean) {
        try {
            val newFile = File(destParent, filename)
            if (newFile.exists() && !overwrite) return
            val assetPath = File(parentAssetPath, filename).path
            // use 保证异常路径下同样关闭流，原实现在异常时会泄漏文件描述符
            context.assets.open(assetPath).use { input ->
                FileOutputStream(newFile).buffered(BUFFER_SIZE).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
