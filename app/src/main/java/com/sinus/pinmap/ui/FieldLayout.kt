package com.sinus.pinmap.ui

import com.sinus.pinmap.data.entity.FieldTemplate
import com.sinus.pinmap.data.entity.FieldType

/** 一行最多容纳的字段数 */
internal const val MAX_FIELDS_PER_ROW = 3

/**
 * 行分组键：rowKey 相同的字段属于同一行。
 * 独占一行的字段用 -id 作为临时键，避免与真实 rowKey 相撞。
 */
internal fun rowGroupKey(template: FieldTemplate): Long = template.rowKey ?: -template.id

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
