package com.yuyan.imemodule.manager

import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.utils.errorRuntime
import com.yuyan.imemodule.utils.withTempDir
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object UserDataManager {

    private val json = Json { prettyPrint = true }

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long
    )

    data class Preview(val version: String, val time: Long, val complete: Boolean, val scopes: Set<String>? = null)

    fun preview(source: InputStream): Preview = java.util.zip.ZipInputStream(source.buffered()).use { zip ->
        var bytes = 0L
        var entries = 0
        val buffer = ByteArray(65536)
        while (true) {
            val entry = zip.nextEntry ?: error("Backup metadata is missing")
            require(++entries <= 20000) { "Too many backup entries" }
            val metadata = if (entry.name == "metadata.json") java.io.ByteArrayOutputStream() else null
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                bytes += read
                require(bytes <= com.yuyan.imemodule.data.transfer.TransferFiles.MAX_EXPANDED_BYTES) { "Backup is too large" }
                if (metadata != null) {
                    require(metadata.size() + read <= 4 * 1024 * 1024) { "Backup metadata is too large" }
                    metadata.write(buffer, 0, read)
                }
            }
            if (metadata != null) {
                val info = org.json.JSONObject(metadata.toString("UTF-8"))
                val format = info.optInt("format", 1)
                require(format in 1..3) { "Unsupported backup format" }
                val scopes = if (format == 3) info.getJSONArray("scopes").let { list -> (0 until list.length()).map { list.getString(it) }.toSet() } else null
                require(scopes == null || scopes.isNotEmpty() && scopes.all { id -> com.yuyan.imemodule.data.transfer.SyncScope.entries.any { it.id == id } }) { "Invalid backup categories" }
                return@use Preview(info.getString("versionName"), info.getLong("exportTime"), format >= 2, scopes)
            }
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis(), scopes: Set<com.yuyan.imemodule.data.transfer.SyncScope> = com.yuyan.imemodule.data.transfer.SyncScope.entries.toSet()) = runCatching {
        com.yuyan.imemodule.data.transfer.BackupArchive.export(Launcher.instance.context, dest, timestamp, scopes)
    }

    fun import(src: InputStream) = runCatching {
        withTempDir { tempDir ->
            val archive = File(tempDir, "incoming.zip")
            src.use { input -> archive.outputStream().use { input.copyTo(it) } }
            val metadataText = java.util.zip.ZipFile(archive).use { zip ->
                val entry = zip.getEntry("metadata.json") ?: errorRuntime(R.string.exception_user_data_metadata)
                require(entry.size in 1..(4L * 1024 * 1024)) { "Invalid backup metadata size" }
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
            if (org.json.JSONObject(metadataText).optInt("format", 1) >= 2) {
                val (info, snapshot) = com.yuyan.imemodule.data.transfer.BackupArchive.read(archive.inputStream(), File(tempDir, "verified"))
                @Suppress("DEPRECATION") val currentVersion = Launcher.instance.context.packageManager.getPackageInfo(Launcher.instance.context.packageName, 0).versionCode.toLong()
                require(info.versionCode <= currentVersion) { "Backup requires a newer app version" }
                com.yuyan.imemodule.data.transfer.BackupArchive.apply(Launcher.instance.context, snapshot)
                Metadata(info.packageName, info.versionCode, info.versionName, info.exportTime)
            } else {
                val legacy = File(tempDir, "legacy")
                com.yuyan.imemodule.data.transfer.TransferFiles.extract(archive.inputStream(), legacy) {
                    it == "metadata.json" || it.startsWith("shared_prefs/") || it.startsWith("databases/") || it.startsWith("external/")
                }
                val metadata = json.decodeFromString<Metadata>(metadataText)
                val context = Launcher.instance.context
                val snapshot = com.yuyan.inputmethod.EngineRuntime.maintenance(context) { shared, user ->
                    val current = com.yuyan.imemodule.data.transfer.LocalDataSnapshots.capture(context, shared, user, File(tempDir, "current"))
                    com.yuyan.imemodule.data.transfer.LocalDataSnapshots.fromLegacy(context, legacy, current, shared, File(tempDir, "converted"))
                }
                com.yuyan.imemodule.data.transfer.BackupArchive.apply(context, snapshot)
                metadata
            }
        }
    }
}
