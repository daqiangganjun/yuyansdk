package com.yuyan.inputmethod.dictionary

import com.yuyan.imemodule.data.transfer.TransferFiles
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GithubDictionarySource(
    val rule: DictionaryRule,
    private val cache: File,
    private val progress: (String) -> Unit,
) {
    companion object {
        private val http = OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        fun text(url: String): String {
            require(url.toHttpUrl().isHttps) { "Source requires HTTPS" }
            return http.newCall(Request.Builder().url(url).header("User-Agent", "YuyanSelfOpt").build()).execute().use { response ->
                check(response.code != 429 && !(response.code == 403 && response.header("X-RateLimit-Remaining") == "0")) { "GitHub rate limit" }
                check(response.isSuccessful && response.request.url.isHttps) { "Dictionary source HTTP ${response.code}" }
                val bytes = requireNotNull(response.body).byteStream().use { it.readBytesLimited(4 * 1024 * 1024) }
                bytes.toString(Charsets.UTF_8)
            }
        }
        private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                require(out.size() + count <= limit) { "Source metadata is too large" }
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }

    val commit: String
    val updatedAt: Long
    val summary: String
    private val entries: Map<String, Pair<String, Long>>
    private var downloaded = 0L
    private var planned = emptySet<String>()
    private val completed = hashSetOf<String>()
    private var completedBytes = 0L
    private var totalBytes = 0L
    private var lastProgressAt = 0L
    init {
        DictionaryRules.validate(rule)
        val endpoint = "https://api.github.com/repos/${rule.repository}/commits/".toHttpUrl().newBuilder().addPathSegment(rule.ref).build()
        val metadata = JSONObject(text(endpoint.toString()))
        commit = metadata.getString("sha")
        require(commit.matches(Regex("[0-9a-f]{40}"))) { "Invalid source commit" }
        val details = metadata.getJSONObject("commit")
        updatedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(details.getJSONObject("committer").getString("date"))!!.time
        summary = details.getString("message").lineSequence().first().take(240)
        val tree = JSONObject(text("https://api.github.com/repos/${rule.repository}/git/trees/$commit?recursive=1"))
        require(!tree.optBoolean("truncated")) { "Source tree is incomplete" }
        val files = tree.getJSONArray("tree")
        entries = (0 until files.length()).map { files.getJSONObject(it) }.filter { it.getString("type") == "blob" && it.getString("mode") != "120000" }
            .associate { it.getString("path") to (it.getString("sha") to it.getLong("size")) }
        cache.mkdirs()
    }

    fun paths(prefix: String) = entries.keys.filter { it.startsWith(prefix) }.sorted()
    fun has(path: String) = path in entries
    fun plan(paths: Set<String>) {
        require(paths.isNotEmpty()) { "Empty source plan" }
        totalBytes = paths.sumOf { path ->
            val size = entries[path]?.second ?: error("Missing source file: $path")
            require(size in 0..256L * 1024 * 1024) { "Source file is too large" }
            size
        }
        require(totalBytes in 1..768L * 1024 * 1024) { "Source download exceeds size limit" }
        planned = paths
        reportFile(paths.first(), 0, false)
    }

    fun dictionaryHeader(path: String): String {
        require(path in entries) { "Missing source file: $path" }
        progress("正在分析文件依赖：${path.substringAfterLast('/')}")
        val request = Request.Builder().url(sourceUrl(path)).header("User-Agent", "YuyanSelfOpt").header("Range", "bytes=0-1048575").build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful && response.request.url.isHttps) { "Dictionary source HTTP ${response.code}" }
            val input = response.body!!.source()
            val header = StringBuilder()
            while (true) {
                require(header.length < 1024 * 1024) { "Dictionary header is too large" }
                val line = input.readUtf8LineStrict((1024 * 1024 - header.length).toLong())
                if (line.trim() == "...") break
                header.appendLine(line.removePrefix("\uFEFF"))
            }
            header.toString()
        }
    }

    private fun sourceUrl(path: String) = "https://raw.githubusercontent.com/".toHttpUrl().newBuilder()
        .addPathSegments(rule.repository).addPathSegment(commit).addPathSegments(path).build()

    private fun reportFile(path: String, read: Long, done: Boolean) {
        if (planned.isEmpty()) { progress("正在下载 ${rule.name}：$path"); return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!done && now - lastProgressAt < 250) return
        lastProgressAt = now
        val size = entries.getValue(path).second
        val current = if (size == 0L) 100 else read * 100 / size
        val total = (completedBytes + if (path in completed) 0 else read).coerceAtMost(totalBytes) * 100 / totalBytes
        progress("文件总进度：$total%（${completed.size}/${planned.size}）\n当前文件：${path.substringAfterLast('/')} $current%")
    }

    private fun finishFile(path: String, size: Long) {
        if (completed.add(path)) completedBytes += size
        reportFile(path, size, true)
    }
    fun file(path: String): File {
        require(path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Invalid source path" }
        val (hash, size) = entries[path] ?: error("Missing source file: $path")
        require(size in 0..256L * 1024 * 1024) { "Source file is too large" }
        val target = TransferFiles.child(cache, path)
        if (path in completed && target.isFile && target.length() == size) return target
        require(planned.isEmpty() || path in planned) { "Unexpected file outside source plan" }
        if (target.isFile && target.length() == size && gitHash(target) == hash) { finishFile(path, size); return target }
        reportFile(path, 0, false)
        val url = sourceUrl(path)
        target.parentFile!!.mkdirs()
        val pending = File(target.path + ".partial")
        try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "YuyanSelfOpt").build()).execute().use { response ->
                check(response.isSuccessful && response.request.url.isHttps) { "Dictionary download HTTP ${response.code}" }
                var copied = 0L
                response.body!!.byteStream().use { input -> pending.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Dictionary download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count; downloaded += count
                        require(copied <= size && downloaded <= 768L * 1024 * 1024) { "Source download exceeds size limit" }
                        output.write(buffer, 0, count)
                        reportFile(path, copied, false)
                    }
                } }
                require(copied == size && gitHash(pending) == hash) { "Source blob checksum mismatch" }
            }
            check(pending.renameTo(target)) { "Cannot save source download" }
            finishFile(path, size)
            return target
        } finally { pending.delete() }
    }

    private fun gitHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
