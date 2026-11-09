package com.yuyan.imemodule.data.transfer

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebDavClient(baseUrl: String, username: String, password: String) {
    private val base: HttpUrl = baseUrl.trim().let { if (it.endsWith('/')) it else "$it/" }.toHttpUrl().also {
        require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null) { "WebDAV requires an HTTPS URL without embedded credentials" }
    }
    private val authorization = Credentials.basic(username, password, Charsets.UTF_8)
    private val directories = hashSetOf<String>()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request()).also {
            android.util.Log.d("SelfOptWebDav", "${chain.request().method} completed with HTTP ${it.code}")
        } }
        .followRedirects(false).followSslRedirects(false).build()

    data class Remote(val text: String, val etag: String?)
    class Conflict : IOException("Remote data changed; retry required")

    private fun url(path: String): HttpUrl {
        require(!path.startsWith('/') && path.split('/').none { it == ".." || it == "." }) { "Invalid WebDAV path" }
        return base.newBuilder().apply { path.split('/').filter { it.isNotEmpty() }.forEach(::addPathSegment) }.build()
    }
    private fun request(path: String) = Request.Builder().url(url(path)).header("Authorization", authorization)
        .header("Accept-Encoding", "identity")
    private fun check(code: Int, accepted: Set<Int>) {
        if (code == 412) throw Conflict()
        if (code !in accepted) throw IOException("WebDAV HTTP $code")
    }

    fun testConnection() {
        val xml = "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
        client.newCall(request("").header("Depth", "0")
            .method("PROPFIND", xml.toRequestBody("application/xml".toMediaType())).build()).execute().use {
            check(it.code, setOf(207))
        }
    }

    fun ensureDirectory(path: String) {
        val segments = path.trim('/').split('/')
        segments.indices.forEach { i ->
            val directory = segments.take(i + 1).joinToString("/")
            if (directory in directories) return@forEach
            client.newCall(request(directory).method("MKCOL", ByteArray(0).toRequestBody()).build())
                .execute().use { check(it.code, setOf(201, 405)) }
            directories += directory
        }
    }

    fun read(path: String): Remote? = client.newCall(request(path).get().build()).execute().use {
        if (it.code == 404) return null
        check(it.code, setOf(200))
        require((it.body?.contentLength() ?: 0) <= 64L * 1024 * 1024) { "Sync index is too large" }
        val body = it.body ?: throw IOException("Empty WebDAV response")
        val bytes = body.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(out.size().toLong() + size <= 64L * 1024 * 1024) { "Sync index is too large" }
                out.write(buffer, 0, size)
            }
            out.toByteArray()
        }
        Remote(bytes.toString(Charsets.UTF_8), it.header("ETag"))
    }

    fun write(path: String, text: String, expected: Remote?) {
        val builder = request(path).put(text.toRequestBody("application/json".toMediaType()))
        if (expected == null) builder.header("If-None-Match", "*")
        else {
            require(!expected.etag.isNullOrBlank() && !expected.etag.startsWith("W/")) { "WebDAV server must provide a strong ETag for safe synchronization" }
            builder.header("If-Match", expected.etag)
        }
        client.newCall(builder.build()).execute().use { check(it.code, setOf(200, 201, 204)) }
    }

    private fun remoteSize(path: String): Long? {
        val xml = "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/></d:prop></d:propfind>"
        client.newCall(request(path).header("Depth", "0").method("PROPFIND", xml.toRequestBody("application/xml".toMediaType())).build()).execute().use {
            if (it.code == 207) {
                val body = it.body ?: error("Empty WebDAV properties")
                require(body.contentLength() <= 65536) { "WebDAV property response is too large" }
                val text = body.string()
                require(text.length <= 65536 && !text.contains("<!DOCTYPE", true)) { "Invalid WebDAV property response" }
                val parser = android.util.Xml.newPullParser().apply { setInput(text.reader()) }
                var size: Long? = null
                while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "getcontentlength") size = parser.nextText().toLongOrNull()
                }
                return size ?: error("WebDAV omitted object size")
            }
            check(it.code, setOf(404))
            return null
        }
    }

    fun upload(path: String, file: File) {
        remoteSize(path)?.let { size -> require(size == file.length()) { "Remote object size mismatch" }; return }
        client.newCall(request(path).header("If-None-Match", "*")
            .put(file.asRequestBody("application/octet-stream".toMediaType())).build()).execute().use {
            if (it.code == 412) require(remoteSize(path) == file.length()) { "Concurrent remote object size mismatch" }
            else check(it.code, setOf(200, 201, 204))
        }
    }

    fun download(path: String, output: File, expectedHash: String) {
        output.parentFile?.mkdirs()
        val pending = File(output.parentFile, output.name + ".partial")
        try {
            client.newCall(request(path).get().build()).execute().use {
                check(it.code, setOf(200))
                val body = it.body ?: throw IOException("Empty WebDAV response")
                require(body.contentLength() <= TransferFiles.MAX_EXPANDED_BYTES) { "Remote object is too large" }
                body.byteStream().use { input -> pending.outputStream().use { target ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        require(total <= TransferFiles.MAX_EXPANDED_BYTES) { "Remote object is too large" }
                        target.write(buffer, 0, size)
                    }
                } }
            }
            require(TransferFiles.sha256(pending) == expectedHash) { "Downloaded object checksum mismatch" }
            check(pending.renameTo(output)) { "Cannot activate downloaded object" }
        } finally { pending.delete() }
    }

    fun delete(path: String) {
        client.newCall(request(path).delete().build()).execute().use { check(it.code, setOf(200, 204, 404)) }
    }
}
