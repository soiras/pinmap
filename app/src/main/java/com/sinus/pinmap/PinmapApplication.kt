package com.sinus.pinmap

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import com.amap.api.maps.MapsInitializer
import com.sinus.pinmap.ui.utils.AppSettings
import com.sinus.pinmap.ui.utils.AuthState

class PinmapApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AuthState.init(this)
        AppSettings.init(this)
        logDatabaseState()
        val savedKey = AuthState.getSavedKey()
        val apiKey = savedKey ?: BuildConfig.MAPS_API_KEY
        MapsInitializer.setApiKey(apiKey)
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        System.setProperty("amap.sdk.update.enable", "false")
        try {
            MapsInitializer.initialize(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 启动时记录数据库与媒体目录状态：用来判断"数据还在但界面为空"到底是库被清空，
     * 还是查询/渲染层没拿到数据。
     */
    private fun logDatabaseState() {
        val tag = "Sinus-DB"
        val dbFile = getDatabasePath("pinmap_database")
        var version = -1
        var pins = -1L
        var categories = -1L
        if (dbFile.exists()) {
            try {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("PRAGMA user_version", null).use { c ->
                        if (c.moveToFirst()) version = c.getInt(0)
                    }
                    db.rawQuery("SELECT COUNT(*) FROM pins", null).use { c ->
                        if (c.moveToFirst()) pins = c.getLong(0)
                    }
                    db.rawQuery("SELECT COUNT(*) FROM categories", null).use { c ->
                        if (c.moveToFirst()) categories = c.getLong(0)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "读取旧库失败: ${e.message}")
            }
        }
        Log.i(
            tag,
            "启动前 DB: path=${dbFile.absolutePath} exists=${dbFile.exists()} " +
                "size=${dbFile.length()}B version=$version pins=$pins categories=$categories"
        )
        Log.i(
            tag,
            "媒体文件: images=${fileCount("images")} avatars=${fileCount("avatars")} " +
                "category_icons=${fileCount("category_icons")}"
        )
    }

    private fun fileCount(dirName: String): Int = File(filesDir, dirName).listFiles()?.size ?: 0
}
