package com.yuyan.imemodule.ui.fragment

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import android.content.SharedPreferences
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.yuyan.imemodule.R
import com.yuyan.imemodule.speech.SpeechCredentials
import com.yuyan.imemodule.speech.SpeechOption
import com.yuyan.imemodule.speech.SpeechOptionType
import com.yuyan.imemodule.speech.SpeechProvider
import com.yuyan.imemodule.speech.SpeechProviders
import com.yuyan.imemodule.speech.SpeechSettings
import com.yuyan.imemodule.speech.SpeechConnectionCheck
import com.yuyan.imemodule.ui.fragment.base.CsPreferenceFragment
import org.json.JSONObject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VoiceSettingsFragment : CsPreferenceFragment() {
    private lateinit var settings: SpeechSettings
    private var credentialPreference: Preference? = null
    private var permissionPreference: Preference? = null
    private var checkPreference: Preference? = null
    private var checkJob: Job? = null
    private var checkGeneration = 0
    private var checkDialog: AlertDialog? = null
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cancelCheck() }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermission()
        if (!it) toast("未获得麦克风权限，可在系统应用设置中允许")
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        settings = SpeechSettings(requireContext())
        preferenceManager.sharedPreferencesName = "speech_settings"
        buildScreen()
        settings.preferences.registerOnSharedPreferenceChangeListener(settingsListener)
    }

    private fun buildScreen() {
        cancelCheck()
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen
        screen.addPreference(SwitchPreferenceCompat(ctx).apply {
            key = "enabled"
            title = "启用语音输入"
            summary = "长按空格开始，松开结束，最终结果自动上屏。录音会发送给所选供应商，不保存录音文件。"
            setDefaultValue(false)
        })
        screen.addPreference(ListPreference(ctx).apply {
            key = "provider"
            title = "识别供应商"
            entries = SpeechProviders.all().map { it.displayName }.toTypedArray()
            entryValues = SpeechProviders.all().map { it.id }.toTypedArray()
            setDefaultValue("doubao")
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                settings.providerId = value.toString()
                buildScreen()
                false
            }
        })
        permissionPreference = Preference(ctx).apply {
            title = "麦克风权限"
            isPersistent = false
            setOnPreferenceClickListener { microphonePermission.launch(Manifest.permission.RECORD_AUDIO); true }
        }.also { screen.addPreference(it) }
        val provider = SpeechProviders.get(settings.providerId) ?: return
        credentialPreference = Preference(ctx).apply {
            title = "设置 API Key"
            isPersistent = false
            setOnPreferenceClickListener { editCredential(provider); true }
        }.also { screen.addPreference(it) }
        checkPreference = Preference(ctx).apply {
            key = "speech_connection_check"
            title = "检查连接"
            summary = CHECK_SUMMARY
            isPersistent = false
            isIconSpaceReserved = false
            setOnPreferenceClickListener { checkConnection(); true }
        }.also { screen.addPreference(it) }
        screen.addPreference(Preference(ctx).apply {
            title = "清除当前供应商密钥"
            summary = "密钥不会加入安装包，也不会随应用备份导出"
            setOnPreferenceClickListener {
                cancelCheck()
                SpeechCredentials(ctx).write(provider.id, "")
                refreshCredential(provider)
                true
            }
        })
        val category = PreferenceCategory(ctx).apply { title = "${provider.displayName}参数" }
        screen.addPreference(category)
        provider.options.forEach { category.addPreference(optionPreference(provider, it)) }
        screen.addPreference(Preference(ctx).apply {
            title = "检查参数"
            summary = "检查当前参数格式；高级参数可覆盖对应控件的值"
            setOnPreferenceClickListener {
                toast(if (runCatching { settings.options(provider) }.isSuccess) "参数格式有效" else "参数无效，请检查整数范围和 JSON 内容")
                true
            }
        })
        refreshPermission()
        refreshCredential(provider)
    }

    private fun optionPreference(provider: SpeechProvider, option: SpeechOption): Preference {
        val ctx = requireContext()
        val preference = when (option.type) {
            SpeechOptionType.SWITCH -> object : SwitchPreferenceCompat(ctx) {
                override fun onBindViewHolder(holder: PreferenceViewHolder) {
                    super.onBindViewHolder(holder)
                    bindHelp(holder, option)
                }
            }
            SpeechOptionType.CHOICE -> object : ListPreference(ctx) {
                override fun onBindViewHolder(holder: PreferenceViewHolder) {
                    super.onBindViewHolder(holder)
                    bindHelp(holder, option)
                }
            }.apply {
                entries = option.choices.values.toTypedArray()
                entryValues = option.choices.keys.toTypedArray()
                summaryProvider = Preference.SummaryProvider<ListPreference> { "${it.entry ?: ""}\n${option.description}" }
            }
            else -> object : EditTextPreference(ctx) {
                override fun onBindViewHolder(holder: PreferenceViewHolder) {
                    super.onBindViewHolder(holder)
                    bindHelp(holder, option)
                }
            }.apply {
                setOnBindEditTextListener { edit ->
                    edit.inputType = if (option.type == SpeechOptionType.INTEGER) InputType.TYPE_CLASS_NUMBER
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    edit.isSingleLine = option.type == SpeechOptionType.INTEGER
                }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { "${it.text?.takeIf(String::isNotBlank) ?: "未设置"}\n${option.description}" }
            }
        }
        return preference.apply {
            layoutResource = R.layout.preference_speech_option
            key = settings.key(provider, option)
            title = option.title
            if (option.type == SpeechOptionType.SWITCH) summary = option.description
            setDefaultValue(if (option.type == SpeechOptionType.SWITCH) option.defaultValue else option.defaultValue.toString())
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, value ->
                val valid = runCatching {
                    when (option.type) {
                        SpeechOptionType.INTEGER -> {
                            if (value.toString().isNotBlank()) {
                                val number = value.toString().toInt()
                                require(option.range == null || number in option.range) { "Integer option is out of range" }
                            }
                        }
                        SpeechOptionType.JSON_OBJECT, SpeechOptionType.JSON_STRING -> if (value.toString().isNotBlank()) {
                            val json = JSONObject(value.toString())
                            if (option.key == "extra_request") {
                                require(!json.has("result_type") || json.optString("result_type") == "full") { "Unsupported result type" }
                            }
                        }
                        else -> Unit
                    }
                }.isSuccess
                if (!valid) toast(if (option.range != null) "请输入 ${option.range.first} 至 ${option.range.last} 的整数" else "请输入有效的 JSON 对象")
                valid
            }
        }
    }

    private fun bindHelp(holder: PreferenceViewHolder, option: SpeechOption) {
        holder.findViewById(R.id.speech_option_help)?.apply {
            contentDescription = "${option.title}：查看说明"
            setOnClickListener {
                AlertDialog.Builder(context).setTitle(option.title).setMessage(option.details)
                    .setPositiveButton("知道了", null).show()
            }
        }
    }

    private fun editCredential(provider: SpeechProvider) {
        cancelCheck()
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            isSaveEnabled = false
            hint = "请输入密钥，留空不修改"
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val dialog = AlertDialog.Builder(ctx).setTitle("${provider.displayName} API Key")
            .setMessage("密钥仅保存在本机，使用 Android Keystore 加密，不参与备份。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    val saved = runCatching { SpeechCredentials(ctx).write(provider.id, key) }.isSuccess
                    toast(if (saved) "密钥已保存" else "密钥保存失败，请重试")
                    refreshCredential(provider)
                }
                input.text.clear()
            }
            .setNegativeButton("取消") { _, _ -> input.text.clear() }.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
    }

    private fun checkConnection() {
        if (checkJob?.isActive == true) return
        cancelCheck()
        val token = checkGeneration
        val ctx = requireContext().applicationContext
        checkPreference?.apply {
            title = "正在检查…"
            summary = "正在发送内置测试语音，请稍候（最长约 25 秒）"
            isEnabled = false
        }
        checkJob = viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = SpeechConnectionCheck.run(ctx)
                if (token != checkGeneration) return@launch
                checkPreference?.summary = result.message
                val details = if (result.success) {
                    "${result.message}\n耗时：${result.elapsedMs} 毫秒\n\n测试语音识别结果：\n${result.recognizedText}"
                } else result.message
                checkDialog = AlertDialog.Builder(requireContext())
                    .setTitle(if (result.success) "检查成功" else "检查未通过")
                    .setMessage(details).setPositiveButton("知道了", null).show()
            } finally {
                if (token == checkGeneration) {
                    checkPreference?.apply { title = "检查连接"; isEnabled = true }
                    checkJob = null
                }
            }
        }
        checkJob?.start()
    }

    private fun cancelCheck() {
        checkGeneration++
        checkJob?.cancel()
        checkJob = null
        checkDialog?.dismiss()
        checkDialog = null
        checkPreference?.apply {
            title = "检查连接"
            summary = CHECK_SUMMARY
            isEnabled = true
        }
    }

    override fun onStop() {
        cancelCheck()
        super.onStop()
    }

    override fun onDestroy() {
        cancelCheck()
        settings.preferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        super.onDestroy()
    }

    private fun refreshPermission() {
        val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        permissionPreference?.summary = if (granted) "已允许" else "点击授权，仅在语音输入期间使用麦克风"
    }

    private fun refreshCredential(provider: SpeechProvider) {
        credentialPreference?.summary = if (SpeechCredentials(requireContext()).read(provider.id).isNullOrBlank()) "未配置" else "已配置（不显示密钥）"
    }

    private fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    companion object {
        private const val CHECK_SUMMARY = "发送内置测试语音，检查当前密钥、资源与参数；不使用麦克风"
    }
}
