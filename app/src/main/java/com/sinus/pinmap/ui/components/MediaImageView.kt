package com.sinus.pinmap.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.min

/**
 * 按显示参数（scale + 归一化偏移）裁剪显示图片。
 * 坐标系：图片以 Crop 铺满容器为基准（scale=1 时图片铺满容器、超出部分裁剪），
 * scale 放大缩小，offset（0~1）表示图片可移动范围内的归一化平移量。
 * scale=1 / offset=0 时 = 普通 Crop 居中效果。
 */
@Composable
fun MediaImageView(
    uri: String,
    modifier: Modifier = Modifier,
    shape: Shape,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f
) {
    var viewWidth by remember { mutableFloatStateOf(0f) }
    var viewHeight by remember { mutableFloatStateOf(0f) }

    val painter = coil.compose.rememberAsyncImagePainter(uri)
    val intrinsic = painter.intrinsicSize
    val imgW = intrinsic.width.takeIf { it.isFinite() } ?: 0f
    val imgH = intrinsic.height.takeIf { it.isFinite() } ?: 0f

    // Crop 基准：图片铺满容器所需缩放（图片尺寸 = 容器尺寸 × cropScale）
    val cropScale = if (viewWidth > 0f && viewHeight > 0f && imgW > 0f && imgH > 0f) {
        maxOf(viewWidth / imgW, viewHeight / imgH)
    } else 1f
    // Crop 后图片显示尺寸
    val cropW = imgW * cropScale
    val cropH = imgH * cropScale

    Box(
        modifier = modifier
            .clip(shape)
            .onSizeChanged { size ->
                viewWidth = size.width.toFloat()
                viewHeight = size.height.toFloat()
            }
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // ContentScale.Crop 已铺满容器；这里仅应用用户缩放/平移
                    scaleX = scale.coerceAtLeast(1f)
                    scaleY = scale.coerceAtLeast(1f)
                    val overflowW = (cropW * scale - viewWidth) / 2f
                    val overflowH = (cropH * scale - viewHeight) / 2f
                    translationX = offsetX * overflowW
                    translationY = offsetY * overflowH
                },
            contentScale = ContentScale.Crop
        )
    }
}
