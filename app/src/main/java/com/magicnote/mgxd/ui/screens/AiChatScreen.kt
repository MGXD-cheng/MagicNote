package com.magicnote.mgxd.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magicnote.mgxd.ui.components.MarkdownText
import com.magicnote.mgxd.ui.viewmodel.AiViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AiChatScreen(vm: AiViewModel) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val markdownRender by vm.markdownRender.collectAsStateWithLifecycle()
    val noteDraft by vm.noteDraft.collectAsStateWithLifecycle()
    val noteSaved by vm.noteSaved.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 选择模式：长按任意消息进入，可多选 → 整理成笔记 / 删除
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showNotePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    // 聊天记录导出（SAF 保存框，无需存储权限）
    var pendingExport by remember { mutableStateOf("") }
    var showExportDialog by remember { mutableStateOf(false) }
    val writeExport: (Uri?) -> Unit = { uri ->
        if (uri == null) {
            Toast.makeText(context, "已取消导出", Toast.LENGTH_SHORT).show()
        } else {
            scope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(pendingExport.toByteArray(Charsets.UTF_8))
                    }
                }.onSuccess {
                    Toast.makeText(context, "✅ 聊天记录已导出", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val mdExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri: Uri? -> writeExport(uri) }
    val mgxdExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? -> writeExport(uri) }

    LaunchedEffect(messages.size, loading) {
        if (messages.isNotEmpty() && !selectionMode) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(noteSaved) {
        noteSaved?.let {
            Toast.makeText(context, "已保存到日记：$it", Toast.LENGTH_LONG).show()
            vm.consumeNoteSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text("已选 ${selectedIds.size} 条")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Magic AI")
                        }
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "退出选择")
                        }
                    }
                },
                actions = {
                    if (!selectionMode && messages.isNotEmpty()) {
                        // 整理成笔记：点按钮 → 勾选要整理的条目 → 生成
                        IconButton(onClick = { showNotePicker = true }) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "把聊天整理成笔记")
                        }
                        IconButton(onClick = { showExportDialog = true }) {
                            Icon(Icons.Default.Download, contentDescription = "导出聊天记录")
                        }
                        IconButton(onClick = { vm.clearChat() }) {
                            Icon(Icons.Default.Delete, contentDescription = "清空对话")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🤖", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "我是你的 Magic AI 生活助手\n我了解你的待办、日程和日记\n有什么想聊的？",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "💡 想整理笔记：点右上角 ✨ 勾选要整理的条目\n长按任意消息还能多选删除",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { "exp_${it.id}" }) { msg ->
                        val selected = msg.id in selectedIds
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            selectedIds = if (selected) selectedIds - msg.id
                                            else selectedIds + msg.id
                                        }
                                    },
                                    onLongClick = {
                                        if (!selectionMode) selectionMode = true
                                        selectedIds = selectedIds + msg.id
                                    }
                                )
                        ) {
                            ChatBubble(
                                text = msg.content,
                                isUser = msg.role == "user",
                                timeMillis = msg.timestamp,
                                markdown = markdownRender,
                                selectable = selectionMode,
                                selected = selected
                            )
                        }
                    }
                    item {
                        // 「思考中」气泡：进入淡入展开，退出时真正卸载
                        AnimatedVisibility(
                            visible = loading,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            ChatBubble(text = "思考中…", isUser = false, showTyping = true)
                        }
                    }
                }
            }

            if (selectionMode) {
                // ===== 多选操作栏 =====
                Surface(tonalElevation = 3.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "已选 ${selectedIds.size}/${messages.size} 条",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { selectedIds = messages.map { it.id }.toSet() }) { Text("全选") }
                            TextButton(onClick = { selectedIds = messages.takeLast(20).map { it.id }.toSet() }) { Text("最近20条") }
                            TextButton(onClick = { selectedIds = emptySet() }) { Text("清空") }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                enabled = selectedIds.isNotEmpty(),
                                onClick = {
                                    val ids = selectedIds.toList()
                                    exitSelection()
                                    vm.generateNote(context, sourceIds = ids)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("✨ 整理成笔记") }
                            OutlinedButton(
                                enabled = selectedIds.isNotEmpty(),
                                onClick = { confirmDelete = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("🗑 删除") }
                        }
                    }
                }
            } else {
                // ===== 输入栏 =====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("问问 Magic AI 今天该怎么安排…") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (input.isNotBlank() && !loading) {
                                vm.sendMessage(context, input.trim())
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank() && !loading,
                        modifier = Modifier
                            .background(
                                if (input.isNotBlank() && !loading) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                RoundedCornerShape(24.dp)
                            )
                            .padding(8.dp)
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp).width(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = if (input.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }

    // ===== 删除确认 =====
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除消息") },
            text = { Text("确定删除选中的 ${selectedIds.size} 条聊天记录吗？删除后无法恢复。") },
            confirmButton = {
                Button(onClick = {
                    val ids = selectedIds.toList()
                    confirmDelete = false
                    vm.deleteChats(ids)
                    exitSelection()
                    Toast.makeText(context, "已删除 ${ids.size} 条", Toast.LENGTH_SHORT).show()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }

    // ===== 整理笔记：先勾选要整理的条目 =====
    if (showNotePicker) {
        MessagePickerDialog(
            messages = messages,
            title = "选择要整理成笔记的聊天",
            hint = "勾选后点「开始整理」，AI 会只根据这些内容生成笔记；生成后可继续提要求让它重写",
            confirmLabel = "开始整理",
            onDismiss = { showNotePicker = false },
            onConfirm = { ids ->
                showNotePicker = false
                vm.generateNote(context, sourceIds = ids)
            }
        )
    }

    // ===== 聊天记录导出弹窗（选范围 + 格式） =====
    if (showExportDialog) {
        ChatExportDialog(
            messages = messages,
            onDismiss = { showExportDialog = false },
            onExport = { kind, selected ->
                showExportDialog = false
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
                if (kind == "mgxd") {
                    pendingExport = vm.buildChatExportMgxd(selected)
                    mgxdExportLauncher.launch("MagicNote-chat-$stamp.mgxd")
                } else {
                    pendingExport = vm.buildChatExportText(selected)
                    mdExportLauncher.launch("MagicAI-chat-$stamp.md")
                }
            }
        )
    }

    // ===== AI 笔记预览弹窗：可提要求重新生成，满意后再保存 =====
    noteDraft?.let { draft ->
        NotePreviewDialog(
            draft = draft,
            markdownRender = markdownRender,
            onRegenerate = { requirement -> vm.generateNote(context, requirement, regenerate = true) },
            onSave = { vm.saveNote() },
            onDismiss = { vm.dismissNoteDraft() }
        )
    }
}

/** AI 笔记预览 / 复核弹窗（保存前可预览、可提要求重新生成） */
@Composable
private fun NotePreviewDialog(
    draft: AiViewModel.NoteDraft,
    markdownRender: Boolean,
    onRegenerate: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var requirement by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!draft.generating) onDismiss() },
        title = {
            Text(
                if (draft.title.isBlank()) "AI 笔记预览"
                else "AI笔记：" + draft.title
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (draft.generating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("正在整理聊天记录…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (draft.sourceIds.isNotEmpty()) {
                    Text(
                        "素材：已选 ${draft.sourceIds.size} 条聊天",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                draft.error?.let {
                    Text(
                        "⚠️ $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (draft.content.isNotBlank()) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        if (markdownRender) {
                            MarkdownText(text = draft.content)
                        } else {
                            Text(draft.content, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                OutlinedTextField(
                    value = requirement,
                    onValueChange = { requirement = it },
                    label = { Text("对笔记提要求（选填）") },
                    placeholder = { Text("例：只保留待办 / 再简洁一点 / 语气活泼些") },
                    minLines = 2,
                    enabled = !draft.generating,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "不满意可以提要求重新生成（沿用同一批聊天），满意后再点「保存到日记」",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = !draft.generating && draft.content.isNotBlank(),
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存到日记") }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    enabled = !draft.generating,
                    onClick = { onRegenerate(requirement) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (requirement.isBlank()) "重新生成" else "按要求重新生成") }
                TextButton(
                    enabled = !draft.generating,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("取消") }
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun ChatBubble(
    text: String,
    isUser: Boolean,
    showTyping: Boolean = false,
    timeMillis: Long = 0L,
    markdown: Boolean = false,
    selectable: Boolean = false,
    selected: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectable && !isUser) {
                Checkbox(checked = selected, onCheckedChange = null)
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(
                        color = if (isUser) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                when {
                    showTyping -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(14.dp).width(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                    // AI 回复：按设置渲染 Markdown（用户消息保持原样）
                    !isUser && markdown -> MarkdownText(text = text)
                    else -> Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (selectable && isUser) {
                Checkbox(checked = selected, onCheckedChange = null)
            }
        }
        // 历史时间标注（今天只显示时分，跨天显示日期）
        if (!showTyping && timeMillis > 0L) {
            Text(
                text = formatChatTime(timeMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/** 聊天时间格式：今天 → HH:mm；更早 → M月d日 HH:mm */
private fun formatChatTime(millis: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(millis).atZone(zone)
    val now = java.time.ZonedDateTime.now(zone)
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    return if (time.toLocalDate() == now.toLocalDate()) {
        time.format(timeFmt)
    } else {
        time.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
    }
}

/** 通用「勾选聊天条目」弹窗：整理笔记 / 导出都用它 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessagePickerDialog(
    messages: List<AiViewModel.ChatItem>,
    title: String,
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit
) {
    var selected by remember(messages) { mutableStateOf<Set<Long>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size}/${messages.size} 条", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { selected = messages.takeLast(20).map { it.id }.toSet() }) { Text("最近20条") }
                    TextButton(onClick = { selected = messages.map { it.id }.toSet() }) { Text("全选") }
                    TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                    items(messages, key = { it.id }) { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        selected = if (m.id in selected) selected - m.id else selected + m.id
                                    }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = m.id in selected,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + m.id else selected - m.id
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (if (m.role == "user") "🧑 我" else "🤖 Magic AI") + " · " + formatChatTime(m.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    m.content.replace('\n', ' '),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = { onConfirm(messages.filter { it.id in selected }.map { it.id }) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("$confirmLabel（${selected.size} 条）") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        },
        dismissButton = {}
    )
}

/** 聊天记录导出对话框：选择导出范围 + Markdown / .mgxd（可导入） */
@Composable
private fun ChatExportDialog(
    messages: List<AiViewModel.ChatItem>,
    onDismiss: () -> Unit,
    onExport: (String, List<AiViewModel.ChatItem>) -> Unit
) {
    var kind by remember { mutableStateOf("md") }
    var selected by remember(messages) { mutableStateOf(messages.map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出聊天记录") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = kind == "md", onClick = { kind = "md" })
                    Text("Markdown", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    RadioButton(selected = kind == "mgxd", onClick = { kind = "mgxd" })
                    Text(".mgxd（可导入）", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    if (kind == "mgxd") "导出为 .mgxd 后，可在「设置 → 数据备份与迁移 → 导入备份」里把聊天记录导回来"
                    else "导出为 Markdown 文本，方便阅读与存档",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("已选 ${selected.size}/${messages.size} 条", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { selected = messages.map { it.id }.toSet() }) { Text("全选") }
                    TextButton(onClick = { selected = messages.takeLast(20).map { it.id }.toSet() }) { Text("最近 20 条") }
                    TextButton(onClick = { selected = emptySet() }) { Text("全不选") }
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                    items(messages, key = { it.id }) { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = m.id in selected,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + m.id else selected - m.id
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (if (m.role == "user") "🧑 我" else "🤖 Magic AI") + " · " + formatChatTime(m.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    m.content.replace('\n', ' '),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = { onExport(kind, messages.filter { it.id in selected }) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("导出所选（${selected.size} 条）") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        },
        dismissButton = {}
    )
}