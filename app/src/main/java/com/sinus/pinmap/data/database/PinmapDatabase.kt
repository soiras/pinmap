package com.sinus.pinmap.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sinus.pinmap.data.store.AttachmentStore
import com.sinus.pinmap.data.store.CategoryStore
import com.sinus.pinmap.data.store.FieldTemplateStore
import com.sinus.pinmap.data.store.FieldValueStore
import com.sinus.pinmap.data.store.OfflineMapStore
import com.sinus.pinmap.data.store.PinStore
import com.sinus.pinmap.data.entity.Attachment
import com.sinus.pinmap.data.entity.Category
import com.sinus.pinmap.data.entity.FieldTemplate
import com.sinus.pinmap.data.entity.FieldValue
import com.sinus.pinmap.data.entity.OfflineMap
import com.sinus.pinmap.data.entity.Pin

@Database(
    entities = [
        Pin::class,
        Category::class,
        FieldTemplate::class,
        FieldValue::class,
        Attachment::class,
        OfflineMap::class
    ],
    version = 11,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PinmapDatabase : RoomDatabase() {
    abstract fun pinStore(): PinStore
    abstract fun categoryStore(): CategoryStore
    abstract fun fieldTemplateStore(): FieldTemplateStore
    abstract fun fieldValueStore(): FieldValueStore
    abstract fun attachmentStore(): AttachmentStore
    abstract fun offlineMapStore(): OfflineMapStore

    companion object {
        @Volatile
        private var mInstance: PinmapDatabase? = null

        /**
         * 逐列加列：开发期出现过"列已经加上、版本号还停在前一版"的数据库，
         * 再次升级时裸 ALTER 会报 duplicate column name 直接崩溃。
         * 这里先查 PRAGMA table_info，已存在就跳过，让这些迁移可以重复执行。
         */
        private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
            query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) {
                        found = true
                        break
                    }
                }
                found
            }

        private fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, definition: String) {
            if (!hasColumn(table, column)) {
                execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
            }
        }

        val mMigration_3_4 = Migration(3, 4) { db ->
            db.addColumnIfMissing("field_templates", "sortOrder", "INTEGER NOT NULL DEFAULT 0")
        }

        val mMigration_4_5 = Migration(4, 5) { db ->
            db.addColumnIfMissing("pins", "avatarPath", "TEXT")
        }

        val mMigration_5_6 = Migration(5, 6) { db ->
            db.addColumnIfMissing("pins", "address", "TEXT")
        }

        val mMigration_6_7 = Migration(6, 7) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS field_values_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    pinId INTEGER NOT NULL,
                    fieldTemplateId INTEGER NOT NULL,
                    value TEXT,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
            """)
            db.execSQL("""
                INSERT INTO field_values_new
                SELECT id, pinId, COALESCE(fieldTemplateId, 0), value, createdAt, updatedAt
                FROM field_values
            """)
            db.execSQL("DROP TABLE field_values")
            db.execSQL("ALTER TABLE field_values_new RENAME TO field_values")
        }

        val mMigration_7_8 = Migration(7, 8) { db ->
            db.addColumnIfMissing("pins", "isFavorite", "INTEGER NOT NULL DEFAULT 0")
        }

        val mMigration_8_9 = Migration(8, 9) { db ->
            db.addColumnIfMissing("pins", "avatarScale", "REAL NOT NULL DEFAULT 1.0")
            db.addColumnIfMissing("pins", "avatarOffsetX", "REAL NOT NULL DEFAULT 0.0")
            db.addColumnIfMissing("pins", "avatarOffsetY", "REAL NOT NULL DEFAULT 0.0")
            db.addColumnIfMissing("field_values", "mediaScale", "REAL NOT NULL DEFAULT 1.0")
            db.addColumnIfMissing("field_values", "mediaOffsetX", "REAL NOT NULL DEFAULT 0.0")
            db.addColumnIfMissing("field_values", "mediaOffsetY", "REAL NOT NULL DEFAULT 0.0")
        }

        val mMigration_9_10 = Migration(9, 10) { db ->
            // 三态标记：必吃 / 关注 / 无。老数据的星标语义就是必吃。
            // isFavorite 不再使用，但 SQLite 的老版本不支持 DROP COLUMN，所以整表重建
            db.execSQL("DROP TABLE IF EXISTS pins_new")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS pins_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT,
                    categoryId INTEGER,
                    avatarPath TEXT,
                    address TEXT,
                    markKind TEXT NOT NULL DEFAULT 'NONE',
                    avatarScale REAL NOT NULL DEFAULT 1.0,
                    avatarOffsetX REAL NOT NULL DEFAULT 0.0,
                    avatarOffsetY REAL NOT NULL DEFAULT 0.0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)
            db.execSQL("""
                INSERT INTO pins_new (id, latitude, longitude, title, description, categoryId,
                    avatarPath, address, markKind, avatarScale, avatarOffsetX, avatarOffsetY,
                    createdAt, updatedAt)
                SELECT id, latitude, longitude, title, description, categoryId,
                    avatarPath, address,
                    CASE WHEN isFavorite = 1 THEN 'MUST_EAT' ELSE 'NONE' END,
                    avatarScale, avatarOffsetX, avatarOffsetY, createdAt, updatedAt
                FROM pins
            """)
            db.execSQL("DROP TABLE pins")
            db.execSQL("ALTER TABLE pins_new RENAME TO pins")
        }

        val mMigration_10_11 = Migration(10, 11) { db ->
            // 字段行融合：相同 rowKey 的模板渲染在同一行，null 表示独占一行
            db.addColumnIfMissing("field_templates", "rowKey", "INTEGER")
        }

        fun getDatabase(context: Context): PinmapDatabase {
            return mInstance ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PinmapDatabase::class.java,
                    "pinmap_database"
                )
                    .addMigrations(
                        mMigration_3_4, mMigration_4_5, mMigration_5_6, mMigration_6_7,
                        mMigration_7_8, mMigration_8_9, mMigration_9_10,
                        mMigration_10_11
                    )
                    // 老版本（如 v2）无迁移路径时直接重建数据库，避免启动崩溃
                    .fallbackToDestructiveMigration()
                    .build()
                mInstance = instance
                instance
            }
        }
    }
}
