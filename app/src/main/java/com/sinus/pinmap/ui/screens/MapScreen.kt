package com.sinus.pinmap.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amap.api.maps.CameraUpdateFactory
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.services.poisearch.PoiSearchV2
import com.sinus.pinmap.data.database.PinmapDatabase
import com.sinus.pinmap.data.entity.FieldType
import com.sinus.pinmap.data.entity.MarkKind
import com.sinus.pinmap.data.entity.Pin
import com.sinus.pinmap.data.repository.PinRepository
import com.sinus.pinmap.ui.color
import com.sinus.pinmap.ui.icon
import com.sinus.pinmap.ui.label
import com.sinus.pinmap.ui.utils.LocationManager
import com.sinus.pinmap.ui.viewmodel.MapHolderViewModel
import com.sinus.pinmap.ui.viewmodel.MapViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import com.amap.api.services.core.PoiItemV2
import com.amap.api.services.poisearch.PoiResultV2
import com.sinus.pinmap.data.repository.CategoryRepository
import com.sinus.pinmap.data.repository.FieldTemplateRepository
import com.sinus.pinmap.data.repository.FieldValueRepository
import com.sinus.pinmap.ui.utils.AiSearchClient
import com.sinus.pinmap.ui.utils.AppSettings
import com.sinus.pinmap.ui.utils.AuthState

/**
 * 地图页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("ClickableViewAccessibility", "LocalContextResourcesRead")
@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    onNavigateToEdit: (Long) -> Unit = {},
    onNavigateToCreate: (Double, Double) -> Unit = { _, _ -> },
    focusLat: Double? = null,
    focusLng: Double? = null
) {
    val context = LocalContext.current
    val database = remember { PinmapDatabase.getDatabase(context) }
    val pinRepository = remember { PinRepository(database.pinStore()) }
    val categoryRepository =
        remember { CategoryRepository(database.categoryStore()) }
    val fieldTemplateRepository =
        remember { FieldTemplateRepository(database.fieldTemplateStore()) }
    val fieldValueRepository =
        remember { FieldValueRepository(database.fieldValueStore()) }
    val viewModel: MapViewModel =
        viewModel { MapViewModel(pinRepository, fieldTemplateRepository, fieldValueRepository) }
    val mapHolder: MapHolderViewModel = viewModel()

    val pins by viewModel._pins.collectAsState()
    val categories by categoryRepository.getAllCategories().collectAsState(initial = emptyList())
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
    var markFilter by remember { mutableStateOf<MarkKind?>(null) }
    val filteredPins by remember(pins, selectedCategoryId, markFilter) {
        derivedStateOf {
            var result = if (selectedCategoryId == null) pins else pins.filter { it.categoryId == selectedCategoryId }
            if (markFilter != null) {
                result = result.filter { it.markKind == markFilter }
            }
            result
        }
    }

    // pinId -> 标记的绘制层级。显式指定才能判断重叠标记里谁画在最上面
    var markerZIndex by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
    val markerZCounter = remember { AtomicLong(0L) }

    // pinId -> 非媒体字段值文本（搜索匹配用）
    var fieldTextByPin by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    // 加载非媒体字段值文本，用于搜索匹配（字段值变化即重建，模板每次重读）
    LaunchedEffect(Unit) {
        fieldValueRepository.getAllFieldValues().collect { values ->
            val templates = fieldTemplateRepository.getAllFieldTemplates().first()
            val textTemplateIds = templates
                .filter { it.fieldType != FieldType.IMAGE && it.fieldType != FieldType.VIDEO }
                .map { it.id }
                .toSet()
            fieldTextByPin = values
                .filter { it.fieldTemplateId in textTemplateIds && !it.value.isNullOrBlank() }
                .groupBy { it.pinId }
                .mapValues { (_, fvs) -> fvs.joinToString(" ") { it.value ?: "" } }
            Log.d("Sinus-Map", "fieldTextByPin built: templates=$textTemplateIds, values=${values.size}, map=${fieldTextByPin}")
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var showSearchResults by remember { mutableStateOf(false) }
    var aiResults by remember { mutableStateOf<List<Pin>>(emptyList()) }
    var isAiSearching by remember { mutableStateOf(false) }
    var aiSearchError by remember { mutableStateOf<String?>(null) }
    var aiSearchJob by remember { mutableStateOf<Job?>(null) }
    var aiQuery by remember { mutableStateOf("") }

    fun cancelAiSearch() {
        aiSearchJob?.cancel()
        aiSearchJob = null
        isAiSearching = false
        aiResults = emptyList()
        aiSearchError = null
        aiQuery = ""
    }

    // 查询词变化时清空上一次的 AI 结果，避免残留
    LaunchedEffect(searchQuery) {
        if (searchQuery != aiQuery) {
            aiSearchJob?.cancel()
            aiSearchJob = null
            isAiSearching = false
            aiResults = emptyList()
            aiSearchError = null
        }
    }

    // 键盘控制器和焦点请求器
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }

    // 搜索结果
    var poiResults by remember { mutableStateOf<List<PoiItemV2>>(emptyList()) }

    val searchResults by remember(searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                pins.filter {
                    it.title.contains(searchQuery, ignoreCase = true) ||
                            (it.description?.contains(searchQuery, ignoreCase = true) == true) ||
                            (fieldTextByPin[it.id]?.contains(searchQuery, ignoreCase = true) == true)
                }
            }
        }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
            try {
                val query = PoiSearchV2.Query(searchQuery, "", null)
                query.pageSize = 10
                query.pageNum = 0
                val search = PoiSearchV2(context, query)
                search.setOnPoiSearchListener(object : PoiSearchV2.OnPoiSearchListener {
                    override fun onPoiSearched(result: PoiResultV2?, code: Int) {
                        if (code == 1000) {
                            poiResults = result?.pois ?: emptyList()
                        }
                    }

                    override fun onPoiItemSearched(item: PoiItemV2?, code: Int) {}
                })
                search.searchPOIAsyn()
            } catch (e: Exception) {
                Log.e("Sinus-Map", "POI search error", e)
            }
        } else {
            poiResults = emptyList()
        }
    }

    // 位置管理器
    val locationManager = remember { LocationManager(context) }

    // 记住 MapView 实例
    val mapView = mapHolder.init(context)

    var myLocationMarker by remember { mutableStateOf<Marker?>(null) }
    var markerMap by remember { mutableStateOf<Map<Long, Marker>>(emptyMap()) }
    var viewerPin by remember { mutableStateOf<Pin?>(null) }
    var highlightedPinId by remember { mutableStateOf<Long?>(null) }
    var previousHighlightedPinId by remember { mutableStateOf<Long?>(null) }
    var renderedPins by remember { mutableStateOf<Map<Long, Pin>>(emptyMap()) }
    var poiHighlightLat by remember { mutableDoubleStateOf(0.0) }
    var poiHighlightLng by remember { mutableDoubleStateOf(0.0) }
    var poiHighlightMarker by remember { mutableStateOf<com.amap.api.maps.model.Marker?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    // 位置权限请求
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }


    // 在地图初始化时请求权限
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // 初始化地图位置
    LaunchedEffect(mapView) {
        if (!mapHolder._isInitialized) {
            val aMap = mapView.map ?: return@LaunchedEffect
            mapHolder.setAMap(aMap)

            // 基本配置
            aMap.mapType = com.amap.api.maps.AMap.MAP_TYPE_NORMAL

            // 启用手势
            val uiSettings = aMap.uiSettings
            uiSettings.isZoomControlsEnabled = true
            uiSettings.isZoomGesturesEnabled = true
            uiSettings.isScrollGesturesEnabled = true
            uiSettings.isCompassEnabled = true

            // 尝试获取当前位置
            val locationResult = locationManager.getCurrentLocation()

            val (targetLocation, zoom) = if (locationResult.isSuccess) {
                // 定位成功，使用当前位置
                locationResult.getOrNull()!! to ZOOM_LOCATION
            } else {
                // 定位失败，使用上次保存的位置
                locationManager.getLastLocation()
            }

            // 移动地图到目标位置
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(targetLocation, zoom))
            mapHolder.markInitialized()
        }
    }

    // 管理生命周期
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    try {
                        mapView.onResume()
                    } catch (e: Exception) {
                        // 忽略异常
                        Log.e("Sinus-Map", "MapScreen: $e")
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    try {
                        mapView.onPause()
                    } catch (e: Exception) {
                        Log.e("Sinus-Map", "MapScreen: $e")
                    }
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(focusLat, focusLng) {
        if (focusLat != null && focusLng != null) {
            mapHolder._aMap?.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    com.amap.api.maps.model.LatLng(focusLat, focusLng), ZOOM_LOCATION
                )
            )
        }
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    var searchBottom by remember { mutableStateOf(16.dp) }
    LaunchedEffect(imeInsets) {
        snapshotFlow { imeInsets.getBottom(density) }
            .collect { imePx ->
                searchBottom = if (imePx > 0) {
                    (with(density) { imePx.toDp() } - NAV_BAR_GAP).coerceAtLeast(0.dp)
                } else {
                    16.dp
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView }
        )

        // 设置地图事件监听
        LaunchedEffect(mapHolder._aMap) {
            val aMap = mapHolder._aMap ?: return@LaunchedEffect

            // 设置地图点击事件（用于取消选择）
            aMap.setOnMapClickListener { _ ->
                viewModel.clearSelectedPin()
            }

            // 设置地图长按事件（用于创建标记）
            aMap.setOnMapLongClickListener { latLng ->
                if (AuthState.isAmapKeyValid(context)) {
                    onNavigateToCreate(latLng.latitude, latLng.longitude)
                } else {
                    Toast.makeText(context, "API Key 验证失败，无法创建标记", Toast.LENGTH_SHORT).show()
                }
            }

            // 设置标记点击事件
            aMap.setOnMarkerClickListener { marker ->
                marker.hideInfoWindow()
                val clickedId = marker.snippet?.toLongOrNull() ?: return@setOnMarkerClickListener false
                // 高德在多个标记重叠时，把点击派发给叠在最下面的那个。标记图标是固定像素尺寸、
                // 不随缩放变小，地图一缩小，屏幕上就会有一团互相压住的标记，点到的往往不是眼睛看到的最上面那个。
                // 这里在屏幕坐标里找出与它重叠的标记，按绘制层级取最上面的一枚，保证"看到的是谁，点开的就是谁"。
                val density = context.resources.displayMetrics.density
                val pinId = topMostOverlappingPinId(
                    aMap = aMap,
                    anchor = marker.position,
                    pins = filteredPins,
                    zIndexByPin = markerZIndex,
                    widthPx = MARKER_SIZE_PX * density,
                    heightPx = (MARKER_SIZE_PX + ARROW_HEIGHT_PX) * density
                ) ?: clickedId
                val pin = pins.find { it.id == pinId }
                if (pin?.avatarPath != null) {
                    viewerPin = pin
                } else {
                    onNavigateToEdit(pinId)
                }
                true
            }

            // 设置标记拖拽事件（长按拖拽）
            aMap.setOnMarkerDragListener(object : com.amap.api.maps.AMap.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: com.amap.api.maps.model.Marker?) {
                    isDragging = true
                }
                override fun onMarkerDrag(marker: com.amap.api.maps.model.Marker?) {}
                override fun onMarkerDragEnd(marker: com.amap.api.maps.model.Marker?) {
                    val id = marker?.snippet?.toLongOrNull() ?: return
                    val pos = marker.position
                    scope.launch {
                        pinRepository.getPinById(id)?.let { pin ->
                            val address = try {
                                kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                                    val search = com.amap.api.services.geocoder.GeocodeSearch(context)
                                    search.setOnGeocodeSearchListener(object : com.amap.api.services.geocoder.GeocodeSearch.OnGeocodeSearchListener {
                                        override fun onRegeocodeSearched(res: com.amap.api.services.geocoder.RegeocodeResult?, code: Int) {
                                            cont.resume(if (code == 1000 && res != null) res.regeocodeAddress.formatAddress else null, null)
                                        }
                                        override fun onGeocodeSearched(res: com.amap.api.services.geocoder.GeocodeResult?, code: Int) {}
                                    })
                                    search.getFromLocationAsyn(
                                        com.amap.api.services.geocoder.RegeocodeQuery(
                                            com.amap.api.services.core.LatLonPoint(pos.latitude, pos.longitude),
                                            200f, com.amap.api.services.geocoder.GeocodeSearch.AMAP
                                        )
                                    )
                                }
                            } catch (_: Exception) { null }
                            pinRepository.updatePin(
                                pin.copy(
                                    latitude = pos.latitude,
                                    longitude = pos.longitude,
                                    address = address ?: pin.address
                                )
                            )
                        }
                    }
                    isDragging = false
                }
            })

            // 监听地图移动，保存位置
            aMap.setOnCameraChangeListener(object : com.amap.api.maps.AMap.OnCameraChangeListener {
                override fun onCameraChange(cameraPosition: com.amap.api.maps.model.CameraPosition?) {}
                override fun onCameraChangeFinish(cameraPosition: com.amap.api.maps.model.CameraPosition?) {
                    cameraPosition?.let {
                        scope.launch {
                            locationManager.saveLastLocation(
                                it.target.latitude,
                                it.target.longitude,
                                it.zoom
                            )
                        }
                    }
                }
            })
        }

        // 底部面板：搜索栏 + 搜索结果
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = searchBottom)
        ) {
            // 搜索结果列表：AI 结果只在"查询词与当前输入一致"时显示，避免残留
            val aiSearchActive = aiQuery.isNotBlank() && searchQuery == aiQuery
            if (showSearchResults && (aiSearchActive || searchResults.isNotEmpty() || poiResults.isNotEmpty())) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = SEARCH_RESULT_MAX_HEIGHT)
                    ) {
                        if (isAiSearching) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text("AI 搜索中...", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else if (aiSearchError != null) {
                            item {
                                Text(
                                    aiSearchError ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        } else if (aiResults.isNotEmpty()) {
                            item {
                                Text(
                                    "AI 搜索结果",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            items(aiResults) { pin ->
                                SearchResultItem(
                                    title = pin.title,
                                    subtitle = pin.address,
                                    onClick = {
                                        scope.launch {
                                            val aMap = mapHolder._aMap ?: return@launch
                                            highlightedPinId = pin.id
                                            aMap.animateCamera(
                                                CameraUpdateFactory.newLatLngZoom(
                                                    LatLng(pin.latitude, pin.longitude), ZOOM_SEARCH
                                                )
                                            )
                                            cancelAiSearch()
                                            showSearchResults = false; keyboardController?.hide()
                                        }
                                    }
                                )
                            }
                        } else if (searchResults.isNotEmpty()) {
                            item {
                                Text(
                                    "标记",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 4.dp
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            items(searchResults) { pin ->
                                SearchResultItem(
                                    title = pin.title,
                                    subtitle = pin.address,
                                    onClick = {
                                        scope.launch {
                                            val aMap = mapHolder._aMap ?: return@launch
                                            highlightedPinId = pin.id
                                            aMap.animateCamera(
                                                CameraUpdateFactory.newLatLngZoom(
                                                    LatLng(pin.latitude, pin.longitude), ZOOM_SEARCH
                                                )
                                            )
                                            cancelAiSearch()
                                            showSearchResults = false
                                            keyboardController?.hide()
                                        }
                                    }
                                )
                            }
                        }
                        if (poiResults.isNotEmpty()) {
                            item {
                                Text(
                                    "地址",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 4.dp
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            items(poiResults) { poi ->
                                SearchResultItem(
                                    title = poi.title,
                                    subtitle = poi.snippet,
                                    onClick = {
                                        val aMap = mapHolder._aMap
                                        if (aMap != null) {
                                            poiHighlightLat = poi.latLonPoint.latitude
                                            poiHighlightLng = poi.latLonPoint.longitude
                                            aMap.animateCamera(
                                                CameraUpdateFactory.newLatLngZoom(
                                                    LatLng(
                                                        poi.latLonPoint.latitude,
                                                        poi.latLonPoint.longitude
                                                    ), ZOOM_SEARCH
                                                )
                                            )
                                            cancelAiSearch()
                                            showSearchResults = false
                                            keyboardController?.hide()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 搜索栏
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { newValue: String ->
                            searchQuery = newValue
                            showSearchResults = newValue.isNotBlank()
                            if (newValue.isBlank()) {
                                cancelAiSearch()
                            }
                        },
                        placeholder = { Text("搜索标记...") },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocusRequester),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = {
                                // 手动触发搜索：展开结果列表
                                if (searchQuery.isNotBlank()) {
                                    showSearchResults = true
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )

                    if (searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = {
                                cancelAiSearch()
                                searchQuery = ""
                                showSearchResults = false
                            }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "清除搜索")
                        }
                    }

                    if (searchQuery.isNotBlank() && AppSettings.isAiSearchEnabled()) {
                        IconButton(
                            onClick = {
                                aiQuery = searchQuery
                                aiSearchJob = scope.launch {
                                    isAiSearching = true
                                    aiSearchError = null
                                    showSearchResults = true
                                    try {
                                        // 加载动态字段：全部字段模板 + 每个 pin 的字段值
                                        val allTemplates = fieldTemplateRepository.getAllFieldTemplates().first()
                                        val templatesById = allTemplates.associateBy { it.id }
                                        val fieldValuesByPin = pins.associate { pin ->
                                            pin.id to fieldValueRepository.getFieldValuesByPin(pin.id).first()
                                        }
                                        val result = AiSearchClient.search(
                                            query = searchQuery,
                                            pins = pins,
                                            categories = categories,
                                            fieldValuesByPin = fieldValuesByPin,
                                            templatesById = templatesById
                                        )
                                        val idSet = result.pinIds.toSet()
                                        aiResults = pins.filter { it.id in idSet }
                                            .sortedBy { result.pinIds.indexOf(it.id) }
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        aiSearchError = "AI 搜索失败：${e.message}"
                                        aiResults = emptyList()
                                    }
                                    isAiSearching = false
                                }
                            },
                            enabled = !isAiSearching
                        ) {
                            if (isAiSearching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(ICON_SIZE_20),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Star, contentDescription = "AI 搜索")
                            }
                        }
                    }
                }
            }
        }

        // 分类筛选按钮 - 左下角
        var showCategoryFilter by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = searchBottom + FAB_OFFSET)
        ) {
            SmallFloatingActionButton(
                onClick = { showCategoryFilter = true },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(FAB_CORNER_RADIUS),
                modifier = Modifier.size(FAB_SIZE)
            ) {
                Icon(Icons.Default.Menu, contentDescription = "筛选分类", modifier = Modifier.size(FAB_ICON_SIZE))
            }
            DropdownMenu(
                expanded = showCategoryFilter,
                onDismissRequest = { showCategoryFilter = false }
            ) {
                DropdownMenuItem(
                    text = { Text("全部") },
                    onClick = {
                        selectedCategoryId = null
                        markFilter = null
                        showCategoryFilter = false
                    },
                    leadingIcon = if (selectedCategoryId == null && markFilter == null) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null
                )
                MarkKind.entries.filter { it != MarkKind.NONE }.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kind.label) },
                        onClick = {
                            markFilter = if (markFilter == kind) null else kind
                            showCategoryFilter = false
                        },
                        leadingIcon = {
                            Icon(
                                kind.icon,
                                contentDescription = null,
                                tint = kind.color
                            )
                        },
                        trailingIcon = if (markFilter == kind) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                }
                categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name) },
                        onClick = { selectedCategoryId = category.id; showCategoryFilter = false },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(Color(category.color), CircleShape)
                            )
                        },
                        trailingIcon = if (selectedCategoryId == category.id) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                }
            }
        }

        // 定位按钮 - 放在搜索框右侧
        SmallFloatingActionButton(
            onClick = {
                if (!hasLocationPermission) {
                    // 没有权限，请求权限
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                } else {
                    // 有权限，执行定位
                    scope.launch {
                        val locationResult = locationManager.getCurrentLocation()
                        if (locationResult.isSuccess) {
                            val location = locationResult.getOrNull()
                            if (location != null) {
                                val aMap = mapHolder._aMap ?: return@launch

                                // 移除旧的当前位置标记
                                myLocationMarker?.remove()

                                // 添加新的当前位置标记
                                val markerOptions = MarkerOptions()
                                    .position(location)
                                    .title("当前位置")
                    .draggable(false)
                                    .snippet("你在当前位置")

                                val marker = aMap.addMarker(markerOptions)
                                myLocationMarker = marker

                                // 移动地图到当前位置
                                aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(location, ZOOM_LOCATION))
                            }
                        }
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = searchBottom + FAB_OFFSET)
                .size(FAB_SIZE),
            shape = RoundedCornerShape(FAB_CORNER_RADIUS),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = "定位到当前位置", modifier = Modifier.size(FAB_ICON_SIZE))
        }

        // 监听 pins 变化，更新地图标记
        LaunchedEffect(filteredPins, categories, highlightedPinId) {
            val aMap = mapHolder._aMap ?: return@LaunchedEffect
            if (isDragging) {
                return@LaunchedEffect
            }

            val currentIds = filteredPins.map { it.id }.toSet()
            val oldIds = markerMap.keys

            val newZIndex = markerZIndex.toMutableMap()
            // 移除已删除的标记
            oldIds.subtract(currentIds).forEach { id ->
                markerMap[id]?.remove()
            }
            // 每个 pin 只建一个标记，之后原地改位置和图标，不删了重建——绘制顺序才不会因为重建而跳动
            fun buildMarkerIcon(pin: Pin): com.amap.api.maps.model.BitmapDescriptor {
                val color = categories.find { it.id == pin.categoryId }?.color ?: DEFAULT_PIN_COLOR
                val label = pin.title.take(1)

                val avatarBitmap = pin.avatarPath?.let { path ->
                    try {
                        val filePath =
                            if (path.startsWith("file://")) path.toUri().path else path
                        filePath?.let { BitmapFactory.decodeFile(it) }
                    } catch (_: Exception) {
                        null
                    }
                }

                val size = context.resources.displayMetrics.density.let { d -> (MARKER_SIZE_PX * d).toInt() }
                val arrowH = (ARROW_HEIGHT_PX * context.resources.displayMetrics.density).toInt()
                val totalH = size + arrowH
                val bubble = createBitmap(size, totalH)
                val canvas = Canvas(bubble)

                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = color

                if (avatarBitmap != null) {
                    val cx = size / 2f
                    val cy = size / 2f
                    val r = size / 2f - PADDING_4F
                    val minDim = minOf(avatarBitmap.width, avatarBitmap.height)
                    val srcX = ((avatarBitmap.width - minDim) / 2f).toInt()
                    val srcY = ((avatarBitmap.height - minDim) / 2f).toInt()
                    val srcRect = android.graphics.Rect(srcX, srcY, srcX + minDim, srcY + minDim)
                    val dstRect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
                    val clipPath = android.graphics.Path()
                        .apply { addCircle(cx, cy, r, android.graphics.Path.Direction.CW) }
                    canvas.save()
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(avatarBitmap, srcRect, dstRect, null)
                    canvas.restore()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = STROKE_WIDTH_AVATAR
                    paint.color = COLOR_WHITE_INT
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.FILL
                } else {
                    val bodyB = totalH - arrowH - PADDING_4F
                    val rect = android.graphics.RectF(PADDING_4F, PADDING_4F, size - PADDING_4F, bodyB)
                    canvas.drawRoundRect(rect, CORNER_RADIUS_BODY, CORNER_RADIUS_BODY, paint)
                    val cx = size / 2f
                    val cy = bodyB / 2f
                    val r = bodyB / 2f - 8f
                    if (label.isNotEmpty()) {
                        paint.color = COLOR_WHITE_INT
                        paint.textSize = r * TEXT_SIZE_MULTIPLIER
                        paint.textAlign = Paint.Align.CENTER
                        paint.typeface = Typeface.DEFAULT_BOLD
                        canvas.drawText(label, cx, cy + paint.textSize / 3, paint)
                    }
                }

                // Arrow
                paint.color = color
                val cx = size / 2f
                val arrowPath = android.graphics.Path().apply {
                    val arrowTop = if (avatarBitmap != null) size - PADDING_4F else totalH - arrowH - PADDING_4F
                    moveTo(cx - ARROW_HALF_WIDTH, arrowTop)
                    lineTo(cx + ARROW_HALF_WIDTH, arrowTop)
                    lineTo(cx, totalH - PADDING_4F)
                    close()
                }
                canvas.drawPath(arrowPath, paint)

                // 高亮边框
                if (pin.id == highlightedPinId) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = STROKE_WIDTH_HIGHLIGHT
                    paint.color = HIGHLIGHT_RED
                    if (avatarBitmap != null) {
                        val r = size / 2f - PADDING_4F
                        canvas.drawCircle(size / 2f, size / 2f, r + PADDING_2F, paint)
                    } else {
                        val bodyB = totalH - arrowH - PADDING_4F
                        val rect = android.graphics.RectF(PADDING_2F, PADDING_2F, size - PADDING_2F, bodyB + PADDING_2F)
                        canvas.drawRoundRect(rect, CORNER_RADIUS_HIGHLIGHT, CORNER_RADIUS_HIGHLIGHT, paint)
                    }
                    paint.style = Paint.Style.FILL
                }

                // 三态标记的小角标（必吃橙、关注蓝）
                if (pin.markKind != MarkKind.NONE) {
                    val badgeR = size * MARKER_BADGE_RADIUS_RATIO
                    val badgeCx = size - badgeR - PADDING_2F
                    val badgeCy = if (avatarBitmap != null) badgeR + PADDING_2F else badgeR + PADDING_4F
                    paint.style = Paint.Style.FILL
                    paint.color = if (pin.markKind == MarkKind.MUST_EAT) MARKER_MUST_EAT_COLOR else MARKER_WATCHED_COLOR
                    canvas.drawCircle(badgeCx, badgeCy, badgeR, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = MARKER_BADGE_STROKE
                    paint.color = COLOR_WHITE_INT
                    canvas.drawCircle(badgeCx, badgeCy, badgeR, paint)
                    paint.style = Paint.Style.FILL
                }

                return BitmapDescriptorFactory.fromBitmap(bubble)
            }

            val newMap = markerMap.toMutableMap()
            filteredPins.forEach { pin ->
                val oldMarker = markerMap[pin.id]
                val prevPin = renderedPins[pin.id]
                val pinChanged = prevPin == null || prevPin.title != pin.title ||
                    prevPin.avatarPath != pin.avatarPath ||
                    prevPin.categoryId != pin.categoryId ||
                    prevPin.markKind != pin.markKind ||
                    prevPin.latitude != pin.latitude ||
                    prevPin.longitude != pin.longitude
                val highlightChanged = pin.id == highlightedPinId || pin.id == previousHighlightedPinId
                if (oldMarker != null) {
                    if (pinChanged || highlightChanged) {
                        oldMarker.setIcon(buildMarkerIcon(pin))
                        oldMarker.position = LatLng(pin.latitude, pin.longitude)
                    }
                    newMap[pin.id] = oldMarker
                } else {
                    // 层级只在标记首次出现时分配，之后（过滤、重建、重新出现）都沿用
                    val zIndex = markerZIndex[pin.id] ?: markerZCounter.incrementAndGet().toFloat()
                    val marker = aMap.addMarker(
                        MarkerOptions()
                            .position(LatLng(pin.latitude, pin.longitude))
                            .icon(buildMarkerIcon(pin))
                            .snippet(pin.id.toString())
                            .draggable(true)
                            .anchor(0.5f, 1.0f)
                            .zIndex(zIndex)
                    )
                    marker?.let {
                        newMap[pin.id] = it
                        newZIndex[pin.id] = zIndex
                    }
                }
            }
            markerMap = newMap
            markerZIndex = newZIndex
            renderedPins = filteredPins.associateBy { it.id }
            previousHighlightedPinId = highlightedPinId
        }
    }

    // POI 地址搜索结果高亮
    LaunchedEffect(poiHighlightLat, poiHighlightLng) {
        val aMap = mapHolder._aMap ?: return@LaunchedEffect
        if (poiHighlightLat != 0.0 || poiHighlightLng != 0.0) {
            // 移除上一次的高亮标记，避免堆积
            poiHighlightMarker?.remove()
            poiHighlightMarker = aMap.addMarker(
                MarkerOptions()
                    .position(LatLng(poiHighlightLat, poiHighlightLng))
                    .title("搜索结果")
                    .zIndex(998f)
            )
        }
    }

    viewerPin?.let { pin ->
        Dialog(
            onDismissRequest = { viewerPin = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { viewerPin = null }
            ) {
                if (pin.avatarPath != null) {
                    Image(
                        painter = coil.compose.rememberAsyncImagePainter(
                            if (pin.avatarPath.startsWith("/")) pin.avatarPath else "file://${pin.avatarPath}"
                        ),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(32.dp)
                ) {
                    Button(onClick = {
                        viewerPin = null
                        onNavigateToEdit(pin.id)
                    }) { Text("编辑") }
                }
            }
        }
    }
}

private val DEFAULT_PIN_COLOR = 0xFF666666.toInt()
private val COLOR_WHITE_INT = 0xFFFFFFFF.toInt()
private val HIGHLIGHT_RED = 0xFFFF4444.toInt()
private val MARKER_MUST_EAT_COLOR = 0xFFFFC107.toInt()
private val MARKER_WATCHED_COLOR = 0xFF1E88E5.toInt()
private val MARKER_BADGE_RADIUS_RATIO = 0.22f
private val MARKER_BADGE_STROKE = 2f
private val NAV_BAR_GAP = 80.dp
private val FAB_OFFSET = 64.dp
private val SEARCH_RESULT_MAX_HEIGHT = 290.dp
private val SEARCH_ITEM_V_MARGIN = 0.dp
private val SEARCH_ITEM_H_PADDING = 12.dp
private val SEARCH_ITEM_V_PADDING = 6.dp

/**
 * 找出与 [anchor] 处图标在屏幕上重叠、且绘制层级最高的标记 id。
 *
 * 高德把重叠标记的点击交给叠在最下面的那枚，标记图标又是固定像素尺寸（不随缩放变小），
 * 地图缩小后容易叠成一团，所以点开前按 zIndex 纠正一次。
 */
private fun topMostOverlappingPinId(
    aMap: com.amap.api.maps.AMap,
    anchor: LatLng,
    pins: List<Pin>,
    zIndexByPin: Map<Long, Float>,
    widthPx: Float,
    heightPx: Float
): Long? = try {
    val base = aMap.projection.toScreenLocation(anchor)
    var bestId: Long? = null
    var bestZ = Float.NEGATIVE_INFINITY
    pins.forEach { pin ->
        val p = aMap.projection.toScreenLocation(LatLng(pin.latitude, pin.longitude))
        // 图标以 (x, y) 为底边中点，向上 heightPx、左右各 widthPx / 2 展开；两个矩形相交即视为重叠
        val overlaps = kotlin.math.abs(p.x - base.x) <= widthPx &&
            kotlin.math.abs(p.y - base.y) <= heightPx
        if (overlaps) {
            val z = zIndexByPin[pin.id] ?: 0f
            if (z > bestZ) {
                bestZ = z
                bestId = pin.id
            }
        }
    }
    bestId
} catch (_: Exception) {
    null
}

@Composable
private fun SearchResultItem(
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SEARCH_ITEM_V_MARGIN)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = SEARCH_ITEM_H_PADDING, vertical = SEARCH_ITEM_V_PADDING)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
private val FAB_SIZE = 30.dp
private val FAB_ICON_SIZE = 18.dp
private val ICON_SIZE_20 = 20.dp
private val FAB_CORNER_RADIUS = 10.dp
private const val MARKER_SIZE_PX = 48
private const val ARROW_HEIGHT_PX = 12
private const val STROKE_WIDTH_AVATAR = 3f
private const val STROKE_WIDTH_HIGHLIGHT = 4f
private const val CORNER_RADIUS_BODY = 16f
private const val CORNER_RADIUS_HIGHLIGHT = 14f
private const val TEXT_SIZE_MULTIPLIER = 1.4f
private const val ARROW_HALF_WIDTH = 10f
private const val PADDING_4F = 4f
private const val PADDING_2F = 2f
private const val ZOOM_LOCATION = 15f
private const val ZOOM_SEARCH = 16f
