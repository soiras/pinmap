package com.sinus.pinmap.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.sinus.pinmap.data.entity.MarkKind

val MARK_COLOR_MUST_EAT = Color(0xFFF57C00)
val MARK_COLOR_WATCHED = Color(0xFF1E88E5)
val MARK_COLOR_NONE = Color(0xFF9E9E9E)

/** 三态标记的界面文案 */
val MarkKind.label: String
    get() = when (this) {
        MarkKind.MUST_EAT -> "必吃"
        MarkKind.WATCHED -> "关注"
        MarkKind.NONE -> "无标记"
    }

/** 三态标记的图标：必吃用星标，关注用心形，无标记用灰色星标 */
val MarkKind.icon: ImageVector
    get() = when (this) {
        MarkKind.MUST_EAT -> Icons.Default.Star
        MarkKind.WATCHED -> Icons.Default.Favorite
        MarkKind.NONE -> Icons.Default.Star
    }

val MarkKind.color: Color
    get() = when (this) {
        MarkKind.MUST_EAT -> MARK_COLOR_MUST_EAT
        MarkKind.WATCHED -> MARK_COLOR_WATCHED
        MarkKind.NONE -> MARK_COLOR_NONE
    }
