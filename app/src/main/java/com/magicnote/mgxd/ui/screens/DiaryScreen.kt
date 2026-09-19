package com.magicnote.mgxd.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.magicnote.mgxd.util.DiaryLock
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.magicnote.mgxd.data.db.DiaryEntity
import com.magicnote.mgxd.ui.components.ConfirmDialog
import com.magicnote.mgxd.ui.components.EmptyState
import com.magicnote.mgxd.ui.components.MOODS
import com.magicnote.mgxd.ui.components.MoodSelector
import com.magicnote.mgxd.ui.viewmodel.DiaryViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    vm: DiaryViewModel,
    onAddClick: () -> Unit,
    onEditClick: (DiaryEntity) -> Unit
) {
    // 日记锁：开启后需先验证（数字密码 / 指纹·人脸·设备锁）
    val lockEnabled by vm.lockEnabled.collectAsStateWithLifecycle()
    val lockMode by vm.lockMode.collectAsStateWithLifecycle()
    var unlocked by remember { mutableStateOf(false) }
    if (lockEnabled && !unlocked) {
        DiaryLockScreen(
            mode = lockMode,
            verifyPassword = { pwd -> vm.verifyPassword(pwd) },
            onUnlocked = { unlocked = true }
        )
        return
    }

    val diaries by vm.diaries.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<DiaryEntity?>(null) }

    // 按天分组（diaries 已按 date DESC, createdAt ASC 排序，分组自然保持顺序）
    // remember(diaries)：只有列表变化才重算，避免每次重组重复 groupBy
    val grouped = remember(diaries) { diaries.groupBy { it.date } }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "写日记")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (grouped.isEmpty()) {
                item {
                    EmptyState(
                        text = "还没有日记\n记录今天的心情吧",
                        icon = Icons.Outlined.Book
                    )
                }
            } else {
                items(grouped.toList(), key = { it.first }) { (dayStart, dayDiaries) ->
                    DiaryGroupCard(
                        dayStart = dayStart,
                        diaries = dayDiaries,
                        onEdit = { onEditClick(it) },
                        onDelete = { deleteTarget = it }
                    )
                }
            }
        }
    }

    deleteTarget?.let { diary ->
        ConfirmDialog(
            title = "删除日记",
            text = "确定要删除这篇日记吗？",
            onConfirm = {
                vm.delete(diary)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

/** 同一天的多篇日记合并成一张卡片，按时间顺序展示 */
@Composable
private fun DiaryGroupCard(
    dayStart: Long,
    diaries: List<DiaryEntity>,
    onEdit: (DiaryEntity) -> Unit,
    onDelete: (DiaryEntity) -> Unit
) {
    val dateFmt = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(formatDate(dayStart, dateFmt), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(4.dp))
                Text(
                    "${diaries.size}篇",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            diaries.forEach { diary ->
                DiaryEntryRow(diary = diary, onEdit = { onEdit(diary) }, onDelete = { onDelete(diary) })
            }
        }
    }
}

/** 单条日记（时间 + 心情 + 内容，点击编辑） */
@Composable
private fun DiaryEntryRow(
    diary: DiaryEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val mood = MOODS.getOrElse(diary.mood) { "🙂" }
    // 图片查看器状态（点击缩略图打开大图）
    var viewerImages by remember { mutableStateOf<List<String>?>(null) }
    var viewerIndex by remember { mutableIntStateOf(0) }
    // 旧数据 createdAt 可能为 0（迁移），回退到 updatedAt
    val timeMillis = if (diary.createdAt > 0L) diary.createdAt else diary.updatedAt
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            formatDate(timeMillis, timeFmt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(44.dp)
        )
        Text(mood, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            diary.title?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = diary.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 日记图片缩略图（仅展示，点击缩略图进入编辑可管理）
            if (diary.imagePaths.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    diary.imagePaths.take(4).forEachIndexed { idx, path ->
                        val bmp = remember(path) {
                            runCatching {
                                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                android.graphics.BitmapFactory.decodeFile(path, opts)
                            }.getOrNull()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "日记图片",
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        viewerImages = diary.imagePaths
                                        viewerIndex = idx
                                    },
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.outline)
        }
    }

    // 全屏图片查看器（点击缩略图打开，支持左右切换 / 页码）
    viewerImages?.let { images ->
        Dialog(onDismissRequest = { viewerImages = null }) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                val currentPath = images.getOrNull(viewerIndex)
                val fullBitmap = remember(currentPath) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(currentPath) }.getOrNull()
                }
                if (fullBitmap != null) {
                    Image(
                        bitmap = fullBitmap.asImageBitmap(),
                        contentDescription = "查看图片",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                }
                if (images.size > 1) {
                    IconButton(
                        onClick = { viewerIndex = (viewerIndex - 1 + images.size) % images.size },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart)
                    ) { Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "上一张", tint = Color.White) }
                    IconButton(
                        onClick = { viewerIndex = (viewerIndex + 1) % images.size },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
                    ) { Icon(Icons.Default.KeyboardArrowRight, contentDescription = "下一张", tint = Color.White) }
                }
                Text(
                    "${viewerIndex + 1} / ${images.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(28.dp)
                )
                IconButton(
                    onClick = { viewerImages = null },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(12.dp)
                ) { Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White) }
            }
        }
    }
}

private fun formatDate(millis: Long, fmt: DateTimeFormatter): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(fmt)

// ==================== 写日记对话框 ====================

@Composable
fun EditDiaryDialog(
    defaultDate: java.time.LocalDate,
    existing: DiaryEntity? = null,
    onDismiss: () -> Unit,
    onConfirm: (title: String?, content: String, mood: Int, imagePaths: List<String>) -> Unit
) {
    val editKey = existing?.id ?: -1L
    val context = androidx.compose.ui.platform.LocalContext.current
    var title by remember(editKey) { mutableStateOf(existing?.title ?: "") }
    var content by remember(editKey) { mutableStateOf(existing?.content ?: "") }
    var mood by remember(editKey) { mutableStateOf(existing?.mood ?: 2) }
    var images by remember(editKey) { mutableStateOf(existing?.imagePaths ?: emptyList()) }

    // 选择图片 → 复制到本地私有目录 diary_images/（不上传，仅本机保存）
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            com.magicnote.mgxd.util.DiaryImageStore.save(context, it)?.let { path ->
                if (!images.contains(path)) images = images + path
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(existing?.let { "编辑日记" } ?: "写日记 - ${defaultDate.format(DateTimeFormatter.ofPattern("M月d日"))}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("今天发生了什么？") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Column {
                    Text("今天的心情", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    MoodSelector(selected = mood, onSelect = { mood = it })
                }
                // 图片：已选缩略图（点击移除）+ 添加按钮
                Column {
                    Text("图片（保存到本机，不联网上传）", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    if (images.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            images.forEach { path ->
                                DiaryThumb(path = path, onClick = { images = images - path })
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (images.isEmpty()) "📷 添加图片" else "📷 再添加一张") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (content.isNotBlank()) onConfirm(title.trim().ifBlank { null }, content, mood, images) },
                enabled = content.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 本地图片缩略图（64dp 方形，点击移除） */
@Composable
private fun DiaryThumb(path: String, onClick: () -> Unit) {
    val bitmap = remember(path) {
        runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            android.graphics.BitmapFactory.decodeFile(path, opts)
        }.getOrNull()
    }
    if (bitmap != null) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clickable(onClick = onClick)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "日记图片",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .size(18.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "移除图片",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onError
                )
            }
        }
    }
}

// ==================== 日记锁屏 ====================
@Composable
private fun DiaryLockScreen(
    mode: String,
    verifyPassword: suspend (String) -> Boolean,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            onUnlocked()
        } else {
            error = "未通过验证，请重试"
        }
    }

    fun launchDeviceCredential(reset: Boolean = false) {
        val intent = DiaryLock.deviceCredentialIntent(
            context,
            if (reset) "验证以解锁日记" else "解锁日记"
        )
        if (intent == null) {
            error = "设备未设置锁屏，请先在系统设置中设置密码 / 指纹 / 面部"
        } else {
            credentialLauncher.launch(intent)
        }
    }

    // 生物识别模式：进入自动弹出一次系统验证
    LaunchedEffect(mode) {
        if (mode == "biometric") launchDeviceCredential()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🔒", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(6.dp))
            Text("日记已锁定", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                if (mode == "biometric") "使用指纹 / 人脸 / 设备密码解锁" else "输入数字密码解锁",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(20.dp))

            if (mode == "password") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(6) { i ->
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    if (i < input.length) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape
                                )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("⌫", "0", "✓")
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { key ->
                            OutlinedButton(
                                onClick = {
                                    when (key) {
                                        "⌫" -> {
                                            if (input.isNotEmpty()) input = input.dropLast(1)
                                            error = null
                                        }
                                        "✓" -> {
                                            if (!checking && input.isNotEmpty()) {
                                                checking = true
                                                scope.launch {
                                                    val ok = verifyPassword(input)
                                                    checking = false
                                                    if (ok) {
                                                        onUnlocked()
                                                    } else {
                                                        error = "密码错误，请重试"
                                                        input = ""
                                                    }
                                                }
                                            }
                                        }
                                        else -> if (input.length < 6) {
                                            input += key
                                            error = null
                                        }
                                    }
                                },
                                modifier = Modifier.size(64.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) { Text(key, style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
            } else {
                Button(onClick = { launchDeviceCredential() }, modifier = Modifier.fillMaxWidth()) {
                    Text("验证指纹 / 人脸 / 设备密码")
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(10.dp))
            }

            TextButton(onClick = { launchDeviceCredential(reset = true) }) {
                Text("忘记密码？用设备锁验证解锁")
            }
        }
    }
}
