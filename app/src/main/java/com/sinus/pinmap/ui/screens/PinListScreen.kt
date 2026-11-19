package com.sinus.pinmap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sinus.pinmap.data.database.PinmapDatabase
import com.sinus.pinmap.data.entity.MarkKind
import com.sinus.pinmap.data.entity.Pin
import com.sinus.pinmap.data.repository.CategoryRepository
import com.sinus.pinmap.data.repository.FieldTemplateRepository
import com.sinus.pinmap.data.repository.FieldValueRepository
import com.sinus.pinmap.data.repository.PinRepository
import com.sinus.pinmap.ui.color
import com.sinus.pinmap.ui.components.MediaImageView
import com.sinus.pinmap.ui.icon
import com.sinus.pinmap.ui.label
import com.sinus.pinmap.ui.utils.LocationManager
import com.sinus.pinmap.ui.utils.MediaFileHelper
import com.sinus.pinmap.ui.utils.haversineDistance
import com.sinus.pinmap.ui.viewmodel.PinListViewModel
import com.sinus.pinmap.ui.viewmodel.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinListScreen(
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    onPinClick: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val database = remember { PinmapDatabase.getDatabase(context) }
    val pinRepository = remember { PinRepository(database.pinStore()) }
    val categoryRepository = remember { CategoryRepository(database.categoryStore()) }
    val valueRepository = remember { FieldValueRepository(database.fieldValueStore()) }
    val templateRepository = remember { FieldTemplateRepository(database.fieldTemplateStore()) }
    val viewModel: PinListViewModel = viewModel { PinListViewModel(pinRepository, categoryRepository, valueRepository, templateRepository) }
    val scope = rememberCoroutineScope()

    val pins by viewModel.filteredPins.collectAsState()
    val categories by viewModel._categories.collectAsState()
    val searchQuery by viewModel._searchQuery.collectAsState()
    val selectedCategoryId by viewModel._selectedCategoryId.collectAsState()
    val markFilter by viewModel._markFilter.collectAsState()
    val sortMode by viewModel._sortMode.collectAsState()
    val currentLat by viewModel.currentLat.collectAsState()
    val currentLng by viewModel.currentLng.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<Pin?>(null) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            val locationManager = LocationManager(context)
            if (locationManager.hasLocationPermission()) {
                val (loc, _) = locationManager.getLastLocation()
                viewModel.setCurrentLocation(loc.latitude, loc.longitude)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("搜索标记...") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = if (searchQuery.isNotBlank()) {
                {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "清除搜索")
                    }
                }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true
        )

        // 分类筛选 + 排序
        if (categories.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MarkKindFilterChip(
                        kind = MarkKind.MUST_EAT,
                        selected = markFilter == MarkKind.MUST_EAT,
                        onClick = { viewModel.setMarkFilter(MarkKind.MUST_EAT) }
                    )
                    MarkKindFilterChip(
                        kind = MarkKind.WATCHED,
                        selected = markFilter == MarkKind.WATCHED,
                        onClick = { viewModel.setMarkFilter(MarkKind.WATCHED) }
                    )
                    FilterChip(
                        selected = selectedCategoryId == null,
                        onClick = { viewModel.setSelectedCategory(null) },
                        label = { Text("全部") },
                                        modifier = Modifier.height(FILTER_CHIP_HEIGHT)
                                    )

                                    categories.forEach { category ->
                                        FilterChip(
                                            selected = selectedCategoryId == category.id,
                                            onClick = { viewModel.setSelectedCategory(category.id) },
                                            label = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(12.dp)
                                                            .background(Color(category.color), CircleShape)
                                                    )
                                                    Text(category.name)
                                                }
                                            },
                                            modifier = Modifier.height(FILTER_CHIP_HEIGHT)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Box {
                    var sortExpanded by remember { mutableStateOf(false) }
                    val sortLabel = when (sortMode) {
                        SortMode.CREATED_DESC -> "最新"
                        SortMode.NAME_ASC -> "名称↑"
                        SortMode.NAME_DESC -> "名称↓"
                        SortMode.DISTANCE_ASC -> "最近"
                    }
                    TextButton(
                        onClick = { sortExpanded = true },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(sortLabel, style = MaterialTheme.typography.labelMedium)
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("最近") },
                            onClick = { viewModel.setSortMode(SortMode.DISTANCE_ASC); sortExpanded = false },
                            leadingIcon = if (sortMode == SortMode.DISTANCE_ASC) {{ Icon(Icons.Default.Check, contentDescription = null) }} else null
                        )
                        DropdownMenuItem(
                            text = { Text("名称↑") },
                            onClick = { viewModel.setSortMode(SortMode.NAME_ASC); sortExpanded = false },
                            leadingIcon = if (sortMode == SortMode.NAME_ASC) {{ Icon(Icons.Default.Check, contentDescription = null) }} else null
                        )
                        DropdownMenuItem(
                            text = { Text("名称↓") },
                            onClick = { viewModel.setSortMode(SortMode.NAME_DESC); sortExpanded = false },
                            leadingIcon = if (sortMode == SortMode.NAME_DESC) {{ Icon(Icons.Default.Check, contentDescription = null) }} else null
                        )
                        DropdownMenuItem(
                            text = { Text("最新") },
                            onClick = { viewModel.setSortMode(SortMode.CREATED_DESC); sortExpanded = false },
                            leadingIcon = if (sortMode == SortMode.CREATED_DESC) {{ Icon(Icons.Default.Check, contentDescription = null) }} else null
                        )
                    }
                }
            }
        }

        if (pins.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank() || selectedCategoryId != null) {
                            "没有找到标记"
                        } else {
                            "还没有标记"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = if (searchQuery.isNotBlank() || selectedCategoryId != null) {
                            "尝试更改搜索或筛选条件"
                        } else {
                            "在地图上长按创建第一个标记"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pins) { pin ->
                    PinItem(
                        pin = pin,
                        category = categories.find { it.id == pin.categoryId },
                        distanceKm = haversineDistance(
                            currentLat, currentLng,
                            pin.latitude, pin.longitude
                        ) / 1000f,
                        onClick = { onPinClick(pin.id) },
                        onSetMark = { kind -> viewModel.setMarkKind(pin, kind) },
                        onDelete = { showDeleteDialog = pin }
                    )
                }
            }
        }
    }

    showDeleteDialog?.let { pin ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除标记") },
            text = {
                Text("确定要删除标记「${pin.title}」吗？\n\n此操作不可恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val refs = buildList {
                                pin.avatarPath?.let { add(it) }
                                valueRepository.getFieldValuesByPin(pin.id).first().forEach { add(it.value ?: "") }
                            }
                            if (refs.isNotEmpty()) {
                                withContext(Dispatchers.IO) {
                                    MediaFileHelper.deleteFiles(context, refs)
                                }
                            }
                        }
                        viewModel.deletePin(pin)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}

private val FILTER_CHIP_HEIGHT = 36.dp

@Composable
private fun MarkKindFilterChip(
    kind: MarkKind,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    kind.icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (selected) kind.color else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(kind.label)
            }
        },
        modifier = Modifier.height(FILTER_CHIP_HEIGHT)
    )
}

private val PIN_AVATAR_SIZE = 48.dp
private val DISTANCE_GREEN = Color(0xFF4CAF50)

@Composable
private fun PinItem(
    pin: Pin,
    category: com.sinus.pinmap.data.entity.Category?,
    distanceKm: Float,
    onClick: () -> Unit,
    onSetMark: (MarkKind) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (pin.avatarPath != null) {
                    MediaImageView(
                        uri = if (pin.avatarPath.startsWith("/")) pin.avatarPath else "file://${pin.avatarPath}",
                        modifier = Modifier.size(PIN_AVATAR_SIZE),
                        shape = CircleShape,
                        scale = pin.avatarScale,
                        offsetX = pin.avatarOffsetX,
                        offsetY = pin.avatarOffsetY
                    )
                } else if (category != null) {
                    Box(
                        modifier = Modifier
                            .size(PIN_AVATAR_SIZE)
                            .background(Color(category.color), CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(PIN_AVATAR_SIZE)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape
                            )
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = pin.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (distanceKm < 1f) "<1km" else "%.1fkm".format(distanceKm),
                            style = MaterialTheme.typography.bodySmall,
                                    color = DISTANCE_GREEN
                        )
                    }
                    pin.address?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Box {
                var showMarkMenu by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { showMarkMenu = true },
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = pin.markKind.color
                    )
                ) {
                    Icon(
                        pin.markKind.icon,
                        contentDescription = "标记状态：${pin.markKind.label}"
                    )
                }
                DropdownMenu(
                    expanded = showMarkMenu,
                    onDismissRequest = { showMarkMenu = false }
                ) {
                    MarkKind.entries.forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.label) },
                            onClick = {
                                showMarkMenu = false
                                if (kind != pin.markKind) onSetMark(kind)
                            },
                            leadingIcon = {
                                Icon(kind.icon, contentDescription = null, tint = kind.color)
                            },
                            trailingIcon = if (pin.markKind == kind) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }
            }

            IconButton(
                onClick = onDelete,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = "删除标记")
            }
        }
    }
}
