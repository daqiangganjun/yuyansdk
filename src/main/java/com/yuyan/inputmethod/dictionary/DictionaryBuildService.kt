package com.yuyan.inputmethod.dictionary

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.transfer.SyncProtocol
import com.yuyan.imemodule.data.transfer.TransferFiles
import com.yuyan.inputmethod.DictionaryPackage
import com.yuyan.inputmethod.DictionaryStore
import com.yuyan.inputmethod.EngineRuntime
import com.yuyan.inputmethod.SchemaRegistry
import com.yuyan.inputmethod.core.Rime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

@Serializable
data class DictionaryBuildRequest(
    val family: String,
    val packages: Map<String, DictionaryPackage>,
    val rule: DictionaryRule? = null,
    val fuzzyRules: List<String> = emptyList(),
    val ruleHash: String = "",
    val blockedBuild: String = "",
    val configurationHash: String = "",
)

@Serializable
data class DictionaryBuildStatus(
    val state: String = "running",
    val message: String,
    val packages: List<DictionaryPackage> = emptyList(),
    val buildKey: String = "",
)

class DictionaryBuildService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var job: String? = null
    @Volatile private var phase = "准备"
    private var buildKey = ""
    override fun onBind(intent: Intent?): IBinder = Binder()
    private fun directory(id: String) = File(filesDir, "dictionary-builds/$id")
    @Synchronized private fun report(message: String, state: String = "running", packages: List<DictionaryPackage> = emptyList()) {
        val id = job ?: return
        TransferFiles.write(File(directory(id), "status.json"), SyncProtocol.json.encodeToString(DictionaryBuildStatus(state, message, packages, buildKey)))
        if (state == "running") getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
    }
    private fun notification(message: String): android.app.Notification {
        val cancel = PendingIntent.getService(this, 0, Intent(this, DictionaryBuildService::class.java).setAction("cancel"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("词库更新").setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true).setOnlyAlertOnce(true).addAction(0, "取消", cancel).build()
    }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "词库更新进度", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { cancel("已取消词库更新，继续使用原词库"); return START_NOT_STICKY }
        val id = intent?.getStringExtra("job") ?: return START_NOT_STICKY
        if (!id.matches(Regex("[0-9a-f-]{36}"))) { stopSelf(); return START_NOT_STICKY }
        if (job != null) return START_NOT_STICKY
        job = id
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification("正在准备词库更新"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, notification("正在准备词库更新"))
        worker.execute {
            try { execute(directory(id)) }
            catch (error: Throwable) {
                File(directory(id), "error.log").writeText(error.stackTraceToString())
                report("${phase}失败，保留原词库：${com.yuyan.imemodule.data.transfer.TransferCoordinator.safeError(error)}", "failed")
            } finally {
                runCatching { Rime.exitRime() }
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun cancel(message: String) {
        report(message, "cancelled")
        stopForeground(true); stopSelf()
        Process.killProcess(Process.myPid())
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancel("系统结束了耗时更新任务，原词库保持不变，可稍后重试") }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy(); Process.killProcess(Process.myPid()) }

    /** 读取应用内置词库包中的单个文件，用于向手机端构建的词库补齐应用自有的脚本 */
    private fun bundledFile(path: String): ByteArray? = assets.open("rime-selfopt.zip").use { input ->
        ZipInputStream(input).use { zip -> generateSequence { zip.nextEntry }.firstOrNull { it.name == path }?.let { zip.readBytes() } }
    }

    private fun execute(work: File) {
        val request = SyncProtocol.json.decodeFromString<DictionaryBuildRequest>(File(work, "request.json").readText())
        require(request.family in setOf("frost", "ice")) { "Invalid build family" }
        require(work.usableSpace > 512L * 1024 * 1024) { "Insufficient storage" }
        for (name in listOf("baseline", "package", "shared", "compiled")) {
            val staging = File(work, name)
            check(!staging.exists() || staging.deleteRecursively()) { "Cannot reset interrupted build directory" }
        }
        val packages = request.packages.toMutableMap()
        val changed: DictionaryPackage
        if (request.rule != null) {
            phase = "下载"
            report("正在检查${request.rule.name}上游版本")
            val source = GithubDictionarySource(request.rule, File(work, "source")) { report(it) }
            buildKey = "${request.ruleHash}:${source.commit}"
            if (buildKey == request.blockedBuild) { report("此上游版本上次构建失败或已取消，等待新版本；可手动重试", "blocked"); return }
            val active = packages[request.family]
            if (active?.revision == source.commit && active.recipe == MobileDictionaryRecipe.VERSION && active.ruleHash == request.ruleHash) {
                report("所选词库已是当前上游版本", "unchanged"); return
            }
            val baseline = File(work, "baseline")
            TransferFiles.extract(DictionaryStore.materialize(this, packages.getValue("frost")).inputStream(), baseline)
            phase = "资源适配"
            report("正在适配${request.rule.name}输入方案")
            val recipe = MobileDictionaryRecipe(source, baseline, File(work, "package"), bundledFile(MobileDictionaryRecipe.PURE_CHINESE_LUA))
            recipe.build()
            changed = recipe.archive(File(work, "dictionary.zip")).copy(ruleHash = request.ruleHash)
            DictionaryStore.validateArchive(File(work, "dictionary.zip"), changed)
            packages[request.family] = changed
        } else {
            changed = packages.getValue(request.family)
            buildKey = "package:${changed.sha256}"
            if (buildKey == request.blockedBuild) { report("此词库包上次测试失败，等待新版本；可手动重试", "blocked"); return }
        }
        phase = "构建"
        report("正在构建词库，原词库继续可用")
        val shared = File(work, "shared").apply { mkdirs() }
        packages.toSortedMap().values.forEach { pkg ->
            val archive = if (request.rule != null && pkg.family == request.family) File(work, "dictionary.zip") else DictionaryStore.materialize(this, pkg)
            TransferFiles.extract(archive.inputStream(), shared)
        }
        val schemas = SchemaRegistry.chineseSchemas.map { "selfopt_${request.family}_$it" } + listOf("selfopt_english", "selfopt_stroke")
        File(shared, "default.yaml").writeText("config_version: '1'\nschema_list:\n" + schemas.joinToString("") { "  - schema: $it\n" } + "menu:\n  page_size: 100\npunctuator:\n  half_shape: {}\n  full_shape: {}\n")
        File(shared, "templates").listFiles().orEmpty().filter { it.name.startsWith("selfopt_${request.family}_") }.forEach { template ->
            val content = DictionaryYaml.render(template.readText(), request.fuzzyRules)
            File(shared, template.name).writeText(content)
        }
        val user = File(work, "compiled").apply { mkdirs(); File(this, "logs").mkdirs() }
        check(Rime.startupRime(this, shared.path, user.path, true)) { Rime.getLastError() }
        phase = "测试"
        schemas.forEach { schema ->
            report("正在测试：${schema.removePrefix("selfopt_")}")
            check(Rime.selectRimeSchema(schema)) { "Schema test cannot select $schema" }
            Rime.setRimeOption("ascii_mode", false); Rime.setRimeOption("traditionalization", false); Rime.setRimeOption("emoji", false)
            val (keys, expected) = when {
                schema.endsWith("_t9_pinyin") -> "MGGAM" to "你好"
                schema.endsWith("_double_pinyin_ls17") -> "NJHB" to "你好"
                schema.endsWith("_double_pinyin_flypy") -> "nihc" to "你好"
                schema.endsWith("_pinyin") -> "nihao" to "你好"
                schema == "selfopt_english" -> "hello" to "hello"
                schema == "selfopt_stroke" -> "HX" to "二"
                else -> "ni" to "你"
            }
            // 第二轮按「中英混输」关闭运行，确认过滤脚本能加载且不会滤掉中文候选
            repeat(2) { round ->
                Rime.setRimeOption(EngineRuntime.PURE_CHINESE_OPTION, round == 1)
                Rime.clearRimeComposition()
                keys.forEach { Rime.processRimeKey(it.code, 0) }
                val candidates = Rime.getRimeContext()?.candidates.orEmpty()
                val index = candidates.indexOfFirst { it.text == expected }
                check(index >= 0) { "Schema test missing expected candidate: $schema" }
                check(Rime.selectRimeCandidate(index)) { "Schema test cannot select candidate: $schema" }
                check(Rime.getRimeCommit()?.commitText == expected) { "Schema test commit mismatch: $schema" }
                Rime.clearRimeComposition()
                check(Rime.getRawInput().isEmpty()) { "Schema test cannot clear composition: $schema" }
            }
        }
        Rime.exitRime()
        if (request.rule != null) {
            val destination = DictionaryStore.packageFile(this, changed).apply { parentFile!!.mkdirs() }
            if (!destination.exists()) check(File(work, "dictionary.zip").renameTo(destination)) { "Cannot persist validated dictionary" }
        }
        File(work, "validated.json").writeText(SyncProtocol.json.encodeToString(packages))
        report("构建与测试通过，等待键盘空闲后启用", "ready", listOf(changed))
    }
    companion object { private const val CHANNEL = "dictionary_build"; private const val NOTIFICATION = 5401 }
}
