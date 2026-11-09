package com.yuyan.imemodule.ui.fragment

import android.os.Bundle
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yuyan.imemodule.data.transfer.BackupArchive
import com.yuyan.imemodule.data.transfer.ConflictPolicy
import com.yuyan.imemodule.data.transfer.TransferCoordinator
import com.yuyan.imemodule.data.transfer.WebDavClient
import com.yuyan.imemodule.data.transfer.WebDavSettings
import com.yuyan.imemodule.data.transfer.WebDavSync
import com.yuyan.imemodule.data.transfer.SyncScope
import com.yuyan.imemodule.ui.fragment.base.CsPreferenceFragment
import com.yuyan.inputmethod.DictionaryStore
import com.yuyan.inputmethod.dictionary.DictionaryRules
import com.yuyan.inputmethod.dictionary.GithubDictionarySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TransferSettingsFragment : CsPreferenceFragment() {
    private lateinit var state: Preference
    private lateinit var currentDictionary: Preference
    private var ruleMode: ListPreference? = null
    private var ruleNetwork: EditTextPreference? = null
    private val conditionalRules = mutableListOf<Pair<Preference, Set<String>>>()
    private var cancelUpdate: Preference? = null
    private val dictionaryImport = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                state.summary = "正在校验本地词库包…"
                val result = withContext(Dispatchers.IO) { runCatching {
                    DictionaryStore.importPackage(context, context.contentResolver.openInputStream(uri)!!)
                } }
                result.fold({ TransferCoordinator.installLocalPackage(context, it) }, {
                    state.summary = "导入失败：${TransferCoordinator.safeError(it)}"
                })
            }
        }
    }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        conditionalRules.clear()
        ruleMode = null
        ruleNetwork = null
        DictionaryStore.configuredIndex(context)
        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen
        state = Preference(context).apply {
            title = "词库状态"
            summary = TransferCoordinator.lastStatus(context, "update")
            isPersistent = false
        }
        screen.addPreference(state)
        val dictionaries = PreferenceCategory(context).apply { title = "词库更新" }
        screen.addPreference(dictionaries)
        currentDictionary = Preference(context).apply {
            title = "当前词库"
            isPersistent = false
            setOnPreferenceClickListener { selectDictionary(); true }
        }
        dictionaries.addPreference(currentDictionary)
        dictionaries.addPreference(Preference(context).apply {
            title = "恢复默认词库与预设"
            summary = "恢复安装包内置白霜及默认更新规则；引擎无法启动时也可使用，保留学习记录"
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(context).setTitle(title)
                    .setMessage("将停止词库更新，恢复当前安装包内置的白霜词库、输入方案及更新规则，清除编译缓存。雾凇恢复为未启用；学习记录、主题和其他个人设置保留。")
                    .setNegativeButton("取消", null).setPositiveButton("恢复默认") { _, _ -> TransferCoordinator.restoreDefaultDictionary(context) }.show()
                true
            }
        })
        refreshDictionary()
        dictionaries.addPreference(frequency("transfer_update_frequency", "自动检查频率"))
        dictionaries.addPreference(Preference(context).apply {
            title = "立即检查词库更新"
            summary = "只更新选中的词库；后台拉取、构建与测试，通过后在键盘空闲时启用"
            setOnPreferenceClickListener { TransferCoordinator.requestUpdate(context); true }
        })
        dictionaries.addPreference(Preference(context).apply {
            title = "导入本地词库包"
            summary = "选择适配本输入法的独立 ZIP 包，校验后空闲启用；保留学习记录"
            setOnPreferenceClickListener { dictionaryImport.launch("application/zip"); true }
        })
        dictionaries.addPreference(Preference(context).apply {
            title = "取消词库更新"
            summary = "停止下载或构建，取消待启用版本，保留当前词库"
            setOnPreferenceClickListener { TransferCoordinator.cancelUpdate(context); true }
        }.also { cancelUpdate = it })
        dictionaries.addPreference(EditTextPreference(context).apply {
            key = "transfer_dictionary_index"
            conditionalRules += this to setOf("package")
            title = "兼容词库包索引地址"
            dialogTitle = title
            positiveButtonText = "确定"
            negativeButtonText = "取消"
            setDefaultValue(DictionaryStore.DEFAULT_INDEX)
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                it.text?.takeIf(String::isNotBlank) ?: "未设置（仅兼容词库包索引方式需要）"
            }
            dialogMessage = "仅在“兼容词库包索引”方式下使用。填写适配本输入法的 HTTPS index.json 地址；直接拉取上游请选“自带官方源规则”。"
            setOnPreferenceChangeListener { _, value ->
                val url = value.toString()
                val valid = url.isBlank() || (android.net.Uri.parse(url).let { it.scheme == "https" && !it.host.isNullOrBlank() })
                if (!valid) android.widget.Toast.makeText(context, "请填写有效的 HTTPS 索引地址", android.widget.Toast.LENGTH_SHORT).show()
                if (valid) DictionaryRules.configure(context, DictionaryRules.settings(context).copy(packageIndex = url.trim()))
                valid
            }
        })
        val rules = PreferenceCategory(context).apply { title = "词库更新规则" }
        screen.addPreference(rules)
        rules.addPreference(ListPreference(context).apply {
            key = "transfer_rule_mode_ui"; negativeButtonText = "取消"
            title = "更新方式"; dialogTitle = title; isPersistent = false
            entries = arrayOf("自带官方源规则", "手动输入或粘贴规则", "网络规则", "兼容词库包索引")
            entryValues = arrayOf("builtin", "custom", "network", "package")
            value = DictionaryRules.settings(context).mode
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                DictionaryRules.configure(context, DictionaryRules.settings(context).copy(mode = value.toString()))
                updateRuleVisibility(value.toString())
                true
            }
        }.also { ruleMode = it })
        ruleMode?.setDefaultValue("builtin")
        rules.addPreference(Preference(context).apply {
            key = "transfer_rule_editor_ui"
            conditionalRules += this to setOf("custom")
            title = "输入、粘贴或复制规则"
            summary = "编辑 JSON 规则；支持白霜、雾凇及使用相同目录结构的 GitHub 源"
            setOnPreferenceClickListener {
                val edit = android.widget.EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    setText(DictionaryRules.export(context)); minLines = 6; maxLines = 14
                    setPadding(32, 16, 32, 16)
                }
                val dialog = MaterialAlertDialogBuilder(context).setTitle("词库更新规则")
                    .setView(edit).setPositiveButton("保存", null).setNegativeButton("取消", null)
                    .setNeutralButton("复制", null).create()
                dialog.setOnShowListener {
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        runCatching {
                            DictionaryRules.import(context, edit.text.toString())
                            DictionaryRules.configure(context, DictionaryRules.settings(context).copy(mode = "custom"))
                        }.fold({ dialog.dismiss(); onCreatePreferences(null, null) }, { edit.error = "规则格式无效，请检查字段、仓库和词库类型" })
                    }
                    dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("词库更新规则", edit.text))
                        android.widget.Toast.makeText(context, "规则已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.show(); true
            }
        })
        rules.addPreference(EditTextPreference(context).apply {
            key = "transfer_rule_network_ui"; negativeButtonText = "取消"; positiveButtonText = "保存"
            conditionalRules += this to setOf("network")
            title = "网络规则地址"; dialogTitle = title; isPersistent = false
            text = DictionaryRules.settings(context).networkUrl
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { it.text?.takeIf(String::isNotBlank) ?: "未设置网络规则地址" }
            setOnPreferenceChangeListener { _, value ->
                runCatching { DictionaryRules.configure(context, DictionaryRules.settings(context).copy(networkUrl = value.toString().trim())) }.isSuccess
            }
        }.also { ruleNetwork = it })
        ruleNetwork?.setDefaultValue("")
        rules.addPreference(Preference(context).apply {
            key = "transfer_rule_fetch_ui"
            conditionalRules += this to setOf("network")
            title = "立即获取网络规则"
            summary = "读取并校验 HTTPS 规则文件；不会因此下载未选中的词库"
            setOnPreferenceClickListener {
                lifecycleScope.launch {
                    state.summary = "正在获取规则…"
                    val result = withContext(Dispatchers.IO) { runCatching {
                        DictionaryRules.import(context, GithubDictionarySource.text(DictionaryRules.settings(context).networkUrl))
                        DictionaryRules.configure(context, DictionaryRules.settings(context).copy(mode = "network"))
                    } }
                    result.fold({ onCreatePreferences(null, null); state.summary = "网络规则已保存" }, { state.summary = "获取失败：${TransferCoordinator.safeError(it)}" })
                }; true
            }
        })
        for ((family, name) in listOf("frost" to "白霜", "ice" to "雾凇")) {
            val choice = ListPreference(context).apply {
            key = "transfer_rule_${family}_ui"; negativeButtonText = "取消"
            conditionalRules += this to setOf("custom", "network")
            title = "${name}使用的自定义规则"; dialogTitle = title; isPersistent = false
            val available = DictionaryRules.all(context).filter { it.family == family }
            entries = available.map { it.name }.toTypedArray(); entryValues = available.map { it.id }.toTypedArray()
            value = DictionaryRules.settings(context).selected[family] ?: "builtin_$family"
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                val config = DictionaryRules.settings(context)
                DictionaryRules.configure(context, config.copy(selected = config.selected + (family to value.toString()))); true
            }
            }
            rules.addPreference(choice)
            choice.setDefaultValue("builtin_$family")
        }
        updateRuleVisibility(DictionaryRules.settings(context).mode)
    }

    private fun updateRuleVisibility(mode: String) {
        conditionalRules.forEach { (preference, modes) -> preference.isVisible = mode in modes }
        cancelUpdate?.isVisible = TransferCoordinator.hasPendingUpdate(requireContext())
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

    private fun refreshDictionary() {
        val context = requireContext()
        val ruleSettings = DictionaryRules.settings(context)
        ruleMode?.value = ruleSettings.mode
        ruleNetwork?.text = ruleSettings.networkUrl
        updateRuleVisibility(ruleSettings.mode)
        val active = runCatching { DictionaryStore.active(context) }.getOrElse {
            currentDictionary.summary = "词库状态损坏，可使用“恢复默认词库与预设”修复"
            return
        }
        val selected = PreferenceManager.getDefaultSharedPreferences(context).getString("rime_dictionary_family", "frost")
        val current = if (selected == "ice" && "ice" in active) "雾凇" else "白霜"
        currentDictionary.summary = "正在使用：$current（点击切换或获取）\n" +
            active.values.joinToString("\n") { "${if (it.family == "ice") "雾凇" else "白霜"}：${it.revision.take(8)}" } +
            if ("ice" !in active) "\n雾凇：未下载" else ""
    }

    private fun selectDictionary() {
        val context = requireContext()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val installed = runCatching { DictionaryStore.hasIce(context) }.getOrDefault(false)
        val selected = if (installed && preferences.getString("rime_dictionary_family", "frost") == "ice") 1 else 0
        MaterialAlertDialogBuilder(context).setTitle("选择词库")
            .setSingleChoiceItems(arrayOf("白霜拼音", if (installed) "雾凇拼音" else "雾凇拼音（未下载）"), selected) { dialog, which ->
                dialog.dismiss()
                if (which == 1 && !installed) {
                    val configured = TransferCoordinator.hasUpdateSource(context)
                    MaterialAlertDialogBuilder(context).setTitle("获取雾凇词库")
                        .setMessage(if (configured) "按所选更新方式获取并测试，通过后自动切换；未完成前继续使用白霜。也可导入本地词库包。" else "尚未配置所选更新方式。可使用自带官方源规则，填写网络规则或兼容包索引，也可导入本地词库包。")
                        .apply { if (configured) setPositiveButton("在线下载") { _, _ -> TransferCoordinator.requestUpdate(context, ice = true) } }
                        .setNeutralButton("导入本地包") { _, _ -> dictionaryImport.launch("application/zip") }
                        .setNegativeButton("取消", null).show()
                } else {
                    context.getSharedPreferences("transfer_state", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("ice_requested", false).apply()
                    preferences.edit().putString("rime_dictionary_family", if (which == 1) "ice" else "frost").apply()
                    refreshDictionary()
                }
            }.setNegativeButton("取消", null).show()
    }

    override fun onResume() {
        super.onResume()
        refreshDictionary()
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        TransferCoordinator.status.observe(viewLifecycleOwner) {
            state.summary = TransferCoordinator.lastStatus(requireContext(), "update")
            refreshDictionary()
        }
    }
}
