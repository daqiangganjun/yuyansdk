package com.yuyan.imemodule.ui.fragment

import android.os.Bundle
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yuyan.imemodule.data.transfer.*
import com.yuyan.imemodule.ui.fragment.base.CsPreferenceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CloudSyncSettingsFragment : CsPreferenceFragment() {
    private lateinit var state: Preference
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen
        state = Preference(context).apply { title = "云同步状态"; isPersistent = false; summary = TransferCoordinator.lastStatus(context, "sync") }
        screen.addPreference(state)
        val sync = PreferenceCategory(context).apply { title = "WebDAV 多设备同步" }
        screen.addPreference(sync)
        val config = WebDavSync.configuration(context)
        fun field(label: String, value: String, password: Boolean = false): EditTextPreference = EditTextPreference(context).apply {
            key = "transfer_form_$label"
            title = label
            dialogTitle = label
            positiveButtonText = "确定"
            negativeButtonText = "取消"
            isPersistent = false
            text = value
            summary = if (password) if (value.isBlank()) "未设置" else "已设置" else value.ifBlank { "未设置" }
            setOnBindEditTextListener { edit ->
                edit.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                edit.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { _, changed ->
                summary = if (password) if (changed.toString().isBlank()) "未设置" else "已设置" else changed.toString()
                true
            }
        }
        val url = field("服务器地址", config.url)
        val username = field("账户", config.username)
        val password = field("应用密码", config.password, true)
        val directory = field("同步目录", config.directory)
        listOf(url, username, password, directory).forEach(sync::addPreference)
        sync.addPreference(Preference(context).apply {
            title = "测试连接并保存"
            summary = "使用服务商提供的应用密码；账户信息仅加密保存在本机"
            setOnPreferenceClickListener {
                val settings = WebDavSettings(url.text.orEmpty(), username.text.orEmpty(), password.text.orEmpty(), directory.text.orEmpty())
                lifecycleScope.launch {
                    state.summary = "正在测试连接…"
                    val result = withContext(Dispatchers.IO) { runCatching {
                        WebDavClient(settings.url, settings.username, settings.password).testConnection()
                        WebDavSync.configure(context, settings)
                    } }
                    state.summary = result.fold({ "连接成功，配置已保存" }, { "连接失败：${TransferCoordinator.safeError(it)}" })
                }
                true
            }
        })
        sync.addPreference(frequency("transfer_sync_frequency", "自动同步频率"))
        sync.addPreference(ListPreference(context).apply {
            key = "transfer_conflict_policy"
            title = "冲突处理策略"
            dialogTitle = title
            negativeButtonText = "取消"
            entries = arrayOf("保留最新", "保留本机", "保留远端", "手动选择")
            entryValues = arrayOf("LATEST", "LOCAL", "REMOTE", "ASK")
            setDefaultValue("LATEST")
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        })
        val scopeChoice = Preference(context).apply {
            title = "参与同步的数据"
            isPersistent = false
            fun refreshSelection() { summary = "已选择 ${SyncScope.entries.count { it.enabled(context) }}/${SyncScope.entries.size} 类数据" }
            refreshSelection()
            setOnPreferenceClickListener {
                DataScopeDialog.show(context, "参与同步的数据", SyncScope.entries.filter { it.enabled(context) }.toSet()) { selected ->
                    PreferenceManager.getDefaultSharedPreferences(context).edit().apply {
                        SyncScope.entries.forEach { putBoolean(it.preferenceKey, it in selected) }
                    }.apply()
                    refreshSelection()
                }
                true
            }
        }
        sync.addPreference(scopeChoice)
        val actions = PreferenceCategory(context).apply { title = "同步操作" }
        screen.addPreference(actions)
        actions.addPreference(Preference(context).apply {
            title = "立即同步"
            summary = "双向合并多设备修改，键盘使用中会等待收起后执行"
            setOnPreferenceClickListener { TransferCoordinator.requestSync(context); true }
        })
        actions.addPreference(Preference(context).apply {
            title = "处理待确认冲突"
            setOnPreferenceClickListener {
                val count = TransferCoordinator.conflictCount(context)
                MaterialAlertDialogBuilder(context).setTitle("同步冲突")
                    .setMessage(if (count == 0) "当前没有待确认的冲突。" else "有 $count 项同时被多台设备修改，请选择本次冲突的处理方式。无冲突的内容仍会正常合并。")
                    .apply { if (count > 0) {
                        setPositiveButton("保留本机") { _, _ -> TransferCoordinator.requestSync(context, ConflictPolicy.LOCAL) }
                        setNeutralButton("保留远端") { _, _ -> TransferCoordinator.requestSync(context, ConflictPolicy.REMOTE) }
                    } }.setNegativeButton("关闭", null).show()
                true
            }
        })
        actions.addPreference(Preference(context).apply {
            title = "恢复上次同步或导入前的数据"
            summary = "恢复本机保留的上一份完整快照，包括学习记录和基础词库"
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(context).setTitle("恢复本机快照")
                    .setMessage("将恢复上次数据应用前的状态。WebDAV 中的内容不会立即改变。")
                    .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching {
                                val work = File(context.cacheDir, "transfer-manual-recovery").apply { deleteRecursively(); mkdirs() }
                                try {
                                    val snapshot = BackupArchive.read(BackupArchive.journal(context).inputStream(), work).second
                                    BackupArchive.apply(context, snapshot)
                                } finally { work.deleteRecursively() }
                            } }
                            state.summary = result.fold({ "已恢复本机数据" }, { "恢复失败：${TransferCoordinator.safeError(it)}" })
                        }
                    }.show()
                true
            }
        })
    }

    private fun frequency(name: String, label: String) = ListPreference(preferenceManager.context).apply {
        key = name
        title = label
        dialogTitle = label
        negativeButtonText = "取消"
        entries = arrayOf("关闭", "每天首次使用", "每周首次使用", "每月首次使用")
        entryValues = arrayOf("off", "daily", "weekly", "monthly")
        setDefaultValue("off")
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        TransferCoordinator.status.observe(viewLifecycleOwner) { state.summary = TransferCoordinator.lastStatus(requireContext(), "sync") }
    }
}
