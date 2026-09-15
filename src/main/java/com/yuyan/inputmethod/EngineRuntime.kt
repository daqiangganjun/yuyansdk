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
import java.util.zip.ZipInputStream

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
            .let { if (it == "ice") "ice" else "frost" }

    private fun settings(context: Context) = family(context) + "\n" + FuzzyPinYinUtils.algebraRules().joinToString("\n")

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
                val root = File(contextApp.filesDir, "rime-selfopt").apply { mkdirs() }
                val revision = contextApp.assets.open("rime-selfopt.version").bufferedReader().use { it.readText().trim() }
                require(revision.matches(Regex("[0-9a-f]{64}"))) { "Invalid bundled dictionary revision" }
                val shared = File(root, "shared-$revision")
                if (force || !File(shared, ".complete").exists()) {
                    val staging = File(root, "shared-staging")
                    staging.deleteRecursively()
                    staging.mkdirs()
                    extract(contextApp, staging)
                    File(staging, ".complete").writeText(revision)
                    if (shared.exists()) check(shared.deleteRecursively()) { "Cannot replace incomplete dictionary" }
                    check(staging.renameTo(shared)) { "Cannot activate bundled dictionary" }
                }
                File(shared, "templates").listFiles()?.forEach { template ->
                    val content = template.readText().replace("\r\n", "\n").replace("  - __SELFOPT_FUZZY__\n",
                        requestedRules.joinToString("") { "  - '$it'\n" })
                    check(!content.contains("__SELFOPT_FUZZY__")) { "Unresolved fuzzy spelling template" }
                    atomicWrite(File(shared, template.name), content)
                }
                val user = File(root, "user")
                if (!user.exists()) migrate(contextApp, root, shared, user)
                File(user, "logs").mkdirs()
                val buildKey = revision + "\n" + desired
                val marker = File(user, ".deployment")
                val needsDeployment = force || !marker.exists() || marker.readText() != buildKey
                val predictionRevision = File(user, ".prediction")
                if (!predictionRevision.exists() || predictionRevision.readText() != revision) {
                    val cachedPrediction = File(user, "predict.db")
                    check(!cachedPrediction.exists() || cachedPrediction.delete()) { "Cannot update prediction dictionary" }
                }
                check(Rime.startupRime(contextApp, shared.path, user.path, needsDeployment)) { Rime.getLastError() }
                synchronized(this) {
                    deployedFamily = requestedFamily
                    check(Rime.selectRimeSchema(SchemaRegistry.physical(logicalSchema, deployedFamily))) {
                        "Requested input schema is unavailable"
                    }
                    applyOptions()
                    check(Rime.validateRimeSchema()) { Rime.getLastError() }
                    atomicWrite(marker, buildKey)
                    atomicWrite(predictionRevision, revision)
                    appliedSettings = desired
                    preparing = false
                }
                publish(Status(Phase.Ready, ""), operation)
                root.listFiles()?.filter { it.isDirectory && it != shared && it.name.matches(Regex("shared-[0-9a-f]{64}")) }
                    ?.forEach { it.deleteRecursively() }
                if (settings(contextApp) != desired) prepare(contextApp)
            } catch (error: Throwable) {
                Log.e("SelfOptRime", "Engine preparation failed", error)
                synchronized(this) { isReady = false; preparing = false }
                publish(Status(Phase.Failed, "词库准备失败，点此重试"), operation)
            }
        }
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

    private fun extract(context: Context, destination: File) {
        val base = destination.canonicalPath + File.separator
        ZipInputStream(context.assets.open("rime-selfopt.zip")).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                val output = File(destination, entry.name)
                require(output.canonicalPath.startsWith(base)) { "Invalid dictionary archive path" }
                if (entry.isDirectory) output.mkdirs()
                else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { archive.copyTo(it) }
                }
                archive.closeEntry()
            }
        }
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
