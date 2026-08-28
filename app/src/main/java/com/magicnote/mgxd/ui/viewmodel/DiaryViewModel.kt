package com.magicnote.mgxd.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magicnote.mgxd.ai.AiClient
import com.magicnote.mgxd.ai.AiPrompter
import com.magicnote.mgxd.ai.Personality
import com.magicnote.mgxd.data.db.DiaryEntity
import com.magicnote.mgxd.data.repo.AppRepository
import com.magicnote.mgxd.notify.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DiaryViewModel(
    private val repo: AppRepository,
    private val appContext: Context
) : ViewModel() {

    private val _diaries = MutableStateFlow<List<DiaryEntity>>(emptyList())
    val diaries: StateFlow<List<DiaryEntity>> = _diaries.asStateFlow()

    private val client = AiClient()
    private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE")

    init {
        viewModelScope.launch {
            repo.observeDiaries().collect { _diaries.value = it }
        }
    }

    fun saveDiary(date: Long, existing: DiaryEntity?, title: String?, content: String, mood: Int, imagePaths: List<String> = emptyList()) {
        viewModelScope.launch {
            val saved: DiaryEntity
            if (existing != null) {
                // 编辑已有日记：保留原 createdAt，更新时间
                saved = existing.copy(
                    title = title, content = content, mood = mood, imagePaths = imagePaths,
                    updatedAt = System.currentTimeMillis()
                )
                repo.updateDiary(saved)
            } else {
                // 新增：同一天可以记多篇，各自带精确 createdAt
                saved = DiaryEntity(
                    date = date, title = title, content = content, mood = mood, imagePaths = imagePaths,
                    createdAt = System.currentTimeMillis()
                )
                repo.insertDiary(saved)
            }
            // 日记自动回复：仅「新增」日记时触发；编辑已保存过的日记不回复
            if (existing == null && repo.diaryAutoReply.collectFirst()) {
                autoReplyDiary(saved)
            }
        }
    }

    /** 生成 AI 日记回复：共情/建议/鼓励，符合所选人格；失败静默不影响写日记 */
    private suspend fun autoReplyDiary(diary: DiaryEntity) {
        try {
            val config = repo.aiConfig.collectFirst()
            if (config.apiKey.isBlank()) return
            val personality = Personality.fromId(config.personalityId)
            val dateStr = Instant.ofEpochMilli(diary.date).atZone(ZoneId.systemDefault()).format(dateTimeFmt)
            val prompt = AiPrompter.buildDiaryReplyPrompt(personality, diary.title, diary.content, diary.mood, dateStr)
            val reply = withContext(Dispatchers.IO) {
                try {
                    client.chat(
                        baseUrl = config.baseUrl,
                        apiKey = config.apiKey,
                        model = config.model,
                        messages = listOf(AiClient.ChatMessage("user", prompt))
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            } ?: return
            repo.insertChat("assistant", "📝日记回复：\n" + reply)
            NotificationHelper.showDiaryAutoReply(appContext, reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 自动回复失败不影响写日记主流程
        }
    }

    fun delete(diary: DiaryEntity) {
        viewModelScope.launch {
            // 删除日记时顺带清理本地图片文件
            diary.imagePaths.forEach { com.magicnote.mgxd.util.DiaryImageStore.delete(it) }
            repo.deleteDiary(diary)
        }
    }
}
