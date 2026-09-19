package com.magicnote.mgxd.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * .mgxd 文件关联：从系统（文件管理器 / 浏览器下载 / 分享）打开备份文件时，
 * MainActivity 读取文本后投递到这里，UI 层（AppNav）消费并弹出导入确认。
 */
object MgxdIntentHolder {

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun post(text: String) {
        _pending.value = text
    }

    fun consume() {
        _pending.value = null
    }
}
