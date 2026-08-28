package com.magicnote.mgxd.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * 日记图片本地存储
 * 图片复制到应用私有目录 files/diary_images/（卸载即清理，无需任何存储权限），
 * 数据库只保存绝对路径。
 */
object DiaryImageStore {

    private const val DIR_NAME = "diary_images"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** 把用户选择的图片 Uri 复制到本地私有目录，返回本地绝对路径；失败返回 null */
    fun save(context: Context, uri: Uri): String? = try {
        val name = "img_" + UUID.randomUUID().toString().substring(0, 8) + ".jpg"
        val target = File(dir(context), name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    } catch (e: Exception) {
        null
    }

    /** 删除图片文件（删除日记时清理本地文件） */
    fun delete(path: String) {
        runCatching { File(path).delete() }
    }
}