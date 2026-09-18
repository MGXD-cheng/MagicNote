package com.magicnote.mgxd.lan

import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * 局域网同步 / 预览服务（零依赖，基于 ServerSocket 的极简 HTTP 服务）
 *
 * 路由：
 * - `GET /`            → HTML 预览页（数据统计 + 下载入口）
 * - `GET /export.mgxd` → 完整备份 JSON（.mgxd 格式，可被另一台设备直接导入合并）
 * - `GET /summary`     → 文本统计
 *
 * 使用场景：两台设备连同一 Wi-Fi，A 开启服务 → B 用浏览器预览，或在
 * Magic Note「设置 → 局域网同步 → 从另一台导入」中填 A 的地址合并数据。
 */
class LanSyncServer(
    private val port: Int = 8898,
    private val exportProvider: suspend () -> String,
    private val summaryProvider: suspend () -> String
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
            val body: String
            var disposition: String? = null
            when {
                path.startsWith("/export") -> {
                    ctype = "application/json; charset=utf-8"
                    body = runBlocking { exportProvider() }
                    // 关键：带 .mgxd 后缀下载文件名，浏览器才会存成 MagicNote-YYYYMMDD.mgxd
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                        .format(java.util.Date())
                    disposition = "attachment; filename=\"MagicNote-" + stamp + ".mgxd\""
                }
                path.startsWith("/summary") -> {
                    ctype = "application/json; charset=utf-8"
                    body = runBlocking { summaryProvider() }
                }
                else -> {
                    ctype = "text/html; charset=utf-8"
                    body = runBlocking { htmlPage() }
                }
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $ctype\r\n")
                disposition?.let { append("Content-Disposition: $it\r\n") }
                append("Content-Length: ${bytes.size}\r\n")
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
              .tip{color:#8b93a7;font-size:12px;line-height:1.6;margin-top:10px}
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
                <h2>同步</h2>
                <a class="btn" href="/export.mgxd" download>⬇ 下载完整备份 (.mgxd)</a>
                <div class="tip">在另一台设备的 Magic Note 中打开「设置 → 局域网同步 → 从另一台导入」，填入本机地址即可合并数据（图片一并同步）。</div>
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