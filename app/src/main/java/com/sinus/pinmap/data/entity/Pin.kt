package com.sinus.pinmap.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 标记实体类
 */
@Entity(tableName = "pins")
data class Pin(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val description: String? = null,
    val categoryId: Long? = null,
    val avatarPath: String? = null,
    val address: String? = null,
    val isFavorite: Boolean = false,
    // 头像显示区域参数（相对值：scale 为缩放倍数，offset 为 0~1 相对偏移）
    val avatarScale: Float = 1f,
    val avatarOffsetX: Float = 0f,
    val avatarOffsetY: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)