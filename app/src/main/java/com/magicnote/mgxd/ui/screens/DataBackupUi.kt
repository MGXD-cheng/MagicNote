package com.magicnote.mgxd.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magicnote.mgxd.ui.viewmodel.ConflictPolicy
import com.magicnote.mgxd.ui.viewmodel.DataTransferViewModel
import com.magicnote.mgxd.util.MgxdCodec
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 数据管理：.mgxd 导入导出 + CSV 出口 */
@Composable
fun DataBackupCard(dataVm: DataTransferViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transferState by dataVm.state.collectAsStateWithLifecycle()

    var showExportPicker by remember { mutableStateOf(false) }
    var exportKind by remember { mutableStateOf("mgxd") }   // mgxd / csv
    var exportKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var preserveAlpha by remember { mutableStateOf(false) }
    var importStage by remember { mutableStateOf<ImportStage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("处理中…") }
    var busyProgress by remember { mutableStateOf<Float?>(null) }

    // 文件保存（SAF，无需存储权限）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null && dataVm.pendingExport != null) {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(dataVm.pendingExport!!.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, "✅ 已导出 ${dataVm.pendingExport!!.length} 字符数据", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(context, "导出内容为空", Toast.LENGTH_SHORT).show()
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null && dataVm.pendingExport != null) {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write("\uFEFF".toByteArray(Charsets.UTF_8)) // BOM：Excel 中文不乱码
                        out.write(dataVm.pendingExport!!.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, "✅ CSV 已导出", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; busyLabel = "读取文件…"; busyProgress = null
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                } catch (e: Exception) { null }
            }
            busy = false
            if (text.isNullOrBlank()) {
                Toast.makeText(context, "无法读取所选文件", Toast.LENGTH_LONG).show()
            } else if (!dataVm.prepareImport(text)) {
                // magic 校验 / JSON 解析错误 → 无效文件
                Toast.makeText(context, "无效文件：不是有效的 .mgxd 备份文件", Toast.LENGTH_LONG).show()
            } else {
                busy = true; busyLabel = "检查冲突…"
                val conflict = dataVm.countConflicts()
                busy = false
                importStage = ImportStage.Review(conflict)
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("数据备份与迁移", style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true; busyLabel = "读取数据…"
                        dataVm.loadCandidates()
                        busy = false
                    }
                    showExportPicker = true
                },
                modifier = Modifier.weight(1f)
            ) { Text("导出数据") }
            Button(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f)
            ) { Text("导入备份") }
        }
        Text(
            "导出为 .mgxd（含图片，自动压缩到 5MB 以内）或 CSV（仅文本）；导入自动合并，不覆盖已有数据",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        }
    }

    // ===== 导出选择对话框 =====
    if (showExportPicker) {
        ExportPickerDialog(
            candidates = dataVm.candidates.collectAsStateWithLifecycle().value,
            onDismiss = { showExportPicker = false },
            onExport = { keys, kind, alpha ->
                exportKeys = keys; exportKind = kind; preserveAlpha = alpha
                showExportPicker = false
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
                when (kind) {
                    "csv" -> {
                        dataVm.prepareCsvExport(keys)
                        // 轮询等待后台编码完成后打开保存框
                        scope.launch {
                            dataVm.state.collect { st ->
                                if (!st.busy && dataVm.pendingExport != null) {
                                    csvLauncher.launch("MagicNote-$stamp.csv")
                                    dataVm.pendingExport = null
                                    return@collect
                                }
                            }
                        }
                    }
                    else -> {
                        dataVm.prepareMgxdExport(keys, alpha)
                        scope.launch {
                            dataVm.state.collect { st ->
                                if (!st.busy && dataVm.pendingExport != null) {
                                    exportLauncher.launch("MagicNote-backup-$stamp.mgxd")
                                    dataVm.pendingExport = null
                                    return@collect
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    // ===== 导入冲突 / 完成 对话框 =====
    importStage?.let { stage ->
        when (stage) {
            is ImportStage.Review -> ImportReviewDialog(
                conflictCount = stage.conflictCount,
                onDismiss = { importStage = null },
                onConfirm = { policy ->
                    if (policy == ConflictPolicy.CANCEL) { importStage = null; return@ImportReviewDialog }
                    importStage = null
                    busy = true
                    dataVm.runImport(context, policy) { result ->
                        busy = false
                        Toast.makeText(
                            context,
                            "导入完成：新增 ${result.imported} · 保留两份 ${result.duplicated} · 覆盖 ${result.overwritten} · 跳过 ${result.skipped}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }
    }

    // ===== 进度覆盖（导入/导出进行中） =====
    if (busy || transferState.busy) {
        val label = if (transferState.busy) transferState.label else busyLabel
        val p = transferState.progress ?: busyProgress
        AlertDialog(
            onDismissRequest = {},
            title = { Text("请稍候") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    } else {
                        CircularProgressIndicator()
                    }
                }
            },
            confirmButton = {}
        )
    }
}

/** 导入阶段状态 */
private sealed interface ImportStage {
    data class Review(val conflictCount: Int) : ImportStage
}

/** 冲突处理策略对话框 */
@Composable
private fun ImportReviewDialog(
    conflictCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (ConflictPolicy) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现 $conflictCount 条重复数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("导入将与现有数据合并，不直接覆盖。请选择重复记录的处理方式：")
                Text("· 保留两份：把备份里的重复内容也加进来（数据更全）")
                Text("· 覆盖：用备份内容替换本机已有的重复记录")
                Text("· 跳过：重复记录不导入，只新增不重复的")
                Text("· 取消：不导入任何内容")
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onConfirm(ConflictPolicy.KEEP_BOTH) }, modifier = Modifier.fillMaxWidth()) { Text("保留两份（推荐）") }
                OutlinedButton(onClick = { onConfirm(ConflictPolicy.OVERWRITE) }, modifier = Modifier.fillMaxWidth()) { Text("覆盖已有") }
                OutlinedButton(onClick = { onConfirm(ConflictPolicy.SKIP) }, modifier = Modifier.fillMaxWidth()) { Text("跳过重复") }
                TextButton(onClick = { onConfirm(ConflictPolicy.CANCEL) }, modifier = Modifier.fillMaxWidth()) { Text("取消导入") }
            }
        },
        dismissButton = {}
    )
}

/** 选择性导出对话框：类型 + 逐条复选框 + 全选/全不选 */
@Composable
private fun ExportPickerDialog(
    candidates: List<MgxdCodec.Candidate>,
    onDismiss: () -> Unit,
    onExport: (Set<String>, String, Boolean) -> Unit
) {
    val context = LocalContext.current
    var checked by remember(candidates) { mutableStateOf(candidates.map { it.key }.toSet()) }
    var preserveAlpha by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要导出的数据") },
        text = {
            if (exporting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(28.dp).height(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("正在生成…")
                }
            } else {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("已选 ${checked.size}/${candidates.size} 条", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { checked = candidates.map { it.key }.toSet() }) { Text("全选") }
                            TextButton(onClick = { checked = emptySet() }) { Text("取消全选") }
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(candidates, key = { it.key }) { cand ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = cand.key in checked,
                                    onCheckedChange = { on ->
                                        checked = if (on) checked + cand.key else checked - cand.key
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cand.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(cand.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                                }
                            }
                        }
                    }
                    // 保留透明通道复选框（PNG 透明图相关）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("保留透明通道", style = MaterialTheme.typography.bodyMedium)
                            Text("PNG 透明背景原样保留（将增大体积）；不勾选则自动补白", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Checkbox(checked = preserveAlpha, onCheckedChange = { preserveAlpha = it })
                    }
                }
            }
        },
        confirmButton = {
            if (!exporting) {
                Column {
                    Button(
                        enabled = checked.isNotEmpty(),
                        onClick = {
                            if (checked.isEmpty()) {
                                Toast.makeText(context, "请至少选择一项", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            exporting = true
                            onExport(checked, "mgxd", preserveAlpha)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("导出 .mgxd（含图片）") }
                    OutlinedButton(
                        enabled = checked.isNotEmpty(),
                        onClick = {
                            if (checked.isEmpty()) {
                                Toast.makeText(context, "请至少选择一项", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            exporting = true
                            onExport(checked, "csv", preserveAlpha)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("导出 CSV（仅文本，图片列留空）") }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
            }
        },
        dismissButton = {}
    )
}