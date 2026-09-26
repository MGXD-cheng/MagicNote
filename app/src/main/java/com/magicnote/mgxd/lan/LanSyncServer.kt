package com.magicnote.mgxd.lan

import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** 局域网提供的 App 安装包（文件名 + APK 字节） */
class LanApk(val fileName: String, val bytes: ByteArray)

/**
 * 局域网同步 / 预览服务（零依赖，基于 ServerSocket 的极简 HTTP 服务）
 *
 * 路由：
 * - `GET /`            → HTML 预览页（数据概览 + 下载备份 + 下载 App 安装包）
 * - `GET /export.mgxd` → 完整备份 JSON（.mgxd 格式，**与「数据备份与迁移 → 导出数据」质量完全一致**）
 * - `GET /summary`     → 文本统计
 * - `GET /app.apk`     → 直接下载 Magic Note 安装包（APK，浏览器/另一台手机都能装）
 *
 * 使用场景：两台设备连同一 Wi-Fi，A 开启服务 → B 用浏览器预览、下载 App 或 .mgxd 备份，
 * 也可以在 Magic Note「设置 → 数据备份与迁移 → 局域网同步 → 从另一台导入」中一键合并数据。
 */
class LanSyncServer(
    private val port: Int = 8898,
    private val exportProvider: suspend () -> String,
    private val summaryProvider: suspend () -> String,
    /** 提供本机 App 安装包；返回 null 表示当前不可用（对应路由回 404） */
    private val apkProvider: (() -> LanApk?)? = null
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running

    /** 启动服务并返回可访问地址（http://ip:port） */
    fun start(): Result<String> = runCatching {
        val ss = ServerSocket(port)
        serverSocket = ss
        running = true
        acceptThread = Thread({ acceptLoop() }, "lan-sync").apply {
            isDaemon = true
            start()
        }
        "http://${localIp() ?: "127.0.0.1"}:$port"
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket?.accept() ?: break
            } catch (e: Exception) {
                if (running) continue else break
            }
            Thread({ runCatching { handle(socket) } }, "lan-sync-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            // 读掉请求头
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val ctype: String
            var bytes: ByteArray
            var disposition: String? = null
            var status = "200 OK"
            when {
                // ===== 下载 App 安装包（APK）=====
                path.startsWith("/app.apk") || path.startsWith("/apk") || path.startsWith("/download.apk") -> {
                    val apk = runCatching { apkProvider?.invoke() }.getOrNull()
                    if (apk == null) {
                        ctype = "text/plain; charset=utf-8"
                        bytes = "APK 当前不可用".toByteArray(Charsets.UTF_8)
                        status = "404 Not Found"
                    } else {
                        ctype = "application/vnd.android.package-archive"
                        bytes = apk.bytes
                        disposition = "attachment; filename=\"" + apk.fileName + "\""
                    }
                }
                // ===== 下载完整备份（与备份导出质量一致）=====
                path.startsWith("/export") -> {
                    ctype = "application/json; charset=utf-8"
                    bytes = runBlocking { exportProvider() }.toByteArray(Charsets.UTF_8)
                    // 关键：带 .mgxd 后缀下载文件名，浏览器才会存成 MagicNote-YYYYMMDD.mgxd
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                        .format(java.util.Date())
                    disposition = "attachment; filename=\"MagicNote-$stamp.mgxd\""
                }
                path.startsWith("/summary") -> {
                    ctype = "application/json; charset=utf-8"
                    bytes = runBlocking { summaryProvider() }.toByteArray(Charsets.UTF_8)
                }
                else -> {
                    ctype = "text/html; charset=utf-8"
                    bytes = runBlocking { htmlPage() }.toByteArray(Charsets.UTF_8)
                }
            }
            val header = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: $ctype\r\n")
                disposition?.let { append("Content-Disposition: $it\r\n") }
                append("Content-Length: ${bytes.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val out: OutputStream = s.getOutputStream()
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    private suspend fun htmlPage(): String {
        val summary = summaryProvider()
        val apk = runCatching { apkProvider?.invoke() }.getOrNull()
        val apkName = apk?.fileName ?: "MagicNote-release.apk"
        val apkSize = apk?.let { String.format("%.1f MB", it.bytes.size / 1024.0 / 1024.0) } ?: "-"
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Magic Note 局域网预览</title>
            <style>
              body{font-family:system-ui,-apple-system,sans-serif;margin:0;padding:24px;background:#0f1115;color:#e8eaf0}
              h1{font-size:20px;margin:0 0 4px}
              .sub{color:#8b93a7;font-size:13px;margin-bottom:20px}
              .card{background:#181c24;border:1px solid #262b36;border-radius:14px;padding:16px;margin-bottom:14px}
              .card h2{font-size:14px;margin:0 0 10px;color:#b9c0d0}
              pre{margin:0;white-space:pre-wrap;font-size:14px;line-height:1.7}
              a.btn{display:block;text-align:center;background:#7c4dff;color:#fff;text-decoration:none;
                    padding:14px;border-radius:12px;font-weight:600;margin-top:6px}
              a.btn2{display:block;text-align:center;background:#2b3140;color:#e8eaf0;text-decoration:none;
                    padding:14px;border-radius:12px;font-weight:600;margin-top:8px}
              .tip{color:#8b93a7;font-size:12px;line-height:1.6;margin-top:10px}
              .badge{display:inline-block;background:#243; color:#9f9; font-size:11px;padding:2px 8px;border-radius:8px;margin-left:6px}
            </style>
            </head>
            <body>
              <h1>Magic Note · 局域网预览</h1>
              <div class="sub">本页由另一台设备的 Magic Note 提供</div>
              <div class="card">
                <h2>数据概览</h2>
                <pre>${summary.replace("\n", "<br>")}</pre>
              </div>
              <div class="card">
                <h2>下载 Magic Note APP <span class="badge">$apkSize</span></h2>
                <a class="btn" href="/app.apk" download>⬇ 下载安装包（$apkName）</a>
                <div class="tip">手机浏览器点开后允许「安装未知来源应用」即可安装；装好后进入 App 就能用「局域网同步」导入本机数据。</div>
              </div>
              <div class="card">
                <h2>同步数据</h2>
                <a class="btn2" href="/export.mgxd" download>⬇ 下载完整备份 (.mgxd)</a>
                <div class="tip">备份质量与「设置 → 数据备份与迁移 → 导出数据」完全一致：包含待办、日程、日记（含全部图片）、打卡、倒数日。在另一台设备的 Magic Note 中打开「设置 → 数据备份与迁移 → 局域网同步 → 从另一台导入」，填入本机地址即可合并数据。</div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        /** 取本机局域网 IPv4 地址（Wi-Fi / 以太网优先） */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("eth") || it.name.startsWith("ap") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}