package com.sinus.pinmap.ui.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 应用设置存储（Ollama 等）
 */
object AppSettings {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_OLLAMA_BASE_URL = "ollama_base_url"
    private const val KEY_OLLAMA_MODEL = "ollama_model"
    private const val DEFAULT_OLLAMA_BASE_URL = "http://192.168.1.103:11434"
    private const val DEFAULT_OLLAMA_MODEL = "qwen2.5-coder:7b"

    private lateinit var mPrefs: SharedPreferences

    fun init(context: Context) {
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getOllamaBaseUrl(): String =
        if (::mPrefs.isInitialized) mPrefs.getString(KEY_OLLAMA_BASE_URL, DEFAULT_OLLAMA_BASE_URL) ?: DEFAULT_OLLAMA_BASE_URL
        else DEFAULT_OLLAMA_BASE_URL

    fun saveOllamaBaseUrl(url: String) {
        if (::mPrefs.isInitialized) {
            mPrefs.edit { putString(KEY_OLLAMA_BASE_URL, url) }
        }
    }

    fun getOllamaModel(): String =
        if (::mPrefs.isInitialized) mPrefs.getString(KEY_OLLAMA_MODEL, DEFAULT_OLLAMA_MODEL) ?: DEFAULT_OLLAMA_MODEL
        else DEFAULT_OLLAMA_MODEL

    fun saveOllamaModel(model: String) {
        if (::mPrefs.isInitialized) {
            mPrefs.edit { putString(KEY_OLLAMA_MODEL, model) }
        }
    }
}
