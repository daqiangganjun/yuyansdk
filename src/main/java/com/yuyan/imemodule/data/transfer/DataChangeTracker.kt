package com.yuyan.imemodule.data.transfer

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.sqlite.db.SupportSQLiteDatabase

object DataChangeTracker {
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "LearningChanges") }
    fun learningChanged(dictionary: String, text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val word = text.toByteArray().joinToString("") { "%02X".format(it.toInt() and 255) }
        writer.execute {
            runCatching {
                val db = com.yuyan.imemodule.database.DataBaseKT.instance.openHelper.writableDatabase
                db.execSQL("INSERT OR REPLACE INTO selfopt_changes(item, changed) VALUES (?, ?)", arrayOf<Any>("learn-word/$dictionary/$word", now))
                db.execSQL("INSERT OR REPLACE INTO selfopt_changes(item, changed) VALUES (?, ?)", arrayOf<Any>("learn-activity/$dictionary", now))
            }.onFailure { android.util.Log.e("SelfOptSync", "Cannot record learning modification time", it) }
        }
    }
    fun flush() { writer.submit {}.get() }
    fun initialize(context: Context) {
        if (listener != null) return
        val times = context.getSharedPreferences("transfer_changes", Context.MODE_PRIVATE)
        if (!times.getBoolean("_initialized", false)) {
            val legacyTime = java.io.File(context.applicationInfo.dataDir, "shared_prefs/${context.packageName}_preferences.xml").lastModified()
            val edit = times.edit().putBoolean("_initialized", true)
            PreferenceManager.getDefaultSharedPreferences(context).all.keys.filter { !it.startsWith("transfer_") }
                .forEach { edit.putLong(it, legacyTime) }
            edit.commit()
        }
        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && !key.startsWith("transfer_")) times.edit().putLong(key, System.currentTimeMillis()).apply()
        }
        PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(listener)
    }
    fun preferenceTime(context: Context, key: String): Long =
        context.getSharedPreferences("transfer_changes", Context.MODE_PRIVATE).getLong(key, 0L)

    fun install(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS selfopt_changes (item TEXT PRIMARY KEY NOT NULL, changed INTEGER NOT NULL)")
        val ownTransaction = !database.inTransaction()
        if (ownTransaction) database.beginTransaction()
        try { LocalDataSnapshots.tables.forEach { (table, keys) ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                val source = if (operation == "DELETE") "OLD" else "NEW"
                val key = keys.joinToString(" || '_' || ") { "hex(CAST($source.`$it` AS TEXT))" }
                val trigger = "selfopt_${table}_${operation.lowercase()}"
                val item = "'row/$table/' || $key"
                val time = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
                database.execSQL("DROP TRIGGER IF EXISTS $trigger")
                // 外层 UPDATE OR ABORT 会覆盖触发器的 OR REPLACE，须显式更新已有记录。
                database.execSQL("CREATE TRIGGER $trigger AFTER $operation ON `$table` BEGIN " +
                    "UPDATE selfopt_changes SET changed = $time WHERE item = $item; " +
                    "INSERT INTO selfopt_changes(item, changed) SELECT $item, $time WHERE NOT EXISTS (SELECT 1 FROM selfopt_changes WHERE item = $item); END")
            }
        }
            if (ownTransaction) database.setTransactionSuccessful()
        } finally { if (ownTransaction) database.endTransaction() }
    }
}
