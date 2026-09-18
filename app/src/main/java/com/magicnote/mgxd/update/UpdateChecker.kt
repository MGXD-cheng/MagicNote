package com.magicnote.mgxd.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 检查更新（数据源：GitHub 仓库 MGXD-cheng/MagicNote）
 *
 * 优先级：
 * 1. `releases/latest` —— 取 tag_name 与 assets 里的 .apk 直链（发布时把 APK 作为 release asset 上传即可全自动更新）
 * 2. 仓库没有 release 时回退 `tags` 取最新 tag（无 APK 直链，只能打开发布页）
 *
 * 版本号比较采用语义化：6.10 > 6.9 > 6.8。
 */
data class UpdateInfo(
    val latestVersion: String,
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkUrl: String?,
    val htmlUrl: String
)

object UpdateChecker {
    private const val OWNER = "MGXD-cheng"
    private const val REPO = "MagicNote"
    private const val REPO_URL = "https://github.com/$OWNER/$REPO"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true }

    /** 检查是否有新版本；返回 null = 已是最新（或仓库暂无发布） */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        // ---------- 1) releases/latest ----------
        val latestResp = getOrNull("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
        if (latestResp != null) {
            val root = runCatching { json.parseToJsonElement(latestResp).jsonObject }.getOrNull()
            val tag = root?.get("tag_name")?.jsonPrimitive?.contentOrNull
            if (!tag.isNullOrBlank()) {
                val ver = normalize(tag)
                if (!isNewer(ver, currentVersion)) return@withContext null
                val apk = (root["assets"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { (it["name"]?.jsonPrimitive?.contentOrNull ?: "").endsWith(".apk", true) }
                    ?.let { it["browser_download_url"]?.jsonPrimitive?.contentOrNull }
                return@withContext UpdateInfo(
                    latestVersion = ver,
                    tagName = tag,
                    releaseName = root["name"]?.jsonPrimitive?.contentOrNull ?: tag,
                    releaseNotes = root["body"]?.jsonPrimitive?.contentOrNull ?: "",
                    apkUrl = apk,
                    htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull ?: "$REPO_URL/releases"
                )
            }
        }
        // ---------- 2) 回退 tags ----------
        val tagsResp = getOrNull("https://api.github.com/repos/$OWNER/$REPO/tags") ?: return@withContext null
        val tags = runCatching { json.parseToJsonElement(tagsResp).jsonArray }.getOrNull() ?: return@withContext null
        val tag = tags.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val ver = normalize(tag)
        if (!isNewer(ver, currentVersion)) return@withContext null
        UpdateInfo(
            latestVersion = ver,
            tagName = tag,
            releaseName = tag,
            releaseNotes = "",
            apkUrl = null,
            htmlUrl = "$REPO_URL/releases"
        )
    }

    /** 下载 APK 到 cache，返回文件；onProgress 回调 0~100 */
    suspend fun downloadApk(
        context: Context,
        url: String,
        version: String,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apk_update").apply { mkdirs() }
        val target = File(dir, "MagicNote-$version.apk")
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("下载失败 HTTP ${resp.code}")
            val total = resp.body?.contentLength() ?: -1L
            resp.body?.byteStream()?.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        }
        target
    }

    /** 调起系统安装器（借助 FileProvider，无需存储权限） */
    fun install(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 浏览器打开发布页 */
    fun openReleasePage(context: Context, url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** "v6.8" → "6.8" */
    private fun normalize(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** 语义化版本比较：6.10 > 6.9 */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-', '_', ' ').mapNotNull { it.toIntOrNull() }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun getOrNull(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) body else null
        }
    }.getOrNull()
}
