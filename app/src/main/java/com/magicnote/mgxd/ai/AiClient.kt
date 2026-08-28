package com.magicnote.mgxd.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI 兼容 Chat Completions API 客户端
 * 支持任意兼容 OpenAI 协议的端点（OpenAI、DeepSeek、Kimi、通义等）
 */
class AiClient {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ChatMessage(
        @SerialName("role") val role: String,
        @SerialName("content") val content: String
    )

    @Serializable
    private data class ChatRequest(
        @SerialName("model") val model: String,
        @SerialName("messages") val messages: List<ChatMessage>,
        @SerialName("temperature") val temperature: Double = 0.8,
        @SerialName("stream") val stream: Boolean = false,
        @SerialName("response_format") val responseFormat: JsonObjectFormat? = null
    )

    @Serializable
    private data class JsonObjectFormat(
        @SerialName("type") val type: String = "json_object"
    )

    @Serializable
    private data class ChatResponse(
        @SerialName("choices") val choices: List<Choice> = emptyList()
    )

    @Serializable
    private data class Choice(
        @SerialName("message") val message: Message
    )

    @Serializable
    private data class Message(
        @SerialName("content") val content: String
    )

    @Serializable
    data class AiError(
        @SerialName("error") val error: ErrorDetail? = null
    )

    @Serializable
    data class ErrorDetail(
        @SerialName("message") val message: String = ""
    )

    /**
     * 发送对话请求，返回 AI 回复文本
     * @param jsonMode 为 true 时启用 JSON 输出模式（response_format），适合结构化解析
     * @param timeoutSeconds 请求超时（秒）；复杂长 prompt（如 AI 规划）可放宽到 90~120
     * @throws AiException 当网络/鉴权/服务异常时
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        jsonMode: Boolean = false,
        timeoutSeconds: Long = 60
    ): String = withContext(Dispatchers.IO) {
        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
        val client = sharedClient.newBuilder()
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        try {
            requestOnce(client, endpoint, apiKey, model, messages, jsonMode)
        } catch (e: AiException) {
            // 兼容性降级：部分 OpenAI 兼容端点不支持 response_format(json_object)，
            // 首次带 jsonMode 请求被拒时，自动去掉 response_format 重试一次
            if (jsonMode && e.jsonFormatUnsupported) {
                requestOnce(client, endpoint, apiKey, model, messages, jsonMode = false)
            } else {
                throw e
            }
        }
    }

    private suspend fun requestOnce(
        client: OkHttpClient,
        endpoint: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        jsonMode: Boolean
    ): String {
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = messages,
                responseFormat = if (jsonMode) JsonObjectFormat() else null
            )
        )

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        return suspendCancellableCoroutine { cont ->
            client.newCall(requestBuilder.build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(AiException("网络请求失败：${e.message}", e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val msg = try {
                                json.decodeFromString(AiError.serializer(), raw).error?.message ?: "HTTP ${response.code}"
                            } catch (e: Exception) {
                                "HTTP ${response.code}"
                            }
                            if (cont.isActive) {
                                val formatUnsupported =
                                    response.code in 400..499 &&
                                        (msg.contains("response_format", ignoreCase = true) ||
                                            msg.contains("json_object", ignoreCase = true) ||
                                            msg.contains("json mode", ignoreCase = true) ||
                                            msg.contains("不支持", ignoreCase = true))
                                cont.resumeWithException(
                                    AiException("请求失败(${response.code})：$msg", jsonFormatUnsupported = formatUnsupported)
                                )
                            }
                            return
                        }
                        val parsed = json.decodeFromString(ChatResponse.serializer(), raw)
                        val content = parsed.choices.firstOrNull()?.message?.content
                        if (content.isNullOrBlank()) {
                            if (cont.isActive) cont.resumeWithException(AiException("AI 返回了空内容"))
                        } else {
                            if (cont.isActive) cont.resume(content)
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(AiException("响应解析失败：${e.message}", e))
                    } finally {
                        response.close()
                    }
                }
            })
        }
    }
}

class AiException(
    message: String,
    cause: Throwable? = null,
    val jsonFormatUnsupported: Boolean = false
) : Exception(message, cause)

/** 全局共享 OkHttpClient：复用连接池与线程池，避免每个 AiClient 实例重复创建 */
private val sharedClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // 默认读超时 60s，具体请求可经 chat(timeoutSeconds=) 覆盖（AiClient 内用 newBuilder 派生）
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}