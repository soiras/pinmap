package com.sinus.pinmap.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "field_values")
data class FieldValue(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val pinId: Long,
    val fieldTemplateId: Long,
    val value: String? = null,
    // 媒体图片显示区域参数（相对值）
    val mediaScale: Float = 1f,
    val mediaOffsetX: Float = 0f,
    val mediaOffsetY: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
