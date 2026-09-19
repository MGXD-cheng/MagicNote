package com.magicnote.mgxd.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.magicnote.mgxd.MGApp
import com.magicnote.mgxd.ai.AiClient
import com.magicnote.mgxd.data.db.DiaryEntity
import com.magicnote.mgxd.data.db.TodoEntity
import com.magicnote.mgxd.ui.theme.MGTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一句话记录（桌面小组件唤起）
 *
 * 输入一句话 → 优先交给 Magic AI 判断「待办 / 日记」并提炼标题 →
 * 自动写入对应数据；AI 不可用（未配置 Key / 网络异常）时降级为今日待办。
 */
class QuickNoteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MGTheme(darkTheme = isSystemInDarkTheme()) {
                Surface(color = Color.Transparent) {
                    QuickNoteDialog(
                        onCancel = { finish() },
                        onSave = { text, useAi -> saveNote(text, useAi) }
                    )
                }
            }
        }
    }

    private fun saveNote(raw: String, useAi: Boolean) {
        val repo = (application as MGApp).container.repository
        lifecycleScope.launch {
            var message: String? = null
            try {
                if (useAi) {
                    val cfg = repo.aiConfig.first()
                    if (cfg.apiKey.isNotBlank()) {
                        val client = AiClient()
                        val reply = client.chat(
                            baseUrl = cfg.baseUrl,
                            apiKey = cfg.apiKey,
                            model = cfg.model,
                            messages = listOf(
                                AiClient.ChatMessage("system", SYSTEM_PROMPT),
                                AiClient.ChatMessage("user", raw)
                            ),
                            jsonMode = true,
                            timeoutSeconds = 30
                        )
                        val parsed = parseAiReply(reply)
                        if (parsed != null) {
                            val (type, title, content) = parsed
                            withContext(Dispatchers.IO) {
                                if (type == "diary") {
                                    repo.insertDiary(
                                        DiaryEntity(
                                            date = todayStart(),
                                            title = title,
                                            content = if (content.isNotBlank()) content else raw,
                                            mood = 3
                                        )
                                    )
                                } else {
                                    repo.insertTodo(
                                        TodoEntity(
                                            title = if (title.isNotBlank()) title else raw,
                                            dueTime = todayStart(),
                                            isLongTerm = false,
                                            source = "widget"
                                        )
                                    )
                                }
                            }
                            message = if (type == "diary") "已记入日记：$title" else "已加入今日待办：$title"
                        }
                    }
                }
            } catch (_: Exception) {
                // 忽略，走降级
            }

            if (message == null) {
                withContext(Dispatchers.IO) {
                    repo.insertTodo(
                        TodoEntity(
                            title = raw,
                            dueTime = todayStart(),
                            isLongTerm = false,
                            source = "widget"
                        )
                    )
                }
                message = "已加入今日待办"
            }

            Toast.makeText(this@QuickNoteActivity, message!!, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun todayStart(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 解析 AI 返回的 JSON（容忍 ```json 包裹） */
    private fun parseAiReply(reply: String): Triple<String, String, String>? = try {
        val cleaned = reply.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val obj = Json.parseToJsonElement(cleaned).jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: "todo"
        val title = obj["title"]?.jsonPrimitive?.content ?: ""
        val content = obj["content"]?.jsonPrimitive?.content ?: ""
        Triple(if (type == "diary") "diary" else "todo", title, content)
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val SYSTEM_PROMPT = """你是 Magic Note 的速记助手。用户会给你一句话，请判断它更适合记为【待办】还是【日记】：
- 待办：有明确要做的事 → type=todo，title 为要做的事（不超过 20 字，尽量动词开头），content 留空
- 日记：情绪、感受、见闻、想法 → type=diary，title 为简短标题（不超过 10 字），content 为润色后的一句话
只输出 JSON，不要解释，格式：{"type":"todo","title":"...","content":""}"""
    }
}

@Composable
private fun QuickNoteDialog(
    onCancel: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var useAi by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("✨ 快速记录", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("想到什么就写什么…") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useAi, onCheckedChange = { useAi = it })
                    Text("Magic AI 自动分类（待办 / 日记）", style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !saving,
                        modifier = Modifier.weight(1f)
                    ) { Text("取消") }
                    Button(
                        onClick = {
                            if (!saving && text.isNotBlank()) {
                                saving = true
                                onSave(text.trim(), useAi)
                            }
                        },
                        enabled = !saving && text.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("保存")
                        }
                    }
                }
                Text(
                    "保存后可在「待办 / 日记」中查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}