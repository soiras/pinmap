package com.sinus.pinmap.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sinus.pinmap.data.entity.Category
import com.sinus.pinmap.data.entity.FieldType
import com.sinus.pinmap.data.entity.MarkKind
import com.sinus.pinmap.data.entity.Pin
import com.sinus.pinmap.data.repository.CategoryRepository
import com.sinus.pinmap.data.repository.FieldTemplateRepository
import com.sinus.pinmap.data.repository.FieldValueRepository
import com.sinus.pinmap.data.repository.PinRepository
import com.sinus.pinmap.ui.utils.LocationManager
import com.sinus.pinmap.ui.utils.haversineDistance
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SortMode {
    NAME_ASC, NAME_DESC, DISTANCE_ASC, CREATED_DESC
}

class PinListViewModel(
    private val mPinRepository: PinRepository,
    private val mCategoryRepository: CategoryRepository,
    private val mFieldValueRepository: FieldValueRepository,
    private val mFieldTemplateRepository: FieldTemplateRepository
) : ViewModel() {

    private val mPins = MutableStateFlow<List<Pin>>(emptyList())
    val _pins: StateFlow<List<Pin>> = mPins.asStateFlow()

    private val mCategories = MutableStateFlow<List<Category>>(emptyList())
    val _categories: StateFlow<List<Category>> = mCategories.asStateFlow()

    // pinId -> 该标记所有非媒体字段值拼接的文本（用于搜索匹配）
    private val mFieldTextByPin = MutableStateFlow<Map<Long, String>>(emptyMap())
    val _fieldTextByPin: StateFlow<Map<Long, String>> = mFieldTextByPin.asStateFlow()

    private val mSearchQuery = MutableStateFlow("")
    val _searchQuery: StateFlow<String> = mSearchQuery.asStateFlow()

    private val mSelectedCategoryId = MutableStateFlow<Long?>(null)
    val _selectedCategoryId: StateFlow<Long?> = mSelectedCategoryId.asStateFlow()

    private val mSortMode = MutableStateFlow(SortMode.CREATED_DESC)
    val _sortMode: StateFlow<SortMode> = mSortMode.asStateFlow()

    private val mCurrentLat = MutableStateFlow(LocationManager.DEFAULT_LATITUDE)
    private val mCurrentLng = MutableStateFlow(LocationManager.DEFAULT_LONGITUDE)
    // 对外暴露成 Flow，列表页 collect 后才能随定位变化重组；读 .value 不会触发重组
    val currentLat: StateFlow<Double> = mCurrentLat.asStateFlow()
    val currentLng: StateFlow<Double> = mCurrentLng.asStateFlow()

    val filteredPins: StateFlow<List<Pin>> = combine(
        mPins,
        mSearchQuery,
        mSelectedCategoryId,
        mSortMode,
        mCurrentLat,
        mCurrentLng,
        mFieldTextByPin
    ) { values: Array<Any?> ->
        val pins = values[0] as List<Pin>
        val query = values[1] as String
        val categoryId = values[2] as Long?
        val sort = values[3] as SortMode
        val lat = values[4] as Double
        val lng = values[5] as Double
        val fieldTextByPin = values[6] as Map<Long, String>

        var result = pins

        if (categoryId != null) {
            result = result.filter { it.categoryId == categoryId }
        }

        if (query.isNotBlank()) {
            result = result.filter { pin ->
                pin.title.contains(query, ignoreCase = true) ||
                        (pin.description?.contains(query, ignoreCase = true) == true) ||
                        (fieldTextByPin[pin.id]?.contains(query, ignoreCase = true) == true)
            }
        }

        when (sort) {
            SortMode.NAME_ASC -> result = result.sortedBy { it.title }
            SortMode.NAME_DESC -> result = result.sortedByDescending { it.title }
            SortMode.DISTANCE_ASC -> result = result.sortedBy {
                haversineDistance(lat, lng, it.latitude, it.longitude)
            }

            SortMode.CREATED_DESC -> result = result.sortedByDescending { it.createdAt }
        }

        // 必吃置顶、其次关注，其余保持当前排序
        result = result.sortedWith(
            compareByDescending<Pin> { markPriority(it.markKind) }
                .thenComparator { a, b ->
                    when (sort) {
                        SortMode.NAME_ASC -> a.title.compareTo(b.title, ignoreCase = true)
                        SortMode.NAME_DESC -> b.title.compareTo(a.title, ignoreCase = true)
                        SortMode.DISTANCE_ASC ->
                            haversineDistance(lat, lng, a.latitude, a.longitude)
                                .compareTo(haversineDistance(lat, lng, b.latitude, b.longitude))
                        SortMode.CREATED_DESC -> b.createdAt.compareTo(a.createdAt)
                    }
                }
        )

        result
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        loadPins()
        loadCategories()
        loadFieldText()
    }

    private fun loadPins() {
        viewModelScope.launch {
            mPinRepository.getAllPins().collect { pinList ->
                Log.i("Sinus-PinList", "列表 ViewModel 收到 ${pinList.size} 个标记")
                mPins.value = pinList
            }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            mCategoryRepository.getAllCategories().collect { categoryList ->
                mCategories.value = categoryList
            }
        }
    }

    /**
     * 订阅字段值变化，构建 pinId -> 非媒体字段值文本 映射（用于搜索匹配）。
     * 每次重建时重新读模板，保证新建字段也能参与索引。
     */
    private fun loadFieldText() {
        viewModelScope.launch {
            mFieldValueRepository.getAllFieldValues().collect { values ->
                // 每次重建都重新取模板，避免新建字段后索引不包含新模板
                val templates = mFieldTemplateRepository.getAllFieldTemplates().first()
                val textTemplateIds = templates
                    .filter { it.fieldType != FieldType.IMAGE && it.fieldType != FieldType.VIDEO }
                    .map { it.id }
                    .toSet()
                mFieldTextByPin.value = values
                    .filter { it.fieldTemplateId in textTemplateIds && !it.value.isNullOrBlank() }
                    .groupBy { it.pinId }
                    .mapValues { (_, fvs) -> fvs.joinToString(" ") { it.value ?: "" } }
                Log.d("Sinus-PinList", "fieldTextByPin updated: templates=$textTemplateIds, values=${values.size}, map=${mFieldTextByPin.value}")
                // 找出被过滤的字段值（调试用）
                val filtered = values.filter { it.fieldTemplateId !in textTemplateIds }
                if (filtered.isNotEmpty()) {
                    Log.d("Sinus-PinList", "filtered out templateIds=${filtered.map { it.fieldTemplateId }.distinct()}, sample=${filtered.take(5).map { "${it.pinId}:${it.value}" }}")
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        mSearchQuery.value = query
    }

    fun setSelectedCategory(categoryId: Long?) {
        mSelectedCategoryId.value = categoryId
    }

    fun setSortMode(mode: SortMode) {
        mSortMode.value = mode
    }

    fun setMarkKind(pin: Pin, kind: MarkKind) {
        viewModelScope.launch {
            mPinRepository.updateMarkKind(pin.id, kind)
        }
    }

    fun setCurrentLocation(lat: Double, lng: Double) {
        mCurrentLat.value = lat
        mCurrentLng.value = lng
    }

    fun deletePin(pin: Pin) {
        viewModelScope.launch {
            mPinRepository.deletePin(pin)
        }
    }

    suspend fun getPinById(pinId: Long): Pin? {
        return mPinRepository.getPinById(pinId)
    }
}

/** 列表置顶优先级：必吃 > 关注 > 无 */
private fun markPriority(kind: MarkKind): Int = when (kind) {
    MarkKind.MUST_EAT -> 2
    MarkKind.WATCHED -> 1
    MarkKind.NONE -> 0
}
