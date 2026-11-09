package com.yuyan.inputmethod.dictionary

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.yuyan.imemodule.data.transfer.SyncProtocol
import com.yuyan.imemodule.data.transfer.TransferFiles
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class DictionaryBuildException(val description: String, val buildKey: String = "") : Exception("Dictionary build failed")
data class PreparedDictionary(val work: File, val result: DictionaryBuildStatus)

object DictionaryBuildClient {
    private fun readStatus(work: File): DictionaryBuildStatus? = sequenceOf("status.json", "status.json.bak").mapNotNull { name ->
        runCatching { SyncProtocol.json.decodeFromString<DictionaryBuildStatus>(File(work, name).readText()) }.getOrNull()
    }.firstOrNull()
    fun run(context: Context, request: DictionaryBuildRequest, progress: (String) -> Unit): PreparedDictionary {
        val preferences = context.getSharedPreferences("transfer_state", Context.MODE_PRIVATE)
        val active = preferences.getString("active_dictionary_job", null)?.takeIf { it.matches(Regex("[0-9a-f-]{36}")) }
        val previous = active?.let { File(context.filesDir, "dictionary-builds/$it") }
        val reusable = previous?.let { old -> runCatching {
            val original = SyncProtocol.json.decodeFromString<DictionaryBuildRequest>(File(old, "request.json").readText())
            val status = readStatus(old) ?: return@runCatching false
            original.copy(blockedBuild = "") == request.copy(blockedBuild = "") && status.state in setOf("running", "ready", "unchanged", "blocked")
        }.getOrDefault(false) } == true
        if (active != null && !reusable) {
            context.stopService(Intent(context, DictionaryBuildService::class.java))
        }
        val id = if (reusable) active!! else UUID.randomUUID().toString()
        val pendingJob = preferences.getString("prepared_dictionary_job", null)
        File(context.filesDir, "dictionary-builds").listFiles().orEmpty().filter { it.isDirectory && it.name != pendingJob && it.name != active }.forEach { old ->
            old.listFiles().orEmpty().filter { it.isDirectory || it.extension == "zip" }.forEach { it.deleteRecursively() }
        }
        val work = File(context.filesDir, "dictionary-builds/$id").apply { mkdirs() }
        TransferFiles.write(File(work, "request.json"), SyncProtocol.json.encodeToString(request))
        if (reusable) {
            val status = requireNotNull(readStatus(work))
            if (status.state != "running") {
                preferences.edit().remove("active_dictionary_job").apply()
                progress(status.message)
                return PreparedDictionary(work, status)
            }
        }
        preferences.edit().putString("active_dictionary_job", id).commit()
        val disconnected = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) { disconnected.set(true) }
            override fun onBindingDied(name: ComponentName?) { disconnected.set(true) }
        }
        val intent = Intent(context, DictionaryBuildService::class.java).putExtra("job", id)
        var bound = false
        try {
            ContextCompat.startForegroundService(context, intent)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Cannot bind dictionary builder" }
            val deadline = android.os.SystemClock.elapsedRealtime() + 30 * 60 * 1000
            var previous: String? = null
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val status = readStatus(work)
                if (status != null) {
                    if (status.message != previous) { progress(status.message); previous = status.message }
                    if (status.state in setOf("ready", "unchanged", "blocked")) return PreparedDictionary(work, status)
                    if (status.state in setOf("failed", "cancelled")) throw DictionaryBuildException(status.message, status.buildKey)
                }
                if (disconnected.get()) throw DictionaryBuildException("构建进程意外结束，原词库保持不变，可手动重试", status?.buildKey.orEmpty())
                Thread.sleep(300)
            }
            context.startService(Intent(context, DictionaryBuildService::class.java).setAction("cancel"))
            throw DictionaryBuildException("构建超时，原词库保持不变，可稍后重试")
        } finally {
            if (bound) context.unbindService(connection)
            preferences.edit().remove("active_dictionary_job").apply()
        }
    }
}
