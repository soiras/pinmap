package com.sinus.pinmap.data.database

import androidx.room.TypeConverter
import com.sinus.pinmap.data.entity.AttachmentType
import com.sinus.pinmap.data.entity.FieldType
import com.sinus.pinmap.data.entity.MarkKind

/**
 * Room 类型转换器
 */
class Converters {
    @TypeConverter
    fun fromFieldType(value: FieldType): String {
        return value.name
    }

    @TypeConverter
    fun toFieldType(value: String): FieldType {
        return FieldType.valueOf(value)
    }

    @TypeConverter
    fun fromAttachmentType(value: AttachmentType): String {
        return value.name
    }

    @TypeConverter
    fun toAttachmentType(value: String): AttachmentType {
        return AttachmentType.valueOf(value)
    }

    @TypeConverter
    fun fromMarkKind(value: MarkKind): String {
        return value.name
    }

    @TypeConverter
    fun toMarkKind(value: String): MarkKind {
        return MarkKind.valueOf(value)
    }
}