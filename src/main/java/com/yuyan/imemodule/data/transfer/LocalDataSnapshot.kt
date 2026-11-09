package com.yuyan.imemodule.data.transfer

import android.content.Context
import android.database.Cursor
import android.util.Base64
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.inputmethod.DictionaryPackage
import com.yuyan.inputmethod.DictionaryStore
import com.yuyan.inputmethod.core.Rime
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LocalDataSnapshot(
    val values: Map<String, String>,
    val blobs: Map<String, File>,
    val modified: Map<String, Long> = emptyMap(),
    val scopes: Set<String>? = null,
) {
    fun select(selected: Set<SyncScope>): LocalDataSnapshot {
        require(selected.isNotEmpty()) { "No backup categories selected" }
        val filtered = values.filterKeys { key -> selected.any { it.matches(key) } }
        val hashes = LocalDataSnapshots.neededBlobs(filtered)
        return LocalDataSnapshot(filtered, blobs.filterKeys { it in hashes }, modified.filterKeys { it in filtered },
            selected.map { it.id }.toSet().takeUnless { selected.size == SyncScope.entries.size })
    }
}

object LocalDataSnapshots {
    val tables = linkedMapOf("phrase" to listOf("content"), "clipboard" to listOf("content"),
        "side_symbol" to listOf("symbolKey"), "usedSymbol" to listOf("symbol"), "skbfun" to listOf("name", "isKeep"))
    val dictionaries = listOf("rime_frost", "rime_ice", "melt_eng", "stroke")
    private val columns = mapOf("phrase" to setOf("content", "isKeep", "t9", "qwerty", "lx17", "time"),
        "clipboard" to setOf("content", "isKeep", "time"), "side_symbol" to setOf("symbolKey", "symbolValue", "type"),
        "usedSymbol" to setOf("symbol", "type", "time"), "skbfun" to setOf("name", "isKeep", "position"))
    private fun encode(value: String) = Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).toString(Charsets.UTF_8)
    private fun hex(value: String) = value.toByteArray().joinToString("") { "%02X".format(it.toInt() and 255) }

    fun fromLegacy(context: Context, directory: File, current: LocalDataSnapshot, shared: File, work: File): LocalDataSnapshot {
        val values = current.values.toMutableMap()
        val blobs = current.blobs.toMutableMap()
        work.mkdirs()
        File(directory, "shared_prefs").listFiles().orEmpty().filter { it.name.endsWith("_preferences.xml") }.forEach { file ->
            val parser = android.util.Xml.newPullParser()
            file.inputStream().use { input ->
                parser.setInput(input, "UTF-8")
                while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG || parser.name == "map") continue
                    val name = parser.getAttributeValue(null, "name") ?: continue
                    if (name.startsWith("transfer_")) continue
                    val kind = parser.name
                    val row = JSONObject().put("type", kind)
                    when (kind) {
                        "string" -> row.put("value", parser.nextText())
                        "boolean" -> row.put("value", parser.getAttributeValue(null, "value").toBooleanStrict())
                        "int", "long" -> row.put("value", parser.getAttributeValue(null, "value").toLong())
                        "float" -> row.put("value", parser.getAttributeValue(null, "value").toDouble())
                        "set" -> {
                            val items = mutableListOf<String>()
                            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                                if (parser.eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "set") break
                                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "string") items += parser.nextText()
                            }
                            row.put("value", JSONArray(items.sorted()))
                        }
                        else -> error("Unsupported legacy preference")
                    }
                    values["pref/" + encode(name)] = row.toString()
                }
            }
        }
        val dbFile = File(directory, "databases/ime_db")
        if (dbFile.isFile) android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("PRAGMA quick_check", null).use { cursor -> require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Legacy database is inconsistent" } }
            tables.forEach { (table, keys) ->
                val exists = database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
                if (!exists) return@forEach
                values.keys.filter { it.startsWith("row/$table/") }.forEach(values::remove)
                database.rawQuery("SELECT * FROM `$table`", null).use { cursor -> while (cursor.moveToNext()) {
                    val row = JSONObject()
                    columns.getValue(table).sorted().forEach { column ->
                        val i = cursor.getColumnIndex(column)
                        row.put(column, if (i < 0) { if (column in setOf("isKeep", "position", "time")) 0 else "" }
                            else if (cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER) cursor.getLong(i) else cursor.getString(i))
                    }
                    if (table == "skbfun" && row.optString("name") in setOf("NumberRow", "TextTransform")) continue
                    values["row/$table/" + keys.joinToString("_") { hex(row.get(it).toString()) }] = row.toString()
                } }
            }
        }
        val theme = File(directory, "external/theme")
        if (theme.isDirectory) {
            values.keys.filter { it.startsWith("theme/") }.forEach(values::remove)
            theme.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(theme).invariantSeparatorsPath
                val portable = if (relative.endsWith(".json")) File(work, "legacy-theme-" + encode(relative)).apply {
                    writeText(file.readText().replace(Regex("/[^\"\\s]+/theme/"), "@theme/"))
                } else file
                val hash = TransferFiles.sha256(portable)
                values["theme/$relative"] = hash
                blobs[hash] = portable
            }
        }
        val legacyUser = File(directory, "external/rime")
        listOf("pinyin" to "rime_frost", "english" to "melt_eng", "stroke" to "stroke").forEach { (old, target) ->
            if (!File(legacyUser, "$old.userdb/CURRENT").isFile) return@forEach
            val exported = File(work, "legacy-$old.tsv")
            check(Rime.transferUserDictionary(shared.path, legacyUser.path, old, exported.path, false) >= 0) { "Cannot read legacy learning data" }
            values.keys.filter { it.startsWith("learn/$target/") }.forEach(values::remove)
            exported.useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val fields = line.split('\t')
                require(fields.size >= 3) { "Invalid legacy learning record" }
                values["learn/$target/" + encode(fields[0] + "\t" + fields[1])] = fields[2]
            } }
        }
        return LocalDataSnapshot(values, neededBlobs(values).associateWith { blobs.getValue(it) })
    }

    fun capture(context: Context, shared: File, user: File, work: File, forSync: Boolean = false): LocalDataSnapshot {
        work.mkdirs()
        val values = sortedMapOf<String, String>()
        val blobs = mutableMapOf<String, File>()
        val modified = mutableMapOf<String, Long>()
        DataChangeTracker.flush()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.all.toSortedMap().forEach { (key, value) ->
            if (key.startsWith("transfer_") || (forSync && key == "rime_dictionary_family")) return@forEach
            val record = JSONObject()
            when (value) {
                is String -> record.put("type", "string").put("value", value)
                is Boolean -> record.put("type", "boolean").put("value", value)
                is Int -> record.put("type", "int").put("value", value)
                is Long -> record.put("type", "long").put("value", value)
                is Float -> record.put("type", "float").put("value", value.toDouble())
                is Set<*> -> record.put("type", "set").put("value", JSONArray(value.filterIsInstance<String>().sorted()))
                else -> return@forEach
            }
            val item = "pref/" + encode(key)
            values[item] = record.toString()
            modified[item] = DataChangeTracker.preferenceTime(context, key)
        }
        val database = DataBaseKT.instance.openHelper.writableDatabase
        database.beginTransaction()
        try {
            tables.forEach { (table, keys) ->
                database.query("SELECT * FROM `$table`").use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        cursor.columnNames.sorted().forEach { column ->
                            val i = cursor.getColumnIndexOrThrow(column)
                            row.put(column, when (cursor.getType(i)) {
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                else -> cursor.getString(i)
                            })
                        }
                        val key = keys.joinToString("_") { hex(row.get(it).toString()) }
                        values["row/$table/$key"] = row.toString()
                        modified["row/$table/$key"] = row.optLong("time", 0L)
                    }
                }
            }
            database.query("SELECT item, changed FROM selfopt_changes").use { cursor ->
                while (cursor.moveToNext()) modified[cursor.getString(0)] = cursor.getLong(1)
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        dictionaries.forEach { dictionary ->
            val file = File(work, "$dictionary.tsv")
            check(Rime.transferUserDictionary(shared.path, user.path, dictionary, file.path, false) >= 0) { Rime.getLastError() }
            file.useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val fields = line.split('\t')
                require(fields.size >= 3) { "Invalid user dictionary export" }
                val key = "learn/$dictionary/" + encode(fields[0] + "\t" + fields[1])
                values[key] = fields[2]
                modified[key] = modified["learn-word/$dictionary/" + hex(fields[0])]
                    ?: modified["learn-activity/$dictionary"] ?: 0L
            } }
        }
        val theme = File(context.getExternalFilesDir(null), "theme")
        theme.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(theme).invariantSeparatorsPath
            val portable = if (relative.endsWith(".json")) {
                File(work, "theme-" + encode(relative)).apply {
                    writeText(file.readText().replace(theme.absolutePath, "@theme"))
                }
            } else file
            val hash = TransferFiles.sha256(portable)
            values["theme/$relative"] = hash
            blobs[hash] = portable
            modified["theme/$relative"] = file.lastModified()
        }
        DictionaryStore.active(context).forEach { (family, pkg) ->
            values["dictionary/$family"] = SyncProtocol.json.encodeToString(pkg)
            modified["dictionary/$family"] = pkg.updatedAt
            blobs[pkg.sha256] = DictionaryStore.materialize(context, pkg)
        }
        com.yuyan.inputmethod.dictionary.DictionaryRules.records(context).forEach { (key, value) ->
            values[key] = value
            modified[key] = JSONObject(value).optLong("updatedAt", 1)
        }
        return LocalDataSnapshot(values, blobs, modified)
    }

    fun neededBlobs(values: Map<String, String>): Set<String> = values.mapNotNull { (key, value) ->
        when {
            key.startsWith("theme/") -> value
            key.startsWith("dictionary/") -> SyncProtocol.json.decodeFromString<DictionaryPackage>(value).sha256
            else -> null
        }
    }.toSet().also { hashes -> require(hashes.all { it.matches(Regex("[0-9a-f]{64}")) }) { "Invalid object checksum" } }

    fun validate(values: Map<String, String>, blobs: Map<String, File>, scopes: Set<String>? = null) {
        if (scopes != null) {
            require(scopes.isNotEmpty() && scopes.all { id -> SyncScope.entries.any { it.id == id } }) { "Invalid backup categories" }
            require(values.keys.all { key -> SyncScope.entries.any { it.id in scopes && it.matches(key) } }) { "Backup record is outside selected categories" }
        }
        neededBlobs(values).forEach { hash ->
            val file = blobs[hash] ?: error("Missing backup object")
            require(TransferFiles.sha256(file) == hash) { "Backup object checksum mismatch" }
        }
        values.forEach { (key, value) -> when {
            key.startsWith("rule/") -> com.yuyan.inputmethod.dictionary.DictionaryRules.validateRecord(key, value)
            key.startsWith("pref/") -> {
                require(!decode(key.removePrefix("pref/")).startsWith("transfer_")) { "Device credentials cannot be restored" }
                val obj = JSONObject(value)
                require(obj.getString("type") in setOf("string", "boolean", "int", "long", "float", "set")) { "Unknown preference type" }
            }
            key.startsWith("row/") -> {
                val parts = key.split('/')
                require(parts.size == 3 && parts[1] in tables) { "Unknown database table" }
                val row = JSONObject(value)
                require(row.keys().asSequence().toSet() == columns[parts[1]]) { "Incompatible database row" }
                require(parts[2] == tables.getValue(parts[1]).joinToString("_") { hex(row.get(it).toString()) }) { "Database record key mismatch" }
            }
            key.startsWith("learn/") -> {
                val parts = key.split('/')
                require(parts.size == 3 && parts[1] in dictionaries && value.toIntOrNull()?.let { it >= 0 } == true) { "Invalid learning record" }
                val fields = decode(parts[2]).split('\t')
                require(fields.size == 2 && fields.all { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') }) { "Invalid learning key" }
            }
            key.startsWith("theme/") -> TransferFiles.child(File("/theme"), key.removePrefix("theme/"))
            key.startsWith("dictionary/") -> {
                val pkg = SyncProtocol.json.decodeFromString<DictionaryPackage>(value)
                require(key == "dictionary/${pkg.family}") { "Dictionary family mismatch" }
                DictionaryStore.validateArchive(blobs.getValue(pkg.sha256), pkg)
            }
            else -> error("Unknown backup record type")
        } }
        if (scopes == null || SyncScope.DICTIONARIES.id in scopes) require("dictionary/frost" in values) { "Backup lacks the base dictionary" }
    }

    fun apply(context: Context, snapshot: LocalDataSnapshot, shared: File, user: File, work: File, forSync: Boolean = false) {
        val values = snapshot.values
        validate(values, snapshot.blobs, snapshot.scopes)
        fun includes(scope: SyncScope) = (snapshot.scopes == null || scope.id in snapshot.scopes) && (!forSync || scope.enabled(context))
        val stagedUser = File(work, "learning-staging").apply { mkdirs(); File(this, "logs").mkdirs() }
        val learningEnabled = includes(SyncScope.LEARNING)
        dictionaries.filter { learningEnabled }.forEach { dictionary ->
            val lines = values.filterKeys { it.startsWith("learn/$dictionary/") }.map { (key, count) ->
                decode(key.substringAfterLast('/')) + "\t" + count
            }
            if (lines.isNotEmpty()) {
                val input = File(work, "$dictionary-import.tsv").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
                check(Rime.transferUserDictionary(shared.path, stagedUser.path, dictionary, input.path, true) == lines.size) { "Learning import count mismatch" }
            }
        }
        Rime.exitRime()
        dictionaries.filter { learningEnabled }.forEach { dictionary ->
            val target = File(user, "$dictionary.userdb")
            check(!target.exists() || target.deleteRecursively()) { "Cannot replace user dictionary" }
            val source = File(stagedUser, "$dictionary.userdb")
            if (source.exists()) check(source.renameTo(target)) { "Cannot activate user dictionary" }
        }
        val database = DataBaseKT.instance.openHelper.writableDatabase
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        database.beginTransaction()
        try {
            tables.keys.forEach { table ->
                if (SyncScope.entries.none { it.matches("row/$table/") && includes(it) }) return@forEach
                database.execSQL("DELETE FROM `$table`")
                values.filterKeys { it.startsWith("row/$table/") }.values.forEach { value ->
                    val row = JSONObject(value)
                    val names = columns.getValue(table).sorted()
                    val args = names.map { name -> row.get(name).let { if (it == JSONObject.NULL) null else it } }.toTypedArray()
                    database.execSQL("INSERT INTO `$table` (${names.joinToString(",") { "`$it`" }}) VALUES (${names.joinToString(",") { "?" }})", args)
                }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        val editor = prefs.edit()
        val settingsEnabled = includes(SyncScope.SETTINGS)
        if (settingsEnabled) prefs.all.keys.filter { !it.startsWith("transfer_") && (!forSync || it != "rime_dictionary_family") }.forEach(editor::remove)
        values.filterKeys { settingsEnabled && it.startsWith("pref/") }.forEach { (key, value) ->
            val name = decode(key.removePrefix("pref/"))
            if (forSync && name == "rime_dictionary_family") return@forEach
            val row = JSONObject(value)
            when (row.getString("type")) {
                "string" -> editor.putString(name, row.getString("value"))
                "boolean" -> editor.putBoolean(name, row.getBoolean("value"))
                "int" -> editor.putInt(name, row.getInt("value"))
                "long" -> editor.putLong(name, row.getLong("value"))
                "float" -> editor.putFloat(name, row.getDouble("value").toFloat())
                "set" -> editor.putStringSet(name, row.getJSONArray("value").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() })
            }
        }
        check(editor.commit()) { "Cannot restore preferences" }
        if (includes(SyncScope.DICTIONARY_RULES)) com.yuyan.inputmethod.dictionary.DictionaryRules.restore(context, values)
        if (includes(SyncScope.THEMES)) {
            val theme = File(context.getExternalFilesDir(null), "theme").apply { mkdirs() }
            val expected = values.keys.filter { it.startsWith("theme/") }.map { it.removePrefix("theme/") }.toSet()
            theme.walkTopDown().filter { it.isFile && it.relativeTo(theme).invariantSeparatorsPath !in expected }.forEach { check(it.delete()) }
            expected.forEach { relative ->
                val file = TransferFiles.child(theme, relative)
                file.parentFile?.mkdirs()
                val source = snapshot.blobs.getValue(values.getValue("theme/$relative"))
                if (relative.endsWith(".json")) file.writeText(source.readText().replace("@theme", theme.absolutePath))
                else if (source.canonicalFile != file.canonicalFile) source.copyTo(file, overwrite = true)
            }
        }
        if (includes(SyncScope.DICTIONARIES)) restorePackages(context, snapshot)
    }

    fun restorePackages(context: Context, snapshot: LocalDataSnapshot) {
        val packages = snapshot.values.filterKeys { it.startsWith("dictionary/") }.values.map {
            SyncProtocol.json.decodeFromString<DictionaryPackage>(it)
        }.associateBy { it.family }
        packages.values.forEach { pkg ->
            val target = DictionaryStore.packageFile(context, pkg)
            val source = snapshot.blobs.getValue(pkg.sha256)
            if (source.canonicalFile != target.canonicalFile) {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        }
        DictionaryStore.setActive(context, packages)
    }
}
