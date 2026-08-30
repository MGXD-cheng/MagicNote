package com.magicnote.mgxd.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * 日记图片本地存储
 * 图片复制到应用私有目录 files/diary_images/（卸载即清理，无需任何存储权限），
 * 数据库只保存绝对路径。
 */
object DiaryImageStore {

    private const val DIR_NAME = "diary_images"
    private const val VISION_MAX_SIDE = 1024

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

    /**
     * 把本地图片压缩并编码为 OpenAI vision data URL（data:image/jpeg;base64,...）
     * 长边压到 1024 内、JPEG 80% 质量，防止超大原图撑爆请求体；失败返回 null。
     * 应在 IO 线程调用。
     */
    fun encodeForVision(path: String): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null
        // 采样：先按 2 的幂次缩小，尽量接近 1024
        var sample = 1
        while (longSide / (sample * 2) >= VISION_MAX_SIDE) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(path, decodeOpts) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}