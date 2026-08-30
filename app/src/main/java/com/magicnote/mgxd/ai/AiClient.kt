package com.magicnote.mgxd.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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

    @Serializable(with = ChatMessageSerializer::class)
    data class ChatMessage(
        @SerialName("role") val role: String,
        @SerialName("content") val content: String,
        /** OpenAI vision 图片 data URL 列表（data:image/jpeg;base64,...），为空则纯文本（与旧版序列化格式完全一致） */
        val images: List<String> = emptyList()
    )

    /**
     * ChatMessage 自定义序列化器：
     * - images 为空 → content 输出普通字符串（向后兼容，所有现有调用不变）
     * - images 非空 → content 输出 OpenAI vision 多模态数组：
     *   [{"type":"text","text":...},{"type":"image_url","image_url":{"url":"data:image/..."}}...]
     */
    object ChatMessageSerializer : KSerializer<ChatMessage> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ChatMessage") {
            element("role", String.serializer().descriptor)
            element("content", JsonElement.serializer().descriptor)
            element("images", ListSerializer(String.serializer()).descriptor, isOptional = true)
        }

        override fun serialize(encoder: Encoder, value: ChatMessage) = encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.role)
            val contentElement: JsonElement = if (value.images.isEmpty()) {
                JsonPrimitive(value.content)
            } else {
                JsonArray(
                    buildList {
                        add(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(value.content))))
                        value.images.forEach { url ->
                            add(
                                JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("image_url"),
                                        "image_url" to JsonObject(mapOf("url" to JsonPrimitive(url)))
                                    )
                                )
                            )
                        }
                    }
                )
            }
            encodeSerializableElement(descriptor, 1, JsonElement.serializer(), contentElement)
        }

        override fun deserialize(decoder: Decoder): ChatMessage = decoder.decodeStructure(descriptor) {
            var role = ""
            var content = ""
            loop@ while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break@loop
                    0 -> role = decodeStringElement(descriptor, 0)
                    1 -> {
                        // 兼容读取：字符串直接取；数组仅提取 text 部分（本项目响应端不解析 ChatMessage，兜底实现）
                        val element = decodeSerializableElement(descriptor, 1, JsonElement.serializer())
                        content = when (element) {
                            is JsonPrimitive -> element.content
                            is JsonArray -> element.firstOrNull { it is JsonObject && it["type"]?.let { t -> t is JsonPrimitive && t.content == "text" } == true }
                                ?.let { (it as JsonObject)["text"] as? JsonPrimitive }?.content ?: ""
                            else -> ""
                        }
                    }
                    else -> error("Unexpected index $index")
                }
            }
            ChatMessage(role, content)
        }
    }

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