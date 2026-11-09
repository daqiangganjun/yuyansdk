package com.yuyan.imemodule.data.transfer

import android.content.Context
import androidx.preference.PreferenceManager
import com.yuyan.inputmethod.DictionaryStore

enum class SyncScope(val id: String, val label: String, val enabledByDefault: Boolean = true) {
    SETTINGS("settings", "应用设置"),
    LEARNING("learning", "学习记录"),
    PHRASES("phrases", "常用语"),
    CLIPBOARD("clipboard", "剪贴板", false),
    THEMES("themes", "主题与背景图片"),
    SYMBOLS("symbols", "符号、表情与菜单"),
    DICTIONARIES("dictionaries", "基础词库"),
    DICTIONARY_RULES("dictionary_rules", "词库更新规则");

    val preferenceKey get() = "transfer_sync_$id"
    fun enabled(context: Context) = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(preferenceKey, enabledByDefault)
    fun matches(key: String): Boolean = when (this) {
        SETTINGS -> key.startsWith("pref/")
        LEARNING -> key.startsWith("learn/")
        PHRASES -> key.startsWith("row/phrase/")
        CLIPBOARD -> key.startsWith("row/clipboard/")
        THEMES -> key.startsWith("theme/")
        SYMBOLS -> listOf("row/side_symbol/", "row/usedSymbol/", "row/skbfun/").any(key::startsWith)
        DICTIONARIES -> key.startsWith("dictionary/")
        DICTIONARY_RULES -> key.startsWith("rule/")
    }

    companion object {
        fun includes(context: Context, key: String): Boolean {
            val scope = entries.firstOrNull { it.matches(key) } ?: return false
            if (!scope.enabled(context)) return false
            if (scope == SETTINGS) {
                val name = android.util.Base64.decode(key.removePrefix("pref/"), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING).toString(Charsets.UTF_8)
                if (name.startsWith("transfer_") || name == "rime_dictionary_family") return false
            }
            if (key == "dictionary/ice") {
                val requested = PreferenceManager.getDefaultSharedPreferences(context).getString("rime_dictionary_family", "frost") == "ice" ||
                    context.getSharedPreferences("transfer_state", Context.MODE_PRIVATE).getBoolean("ice_requested", false)
                if (!requested && !DictionaryStore.hasIce(context)) return false
            }
            return true
        }
        fun select(context: Context, values: Map<String, String>) = values.filterKeys { includes(context, it) }
    }
}
