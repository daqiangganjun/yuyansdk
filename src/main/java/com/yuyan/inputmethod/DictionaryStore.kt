package com.yuyan.inputmethod

import android.content.Context
import com.yuyan.imemodule.data.transfer.SyncProtocol
import com.yuyan.imemodule.data.transfer.TransferFiles
import com.yuyan.inputmethod.dictionary.MobileDictionaryRecipe
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@Serializable
data class DictionaryPackage(
    val family: String,
    val revision: String,
    val sha256: String,
    val size: Long,
    val compatibility: String = "selfopt-1",
    val updatedAt: Long = 0,
    val summary: String = "",
    val url: String = "",
    val recipe: Int = 0,
    val ruleHash: String = ""
)

@Serializable
data class DictionaryIndex(
    val format: Int = 1,
    val compatibility: String,
    val minAppVersion: Long,
    val packages: List<DictionaryPackage>
)

object DictionaryStore {
    const val DEFAULT_INDEX = ""
    private const val UNPUBLISHED_INDEX = "https://github.com/daqiangganjun/YuyanSelfOpt/releases/download/dictionaries-v1/index.json"
    @Synchronized
    fun configuredIndex(context: Context): String {
        val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val url = preferences.getString("transfer_dictionary_index", DEFAULT_INDEX).orEmpty().trim()
        val state = context.getSharedPreferences("transfer_state", Context.MODE_PRIVATE)
        if (!state.getBoolean("dictionary_source_migrated", false)) {
            if (url == UNPUBLISHED_INDEX) preferences.edit().putString("transfer_dictionary_index", "").commit()
            state.edit().putBoolean("dictionary_source_migrated", true).commit()
            if (url == UNPUBLISHED_INDEX) return ""
        }
        return url
    }
    private val json get() = SyncProtocol.json
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).build()
    fun root(context: Context) = File(context.filesDir, "rime-selfopt")
    private fun stateFile(context: Context) = File(root(context), "packages/active.json")
    fun builtin(context: Context): DictionaryPackage = context.assets.open("rime-selfopt.manifest.json").bufferedReader().use {
        json.decodeFromString(it.readText())
    }

    @Synchronized
    fun active(context: Context): Map<String, DictionaryPackage> {
        val baseline = builtin(context)
        val stored = stateFile(context).takeIf(TransferFiles::exists)?.let {
            json.decodeFromString<Map<String, DictionaryPackage>>(TransferFiles.read(it))
        }.orEmpty().filter { (family, pkg) -> family == pkg.family && packageFile(context, pkg).isFile }.toMutableMap()
        val marker = File(root(context), "packages/bundled-version")
        val previousBaseline = marker.takeIf(TransferFiles::exists)?.let(TransferFiles::read)
        if (previousBaseline != baseline.sha256) {
            if (previousBaseline != null && stored["frost"]?.sha256 == previousBaseline) {
                stored.remove("frost")
                TransferFiles.write(stateFile(context), json.encodeToString(stored))
            }
            TransferFiles.write(marker, baseline.sha256)
        }
        return mapOf("frost" to baseline) + stored.mapValues { (_, pkg) -> if (pkg.family == "frost" && pkg.sha256 == baseline.sha256) baseline else pkg }
    }

    fun hasIce(context: Context) = "ice" in active(context)
    fun restoreBundled(context: Context) {
        val baseline = builtin(context)
        val target = packageFile(context, baseline).apply { parentFile!!.mkdirs() }
        val temporary = File(target.parentFile, "restore.partial")
        context.assets.open("rime-selfopt.zip").use { source -> temporary.outputStream().use { source.copyTo(it) } }
        validateArchive(temporary, baseline)
        check(!target.exists() || target.delete()) { "Cannot replace damaged bundled dictionary" }
        check(temporary.renameTo(target)) { "Cannot restore bundled dictionary" }
        setActive(context, mapOf("frost" to baseline))
        android.util.AtomicFile(activationJournal(context)).delete()
        root(context).listFiles().orEmpty().filter { it.isDirectory && (it.name.startsWith("shared-") || it.name == "ice-staging") }
            .forEach { check(it.deleteRecursively()) { "Cannot reset dictionary resources" } }
    }
    private fun activationJournal(context: Context) = File(root(context), "packages/activation-previous.json")
    fun beginActivation(context: Context, previous: Map<String, DictionaryPackage>) {
        previous.values.forEach { materialize(context, it) }
        TransferFiles.write(activationJournal(context), json.encodeToString(previous))
    }
    fun finishActivation(context: Context) { activationJournal(context).delete() }
    fun prunePackages(context: Context, previous: Map<String, DictionaryPackage>) {
        val retained = (active(context).values + previous.values).map { it.sha256 + ".zip" }.toSet()
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        for (family in listOf("frost", "ice")) {
            File(root(context), "packages/$family").listFiles().orEmpty().filter {
                it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.zip")) && it.name !in retained && it.lastModified() < cutoff
            }.forEach { it.delete() }
        }
    }
    fun recoverActivation(context: Context) {
        val file = activationJournal(context)
        if (TransferFiles.exists(file)) {
            val previous = json.decodeFromString<Map<String, DictionaryPackage>>(TransferFiles.read(file))
            val interrupted = active(context).values.filter { previous[it.family]?.sha256 != it.sha256 }.map { it.sha256 }.toSet()
            setActive(context, previous)
            val build = File(root(context), "user/build")
            check(!build.exists() || build.deleteRecursively()) { "Cannot reset interrupted dictionary build" }
            File(root(context), "user/.deployment").delete()
            val transfer = File(context.filesDir, "transfer")
            val pending = File(transfer, "dictionary-pending.json")
            if (TransferFiles.exists(pending)) {
                TransferFiles.write(File(transfer, "dictionary-failed.json"), TransferFiles.read(pending))
                android.util.AtomicFile(pending).delete()
            }
            val state = context.getSharedPreferences("transfer_state", Context.MODE_PRIVATE)
            state.edit().putStringSet("blocked_dictionaries", state.getStringSet("blocked_dictionaries", emptySet()).orEmpty() + interrupted)
                .putBoolean("update_requested", false).putBoolean("update_running", false).remove("update_manual")
                .putString("status", "上次词库部署中断，已恢复旧版；请手动重试或等待新版本").commit()
            check(file.delete()) { "Cannot finish dictionary recovery" }
        }
    }
    fun packageFile(context: Context, pkg: DictionaryPackage): File {
        validateDescriptor(pkg)
        return File(root(context), "packages/${pkg.family}/${pkg.sha256}.zip")
    }

    fun materialize(context: Context, pkg: DictionaryPackage): File {
        val target = packageFile(context, pkg)
        if (!target.exists() && pkg.sha256 == builtin(context).sha256) {
            target.parentFile?.mkdirs()
            val pending = File(target.parentFile, target.name + ".partial")
            context.assets.open("rime-selfopt.zip").use { source -> pending.outputStream().use { source.copyTo(it) } }
            require(TransferFiles.sha256(pending) == pkg.sha256) { "Bundled dictionary checksum mismatch" }
            check(pending.renameTo(target)) { "Cannot persist bundled dictionary" }
        }
        require(target.isFile) { "Dictionary package is missing" }
        return target
    }

    fun validateDescriptor(pkg: DictionaryPackage) {
        require(pkg.family in setOf("frost", "ice") && pkg.compatibility == "selfopt-1") { "Incompatible dictionary package" }
        require(pkg.sha256.matches(Regex("[0-9a-f]{64}")) && pkg.revision.matches(Regex("[0-9a-f]{40}"))) { "Invalid dictionary revision" }
        require(pkg.size in 1..(256L * 1024 * 1024)) { "Invalid dictionary package size" }
    }

    fun importPackage(context: Context, input: InputStream): DictionaryPackage {
        val temporary = File.createTempFile("dictionary-import-", ".zip", context.cacheDir)
        try {
            input.use { source -> temporary.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var count = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= 256L * 1024 * 1024) { "Dictionary package is too large" }
                    output.write(buffer, 0, read)
                }
            } }
            val metadata = ZipFile(temporary).use { zip ->
                val entry = zip.getEntry("bundle.json") ?: error("Dictionary manifest missing")
                require(entry.size in 1..65536) { "Invalid dictionary manifest size" }
                org.json.JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
            }
            val pkg = DictionaryPackage(family = metadata.getString("family"), revision = metadata.getString("revision"),
                sha256 = TransferFiles.sha256(temporary), size = temporary.length(), compatibility = metadata.getString("compatibility"),
                updatedAt = metadata.optLong("updatedAt", 0L), summary = "本地导入词库")
            validateArchive(temporary, pkg)
            val target = packageFile(context, pkg)
            target.parentFile?.mkdirs()
            if (target.exists()) validateArchive(target, pkg)
            else check(temporary.renameTo(target)) { "Cannot store imported dictionary" }
            target.setLastModified(System.currentTimeMillis())
            return pkg
        } finally { temporary.delete() }
    }

    fun validateArchive(file: File, pkg: DictionaryPackage) {
        validateDescriptor(pkg)
        require(file.length() == pkg.size && TransferFiles.sha256(file) == pkg.sha256) { "Dictionary package checksum mismatch" }
        ZipFile(file).use { zip ->
            val metadata = zip.getEntry("bundle.json") ?: error("Dictionary manifest missing")
            val meta = org.json.JSONObject(zip.getInputStream(metadata).bufferedReader().readText())
            require(meta.getInt("format") == 1 && meta.getString("compatibility") == "selfopt-1" &&
                meta.getString("family") == pkg.family && meta.getString("revision") == pkg.revision) { "Dictionary manifest mismatch" }
            require(!meta.has("updatedAt") || meta.getLong("updatedAt") == pkg.updatedAt) { "Dictionary timestamp mismatch" }
            require(zip.getEntry("rime_${pkg.family}.dict.yaml") != null &&
                zip.getEntry("templates/selfopt_${pkg.family}_pinyin.schema.yaml") != null) { "Dictionary package is incomplete" }
            require(SchemaRegistry.chineseSchemas.all { zip.getEntry("templates/selfopt_${pkg.family}_$it.schema.yaml") != null }) {
                "Dictionary package lacks supported keyboard schemas"
            }
            if (pkg.family == "frost") require(zip.getEntry("selfopt_english.schema.yaml") != null && zip.getEntry("predict.tsv") != null) {
                "Shared dictionary resources are missing"
            }
            val paths = hashSetOf<String>()
            var size = 0L
            zip.entries().asSequence().forEach { entry ->
                val name = entry.name
                TransferFiles.child(file.parentFile!!, name)
                require(paths.add(name) && paths.size < 20000 && entry.size >= 0) { "Invalid dictionary entries" }
                size += entry.size
                require(size <= TransferFiles.MAX_EXPANDED_BYTES) { "Dictionary expands beyond size limit" }
                // 雾凇包自带「中英混输」过滤脚本，组合时白霜包已有同名文件则以白霜包为准
                if (pkg.family == "ice") require(name == "bundle.json" || name == "sources.json" ||
                    name == "rime_ice.dict.yaml" || name.startsWith("ice_") || name == MobileDictionaryRecipe.PURE_CHINESE_LUA ||
                    name.startsWith("templates/selfopt_ice_") || name.startsWith("licenses/rime-ice/")) { "Optional dictionary overwrites shared files" }
            }
        }
    }

    @Synchronized
    fun setActive(context: Context, packages: Map<String, DictionaryPackage>) {
        require(packages.containsKey("frost")) { "Base dictionary is required" }
        packages.values.forEach { pkg -> validateArchive(materialize(context, pkg), pkg) }
        TransferFiles.write(stateFile(context), json.encodeToString(packages))
        TransferFiles.write(File(root(context), "packages/bundled-version"), builtin(context).sha256)
    }

    fun compose(context: Context, force: Boolean = false): File {
        val packages = active(context).toSortedMap()
        val digest = MessageDigest.getInstance("SHA-256").digest(("compose-2|" + packages.values.joinToString("|") { it.sha256 }).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val shared = File(root(context), "shared-$digest")
        if (!force && File(shared, ".complete").isFile) return shared
        val staging = File(root(context), "shared-staging")
        check(!staging.exists() || staging.deleteRecursively()) { "Cannot reset dictionary staging" }
        try {
            packages.forEach { (family, pkg) ->
                val file = packageFile(context, pkg)
                val input = if (!file.exists() && family == "frost" && pkg.sha256 == builtin(context).sha256)
                    context.assets.open("rime-selfopt.zip") else file.inputStream()
                val target = if (family == "frost") staging else File(root(context), "ice-staging")
                if (family == "ice") { check(!target.exists() || target.deleteRecursively()); target.mkdirs() }
                TransferFiles.extract(input, target)
                if (family == "ice") {
                    target.walkTopDown().filter { it.isFile && it.name !in setOf("bundle.json", "sources.json") }.forEach { source ->
                        val destination = TransferFiles.child(staging, source.relativeTo(target).invariantSeparatorsPath)
                        destination.parentFile?.mkdirs()
                        source.copyTo(destination, overwrite = false)
                    }
                    target.deleteRecursively()
                }
            }
            val schemas = packages.keys.flatMap { family -> SchemaRegistry.chineseSchemas.map { "selfopt_${family}_$it" } } +
                listOf("selfopt_english", "selfopt_stroke")
            File(staging, "default.yaml").writeText("config_version: '1'\nschema_list:\n" +
                schemas.joinToString("") { "  - schema: $it\n" } + "menu:\n  page_size: 100\npunctuator:\n  half_shape: {}\n  full_shape: {}\n")
            File(staging, ".complete").writeText(digest)
            check(!shared.exists() || shared.deleteRecursively()) { "Cannot replace incomplete dictionary" }
            check(staging.renameTo(shared)) { "Cannot activate dictionary directory" }
            return shared
        } catch (error: Throwable) { staging.deleteRecursively(); throw error }
    }

    fun fetchIndex(context: Context, url: String, http: OkHttpClient = client): DictionaryIndex {
        require(url.startsWith("https://")) { "Dictionary index requires HTTPS" }
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Dictionary index HTTP ${response.code}" }
            val body = response.body ?: error("Empty dictionary index")
            require(body.contentLength() <= 1048576L) { "Dictionary index is too large" }
            val text = body.byteStream().use { input ->
                val buffer = ByteArray(1048577)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count <= 1048576) { "Dictionary index is too large" }
                String(buffer, 0, count, Charsets.UTF_8)
            }
            val index = json.decodeFromString<DictionaryIndex>(text)
            @Suppress("DEPRECATION") val version = context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            require(index.format == 1 && index.compatibility == "selfopt-1" && index.minAppVersion <= version) { "Dictionary update requires a newer app" }
            require(index.packages.map { it.family }.distinct().size == index.packages.size) { "Duplicate dictionary families" }
            index.packages.forEach(::validateDescriptor)
            return index
        }
    }

    fun download(context: Context, pkg: DictionaryPackage, http: OkHttpClient = client, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        validateDescriptor(pkg)
        require(pkg.url.startsWith("https://")) { "Dictionary download requires HTTPS" }
        val destination = packageFile(context, pkg)
        if (destination.exists()) { validateArchive(destination, pkg); return destination }
        destination.parentFile?.mkdirs()
        val pending = File(destination.parentFile, destination.name + ".partial")
        try {
            http.newCall(Request.Builder().url(pkg.url).build()).execute().use { response ->
                check(response.isSuccessful) { "Dictionary download HTTP ${response.code}" }
                val body = response.body ?: error("Empty dictionary download")
                body.byteStream().use { input -> pending.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var read = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        read += count
                        require(read <= pkg.size) { "Dictionary download exceeds declared size" }
                        output.write(buffer, 0, count)
                        progress(read, pkg.size)
                    }
                } }
            }
            validateArchive(pending, pkg)
            check(pending.renameTo(destination)) { "Cannot store dictionary download" }
            return destination
        } finally { pending.delete() }
    }
}
