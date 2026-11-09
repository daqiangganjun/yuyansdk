package com.yuyan.imemodule.ui.fragment

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.preference.PreferenceScreen
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.manager.UserDataManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.ui.activity.LauncherActivity
import com.yuyan.imemodule.ui.fragment.base.ManagedPreferenceFragment
import com.yuyan.imemodule.utils.AppUtil
import com.yuyan.imemodule.utils.addPreference
import com.yuyan.imemodule.utils.importErrorDialog
import com.yuyan.imemodule.utils.queryFileName
import com.yuyan.imemodule.utils.TimeUtils
import com.yuyan.imemodule.view.preference.ManagedPreference
import com.yuyan.imemodule.view.widget.withLoadingDialog
import com.yuyan.imemodule.data.transfer.SyncScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable

private val imeHideIcon = AppPrefs.getInstance().other.imeHideIcon

private val switchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
    val componentName = ComponentName(Launcher.instance.context.packageName, LauncherActivity::class.java.name)
    Launcher.instance.context.packageManager.setComponentEnabledSetting(componentName, if(value) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
}

class OtherSettingsFragment: ManagedPreferenceFragment(AppPrefs.getInstance().other){

    private var exportTimestamp = System.currentTimeMillis()
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<String>
    private val exportScopeKey = "transfer_export_scopes"
    private fun exportScopes(): Set<SyncScope> {
        val saved = PreferenceManager.getDefaultSharedPreferences(requireContext()).getStringSet(exportScopeKey, null)
        return SyncScope.entries.filter { saved == null || it.id in saved }.toSet().ifEmpty { SyncScope.entries.toSet() }
    }

    override fun onStart() {
        super.onStart()
        imeHideIcon.registerOnChangeListener(switchKeyListener)
    }

    override fun onStop() {
        super.onStop()
        imeHideIcon.unregisterOnChangeListener(switchKeyListener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        UserDataManager.preview(ctx.contentResolver.openInputStream(uri)!!)
                    } }
                    result.fold({ preview ->
                        if (!isAdded) return@fold
                        val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(preview.time))
                        val scope = preview.scopes?.let { ids -> SyncScope.entries.filter { it.id in ids }.joinToString("、") { it.label } }
                            ?: if (preview.complete) "设置、剪贴板、常用语、学习记录、基础词库、词库更新规则和主题" else "旧版设置、数据库、主题及可读取的学习记录"
                        val excluded = if (preview.scopes != null) "\n未包含的类别保留本机现有数据。" else ""
                        MaterialAlertDialogBuilder(ctx).setTitle("恢复用户数据")
                            .setMessage("备份版本：${preview.version}\n导出时间：$date\n\n将恢复$scope，并自动保留恢复前的本机快照。$excluded")
                            .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ ->
                                lifecycleScope.withLoadingDialog(ctx) {
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        try {
                                            UserDataManager.import(ctx.contentResolver.openInputStream(uri)!!).getOrThrow()
                                            withContext(Dispatchers.Main) {
                                                AppUtil.showRestartNotification(ctx)
                                                Toast.makeText(ctx, R.string.user_data_imported, Toast.LENGTH_SHORT).show()
                                            }
                                            lifecycleScope.launch(NonCancellable + Dispatchers.Main) { delay(400L); AppUtil.exit() }
                                        } catch (error: Exception) { ctx.importErrorDialog(error) }
                                    }
                                }
                            }.show()
                    }, { error -> ctx.importErrorDialog(error) })
                }
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(requireContext()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            UserDataManager.export(outputStream, exportTimestamp, exportScopes()).getOrThrow()
                        } catch (e: Exception) {
                            ctx.importErrorDialog(e)
                        }
                    }
                }
            }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = requireContext()
        screen.addPreference(androidx.preference.Preference(ctx).apply {
            title = "词库"
            summary = "词库选择、更新规则、自动更新与恢复默认"
            setOnPreferenceClickListener {
                androidx.navigation.fragment.NavHostFragment.findNavController(this@OtherSettingsFragment).navigate(R.id.transferSettingsFragment)
                true
            }
        })
        screen.addPreference(androidx.preference.Preference(ctx).apply {
            title = "云同步"
            summary = "WebDAV、多设备同步、数据范围与冲突处理"
            setOnPreferenceClickListener {
                androidx.navigation.fragment.NavHostFragment.findNavController(this@OtherSettingsFragment).navigate(R.id.cloudSyncSettingsFragment)
                true
            }
        })
        screen.addPreference(R.string.export_user_data, "选择要导出的数据类别，与云同步范围独立") {
            DataScopeDialog.show(ctx, "选择导出数据", exportScopes(), requireSelection = true) { selected ->
                PreferenceManager.getDefaultSharedPreferences(ctx).edit().putStringSet(exportScopeKey, selected.map { it.id }.toSet()).apply()
                exportTimestamp = System.currentTimeMillis()
                exportLauncher.launch("yuyanIme_${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip")
            }
        }
        screen.addPreference(R.string.import_user_data) {
            importLauncher.launch("application/zip")
        }
    }
}
