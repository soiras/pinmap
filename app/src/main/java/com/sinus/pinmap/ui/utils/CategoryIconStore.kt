package com.sinus.pinmap.ui.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 分类自定义图标的本地存储。
 *
 * 图标统一复制到 files/category_icons/ 下，数据库里存文件的绝对路径；
 * 路径为空表示使用纯色圆点。
 */
object CategoryIconStore {

    private const val TAG = "Soiras-CategoryIconStore"
    private const val DIR_NAME = "category_icons"
    private const val COPY_BUFFER_SIZE = 8 * 1024

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    /**
     * 把选中的图片复制进应用私有目录，返回绝对路径；失败返回 null。
     */
    fun save(context: Context, uri: Uri): String? {
        return try {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val ext = mime.substringAfterLast('/', "")
                .takeIf { it.length in 1..5 && it.all { ch -> ch.isLetterOrDigit() } }
                ?: "jpg"
            val target = File(dir(context), "ICON_${System.currentTimeMillis()}.$ext")
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                FileOutputStream(target).use { output ->
                    source.copyTo(output, COPY_BUFFER_SIZE)
                }
            }
            target.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "save failed: $uri", e)
            null
        }
    }

    /**
     * 删除图标文件，只处理应用私有目录下的文件。
     */
    fun delete(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        try {
            val file = if (path.startsWith("file://")) {
                Uri.parse(path).path?.let { File(it) }
            } else {
                File(path)
            } ?: return
            if (!file.exists()) return
            val iconDir = dir(context).canonicalPath
            if (!file.canonicalPath.startsWith(iconDir)) return
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "delete failed: $path", e)
        }
    }
}
