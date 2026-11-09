package com.yuyan.imemodule.data.transfer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.yuyan.inputmethod.DictionaryPackage
import com.yuyan.inputmethod.DictionaryStore
import com.yuyan.inputmethod.EngineRuntime
import com.yuyan.inputmethod.dictionary.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object TransferCoordinator {
    val status = MutableLiveData("尚未执行更新或同步")
    @Volatile var keyboardVisible = false
        private set
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "DictionarySync") }
    private val busy = AtomicBoolean(false)
    @Volatile private var activatingDictionary = false
    fun isRunning(): Boolean = busy.get()
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private fun state(context: Context) = context.getSharedPreferences("transfer_state", Context.MODE_PRIVATE)
    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)
    private fun pending(context: Context) = File(BackupArchive.directory(context), "dictionary-pending.json")
    fun hasPendingUpdate(context: Context) = state(context).getBoolean("update_requested", false) ||
        state(context).getBoolean("update_running", false) || TransferFiles.exists(pending(context))
    private fun report(context: Context, message: String, task: String = "update") {
        state(context).edit().putString("status", message).putString("status_task", task).putString("${task}_status", message).apply()
        main.post { status.value = message }
    }

    fun initialize(context: Context) {
        main.post {
            if (initialized) return@post
            initialized = true
            status.value = lastStatus(context)
            if (state(context).getBoolean("dictionary_recovery_requested", false)) restoreDefaultDictionary(context)
            EngineRuntime.status.observeForever { phase ->
                if (phase.phase == EngineRuntime.Phase.Ready && !keyboardVisible &&
                    (pending(context).isFile || state(context).getBoolean("sync_requested", false))) drain(context.applicationContext)
            }
        }
    }

    private fun recoverInterrupted(context: Context) {
        if (busy.get()) return
        val state = state(context)
        val edit = state.edit()
        for (task in listOf("update", "sync")) if (state.getBoolean("${task}_running", false)) {
            edit.putBoolean("${task}_requested", true).putBoolean("${task}_running", false)
        }
        edit.apply()
    }

    private fun due(context: Context, task: String): Boolean {
        if (task == "update" && (!hasUpdateSource(context) || state(context).getBoolean("dictionary_recovery_requested", false))) return false
        if (task == "sync" && state(context).getBoolean("sync_requires_attention", false)) return false
        val frequency = prefs(context).getString("transfer_${task}_frequency", "off").orEmpty()
        val now = System.currentTimeMillis()
        val period = SyncProtocol.period(frequency, now) ?: return false
        return period != state(context).getString("${task}_period", "") &&
            now - state(context).getLong("${task}_attempt", 0L) >= 30 * 60 * 1000L
    }

    private fun success(context: Context, task: String) {
        val frequency = prefs(context).getString("transfer_${task}_frequency", "off").orEmpty()
        state(context).edit().putString("${task}_period", SyncProtocol.period(frequency, System.currentTimeMillis()))
            .putLong("${task}_success", System.currentTimeMillis()).putBoolean("${task}_requested", false).apply()
        main.post { status.value = lastStatus(context) }
    }

    fun onKeyboardShown(context: Context) {
        keyboardVisible = true
        val app = context.applicationContext
        recoverInterrupted(app)
        if (hasUpdateSource(app) && !state(app).getBoolean("dictionary_recovery_requested", false) && prefs(app).getString("rime_dictionary_family", "frost") == "ice" && !DictionaryStore.hasIce(app) &&
            System.currentTimeMillis() - state(app).getLong("update_attempt", 0L) >= 30 * 60 * 1000L && !pending(app).exists()) {
            state(app).edit().putBoolean("ice_requested", true).putBoolean("update_requested", true).apply()
        }
        if (due(app, "update")) state(app).edit().putBoolean("update_requested", true).apply()
        if (due(app, "sync")) state(app).edit().putBoolean("sync_requested", true).apply()
        drain(app)
    }

    fun onKeyboardHidden(context: Context) {
        keyboardVisible = false
        main.postDelayed({ if (!keyboardVisible) drain(context.applicationContext) }, 500)
    }

    fun requestUpdate(context: Context, ice: Boolean = false) {
        if (state(context).getBoolean("dictionary_recovery_requested", false)) return
        if (!hasUpdateSource(context)) {
            report(context, "尚未配置在线词库源，请填写有效的 HTTPS 索引地址，或导入本地词库包")
            return
        }
        state(context).edit().putBoolean("update_cancel_requested", false).putBoolean("update_requires_attention", false).putBoolean("update_requested", true).putBoolean("update_manual", true)
            .putBoolean("ice_requested", ice || state(context).getBoolean("ice_requested", false)).apply()
        drain(context.applicationContext)
    }

    fun requestSync(context: Context, policy: ConflictPolicy? = null) {
        report(context, "已请求同步，等待键盘空闲后执行", "sync")
        state(context).edit().putBoolean("sync_requested", true).putBoolean("sync_requires_attention", false).putString("sync_override", policy?.name).apply()
        drain(context.applicationContext)
    }

    fun restoreDefaultDictionary(context: Context) {
        val app = context.applicationContext
        state(app).edit().putBoolean("dictionary_recovery_requested", true).putBoolean("update_requested", false).putBoolean("ice_requested", false).apply()
        runCatching { app.startService(android.content.Intent(app, DictionaryBuildService::class.java).setAction("cancel")) }
        report(app, "正在停止更新并恢复默认词库…")
        executor.execute {
            try {
                TransferOperation.run {
                    android.util.AtomicFile(pending(app)).delete()
                    state(app).edit().remove("prepared_dictionary_job").remove("active_dictionary_job").remove("blocked_dictionaries")
                        .remove("update_requires_attention").remove("update_running").remove("update_requested").remove("update_details").remove("failed_build_revision").apply()
                    EngineRuntime.restoreBundled(app)
                    state(app).edit().putString("update_period", SyncProtocol.period(prefs(app).getString("transfer_update_frequency", "off").orEmpty(), System.currentTimeMillis())).apply()
                }
                report(app, "已恢复内置白霜词库与默认更新规则，学习记录和其他个人设置已保留")
            } catch (error: Throwable) { report(app, "默认词库恢复失败：${safeError(error)}") }
            finally { state(app).edit().remove("dictionary_recovery_requested").apply() }
        }
    }

    fun cancelUpdate(context: Context) {
        if (activatingDictionary) { report(context, "词库正在完成切换，完成后可使用恢复默认"); return }
        val period = SyncProtocol.period(prefs(context).getString("transfer_update_frequency", "off").orEmpty(), System.currentTimeMillis())
        state(context).edit().putBoolean("update_cancel_requested", true).putBoolean("update_requested", false)
            .putBoolean("ice_requested", false).putString("update_period", period).remove("prepared_dictionary_job").apply()
        android.util.AtomicFile(pending(context)).delete()
        runCatching { context.startService(android.content.Intent(context, DictionaryBuildService::class.java).setAction("cancel")) }
        report(context, "已取消词库更新，继续使用原词库")
    }

    fun installLocalPackage(context: Context, pkg: DictionaryPackage) {
        val app = context.applicationContext
        executor.execute {
            try {
                state(app).edit().remove("prepared_dictionary_job").apply()
                if (DictionaryStore.active(app)[pkg.family]?.sha256 == pkg.sha256) {
                    if (pkg.family == "ice") prefs(app).edit().putString("rime_dictionary_family", "ice").apply()
                    report(app, "此词库版本已经安装")
                    return@execute
                }
                val queued = pending(app).takeIf(TransferFiles::exists)?.let {
                    SyncProtocol.json.decodeFromString<List<DictionaryPackage>>(TransferFiles.read(it))
                }.orEmpty().associateBy { it.family }
                TransferFiles.write(pending(app), SyncProtocol.json.encodeToString((queued + (pkg.family to pkg)).values.toList()))
                if (pkg.family == "ice") state(app).edit().putBoolean("ice_requested", true).apply()
                report(app, "本地词库已校验，等待键盘空闲后启用")
                drain(app)
            } catch (error: Throwable) { report(app, "本地词库导入失败：${safeError(error)}") }
        }
    }

    private fun drain(context: Context) {
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                if (state(context).getBoolean("update_requested", false)) {
                    state(context).edit().putLong("update_attempt", System.currentTimeMillis()).putBoolean("update_cancel_requested", false).putBoolean("update_requested", false).putBoolean("update_running", true).commit()
                    try { update(context) } catch (error: Throwable) {
                        state(context).edit().putBoolean("update_requires_attention", true).apply()
                        if (error is DictionaryBuildException && error.buildKey.isNotBlank()) state(context).edit().putString("failed_build_revision", error.buildKey).apply()
                        report(context, "词库更新失败：${safeError(error)}")
                    }
                    finally { state(context).edit().putBoolean("update_running", false).remove("update_manual").apply() }
                }
                if (!keyboardVisible && pending(context).isFile && EngineRuntime.isReady) {
                    runCatching { activate(context) }.onFailure { report(context, "词库启用失败，已尝试恢复上一版：${safeError(it)}") }
                }
                if (!keyboardVisible && state(context).getBoolean("sync_requested", false) && EngineRuntime.isReady) {
                    state(context).edit().putLong("sync_attempt", System.currentTimeMillis()).putBoolean("sync_requested", false).putBoolean("sync_running", true).commit()
                    val policy = state(context).getString("sync_override", null) ?: prefs(context).getString("transfer_conflict_policy", "LATEST")!!
                    try {
                        WebDavSync.synchronize(context, ConflictPolicy.entries.firstOrNull { it.name == policy } ?: ConflictPolicy.LATEST, { !keyboardVisible }) { report(context, it, "sync") }
                        selectRequestedIce(context)
                        success(context, "sync")
                        state(context).edit().remove("sync_conflicts").remove("sync_override").apply()
                    } catch (_: SyncDeferred) {
                        state(context).edit().putBoolean("sync_requested", true).apply()
                        report(context, "同步已准备，等待收起键盘后继续", "sync")
                    } catch (conflicts: SyncNeedsDecision) {
                        state(context).edit().putInt("sync_conflicts", conflicts.count).apply()
                        report(context, "发现 ${conflicts.count} 项冲突，请在同步设置中选择处理方式", "sync")
                    } catch (error: Throwable) { report(context, "同步失败：${safeError(error)}", "sync") }
                    finally { state(context).edit().putBoolean("sync_running", false).apply() }
                }
            } finally {
                busy.set(false)
                main.post {
                    if (state(context).getBoolean("update_requested", false) ||
                        (!keyboardVisible && EngineRuntime.isReady && (pending(context).isFile || state(context).getBoolean("sync_requested", false)))) drain(context)
                }
            }
        }
    }

    fun hasUpdateSource(context: Context): Boolean = when (val mode = DictionaryRules.settings(context).mode) {
        "package" -> DictionaryStore.configuredIndex(context).isNotBlank()
        "network" -> DictionaryRules.settings(context).networkUrl.isNotBlank()
        else -> mode in setOf("builtin", "custom")
    }

    private fun requestedFamily(context: Context) = if (state(context).getBoolean("ice_requested", false)) "ice"
        else prefs(context).getString("rime_dictionary_family", "frost").let { if (it == "ice") "ice" else "frost" }

    private fun update(context: Context) {
        val family = requestedFamily(context)
        val configuration = DictionaryRules.settings(context)
        val active = DictionaryStore.active(context)
        active.values.forEach { DictionaryStore.materialize(context, it) }
        val request = if (configuration.mode == "package") {
            val index = DictionaryStore.fetchIndex(context, DictionaryStore.configuredIndex(context))
            val pkg = index.packages.singleOrNull { it.family == family } ?: error("Selected dictionary is absent from index")
            if (active[family]?.sha256 == pkg.sha256) { report(context, "所选词库已是当前发布的最新版本"); success(context, "update"); return }
            report(context, "正在下载所选词库…")
            DictionaryStore.download(context, pkg) { read, total -> report(context, "正在下载词库：${read * 100 / total}%") }
            DictionaryBuildRequest(family, active + (family to pkg), fuzzyRules = com.yuyan.inputmethod.util.FuzzyPinYinUtils.algebraRules())
        } else {
            if (configuration.mode == "network") {
                report(context, "正在获取词库更新规则…")
                DictionaryRules.import(context, GithubDictionarySource.text(configuration.networkUrl))
            }
            val rule = DictionaryRules.selected(context, family)
            val ruleHash = java.security.MessageDigest.getInstance("SHA-256").digest(SyncProtocol.json.encodeToString(rule.copy(updatedAt = 0)).toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            DictionaryBuildRequest(family, active, rule, com.yuyan.inputmethod.util.FuzzyPinYinUtils.algebraRules(), ruleHash)
        }
        if (state(context).getBoolean("update_cancel_requested", false) || state(context).getBoolean("dictionary_recovery_requested", false)) return
        val blocked = if (state(context).getBoolean("update_manual", false)) "" else state(context).getString("failed_build_revision", "").orEmpty()
        val configurationHash = DictionaryRules.configurationHash(context)
        val prepared = DictionaryBuildClient.run(context, request.copy(blockedBuild = blocked, configurationHash = configurationHash)) { report(context, it) }
        if (state(context).getBoolean("dictionary_recovery_requested", false) || state(context).getBoolean("update_cancel_requested", false)) return
        if (prepared.result.state in setOf("unchanged", "blocked")) { success(context, "update"); return }
        if (requestedFamily(context) != family || request.fuzzyRules != com.yuyan.inputmethod.util.FuzzyPinYinUtils.algebraRules() || configurationHash != DictionaryRules.configurationHash(context)) {
            report(context, "构建已完成，但词库选择或配置已变化，本次不启用；请重新检查")
            return
        }
        TransferFiles.write(pending(context), SyncProtocol.json.encodeToString(prepared.result.packages))
        state(context).edit().putString("prepared_dictionary_job", prepared.work.name).apply()
        report(context, "构建与测试通过，等待键盘空闲后启用")
        success(context, "update")
    }
    private fun activate(context: Context) {
        val packages = SyncProtocol.json.decodeFromString<List<DictionaryPackage>>(TransferFiles.read(pending(context)))
        report(context, "正在启用新词库…")
        activatingDictionary = true
        try {
            val job = state(context).getString("prepared_dictionary_job", null)
            val prepared = job?.takeIf { it.matches(Regex("[0-9a-f-]{36}")) }?.let { File(context.filesDir, "dictionary-builds/$it") }
            EngineRuntime.activatePackages(context, DictionaryStore.active(context) + packages.associateBy { it.family }, prepared)
            selectRequestedIce(context)
            state(context).edit().putStringSet("blocked_dictionaries",
                state(context).getStringSet("blocked_dictionaries", emptySet()).orEmpty() - packages.map { it.sha256 }.toSet()).apply()
            val details = packages.joinToString("\n\n") {
                val date = if (it.updatedAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(it.updatedAt)) else it.revision.take(8)
                "${if (it.family == "ice") "雾凇" else "白霜"} · $date\n${it.summary}"
            }
            state(context).edit().putString("update_details", details).apply()
            pending(context).delete()
            state(context).edit().remove("prepared_dictionary_job").apply()
            prepared?.deleteRecursively()
            report(context, "词库更新完成，下次打开键盘时显示详情")
        } catch (error: Throwable) {
            pending(context).renameTo(File(pending(context).parentFile, "dictionary-failed.json"))
            state(context).edit().remove("prepared_dictionary_job").apply()
            throw error
        } finally { activatingDictionary = false }
    }

    private fun selectRequestedIce(context: Context) {
        if (state(context).getBoolean("ice_requested", false) && DictionaryStore.hasIce(context)) {
            prefs(context).edit().putString("rime_dictionary_family", "ice").commit()
            state(context).edit().putBoolean("ice_requested", false).apply()
        }
    }

    fun updateDetails(context: Context): String? = state(context).getString("update_details", null)
    fun acknowledgeDetails(context: Context) { state(context).edit().remove("update_details").apply() }
    fun lastStatus(context: Context, selectedTask: String? = null): String = buildString {
        val preferences = state(context)
        val key = selectedTask?.let { "${it}_status" } ?: "status"
        val fallback = if (selectedTask == null || preferences.getString("status_task", null) == selectedTask)
            preferences.getString("status", "尚未执行任务") else when (selectedTask) {
                "update" -> "暂无词库更新状态"
                "sync" -> "暂无云同步状态"
                else -> "尚未执行任务"
            }
        append(preferences.getString(key, fallback))
        if (selectedTask != "update" && preferences.getBoolean("sync_requires_attention", false)) append("\n自动同步等待手动重试")
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        for ((task, label) in listOf("update" to "上次成功检查", "sync" to "上次成功同步")) {
            if (selectedTask != null && task != selectedTask) continue
            val time = preferences.getLong("${task}_success", 0L)
            if (time > 0) append("\n$label：${format.format(java.util.Date(time))}")
        }
    }
    fun conflictCount(context: Context) = state(context).getInt("sync_conflicts", 0)
    fun safeError(error: Throwable): String {
        var cause = error
        while (cause.cause != null && cause.cause != cause) cause = cause.cause!!
        val message = cause.message.orEmpty()
        return when {
            cause is DictionaryBuildException -> cause.description
            "Schema test" in message -> "输入方案测试未通过：${message.substringAfterLast(": ")}"
            "Dictionary settings changed" in message -> "配置已变化，请重新构建验证"
            "Missing source" in message -> "上游缺少规则指定的资源文件"
            "deployment failed" in message.lowercase() -> "词库编译失败，请检查更新规则或上游资源"
            "GitHub rate limit" in message -> "GitHub 请求频率受限，请稍后重试"
            cause is org.yaml.snakeyaml.error.YAMLException -> "上游 YAML 格式无效或超出解析限制"
            "Invalid" in message && ("rule" in message || "source" in message || "dictionary" in message) -> "更新规则或上游资源格式无效"
            "401" in message -> "认证失败，请检查账户和应用密码"
            "403" in message -> "服务器拒绝此操作，请检查目录权限或服务限制"
            "404" in message && "Dictionary source" in message -> "上游仓库或版本不存在，请检查更新规则"
            "404" in message && "Dictionary" in message -> "词库文件不存在，请检查更新规则或索引地址"
            "404" in message -> "远端资源不存在，请检查 WebDAV 目录"
            "checksum" in message -> "文件校验失败"
            "ETag" in message -> "服务器未提供安全的并发版本标识"
            "newer app" in message || "Incompatible" in message -> "资源版本不兼容，请先升级应用"
            "not configured" in message -> "请先配置 WebDAV"
            "Insufficient storage" in message -> "可用空间不足，无法保留恢复快照"
            "device clock" in message -> "其他设备时间偏差过大，请校准时间或手动选择冲突策略"
            "manifest" in message || "schema" in message -> "词库包格式不正确或缺少所需输入方案"
            "too large" in message || "size limit" in message -> "文件超过支持的大小"
            cause is org.json.JSONException || cause is kotlinx.serialization.SerializationException || cause is java.util.zip.ZipException -> "文件或远端数据格式不正确"
            cause is java.io.FileNotFoundException -> "所需文件不存在或不可读取"
            else -> "${cause.javaClass.simpleName}，请检查网络后重试"
        }
    }
}
