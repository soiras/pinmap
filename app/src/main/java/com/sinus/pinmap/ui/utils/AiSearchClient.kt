package com.sinus.pinmap.ui.utils

import android.util.Log
import com.sinus.pinmap.data.entity.Category
import com.sinus.pinmap.data.entity.FieldTemplate
import com.sinus.pinmap.data.entity.FieldType
import com.sinus.pinmap.data.entity.FieldValue
import com.sinus.pinmap.data.entity.Pin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 基于本地 Ollama LLM 的意图搜索。
 * 将标记文本组装成 prompt，让模型返回匹配的标记 ID 数组（按相关度排序）。
 */
object AiSearchClient {

    private const val TAG = "Sinus-AiSearch"
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val MAX_PINS_IN_PROMPT = 200

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    data class SearchResult(
        val pinIds: List<Long>,
        val rawText: String
    )

    /**
     * 用 LLM 搜索标记。lat/lng 为空则不带距离列。
     * @param fieldValuesByPin pinId -> 该标记的自定义字段值
     * @param templatesById templateId -> 字段模板（用于取字段名）
     */
    suspend fun search(
        query: String,
        pins: List<Pin>,
        categories: List<Category>,
        fieldValuesByPin: Map<Long, List<FieldValue>> = emptyMap(),
        templatesById: Map<Long, FieldTemplate> = emptyMap(),
        lat: Double? = null,
        lng: Double? = null
    ): SearchResult = withContext(Dispatchers.IO) {
        val categoryMap = categories.associate { it.id to it.name }
        val trimmed = pins.take(MAX_PINS_IN_PROMPT)

        Log.d(TAG, "search start: query='$query', pins=${pins.size} (trimmed=${trimmed.size}), categories=${categories.size}, fieldValues=${fieldValuesByPin.size}")
        Log.d(TAG, "ollama baseUrl=${AppSettings.getOllamaBaseUrl()}, model=${AppSettings.getOllamaModel()}")

        // 按分类分组，每个分类一张独立表格
        // 基础列：标题、描述、地址、距离；动态列：该分类自己的字段
        val baseColumns = listOf("标题", "描述", "地址", "距离")

        data class CategoryTable(val name: String, val pins: List<Pin>)
        val byCategory = mutableMapOf<String, MutableList<Pin>>()
        trimmed.forEach { pin ->
            val catName = pin.categoryId?.let { categoryMap[it] } ?: "未分类"
            byCategory.getOrPut(catName) { mutableListOf() }.add(pin)
        }

        fun fieldsForCategory(catName: String): List<String> {
            val fieldNames = linkedSetOf<String>()
            byCategory[catName].orEmpty().flatMap { fieldValuesByPin[it.id].orEmpty() }.forEach { fv ->
                val template = templatesById[fv.fieldTemplateId]
                if (template != null && template.fieldType != FieldType.IMAGE && template.fieldType != FieldType.VIDEO) {
                    fieldNames.add(template.fieldName)
                }
            }
            return fieldNames.toList()
        }

        fun formatTable(catName: String): String {
            val dynamicColumns = fieldsForCategory(catName)
            val columns = listOf("ID") + baseColumns + dynamicColumns
            val header = columns.joinToString(" | ")
            val rows = byCategory[catName].orEmpty().map { pin ->
                val distance = if (lat != null && lng != null) {
                    "%.1fkm".format(haversineDistance(lat, lng, pin.latitude, pin.longitude) / 1000f)
                } else null
                val dynamicValues = dynamicColumns.map { fieldName ->
                    val value = fieldValuesByPin[pin.id].orEmpty().firstOrNull { fv ->
                        templatesById[fv.fieldTemplateId]?.fieldName == fieldName
                    }?.value
                    value?.takeIf { it.isNotBlank() } ?: "-"
                }
                val values = listOfNotNull(
                    pin.id.toString(),
                    pin.title,
                    pin.description,
                    pin.address,
                    distance
                ) + dynamicValues
                values.joinToString(" | ")
            }
            return (listOf(header) + rows).joinToString("\n")
        }

        val tables = byCategory.keys.map { catName ->
            "$catName:\n${formatTable(catName)}"
        }.joinToString("\n\n")

        val prompt = buildString {
            append("你是标记数据搜索助手。以下是用户记录的地点标记列表，按分类分为多个表格，每个表格第一行是列名：\n")
            append(tables)
            append("\n\n用户想找：$query\n")
            append("请从上面的标记中选出最匹配的，按相关度降序排列。\n")
            append("必须至少返回 1 个最接近的标记 ID；即使不确定也返回最接近的那个。\n")
            append("严格输出 JSON：{\"ids\": [12, 31]}，不要输出任何其他内容。")
        }

        // 强制模型按 schema 输出：{"ids": [Long, ...]}
        val formatSchema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("ids", JSONObject().apply {
                    put("type", "array")
                    put("items", JSONObject().apply {
                        put("type", "integer")
                    })
                })
            })
            put("required", JSONArray().put("ids"))
        }

        val body = JSONObject().apply {
            put("model", AppSettings.getOllamaModel())
            put("prompt", prompt)
            put("stream", false)
            put("format", formatSchema)
            put("options", JSONObject().apply {
                put("temperature", 0.1)
            })
        }
        Log.d(TAG, "request body: model=${body.optString("model")}, promptLength=${prompt.length} chars")
        Log.d(TAG, "full prompt:\n$prompt")

        val request = Request.Builder()
            .url("${AppSettings.getOllamaBaseUrl().trimEnd('/')}/api/generate")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseText = try {
            val startTime = System.currentTimeMillis()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Ollama HTTP ${resp.code}")
                }
                resp.body?.string() ?: ""
            }.also {
                Log.d(TAG, "response received in ${System.currentTimeMillis() - startTime}ms, length=${it.length}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ollama request failed", e)
            throw e
        }
        Log.d(TAG, "raw response: $responseText")

        val ids = try {
            val json = JSONObject(responseText)
            val content = json.optString("response", "")
            val trimmed2 = content.trim()

            // 1) 期望格式：{"ids": [12, 31]}
            val fromIdsField = try {
                JSONObject(trimmed2).optJSONArray("ids")
            } catch (_: Exception) {
                null
            }
            val array = fromIdsField ?: if (trimmed2.startsWith("[")) {
                JSONArray(trimmed2)
            } else {
                // 兜底：尝试从字符串里提取数组
                val start = trimmed2.indexOf('[')
                val end = trimmed2.lastIndexOf(']')
                if (start >= 0 && end > start) JSONArray(trimmed2.substring(start, end + 1)) else JSONArray()
            }
            (0 until array.length()).mapNotNull { i ->
                array.optLong(i).takeIf { it > 0 }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse response failed: $responseText", e)
            emptyList()
        }
        Log.d(TAG, "parsed ids: $ids")

        SearchResult(ids, responseText)
    }
}
