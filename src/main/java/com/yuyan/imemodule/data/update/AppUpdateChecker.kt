package com.yuyan.imemodule.data.update

import com.yuyan.imemodule.application.CustomConstant
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 通过 GitHub Release 检查应用更新。
 *
 * 只认本分支 selfopt-v 前缀的正式版：仓库中继承自上游的 v4.x 标签与词库频道等预发布都会被跳过，
 * 因此取发布列表自行挑选最高版本，而不依赖 releases/latest 的排序。
 */
object AppUpdateChecker {
    private const val TAG_PREFIX = "selfopt-v"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    data class Release(val version: String, val notes: String, val pageUrl: String, val apkUrl: String?)

    /** 最新正式版，网络或接口异常时抛出 */
    fun latestRelease(): Release {
        val request = Request.Builder().url("${CustomConstant.YUYAN_IME_RELEASES_API}?per_page=30")
            .header("User-Agent", "YuyanSelfOpt").header("Accept", "application/vnd.github+json").build()
        val body = http.newCall(request).execute().use { response ->
            check(response.code != 429 && !(response.code == 403 && response.header("X-RateLimit-Remaining") == "0")) { "GitHub rate limit" }
            check(response.isSuccessful) { "Release query HTTP ${response.code}" }
            response.body.string()
        }
        val releases = JSONArray(body)
        return (0 until releases.length()).map { releases.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") && it.optString("tag_name").startsWith(TAG_PREFIX) }
            .map { release ->
                val assets = release.optJSONArray("assets") ?: JSONArray()
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith("_release.apk") }
                Release(
                    version = release.getString("tag_name").removePrefix(TAG_PREFIX),
                    notes = release.optString("body").trim(),
                    pageUrl = release.getString("html_url"),
                    apkUrl = apk?.optString("browser_download_url")?.takeIf { it.startsWith("https://") },
                )
            }
            .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
            ?: error("No published release")
    }

    /** 三段式语义化版本比较，缺位按 0 计，非数字部分忽略 */
    fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val right = b.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
