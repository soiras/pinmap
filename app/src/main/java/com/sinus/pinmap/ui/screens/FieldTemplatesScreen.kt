package com.sinus.pinmap.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import com.sinus.pinmap.data.database.PinmapDatabase
import com.sinus.pinmap.data.entity.FieldTemplate
import com.sinus.pinmap.data.repository.CategoryRepository
import com.sinus.pinmap.data.repository.FieldTemplateRepository
import com.sinus.pinmap.data.repository.FieldValueRepository
import com.sinus.pinmap.data.repository.PinRepository
import com.sinus.pinmap.ui.MAX_FIELDS_PER_ROW
import com.sinus.pinmap.ui.groupFieldRows
import com.sinus.pinmap.ui.hasFullRowField
import com.sinus.pinmap.ui.rowGroupKey
import com.sinus.pinmap.ui.utils.PinExporter
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 拖动框中线落在目标卡片中线上下各 1/3 高度内，才算「融合」，否则按上下半区换行 */
private const val MERGE_BAND_RATIO = 1f / 3f

/** 换行提示线的粗细 */
private const val INSERT_LINE_HEIGHT_DP = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldTemplatesScreen(
    categoryId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val database = remember { PinmapDatabase.getDatabase(context) }
    val templateRepo = remember { FieldTemplateRepository(database.fieldTemplateStore()) }
    val pinRepo = remember { PinRepository(database.pinStore()) }
    val categoryRepo = remember { CategoryRepository(database.categoryStore()) }
    val valueRepo = remember { FieldValueRepository(database.fieldValueStore()) }
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<List<FieldTemplate>>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var templateToDelete by remember { mutableStateOf<FieldTemplate?>(null) }
    var templateToEdit by remember { mutableStateOf<FieldTemplate?>(null) }
    var menuTemplateId by remember { mutableStateOf<Long?>(null) }
    var draggingKey by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dropPoint by remember { mutableStateOf(Offset.Zero) }
    var hoverKey by remember { mutableStateOf<Long?>(null) }
    val rowBounds = remember { mutableStateMapOf<Long, Rect>() }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var hasPins by remember { mutableStateOf(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun load() {
        scope.launch {
            // 行会随融合/拆行变化，旧几何信息不能留到下轮拖拽
            rowBounds.clear()
            rows = groupFieldRows(templateRepo.getFieldTemplatesByCategory(categoryId).first())
            hasPins = pinRepo.getPinsByCategory(categoryId).first().isNotEmpty()
        }
    }

    /** 重写所有模板的 rowKey 与 sortOrder，顺序即新行序、行内顺序 */
    suspend fun persistRows(newRows: List<List<FieldTemplate>>) {
        var order = 0
        newRows.forEach { row ->
            val key: Long? = if (row.size <= 1) null else (row.first().rowKey ?: row.first().id)
            row.forEach { template ->
                val updated = template.copy(rowKey = key, sortOrder = order)
                order++
                if (updated != template) templateRepo.updateFieldTemplate(updated)
            }
        }
        load()
    }

    fun moveRow(list: List<List<FieldTemplate>>, from: Int, to: Int): List<List<FieldTemplate>> {
        val mutable = list.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to.coerceIn(0, mutable.size), item)
        return mutable
    }

    fun splitRow(row: List<FieldTemplate>) {
        scope.launch {
            row.forEach { template -> templateRepo.updateFieldTemplate(template.copy(rowKey = null)) }
            val list = templateRepo.getFieldTemplatesByCategory(categoryId).first()
            persistRows(groupFieldRows(list))
        }
    }

    fun unmerge(template: FieldTemplate) {
        scope.launch {
            templateRepo.updateFieldTemplate(template.copy(rowKey = null))
            val list = templateRepo.getFieldTemplatesByCategory(categoryId).first()
            persistRows(groupFieldRows(list))
        }
    }

    /** 命中最上层的目标行（排除被拖动的那一行） */
    fun hitTestRow(excludeKey: Long, point: Offset): Long? =
        rowBounds.entries.firstOrNull { it.key != excludeKey && it.value.contains(point) }?.key

    /** 整行换位：把 fromIndex 那行插到 targetKey 那行的前/后 */
    fun reorderRow(current: List<List<FieldTemplate>>, fromIndex: Int, targetKey: Long, insertBefore: Boolean) {
        val newRows = current.toMutableList()
        val moved = newRows.removeAt(fromIndex)
        val targetAt = newRows.indexOfFirst { rowGroupKey(it.first()) == targetKey }
        if (targetAt < 0) return
        newRows.add(if (insertBefore) targetAt else targetAt + 1, moved)
        scope.launch { persistRows(newRows) }
    }

    /** 单字段并入目标行，目标行排在其原有位置 */
    fun mergeIntoRow(current: List<List<FieldTemplate>>, fromIndex: Int, targetIndex: Int) {
        val newRows = current.toMutableList()
        val field = newRows.removeAt(fromIndex).first()
        val targetAt = if (targetIndex > fromIndex) targetIndex - 1 else targetIndex
        newRows[targetAt] = newRows[targetAt] + listOf(field)
        scope.launch {
            persistRows(newRows)
            toast("已并成一行")
        }
    }

    fun finishDrag() {
        val key = draggingKey
        draggingKey = null
        dragOffset = Offset.Zero
        hoverKey = null
        if (key == null) return
        val current = rows
        val fromIndex = current.indexOfFirst { rowGroupKey(it.first()) == key }
        if (fromIndex < 0) return
        val dragged = current[fromIndex]
        val targetKey = hitTestRow(key, dropPoint)
        val targetIndex = if (targetKey == null) -1 else current.indexOfFirst { rowGroupKey(it.first()) == targetKey }

        // 没压在任何卡片上：按中线 Y 找插入位置
        if (targetIndex < 0) {
            val insertAt = current.indices.firstOrNull { i ->
                rowBounds[rowGroupKey(current[i].first())]?.center?.y?.let { it > dropPoint.y } == true
            } ?: current.size
            val to = if (insertAt > fromIndex) insertAt - 1 else insertAt
            if (to != fromIndex) {
                scope.launch { persistRows(moveRow(current, fromIndex, to)) }
            }
            return
        }
        val hitKey = targetKey ?: return
        if (targetIndex == fromIndex) return
        val target = current[targetIndex]
        val targetRect = rowBounds[hitKey] ?: return
        val insertBefore = dropPoint.y < targetRect.center.y
        // 只有拖动框中线进入目标卡片中线区间才允许融合；上下两侧留出来做换行
        val inMergeBand = abs(dropPoint.y - targetRect.center.y) <= targetRect.height * MERGE_BAND_RATIO

        if (dragged.size > 1 || !inMergeBand) {
            reorderRow(current, fromIndex, hitKey, insertBefore)
            return
        }
        when {
            dragged.hasFullRowField() || target.hasFullRowField() -> toast("图片/视频字段需要独占一行")
            target.size >= MAX_FIELDS_PER_ROW -> toast("一行最多 $MAX_FIELDS_PER_ROW 个字段")
            else -> mergeIntoRow(current, fromIndex, targetIndex)
        }
    }

    LaunchedEffect(categoryId) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("字段模板") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!hasPins) {
                                toast("该分类下暂无可导出的标记")
                                return@IconButton
                            }
                            if (!isExporting) {
                                isExporting = true
                                exportProgress = 0f
                                exportJob = scope.launch {
                                    try {
                                        val cat = categoryRepo.getCategoryById(categoryId) ?: return@launch
                                        withContext(Dispatchers.IO) {
                                            PinExporter.exportByCategory(context, database, categoryId,
                                                onProgress = { done, total -> exportProgress = done.toFloat() / total },
                                                fileName = "export_category_${cat.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.pinmap"
                                            )
                                        }.let { uri ->
                                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "application/octet-stream"
                                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(android.content.Intent.createChooser(intent, "导出标记"))
                                        }
                                    } catch (_: Exception) { }
                                    isExporting = false
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "导出本分类标记",
                            tint = if (hasPins) LocalContentColor.current else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (rows.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("还没有字段模板", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(
                        text = if (draggingKey != null) {
                            "拖到卡片中间合并（最多 $MAX_FIELDS_PER_ROW 个），拖到卡片上下边缘则换行"
                        } else {
                            "长按拖动可排序；把一个字段拖到另一个字段上可并成一行（最多 $MAX_FIELDS_PER_ROW 个）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (draggingKey != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(rows, key = { _, row -> rowGroupKey(row.first()) }) { _, row ->
                            val key = rowGroupKey(row.first())
                            val isDragging = draggingKey == key
                            val hovered = hoverKey == key && draggingKey != null
                            val draggedRow = rows.firstOrNull { rowGroupKey(it.first()) == draggingKey }
                            val targetRect = rowBounds[key]
                            val centerOffset = if (targetRect != null) dropPoint.y - targetRect.center.y else 0f
                            val inMergeBand = hovered && targetRect != null &&
                                abs(centerOffset) <= targetRect.height * MERGE_BAND_RATIO
                            val insertBefore = hovered && !inMergeBand && centerOffset < 0
                            // 整行拖动时任何落点都合法；单字段拖进满行或含媒体字段的行才提示不可合并
                            val hoverInvalid = inMergeBand && draggedRow != null && draggedRow.size == 1 &&
                                (row.hasFullRowField() || draggedRow.hasFullRowField() || row.size >= MAX_FIELDS_PER_ROW)
                            val insertLineColor = MaterialTheme.colorScheme.secondary
                            Card(
                                modifier = Modifier
                                    .border(
                                        width = if (hovered) 2.dp else 0.dp,
                                        color = when {
                                            inMergeBand && hoverInvalid -> MaterialTheme.colorScheme.error
                                            inMergeBand -> MaterialTheme.colorScheme.primary
                                            hovered -> MaterialTheme.colorScheme.secondary
                                            else -> Color.Transparent
                                        },
                                        shape = CardDefaults.shape
                                    )
                                    // 换行落点：在卡片上/下边缘画一条提示线
                                    .drawWithContent {
                                        drawContent()
                                        if (hovered && !inMergeBand) {
                                            val h = INSERT_LINE_HEIGHT_DP.dp.toPx()
                                            drawRoundRect(
                                                color = insertLineColor,
                                                topLeft = Offset(0f, if (insertBefore) 0f else size.height - h),
                                                size = Size(size.width, h),
                                                cornerRadius = CornerRadius(h / 2f)
                                            )
                                        }
                                    }
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        translationX = if (isDragging) dragOffset.x else 0f
                                        translationY = if (isDragging) dragOffset.y else 0f
                                    }
                                    .onGloballyPositioned { rowBounds[key] = it.boundsInRoot() }
                                    .pointerInput(key) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingKey = key
                                                dragOffset = Offset.Zero
                                                // 以拖动框自身的中心线判定落点
                                                dropPoint = rowBounds[key]?.center ?: Offset.Zero
                                                hoverKey = null
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount
                                                dropPoint = (rowBounds[key]?.center ?: Offset.Zero) + dragOffset
                                                hoverKey = hitTestRow(key, dropPoint)
                                            },
                                            onDragEnd = {
                                                hoverKey = null
                                                finishDrag()
                                            },
                                            onDragCancel = {
                                                draggingKey = null
                                                dragOffset = Offset.Zero
                                                hoverKey = null
                                            }
                                        )
                                    }
                            ) {
                                if (row.size == 1) {
                                    val template = row.first()
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f).clickable { templateToEdit = template }
                                        ) {
                                            Text(text = template.fieldName, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                text = template.fieldType.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = { templateToDelete = template }) {
                                            Icon(Icons.Default.Close, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                } else {
                                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${row.size} 个字段",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(onClick = { splitRow(row) }) { Text("拆行") }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            row.forEachIndexed { index, template ->
                                                if (index > 0) {
                                                    VerticalDivider(
                                                        modifier = Modifier.height(40.dp),
                                                        color = MaterialTheme.colorScheme.outlineVariant
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                                    Text(
                                                        text = template.fieldName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = template.fieldType.name,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Box {
                                                    IconButton(
                                                        onClick = { menuTemplateId = template.id },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(Icons.Default.MoreVert, contentDescription = "字段操作", modifier = Modifier.size(18.dp))
                                                    }
                                                    DropdownMenu(
                                                        expanded = menuTemplateId == template.id,
                                                        onDismissRequest = { menuTemplateId = null }
                                                    ) {
                                                        DropdownMenuItem(
                                                            text = { Text("编辑") },
                                                            onClick = { menuTemplateId = null; templateToEdit = template }
                                                        )
                                                        DropdownMenuItem(
                                                            text = { Text("拆出本行") },
                                                            onClick = { menuTemplateId = null; unmerge(template) }
                                                        )
                                                        DropdownMenuItem(
                                                            text = { Text("删除") },
                                                            onClick = { menuTemplateId = null; templateToDelete = template }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("添加字段")
                }
            }

            if (isExporting) {
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
                            "正在导出... (${(exportProgress * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { exportJob?.cancel(); isExporting = false },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "取消", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            CreateFieldTemplateDialog(
                onConfirm = { name, type ->
                    scope.launch {
                        val nextOrder = templateRepo.nextSortOrder(categoryId)
                        templateRepo.insertFieldTemplate(FieldTemplate(categoryId = categoryId, fieldName = name, fieldType = type, sortOrder = nextOrder))
                        load()
                    }
                    showAddDialog = false
                },
                onDismiss = { showAddDialog = false }
            )
        }

        templateToEdit?.let { template ->
            var editName by remember { mutableStateOf(template.fieldName) }
            Dialog(onDismissRequest = { templateToEdit = null }) {
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("编辑字段", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("字段名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Text("类型: ${template.fieldType.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { templateToEdit = null }) { Text("取消") }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                scope.launch { templateRepo.updateFieldTemplate(template.copy(fieldName = editName)); load() }
                                templateToEdit = null
                            }, enabled = editName.isNotBlank()) { Text("保存") }
                        }
                    }
                }
            }
        }

        templateToDelete?.let { template ->
            AlertDialog(
                onDismissRequest = { templateToDelete = null },
                title = { Text("删除字段") },
                text = { Text("确定要删除字段「${template.fieldName}」吗？\n\n这将同时删除所有标记中该字段的值。") },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            // 先删该模板的所有字段值，再删模板本身
                            valueRepo.deleteFieldValuesByTemplate(template.id)
                            templateRepo.deleteFieldTemplate(template)
                            load()
                        }
                        templateToDelete = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除") }
                },
                dismissButton = { TextButton(onClick = { templateToDelete = null }) { Text("取消") } }
            )
        }
    }
}
