package com.sinus.pinmap.ui.utils

import android.util.Log
import com.sinus.pinmap.data.entity.Category
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

    private const val TAG = "AiSearchClient"
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
     */
    suspend fun search(
        query: String,
        pins: List<Pin>,
        categories: List<Category>,
        lat: Double? = null,
        lng: Double? = null
    ): SearchResult = withContext(Dispatchers.IO) {
        val categoryMap = categories.associate { it.id to it.name }
        val trimmed = pins.take(MAX_PINS_IN_PROMPT)

        val lines = trimmed.map { pin ->
            val catName = pin.categoryId?.let { categoryMap[it] } ?: "未分类"
            val distance = if (lat != null && lng != null) {
                "%.1f".format(haversineDistance(lat, lng, pin.latitude, pin.longitude) / 1000f)
            } else null
            val fields = listOfNotNull(
                pin.title,
                catName,
                pin.description,
                pin.address,
                distance?.let { "$it km" }
            ).joinToString(" | ")
            "[${pin.id}] $fields"
        }.joinToString("\n")

        val prompt = buildString {
            append("你是标记数据搜索助手。以下是用户记录的地点标记列表：\n")
            append(lines)
            append("\n\n用户想找：$query\n")
            append("请返回最匹配的标记 ID，按相关度降序，只输出 JSON 数组，如 [12, 31]。如果不相关返回 []。")
        }

        val body = JSONObject().apply {
            put("model", AppSettings.getOllamaModel())
            put("prompt", prompt)
            put("stream", false)
            put("format", "json")
            put("options", JSONObject().apply {
                put("temperature", 0.1)
            })
        }

        val request = Request.Builder()
            .url("${AppSettings.getOllamaBaseUrl().trimEnd('/')}/api/generate")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseText = try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Ollama HTTP ${resp.code}")
                }
                resp.body?.string() ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ollama request failed", e)
            throw e
        }

        val ids = try {
            val json = JSONObject(responseText)
            val content = json.optString("response", "")
            val trimmed2 = content.trim()
            val array = if (trimmed2.startsWith("[")) {
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

        SearchResult(ids, responseText)
    }
}
