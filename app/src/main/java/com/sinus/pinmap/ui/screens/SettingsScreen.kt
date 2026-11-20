package com.sinus.pinmap.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sinus.pinmap.BuildConfig
import com.sinus.pinmap.data.database.PinmapDatabase
import com.sinus.pinmap.data.repository.PinRepository
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.sinus.pinmap.ui.utils.AppSettings
import com.sinus.pinmap.ui.utils.CIRCLE_RADIUS_OPTIONS
import com.sinus.pinmap.ui.utils.circleRadiusLabel
import com.sinus.pinmap.ui.utils.AuthState
import com.sinus.pinmap.ui.utils.PinExporter
import com.sinus.pinmap.ui.utils.PinImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { PinmapDatabase.getDatabase(context) }

    val currentKey = remember { AuthState.getSavedKey() ?: BuildConfig.MAPS_API_KEY }
    var isBusy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var busyProgress by remember { mutableStateOf(0f) }
    var busyJob by remember { mutableStateOf<Job?>(null) }
    var hasData by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<PinImporter.ImportPreview?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) {
        val pinRepo = PinRepository(database.pinStore())
        pinRepo.getAllPins().collect { pinList ->
            hasData = pinList.isNotEmpty()
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                try {
                    val preview = withContext(Dispatchers.IO) {
                        PinImporter.preview(context, selectedUri)
                    }
                    pendingImportUri = selectedUri
                    importPreview = preview
                } catch (e: Exception) {
                    Toast.makeText(context, "文件无效或解析失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("API Key 设置", style = MaterialTheme.typography.titleLarge)
            Text(
                "当前 API Key 在 Application 初始化时设置，暂不支持在线修改。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Box {
                OutlinedTextField(
                    value = currentKey,
                    onValueChange = {},
                    label = { Text("API Key") },
                    enabled = false,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable {
                            Toast.makeText(context, "暂不可用", Toast.LENGTH_SHORT).show()
                        }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            var aiEnabled by remember { mutableStateOf(AppSettings.isAiSearchEnabled()) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("AI 搜索", style = MaterialTheme.typography.titleLarge)
                Switch(
                    checked = aiEnabled,
                    onCheckedChange = { /* 暂时锁定，不可修改 */ },
                    enabled = false
                )
            }
            val aiConfigEnabled = aiEnabled
            Text(
                "使用本地 Ollama 进行意图搜索。需与 Ollama 处于同一局域网。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            var ollamaUrl by remember { mutableStateOf(AppSettings.getOllamaBaseUrl()) }
            var aiConfigChanged by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = ollamaUrl,
                onValueChange = { ollamaUrl = it; aiConfigChanged = true },
                label = { Text("Ollama 地址") },
                singleLine = true,
                enabled = aiConfigEnabled,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            var ollamaModel by remember { mutableStateOf(AppSettings.getOllamaModel()) }
            OutlinedTextField(
                value = ollamaModel,
                onValueChange = { ollamaModel = it; aiConfigChanged = true },
                label = { Text("模型名称") },
                singleLine = true,
                enabled = aiConfigEnabled,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    AppSettings.saveOllamaBaseUrl(ollamaUrl.trim())
                    AppSettings.saveOllamaModel(ollamaModel.trim())
                    ollamaUrl = ollamaUrl.trim()
                    ollamaModel = ollamaModel.trim()
                    aiConfigChanged = false
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                },
                enabled = aiConfigChanged && aiConfigEnabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存 AI 配置") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("圈选半径", style = MaterialTheme.typography.titleLarge)
            Text(
                "在地图上单击设置圆心时使用的默认半径，圈选时仍可在底部临时切换。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var circleRadius by remember { mutableStateOf(AppSettings.getCircleRadius()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CIRCLE_RADIUS_OPTIONS.forEach { radius ->
                    val selected = circleRadius == radius
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            circleRadius = radius
                            AppSettings.saveCircleRadius(radius)
                        }
                    ) {
                        Text(
                            text = circleRadiusLabel(radius),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("数据管理", style = MaterialTheme.typography.titleLarge)

            Box {
                Button(
                    onClick = {
                        if (!isBusy) {
                            isBusy = true
                            busyLabel = "正在导出"
                            busyProgress = 0f
                            busyJob = scope.launch {
                                try {
                                    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                    val uri = withContext(Dispatchers.IO) {
                                        PinExporter.exportAll(context, database,
                                            onProgress = { done, total -> busyProgress = done.toFloat() / total },
                                            fileName = "export_$dateStr.pinmap"
                                        )
                                    }
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "application/octet-stream"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "导出全部标记"))
                                } catch (_: Exception) { }
                                isBusy = false
                            }
                        }
                    },
                    enabled = !isBusy && hasData,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("导出全部标记") }
                if (!hasData) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable {
                                Toast.makeText(context, "暂无可导出的数据", Toast.LENGTH_SHORT).show()
                            }
                    )
                }
            }

            Button(
                onClick = { importPicker.launch("*/*") },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("导入数据") }
        }

        if (isBusy) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "$busyLabel... (${(busyProgress * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { busyJob?.cancel(); isBusy = false },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "取消", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                importPreview = null
                pendingImportUri = null
            },
            title = { Text("确认导入") },
            text = { Text("将导入 ${preview.categoryCount} 个分类、${preview.templateCount} 个字段、${preview.pinCount} 个标记。") },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingImportUri ?: run {
                            importPreview = null
                            return@Button
                        }
                        importPreview = null
                        pendingImportUri = null
                        isBusy = true
                        busyLabel = "正在导入"
                        busyProgress = 0f
                        busyJob = scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    PinImporter.import(context, database, uri,
                                        onProgress = { done, total -> busyProgress = done.toFloat() / total })
                                }
                                Toast.makeText(
                                    context,
                                    "导入完成：${result.categoryCount} 分类、${result.templateCount} 字段、${result.pinCount} 标记、${result.mediaCount} 媒体",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            isBusy = false
                        }
                    }
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        importPreview = null
                        pendingImportUri = null
                    }
                ) { Text("取消") }
            }
        )
    }
}
