package com.stream4k60.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stream4k60.app.data.local.dao.*
import com.stream4k60.app.data.local.entity.*

@Database(
    entities = [SceneCollectionEntity::class, SceneEntity::class, SourceEntity::class, FilterEntity::class, ProfileEntity::class, HotkeyBindingEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sceneDao(): SceneDao
    abstract fun sourceDao(): SourceDao
    abstract fun profileDao(): ProfileDao

    companion object {
        /** Preserve the tiny v1 skeleton database instead of destructively deleting a user's projects. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS scene_collections (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, activeSceneId TEXT, sortOrder INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS scenes (id TEXT NOT NULL PRIMARY KEY, collectionId TEXT NOT NULL, name TEXT NOT NULL, sortOrder INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sources (id TEXT NOT NULL PRIMARY KEY, sceneId TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, sortOrder INTEGER NOT NULL DEFAULT 0, visible INTEGER NOT NULL DEFAULT 1, locked INTEGER NOT NULL DEFAULT 0, configJson TEXT NOT NULL DEFAULT '{}', transformJson TEXT NOT NULL DEFAULT '{}', audioJson TEXT NOT NULL DEFAULT '{}')")
                db.execSQL("CREATE TABLE IF NOT EXISTS filters (id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, sortOrder INTEGER NOT NULL DEFAULT 0, configJson TEXT NOT NULL DEFAULT '{}')")
                db.execSQL("CREATE TABLE IF NOT EXISTS profiles (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, configJson TEXT NOT NULL DEFAULT '{}', isActive INTEGER NOT NULL DEFAULT 0, obsBasicIni TEXT NOT NULL DEFAULT '', obsServiceJson TEXT NOT NULL DEFAULT '', importedPath TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS hotkeys (id TEXT NOT NULL PRIMARY KEY, action TEXT NOT NULL, keyCode INTEGER NOT NULL, modifiers INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1)")
                migrateIds(db, "SceneCollectionEntity", "scene_collections", "INSERT OR IGNORE INTO scene_collections(id,name) VALUES(?, 'Recovered Collection')")
                migrateIds(db, "SceneEntity", "scenes", "INSERT OR IGNORE INTO scenes(id,collectionId,name) VALUES(?, '', 'Recovered Scene')")
                migrateIds(db, "SourceEntity", "sources", "INSERT OR IGNORE INTO sources(id,sceneId,name,type) VALUES(?, '', 'Recovered Source', 'OBS:RECOVERED')")
                migrateIds(db, "FilterEntity", "filters", "INSERT OR IGNORE INTO filters(id,sourceId,name,type) VALUES(?, '', 'Recovered Filter', 'OBS_FILTER')")
                migrateIds(db, "ProfileEntity", "profiles", "INSERT OR IGNORE INTO profiles(id,name) VALUES(?, 'Recovered Profile')")
                migrateIds(db, "HotkeyBindingEntity", "hotkeys", "INSERT OR IGNORE INTO hotkeys(id,action,keyCode,modifiers) VALUES(?, 'Recovered Action', 0, 0)")
            }

            private fun migrateIds(db: SupportSQLiteDatabase, oldTable: String, newTable: String, insertSql: String) {
                runCatching {
                    db.query("SELECT id FROM $oldTable").use { cursor ->
                        val insert = db.compileStatement(insertSql)
                        while (cursor.moveToNext()) {
                            insert.clearBindings()
                            insert.bindString(1, cursor.getString(0))
                            insert.executeInsert()
                        }
                    }
                    db.execSQL("DROP TABLE IF EXISTS $oldTable")
                }
            }
        }
    }
}
