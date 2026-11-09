package com.yuyan.inputmethod

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.inputmethod.core.Rime
import com.yuyan.inputmethod.util.FuzzyPinYinUtils
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Callable

object EngineRuntime {
    enum class Phase { Preparing, Ready, Failed }
    data class Status(val phase: Phase, val message: String)

    val status = MutableLiveData(Status(Phase.Preparing, "正在准备词库…"))
    @Volatile var isReady = false
        private set
    @Volatile var logicalSchema = "pinyin"
        private set
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "RimeDeployment") }
    private val main = Handler(Looper.getMainLooper())
    private var preparing = false
    @Volatile private var preparationId = 0L
    private var appliedSettings = ""
    private var initializedContext: Context? = null
    private var deployedFamily = "frost"
    fun learningDictionary(): String = when (logicalSchema) {
        "english" -> "melt_eng"
        "stroke" -> "stroke"
        else -> if (deployedFamily == "ice") "rime_ice" else "rime_frost"
    }
    @Volatile private var activeShared: File? = null
    private var previousShared: File? = null

    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("fuzzy_pinyin_") == true || key == "rime_dictionary_family") {
            initializedContext?.let { prepare(it) }
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (initializedContext == null) {
            initializedContext = context.applicationContext
            logicalSchema = SchemaRegistry.normalize(AppPrefs.getInstance().internal.pinyinModeRime.getValue())
            PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(preferenceListener)
        }
        prepare(context)
    }

    private fun family(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString("rime_dictionary_family", "frost")
            .let { if (it == "ice" && DictionaryStore.hasIce(context)) "ice" else "frost" }

    private fun settings(context: Context) = family(context) + "\n" + FuzzyPinYinUtils.algebraRules().joinToString("\n")

    fun restoreBundled(context: Context) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Dictionary recovery must run off the main thread" }
        executor.submit {
            synchronized(this) { preparationId++; preparing = true; isReady = false }
            publish(Status(Phase.Preparing, "正在恢复内置词库…"))
            try {
                Rime.exitRime()
                DictionaryStore.restoreBundled(context)
                val user = File(DictionaryStore.root(context), "user")
                for (name in listOf("build", ".deployment", ".prediction", "predict.db")) {
                    val file = File(user, name)
                    check(!file.exists() || file.deleteRecursively()) { "Cannot reset damaged dictionary cache" }
                }
                com.yuyan.inputmethod.dictionary.DictionaryRules.reset(context)
                PreferenceManager.getDefaultSharedPreferences(context).edit().putString("rime_dictionary_family", "frost").commit()
                activeShared = null; appliedSettings = ""
            } finally { synchronized(this) { preparing = false } }
        }.get()
        prepare(context, force = true)
        executor.submit { check(isReady) { "Bundled dictionary recovery failed" } }.get()
    }

    @Synchronized
    fun prepare(context: Context, force: Boolean = false) {
        val requestedFamily = family(context)
        val requestedRules = FuzzyPinYinUtils.algebraRules()
        val desired = requestedFamily + "\n" + requestedRules.joinToString("\n")
        if (preparing || (!force && isReady && desired == appliedSettings)) return
        preparing = true
        val operation = ++preparationId
        isReady = false
        publish(Status(Phase.Preparing, "正在准备词库，首次使用可能需要片刻…"), operation)
        val contextApp = context.applicationContext
        executor.execute {
            try {
                Rime.exitRime()
                DictionaryStore.recoverActivation(contextApp)
                com.yuyan.imemodule.data.transfer.BackupArchive.recoverOnStartup(contextApp)
                val resolvedFamily = family(contextApp)
                val resolvedRules = FuzzyPinYinUtils.algebraRules()
                val resolvedSettings = resolvedFamily + "\n" + resolvedRules.joinToString("\n")
                val root = File(contextApp.filesDir, "rime-selfopt").apply { mkdirs() }
                val shared = DictionaryStore.compose(contextApp, force)
                val revision = shared.name.removePrefix("shared-")
                File(shared, "templates").listFiles()?.forEach { template ->
                    val content = com.yuyan.inputmethod.dictionary.DictionaryYaml.render(template.readText(), resolvedRules)
                    check(!content.contains("__SELFOPT_FUZZY__")) { "Unresolved fuzzy spelling template" }
                    val target = File(shared, template.name)
                    if (!target.exists() || target.readText() != content) atomicWrite(target, content)
                }
                val user = File(root, "user")
                if (!user.exists()) migrate(contextApp, root, shared, user)
                File(user, "logs").mkdirs()
                val buildKey = revision + "\n" + resolvedRules.joinToString("\n")
                val marker = File(user, ".deployment")
                val needsDeployment = force || !marker.exists() || marker.readText() != buildKey
                val predictionRevision = File(user, ".prediction")
                if (!predictionRevision.exists() || predictionRevision.readText() != revision) {
                    val cachedPrediction = File(user, "predict.db")
                    check(!cachedPrediction.exists() || cachedPrediction.delete()) { "Cannot update prediction dictionary" }
                }
                check(Rime.startupRime(contextApp, shared.path, user.path, needsDeployment)) { Rime.getLastError() }
                val priorShared = activeShared ?: File(root, "shared-" + marker.takeIf { it.exists() }?.readText().orEmpty().substringBefore('\n'))
                activeShared = shared
                synchronized(this) {
                    deployedFamily = resolvedFamily
                    check(Rime.selectRimeSchema(SchemaRegistry.physical(logicalSchema, deployedFamily))) {
                        "Requested input schema is unavailable"
                    }
                    applyOptions()
                    check(Rime.validateRimeSchema()) { Rime.getLastError() }
                    atomicWrite(marker, buildKey)
                    atomicWrite(predictionRevision, revision)
                    appliedSettings = resolvedSettings
                    preparing = false
                }
                publish(Status(Phase.Ready, ""), operation)
                cleanupShared(contextApp, shared, priorShared)
                if (settings(contextApp) != resolvedSettings) prepare(contextApp)
            } catch (error: Throwable) {
                Log.e("SelfOptRime", "Engine preparation failed", error)
                synchronized(this) { isReady = false; preparing = false }
                publish(Status(Phase.Failed, "词库准备失败，点此重试"), operation)
            }
        }
    }

    fun <T> maintenance(context: Context, canStart: () -> Boolean = { true }, action: (File, File) -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Engine maintenance must run off the main thread" }
        val future = executor.submit(Callable {
            if (!canStart()) throw com.yuyan.imemodule.data.transfer.SyncDeferred()
            val oldShared = activeShared ?: error("Input engine is not ready")
            val user = File(context.filesDir, "rime-selfopt/user")
            synchronized(this) { preparing = true; preparationId++; isReady = false }
            publish(Status(Phase.Preparing, "正在同步输入数据…"))
            Rime.exitRime()
            try { action(oldShared, user) } finally {
                try {
                    val shared = DictionaryStore.compose(context)
                    val rules = FuzzyPinYinUtils.algebraRules()
                    File(shared, "templates").listFiles()?.forEach { template ->
                        val content = com.yuyan.inputmethod.dictionary.DictionaryYaml.render(template.readText(), rules)
                        check(!content.contains("__SELFOPT_FUZZY__")) { "Unresolved fuzzy spelling template" }
                        val target = File(shared, template.name)
                        if (!target.exists() || target.readText() != content) atomicWrite(target, content)
                    }
                    val prediction = File(user, ".prediction")
                    if (!prediction.exists() || prediction.readText() != shared.name.removePrefix("shared-")) {
                        check(!File(user, "predict.db").exists() || File(user, "predict.db").delete()) { "Cannot reset prediction dictionary" }
                    }
                    val buildKey = shared.name.removePrefix("shared-") + "\n" + rules.joinToString("\n")
                    val deployment = File(user, ".deployment")
                    val needsDeployment = !deployment.exists() || deployment.readText() != buildKey
                    check(Rime.startupRime(context, shared.path, user.path, needsDeployment)) { Rime.getLastError() }
                    deployedFamily = family(context)
                    check(Rime.selectRimeSchema(SchemaRegistry.physical(logicalSchema, deployedFamily))) { "Cannot select restored schema" }
                    applyOptions()
                    check(Rime.validateRimeSchema()) { Rime.getLastError() }
                    activeShared = shared
                    appliedSettings = settings(context)
                    atomicWrite(prediction, shared.name.removePrefix("shared-"))
                    atomicWrite(deployment, buildKey)
                    cleanupShared(context, shared, oldShared)
                    publish(Status(Phase.Ready, ""))
                } catch (error: Throwable) {
                    publish(Status(Phase.Failed, "数据部署失败，请重试或恢复上一版"))
                    throw error
                } finally { synchronized(this) { preparing = false } }
            }
        })
        return try { future.get() } catch (error: java.util.concurrent.ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    fun activatePackages(context: Context, packages: Map<String, DictionaryPackage>, preparedWork: File? = null) = com.yuyan.imemodule.data.transfer.TransferOperation.run {
        if (preparedWork != null) {
            val validated = com.yuyan.imemodule.data.transfer.SyncProtocol.json.decodeFromString<Map<String, DictionaryPackage>>(File(preparedWork, "validated.json").readText())
            val request = com.yuyan.imemodule.data.transfer.SyncProtocol.json.decodeFromString<com.yuyan.inputmethod.dictionary.DictionaryBuildRequest>(File(preparedWork, "request.json").readText())
            check(validated == packages && request.fuzzyRules == FuzzyPinYinUtils.algebraRules()) { "Dictionary settings changed after validation" }
            check(request.configurationHash.isEmpty() || request.configurationHash == com.yuyan.inputmethod.dictionary.DictionaryRules.configurationHash(context)) { "Dictionary settings changed after validation" }
            check(File(preparedWork, "compiled/build").isDirectory) { "Validated dictionary build is missing" }
        }
        val previous = DictionaryStore.active(context)
        DictionaryStore.beginActivation(context, previous)
        try {
            maintenance(context) { _, user ->
                if (preparedWork != null) {
                    val build = File(user, "build")
                    val staging = File(preparedWork, "activation-build")
                    val bytes = build.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
                        File(preparedWork, "compiled/build").walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    check(user.usableSpace > bytes + 64 * 1024 * 1024) { "Insufficient storage" }
                    if (build.isDirectory) build.copyRecursively(staging, overwrite = true) else staging.mkdirs()
                    File(preparedWork, "compiled/build").copyRecursively(staging, overwrite = true)
                    if (build.exists()) check(build.renameTo(File(preparedWork, "previous-build"))) { "Cannot preserve previous build" }
                    check(staging.renameTo(build)) { "Cannot install validated build" }
                }
                DictionaryStore.setActive(context, packages)
                if (preparedWork != null) {
                    val shared = DictionaryStore.compose(context)
                    atomicWrite(File(user, ".deployment"), shared.name.removePrefix("shared-") + "\n" + FuzzyPinYinUtils.algebraRules().joinToString("\n"))
                }
            }
            DictionaryStore.finishActivation(context)
            DictionaryStore.prunePackages(context, previous)
        } catch (error: Throwable) {
            DictionaryStore.setActive(context, previous)
            maintenance(context) { _, user ->
                val build = File(user, "build")
                check(!build.exists() || build.deleteRecursively()) { "Cannot reset failed dictionary build" }
                File(user, ".deployment").delete()
            }
            DictionaryStore.finishActivation(context)
            throw error
        }
    }

    private fun cleanupShared(context: Context, current: File, prior: File? = null) {
        val others = DictionaryStore.root(context).listFiles()?.filter {
            it.isDirectory && it != current && it.name.matches(Regex("shared-[0-9a-f]{64}"))
        }.orEmpty()
        val retained = prior?.takeIf { it != current && it in others }
            ?: previousShared?.takeIf { it in others } ?: others.maxByOrNull { it.lastModified() }
        previousShared = retained
        others.filter { it != retained }.forEach { it.deleteRecursively() }
    }

    @Synchronized
    fun selectSchema(schema: String): Boolean {
        logicalSchema = SchemaRegistry.normalize(schema)
        if (!isReady) return false
        val selected = Rime.selectRimeSchema(SchemaRegistry.physical(logicalSchema, deployedFamily)) && Rime.validateRimeSchema()
        if (selected) applyOptions()
        else {
            isReady = false
            publish(Status(Phase.Failed, "输入方案加载失败，点此重试"))
        }
        return selected
    }

    private fun applyOptions() {
        Rime.setRimeOption("ascii_mode", false)
        Rime.setRimeOption("traditionalization", AppPrefs.getInstance().input.chineseFanTi.getValue())
        Rime.setRimeOption("emoji", AppPrefs.getInstance().input.emojiInput.getValue())
        Rime.setRimePageSize(100)
    }

    private fun publish(value: Status, operation: Long = preparationId) {
        main.post {
            if (operation != preparationId) return@post
            isReady = value.phase == Phase.Ready
            status.value = value
        }
    }

    private fun migrate(context: Context, root: File, shared: File, user: File) {
        val stagedUser = File(root, "user-staging")
        check(!stagedUser.exists() || stagedUser.deleteRecursively()) { "Cannot reset interrupted migration" }
        stagedUser.mkdirs()
        File(stagedUser, "logs").mkdirs()
        File(stagedUser, "legacy/logs").mkdirs()
        val legacy = context.getExternalFilesDir("rime")
        val backup = File(root, "legacy-backup")
        if (!File(backup, ".complete").exists()) {
            check(!backup.exists() || backup.deleteRecursively()) { "Cannot reset incomplete backup" }
            backup.mkdirs()
            if (legacy != null) {
                listOf("pinyin.userdb", "english.userdb", "stroke.userdb").forEach { name ->
                    val source = File(legacy, name)
                    if (source.isDirectory) check(source.copyRecursively(File(backup, name), overwrite = false)) {
                        "Cannot back up legacy user dictionary"
                    }
                }
            }
            atomicWrite(File(backup, ".complete"), "1")
        }
        backup.listFiles()?.filter { it.isDirectory }?.forEach { source ->
            check(source.copyRecursively(File(stagedUser, "legacy/${source.name}"))) { "Cannot stage legacy user dictionary" }
        }
        check(Rime.migrateUserData(shared.path, stagedUser.path)) { Rime.getLastError() }
        atomicWrite(File(stagedUser, ".migration"), "1")
        // 整个用户目录只激活一次，中断重试从备份重建，避免重复导入累加词频。
        check(stagedUser.renameTo(user)) { "Cannot activate migrated user dictionary" }
    }

    private fun atomicWrite(file: File, content: String) {
        val pending = File(file.parentFile, file.name + ".pending")
        FileOutputStream(pending).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(pending.renameTo(file)) { "Cannot atomically replace configuration" }
    }
}
