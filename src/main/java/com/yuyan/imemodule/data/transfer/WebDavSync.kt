package com.yuyan.imemodule.data.transfer

import android.content.Context
import com.yuyan.imemodule.speech.SpeechCredentials
import com.yuyan.inputmethod.EngineRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

@Serializable
data class WebDavSettings(val url: String = "", val username: String = "", val password: String = "", val directory: String = "YuyanSelfOpt")

class SyncNeedsDecision(val count: Int) : Exception("Sync conflicts need a decision")
class SyncDeferred : Exception("Sync application deferred until the keyboard is hidden")

object WebDavSync {
    private val json get() = SyncProtocol.json
    fun configuration(context: Context): WebDavSettings = SpeechCredentials(context).read("webdav")?.let {
        json.decodeFromString<WebDavSettings>(it)
    } ?: WebDavSettings()

    fun configure(context: Context, settings: WebDavSettings) {
        require(settings.directory.matches(Regex("[A-Za-z0-9_-]{1,32}(/[A-Za-z0-9_-]{1,64})?"))) { "Invalid remote directory name" }
        WebDavClient(settings.url, settings.username, settings.password)
        val old = configuration(context)
        SpeechCredentials(context).write("webdav", json.encodeToString(settings))
        if (old.url != settings.url || old.username != settings.username || old.directory != settings.directory) {
            stateFile(context).delete()
            context.getSharedPreferences("transfer_state", Context.MODE_PRIVATE).edit().remove("sync_period")
                .putString("device_id", UUID.randomUUID().toString()).apply()
        }
    }

    fun stateFile(context: Context) = File(BackupArchive.directory(context), "sync-state.json")
    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("transfer_state", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("device_id", it).commit()) { "Cannot persist sync device ID" }
        }
    }

    fun synchronize(context: Context, policy: ConflictPolicy, idle: () -> Boolean = { true }, status: (String) -> Unit = {}): Int = TransferOperation.run {
        val scopes = SyncScope.entries.filter { it.enabled(context) }
        if (scopes.isEmpty()) { status("未选择参与云同步的数据"); return@run 0 }
        fun checkSelection() {
            if (scopes != SyncScope.entries.filter { it.enabled(context) }) throw SyncDeferred()
        }
        val config = configuration(context)
        require(config.url.isNotBlank() && config.username.isNotBlank() && config.password.isNotBlank()) { "WebDAV is not configured" }
        val client = WebDavClient(config.url, config.username, config.password)
        val remote = config.directory
        client.ensureDirectory(remote)
        client.ensureDirectory("$remote/objects")
        client.ensureDirectory("$remote/history")
        client.ensureDirectory("$remote/categories")
        val objects = File(BackupArchive.directory(context), "objects").apply { mkdirs() }
        val device = deviceId(context)
        val state = stateFile(context)
        repeat(4) { attempt ->
            if (!idle()) throw SyncDeferred()
            status("正在读取本机数据…")
            val work = File(BackupArchive.directory(context), "sync-work").apply { deleteRecursively(); mkdirs() }
            try {
                val snapshot = EngineRuntime.maintenance(context, idle) { shared, user ->
                    LocalDataSnapshots.capture(context, shared, user, work, forSync = true)
                }
                val previous = if (TransferFiles.exists(state)) json.decodeFromString<SyncDocument>(TransferFiles.read(state)) else SyncDocument()
                val selectedValues = SyncScope.select(context, snapshot.values)
                val selected = SyncDocument(records = previous.records.filterKeys { SyncScope.includes(context, it) })
                val observed = SyncProtocol.capture(selected, selectedValues, device, System.currentTimeMillis(), snapshot.modified)
                val local = SyncDocument(records = previous.records + observed.records)
                TransferFiles.write(state, json.encodeToString(local))
                status("正在比较其他设备的修改…")
                val heads = mutableMapOf<SyncScope, WebDavClient.Remote?>()
                val documents = mutableMapOf<SyncScope, SyncDocument>()
                var conflictCount = 0
                scopes.forEach { scope ->
                    checkSelection()
                    val head = client.read("$remote/categories/${scope.id}.json")
                    heads[scope] = head
                    val other = head?.let { json.decodeFromString<SyncDocument>(it.text) } ?: SyncDocument()
                    require(other.format == 1 && other.records.keys.all(scope::matches)) { "Remote data category mismatch" }
                    val remoteSelected = other.records.filterKeys { SyncScope.includes(context, it) }
                    if (policy == ConflictPolicy.LATEST) require(remoteSelected.values.none { it.modified > System.currentTimeMillis() + 5 * 60 * 1000L }) {
                        "Remote device clock is ahead; correct its time or choose a conflict policy explicitly"
                    }
                    val merged = SyncProtocol.merge(
                        SyncDocument(records = local.records.filterKeys { scope.matches(it) && SyncScope.includes(context, it) }),
                        SyncDocument(records = remoteSelected), policy)
                    conflictCount += merged.conflicts.size
                    documents[scope] = SyncDocument(records = (other.records.filterKeys { !SyncScope.includes(context, it) } + merged.document.records).toSortedMap())
                }
                if (policy == ConflictPolicy.ASK && conflictCount > 0) throw SyncNeedsDecision(conflictCount)
                val mergedRecords = documents.values.flatMap { it.records.entries }.associate { it.toPair() }
                val values = mergedRecords.filter { (key, entry) -> entry.value != null && SyncScope.includes(context, key) }
                    .mapValues { it.value.value!! }
                val needed = LocalDataSnapshots.neededBlobs(values)
                snapshot.blobs.forEach { (hash, file) ->
                    if (hash in needed) {
                        checkSelection()
                        status("正在上传数据资源…")
                        client.upload("$remote/objects/$hash", file)
                    }
                }
                val downloaded = needed.associateWith { hash ->
                    snapshot.blobs[hash] ?: File(objects, hash).also { file ->
                        if (!file.exists() || TransferFiles.sha256(file) != hash) {
                            checkSelection()
                            file.delete()
                            status("正在下载其他设备的数据…")
                            client.download("$remote/objects/$hash", file, hash)
                        }
                    }
                }
                if (!idle()) throw SyncDeferred()
                // 网络请求期间可能重新输入，提交远端前再读取一次，禁止用旧快照覆盖新学习。
                val latest = EngineRuntime.maintenance(context, idle) { shared, user ->
                    LocalDataSnapshots.capture(context, shared, user, File(work, "recheck"), forSync = true)
                }
                checkSelection()
                if (SyncScope.select(context, latest.values) != selectedValues) return@repeat
                val appliedValues = latest.values.filterKeys { !SyncScope.includes(context, it) } + values
                val available = snapshot.blobs + downloaded + latest.blobs
                val files = LocalDataSnapshots.neededBlobs(appliedValues).associateWith { available.getValue(it) }
                LocalDataSnapshots.validate(appliedValues, files)
                val changedDocuments = documents.filter { (scope, document) -> heads[scope]?.text != json.encodeToString(document) }
                try {
                    changedDocuments.forEach { (scope, document) ->
                        checkSelection()
                        client.write("$remote/categories/${scope.id}.json", json.encodeToString(document), heads[scope])
                    }
                } catch (conflict: WebDavClient.Conflict) {
                    if (attempt == 3) throw conflict else return@repeat
                }
                if (!idle()) throw SyncDeferred()
                val changed = (selectedValues.keys + values.keys).count { selectedValues[it] != values[it] }
                if (changed > 0) {
                    status("正在应用同步数据…")
                    BackupArchive.apply(context, LocalDataSnapshot(appliedValues, files), forSync = true,
                        canApply = { checkSelection(); idle() }, expectedValues = selectedValues)
                }
                TransferFiles.write(state, json.encodeToString(SyncDocument(records = (local.records + mergedRecords).toSortedMap())))
                changedDocuments.forEach { (scope, document) ->
                    checkSelection()
                    val text = json.encodeToString(document)
                    val prefix = "$remote/history/${scope.id}-$device-"
                    val historyName = "$prefix${System.currentTimeMillis()}.json"
                    client.write(historyName, text, null)
                    val prefs = context.getSharedPreferences("transfer_state", Context.MODE_PRIVATE)
                    val historyKey = "sync_history_${scope.id}"
                    val history = prefs.getString(historyKey, "").orEmpty().lines().filter { it.startsWith(prefix) } + historyName
                    history.dropLast(5).forEach { client.delete(it) }
                    prefs.edit().putString(historyKey, history.takeLast(5).joinToString("\n")).apply()
                }
                status("同步完成，本机应用 $changed 项变更，处理 $conflictCount 项冲突")
                return@run changed
            } finally { work.deleteRecursively() }
        }
        error("Data kept changing during synchronization; retry when idle")
    }
}
