package com.yuyan.imemodule.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yuyan.imemodule.ui.fragment.base.CsPreferenceFragment
import com.yuyan.imemodule.BuildConfig
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.data.update.AppUpdateChecker
import com.yuyan.imemodule.utils.TimeUtils
import com.yuyan.imemodule.utils.addCategory
import com.yuyan.imemodule.utils.addPreference
import com.yuyan.imemodule.view.widget.withLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AboutFragment : CsPreferenceFragment() {
    private var exportTimestamp = System.currentTimeMillis()
    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(requireContext()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                        export(outputStream).getOrThrow()
                    }
                }
            }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream) = runCatching {
        val outputDir = File(Launcher.instance.context.filesDir, "crash_logs")
        ZipOutputStream(dest.buffered()).use { zipStream ->
            writeFileTree(outputDir, zipStream)
            zipStream.closeEntry()
        }
    }

    private fun writeFileTree(srcDir: File, dest: ZipOutputStream) {
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "") {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("${related.path}/"))
                } else if (f.isFile) {
                    dest.putNextEntry(ZipEntry("${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.privacy_policy) {
                findNavController().navigate(R.id.action_aboutFragment_to_privacyPolicyFragment)
            }
            addPreference(R.string.source_code, R.string.github_repo) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CustomConstant.YUYAN_IME_REPO)))
            }
            addPreference(R.string.upstream_source, R.string.upstream_repo) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CustomConstant.UPSTREAM_IME_REPO)))
            }
            addPreference(R.string.license, "BSD-3-Clause（项目源码）") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CustomConstant.LICENSE_URL)))
            }
            addCategory(R.string.app_version) {
                isIconSpaceReserved = false
                addPreference(R.string.version, BuildConfig.versionName){
                    val uri = Uri.parse("${CustomConstant.YUYAN_IME_REPO}/releases/latest")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                addPreference(R.string.check_update, R.string.check_update_summary) { checkUpdate() }
                addPreference(R.string.build_git_hash, BuildConfig.AppCommitHead) {
                    val commit = BuildConfig.AppCommitHead.substringBefore('-')
                    val uri = Uri.parse("${CustomConstant.YUYAN_IME_REPO}/commit/${commit}")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                addPreference(R.string.build_time, BuildConfig.AppBuildTime)

            }

            addCategory(R.string.app_log) {
                isIconSpaceReserved = false
                addPreference(R.string.export_crash_log) {
                    lifecycleScope.launch {
                        exportTimestamp = System.currentTimeMillis()
                        exportLauncher.launch("yuyanIme_crash_log${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip")
                    }
                }
            }
        }


    }

    private fun checkUpdate() {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx, R.string.check_update_loading) {
            val result = withContext(Dispatchers.IO) { runCatching { AppUpdateChecker.latestRelease() } }
            if (!isAdded) return@withLoadingDialog
            result.onSuccess { release ->
                if (AppUpdateChecker.compareVersions(release.version, BuildConfig.versionName) > 0) showUpdate(release)
                else Toast.makeText(ctx, getString(R.string.update_latest, BuildConfig.versionName), Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                val reason = when {
                    error is java.io.IOException -> "无法连接 GitHub"
                    error.message == "GitHub rate limit" -> "GitHub 访问次数受限，请稍后再试"
                    else -> error.message ?: error.javaClass.simpleName
                }
                Toast.makeText(ctx, getString(R.string.update_failed, reason), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showUpdate(release: AppUpdateChecker.Release) {
        val message = buildString {
            append(getString(R.string.update_current_version, BuildConfig.versionName))
            // 发布说明为 Markdown，对话框按纯文本显示，去掉加粗标记即可读
            if (release.notes.isNotEmpty()) append("\n\n").append(release.notes.replace("**", "").take(2000))
        }
        val apkUrl = release.apkUrl
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.update_found, release.version))
            .setMessage(message)
            .setPositiveButton(if (apkUrl != null) R.string.update_download else R.string.update_release_page) { _, _ ->
                openUrl(apkUrl ?: release.pageUrl)
            }
            .apply { if (apkUrl != null) setNeutralButton(R.string.update_release_page) { _, _ -> openUrl(release.pageUrl) } }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}


