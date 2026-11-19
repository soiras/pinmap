package com.sinus.pinmap.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.min

/**
 * 图片显示区域调整器。
 * 图片以 Crop 铺满容器（初始覆盖全屏，圆形选区即容器短边），可缩放/平移调整显示区域。
 * 圆形选区 = 容器短边直径，始终被图片覆盖（scale≥1，不露白）。
 * 确定后回调 scale / offsetX / offsetY：offset 为图片超出圆形选区的量归一化（0~1）。
 */
@Composable
fun MediaAdjuster(
    uri: String,
    initialScale: Float,
    initialOffsetX: Float,
    initialOffsetY: Float,
    onDismiss: () -> Unit,
    onConfirm: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    onPickNew: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(initialScale.coerceAtLeast(1f)) }
    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }
    var viewWidth by remember { mutableFloatStateOf(0f) }
    var viewHeight by remember { mutableFloatStateOf(0f) }

    val painter = coil.compose.rememberAsyncImagePainter(uri)
    val intrinsic = painter.intrinsicSize
    val imgW = intrinsic.width.takeIf { it.isFinite() } ?: 0f
    val imgH = intrinsic.height.takeIf { it.isFinite() } ?: 0f

    // Crop 铺满容器基准：图片放大到覆盖整个容器
    val cropScale = if (viewWidth > 0f && viewHeight > 0f && imgW > 0f && imgH > 0f) {
        maxOf(viewWidth / imgW, viewHeight / imgH)
    } else 1f
    val cropW = imgW * cropScale
    val cropH = imgH * cropScale

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
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
                        // ContentScale.Crop 已让图片铺满容器；这里仅应用用户缩放
                        scaleX = scale
                        scaleY = scale
                        // 平移：offset 归一化到图片超出容器的量
                        val overflowW = (cropW * scale - viewWidth) / 2f
                        val overflowH = (cropH * scale - viewHeight) / 2f
                        translationX = offsetX * overflowW
                        translationY = offsetY * overflowH
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            // 平移范围：图片超出容器部分（scale≥1，容器内始终有图）
                            val overflowW = if (cropW > 0f) (cropW * scale - viewWidth) / 2f else 0f
                            val overflowH = if (cropH > 0f) (cropH * scale - viewHeight) / 2f else 0f
                            // 手指向哪,图片内容向哪移：translationX = offsetX*overflowW, offset增大→图片右移
                            if (overflowW > 0f) offsetX = (offsetX + pan.x / overflowW).coerceIn(-1f, 1f)
                            if (overflowH > 0f) offsetY = (offsetY + pan.y / overflowH).coerceIn(-1f, 1f)
                        }
                    },
                contentScale = ContentScale.Crop
            )

            // 圆形选区提示（容器短边直径）
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = min(size.width, size.height) / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                drawCircle(color = Color.White, radius = r, center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
            }

            // 顶部提示
            Text(
                "拖动调整位置，双指缩放",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            )

            // 底部操作区
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPickNew) { Text("重新选择", color = Color.White) }
                TextButton(onClick = onDismiss) { Text("取消", color = Color.White) }
                Button(
                    onClick = { onConfirm(scale, offsetX, offsetY) }
                ) { Text("确定") }
            }
        }
    }
}
