package com.sinus.pinmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sinus.pinmap.data.entity.Category
import com.sinus.pinmap.data.entity.MarkKind
import com.sinus.pinmap.data.entity.Pin
import com.sinus.pinmap.ui.color
import com.sinus.pinmap.ui.icon
import com.sinus.pinmap.ui.label

private val PIN_AVATAR_SIZE = 48.dp
private val DISTANCE_GREEN = Color(0xFF4CAF50)

/**
 * 标记列表中的一行：头像 / 分类色、标题、地址、距离、三态标记与删除。
 * 地图底部卡片与标记列表页共用。
 */
@Composable
fun PinItem(
    pin: Pin,
    category: Category?,
    distanceKm: Float?,
    onClick: () -> Unit,
    onSetMark: (MarkKind) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                when {
                    pin.avatarPath != null -> MediaImageView(
                        uri = if (pin.avatarPath.startsWith("/")) pin.avatarPath
                        else "file://${pin.avatarPath}",
                        modifier = Modifier.size(PIN_AVATAR_SIZE),
                        shape = CircleShape,
                        scale = pin.avatarScale,
                        offsetX = pin.avatarOffsetX,
                        offsetY = pin.avatarOffsetY
                    )
                    category != null -> CategoryBadge(
                        color = category.color,
                        iconPath = category.icon,
                        size = PIN_AVATAR_SIZE
                    )
                    else -> Box(
                        modifier = Modifier
                            .size(PIN_AVATAR_SIZE)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = pin.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (distanceKm != null) {
                            Text(
                                text = if (distanceKm < 1f) "<1km" else "%.1fkm".format(distanceKm),
                                style = MaterialTheme.typography.bodySmall,
                                color = DISTANCE_GREEN
                            )
                        }
                    }
                    pin.address?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Box {
                var showMarkMenu by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { showMarkMenu = true },
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = pin.markKind.color
                    )
                ) {
                    Icon(
                        pin.markKind.icon,
                        contentDescription = "标记状态：${pin.markKind.label}"
                    )
                }
                DropdownMenu(
                    expanded = showMarkMenu,
                    onDismissRequest = { showMarkMenu = false }
                ) {
                    MarkKind.entries.forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.label) },
                            onClick = {
                                showMarkMenu = false
                                if (kind != pin.markKind) onSetMark(kind)
                            },
                            leadingIcon = {
                                Icon(kind.icon, contentDescription = null, tint = kind.color)
                            },
                            trailingIcon = if (pin.markKind == kind) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }
            }

            if (onDelete != null) {
                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "删除标记")
                }
            }
        }
    }
}
