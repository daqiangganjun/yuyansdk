package com.yuyan.imemodule.data.transfer

import android.content.Context
import com.yuyan.inputmethod.DictionaryStore
import com.yuyan.inputmethod.EngineRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class BackupMetadata(
    val format: Int = 2,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val exportTime: Long,
    val checksums: Map<String, String> = emptyMap(),
    val scopes: Set<String>? = null,
)

@Serializable
private data class PartialBackupData(val values: Map<String, String>, val scopes: Set<String>)

object BackupArchive {
    private val json get() = SyncProtocol.json
    fun directory(context: Context) = File(context.filesDir, "transfer").apply { mkdirs() }
    fun journal(context: Context) = File(directory(context), "recovery.zip")
    private fun marker(context: Context) = File(directory(context), "restore-pending")

    fun write(context: Context, output: OutputStream, snapshot: LocalDataSnapshot, timestamp: Long = System.currentTimeMillis()) {
        LocalDataSnapshots.validate(snapshot.values, snapshot.blobs, snapshot.scopes)
        val data = (snapshot.scopes?.let { json.encodeToString(PartialBackupData(snapshot.values, it)) }
            ?: json.encodeToString(snapshot.values)).toByteArray()
        val checksums = mutableMapOf("data.json" to TransferFiles.sha256(data.inputStream()))
        snapshot.blobs.keys.forEach { checksums["blobs/$it"] = it }
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val metadata = BackupMetadata(format = if (snapshot.scopes == null) 2 else 3, packageName = context.packageName, versionCode = info.versionCode.toLong(),
            versionName = info.versionName.orEmpty(), exportTime = timestamp, checksums = checksums, scopes = snapshot.scopes)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("data.json")); zip.write(data); zip.closeEntry()
            zip.setLevel(0)
            snapshot.blobs.toSortedMap().forEach { (hash, file) ->
                zip.putNextEntry(ZipEntry("blobs/$hash"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write(json.encodeToString(metadata).toByteArray())
            zip.closeEntry()
        }
    }

    fun read(input: InputStream, target: File): Pair<BackupMetadata, LocalDataSnapshot> {
        val files = TransferFiles.extract(input, target) { it == "metadata.json" || it == "data.json" || it.matches(Regex("blobs/[0-9a-f]{64}")) }
        val metadata = json.decodeFromString<BackupMetadata>(File(target, "metadata.json").readText())
        require(metadata.format in 2..3 && (metadata.format == 3) == (metadata.scopes != null)) { "Unsupported backup format" }
        require(files.map { it.relativeTo(target).invariantSeparatorsPath }.toSet() == metadata.checksums.keys + "metadata.json") { "Backup file list mismatch" }
        metadata.checksums.forEach { (name, hash) -> require(TransferFiles.sha256(TransferFiles.child(target, name)) == hash) { "Backup checksum mismatch" } }
        val data = File(target, "data.json").readText()
        val values = if (metadata.format == 3) {
            val partial = json.decodeFromString<PartialBackupData>(data)
            require(partial.scopes == metadata.scopes) { "Backup categories checksum mismatch" }
            partial.values
        } else json.decodeFromString<Map<String, String>>(data)
        val blobs = LocalDataSnapshots.neededBlobs(values).associateWith { File(target, "blobs/$it") }
        LocalDataSnapshots.validate(values, blobs, metadata.scopes)
        return metadata to LocalDataSnapshot(values, blobs, scopes = metadata.scopes)
    }

    fun export(context: Context, output: OutputStream, timestamp: Long, scopes: Set<SyncScope> = SyncScope.entries.toSet()) = TransferOperation.run {
        val work = File(directory(context), "export-work")
        check(!work.exists() || work.deleteRecursively()) { "Cannot clear export workspace" }
        try {
            EngineRuntime.maintenance(context) { shared, user -> write(context, output, LocalDataSnapshots.capture(context, shared, user, work).select(scopes), timestamp) }
        } finally { work.deleteRecursively() }
    }

    fun apply(context: Context, snapshot: LocalDataSnapshot, forSync: Boolean = false,
              canApply: () -> Boolean = { true }, expectedValues: Map<String, String>? = null): Unit = TransferOperation.run {
        val work = File(directory(context), "apply-work")
        check(!work.exists() || work.deleteRecursively()) { "Cannot clear restore workspace" }
        work.mkdirs()
        require(context.filesDir.usableSpace >= snapshot.blobs.values.sumOf { it.length() } * 3 + 64L * 1024 * 1024) {
            "Insufficient storage for a recoverable restore"
        }
        LocalDataSnapshots.validate(snapshot.values, snapshot.blobs, snapshot.scopes)
        try {
            EngineRuntime.maintenance(context, canApply) { shared, user ->
                if (expectedValues != null && SyncScope.select(context, LocalDataSnapshots.capture(context, shared, user, File(work, "verify"), forSync = true).values) != expectedValues)
                    throw SyncDeferred()
                val old = LocalDataSnapshots.capture(context, shared, user, File(work, "before"))
                val pending = File(directory(context), "recovery.partial")
                write(context, pending.outputStream(), old)
                check(pending.renameTo(journal(context))) { "Cannot persist recovery snapshot" }
                TransferFiles.write(marker(context), "2")
                LocalDataSnapshots.apply(context, snapshot, shared, user, File(work, "incoming"), forSync)
            }
            check(marker(context).delete()) { "Cannot complete restore transaction" }
            com.yuyan.imemodule.database.SkbMenuCache.invalidate()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                com.yuyan.imemodule.data.theme.ThemeManager.refreshThemes()
            }
        } catch (error: Throwable) {
            if (marker(context).exists()) {
                runCatching { EngineRuntime.maintenance(context) { _, _ -> recoverOnStartup(context) } }
                    .onFailure { error.addSuppressed(it) }
            }
            throw error
        } finally { work.deleteRecursively() }
        Unit
    }

    fun recoverOnStartup(context: Context) {
        if (!marker(context).exists()) return
        val work = File(directory(context), "recovery-work")
        check(!work.exists() || work.deleteRecursively()) { "Cannot clear recovery workspace" }
        try {
            val snapshot = read(journal(context).inputStream(), work).second
            LocalDataSnapshots.restorePackages(context, snapshot)
            val shared = DictionaryStore.compose(context)
            val user = File(DictionaryStore.root(context), "user").apply { mkdirs(); File(this, "logs").mkdirs() }
            LocalDataSnapshots.apply(context, snapshot, shared, user, File(work, "learning"))
            val build = File(user, "build")
            check(!build.exists() || build.deleteRecursively()) { "Cannot reset interrupted data deployment" }
            File(user, ".deployment").delete()
            check(marker(context).delete()) { "Cannot finish recovery" }
            context.getSharedPreferences("transfer_state", Context.MODE_PRIVATE).edit()
                .putBoolean("sync_requested", false).putBoolean("sync_running", false).putBoolean("sync_requires_attention", true)
                .putString("status", "上次数据应用中断，已恢复本机快照；请检查同步范围后手动重试").commit()
        } finally { work.deleteRecursively() }
    }
}
