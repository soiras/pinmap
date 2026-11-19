package com.sinus.pinmap.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sinus.pinmap.ui.theme.CategoryPresetColors
import java.io.File

private const val GRID_COLUMNS = 4
private const val COLOR_OPTION_SIZE_DP = 24
private const val SELECTED_RING_WIDTH_DP = 3
private const val EMPTY_TILE_BORDER_WIDTH_DP = 1
private const val EMPTY_TILE_BACKGROUND_ALPHA = 0.06f

/**
 * 分类编辑用的颜色网格：3 行 4 列，最后一格是「选择图片」入口。
 *
 * 颜色与图片互斥：当前选中的是图片时高亮框落在图片格上，否则落在颜色格上。
 */
@Composable
fun CategoryColorGrid(
    selectedColor: Color,
    iconPath: String?,
    isIconSelected: Boolean,
    onColorSelected: (Color) -> Unit,
    onPickIcon: () -> Unit
) {
    // 最后一格替换成图片入口，网格仍是 4x3

    val hasIcon = !iconPath.isNullOrBlank()
    val colorCells = CategoryPresetColors.dropLast(1)
    val cells: List<Color?> = colorCells + listOf(null)
    Column {
        cells.chunked(GRID_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { color ->
                    if (color == null) {
                        IconOption(
                            iconPath = iconPath,
                            isSelected = isIconSelected,
                            onClick = onPickIcon,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        ColorOption(
                            color = color,
                            isSelected = !isIconSelected && color == selectedColor,
                            onClick = { onColorSelected(color) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * 颜色选项
 */
@Composable
private fun ColorOption(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .background(color, CircleShape)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = SELECTED_RING_WIDTH_DP.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(COLOR_OPTION_SIZE_DP.dp)
            )
        }
    }
}

/**
 * 图片选项：未选图片时是带淡底的空槽，已选图片时显示预览。
 */
@Composable
private fun IconOption(
    iconPath: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val path = iconPath
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = EMPTY_TILE_BACKGROUND_ALPHA),
                shape = CircleShape
            )
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = SELECTED_RING_WIDTH_DP.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
                } else if (path.isNullOrBlank()) {
                    Modifier.border(
                        width = EMPTY_TILE_BORDER_WIDTH_DP.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (path.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "选择图片",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(COLOR_OPTION_SIZE_DP.dp)
            )
        } else {
            AsyncImage(
                model = categoryIconModel(path),
                contentDescription = "分类图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        }
    }
}

/**
 * 分类图标在不同来源下的 Coil model：支持 file:// URI 与绝对路径。
 */
internal fun categoryIconModel(path: String): Any =
    if (path.startsWith("file://")) Uri.parse(path) else File(path)
