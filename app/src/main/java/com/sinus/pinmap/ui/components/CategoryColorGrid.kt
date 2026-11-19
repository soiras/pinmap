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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
private const val ICON_CLEAR_BADGE_SIZE_DP = 16
private const val ICON_CLEAR_GLYPH_SIZE_DP = 10

/**
 * 分类编辑用的颜色网格：3 行 4 列，最后一格是「选择图片」入口。
 *
 * 颜色与图片互斥：选了图片时高亮框落在图片格上，颜色格不高亮；
 * 重新点任一颜色会清除图片并把高亮还给颜色。
 */
@Composable
fun CategoryColorGrid(
    selectedColor: Color,
    iconPath: String?,
    onColorSelected: (Color) -> Unit,
    onPickIcon: () -> Unit,
    onClearIcon: () -> Unit
) {
    // 最后一格替换成图片入口，网格仍是 4x3

    val iconSelected = !iconPath.isNullOrBlank()
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
                            isSelected = iconSelected,
                            onClick = onPickIcon,
                            onClear = onClearIcon,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        ColorOption(
                            color = color,
                            isSelected = !iconSelected && color == selectedColor,
                            onClick = {
                                if (iconSelected) {
                                    onClearIcon()
                                }
                                onColorSelected(color)
                            },
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
 * 图片选项：未选图片时是一个带描边的空白圆槽，已选图片时显示预览并提供清除。
 */
@Composable
private fun IconOption(
    iconPath: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val path = iconPath
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = SELECTED_RING_WIDTH_DP.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
                } else {
                    Modifier.border(
                        width = EMPTY_TILE_BORDER_WIDTH_DP.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    )
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(ICON_CLEAR_BADGE_SIZE_DP.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "清除图片",
                    tint = Color.White,
                    modifier = Modifier.size(ICON_CLEAR_GLYPH_SIZE_DP.dp)
                )
            }
        }
    }
}

/**
 * 分类图标在不同来源下的 Coil model：支持 file:// URI 与绝对路径。
 */
internal fun categoryIconModel(path: String): Any =
    if (path.startsWith("file://")) Uri.parse(path) else File(path)
