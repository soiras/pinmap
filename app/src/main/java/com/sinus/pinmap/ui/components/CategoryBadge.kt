package com.sinus.pinmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage

/**
 * 分类标识：有自定义图片时显示圆形图片，否则显示纯色圆点。
 *
 * @param color 分类颜色（ARGB int），无图片时的底色
 * @param iconPath 分类图标路径，支持绝对路径与 file:// URI
 */
@Composable
fun CategoryBadge(
    color: Int,
    iconPath: String?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(color)),
        contentAlignment = Alignment.Center
    ) {
        val path = iconPath
        if (!path.isNullOrBlank()) {
            AsyncImage(
                model = categoryIconModel(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
