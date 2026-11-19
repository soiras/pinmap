package com.sinus.pinmap.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.sinus.pinmap.ui.components.CategoryColorGrid
import com.sinus.pinmap.ui.theme.CategoryPresetColors
import com.sinus.pinmap.ui.utils.CategoryIconStore

/**
 * 创建分类对话框
 */
@Composable
fun CreateCategoryDialog(
    onConfirm: (name: String, color: Int, icon: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(CategoryPresetColors[0]) }
    var iconPath by remember { mutableStateOf<String?>(null) }
    // 当前选中的是颜色还是图片：选颜色只是把高亮切回颜色，图片预览保留到保存时才丢弃
    var useColor by remember { mutableStateOf(true) }
    val context = LocalContext.current
    // 本次弹窗里复制进来的图片：取消时统一清掉，避免留下孤儿文件

    val createdIcons = remember { mutableListOf<String>() }

    fun discardDraft() {
        createdIcons.forEach { CategoryIconStore.delete(context, it) }
        createdIcons.clear()
    }

    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val saved = CategoryIconStore.save(context, uri)
            if (saved != null) {
                createdIcons.add(saved)
                iconPath = saved
                useColor = false
            }
        }
    }

    Dialog(
        onDismissRequest = {
            discardDraft()
            onDismiss()
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "创建新分类",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "选择颜色或图片",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                CategoryColorGrid(
                    selectedColor = selectedColor,
                    iconPath = iconPath,
                    isIconSelected = !useColor,
                    onColorSelected = {
                        selectedColor = it
                        useColor = true
                    },
                    onPickIcon = { iconPicker.launch("image/*") }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            discardDraft()
                            onDismiss()
                        }
                    ) {
                        Text("取消")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val finalIcon = if (useColor) null else iconPath
                            createdIcons.filter { it != finalIcon }.forEach { CategoryIconStore.delete(context, it) }
                            onConfirm(name, selectedColor.hashCode(), finalIcon)
                        },
                        enabled = name.isNotBlank()
                    ) {
                        Text("创建")
                    }
                }
            }
        }
    }
}
