package com.sinus.pinmap.ui

import com.sinus.pinmap.data.entity.FieldTemplate
import com.sinus.pinmap.data.entity.FieldType

/** 一行最多容纳的字段数 */
internal const val MAX_FIELDS_PER_ROW = 3

/**
 * 行分组键：rowKey 相同的字段属于同一行。
 * 单独成行的字段用自身 id：融合时新行的 rowKey 正好取首字段的 id，
 * 这样列表项的 key 不会在合并/拆行时变化，动画不会闪。
 */
internal fun rowGroupKey(template: FieldTemplate): Long = template.rowKey ?: template.id

/** 按 rowKey 把模板分组成行；行内与行间都按 sortOrder 升序。 */
internal fun groupFieldRows(templates: List<FieldTemplate>): List<List<FieldTemplate>> {
    val groups = LinkedHashMap<Long, MutableList<FieldTemplate>>()
    templates.sortedBy { it.sortOrder }.forEach { template ->
        groups.getOrPut(rowGroupKey(template)) { mutableListOf() }.add(template)
    }
    return groups.values.toList()
}

/** 图片/视频字段必须独占一行，不允许参与融合。 */
internal fun FieldTemplate.needsFullRow(): Boolean =
    fieldType == FieldType.IMAGE || fieldType == FieldType.VIDEO

internal fun List<FieldTemplate>.hasFullRowField(): Boolean = any { it.needsFullRow() }
