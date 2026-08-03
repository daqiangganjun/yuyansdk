
package com.yuyan.imemodule.data.theme

import android.content.SharedPreferences
import androidx.core.content.edit
import com.yuyan.imemodule.view.preference.ManagedPreference

class ManagedThemePreference(
    sharedPreferences: SharedPreferences,
    key: String,
    defaultValue: Theme,
) : ManagedPreference<Theme>(
    sharedPreferences, key, defaultValue
) {

    override fun setValue(value: Theme) {
        sharedPreferences.edit { putString(key, value.name) }
    }

    // 用 getTheme 按名查找，避免 getAllThemes 每次拼出一个新列表再线性扫描
    override fun getValue(): Theme =
        sharedPreferences.getString(key, null)?.let { name ->
            ThemeManager.getTheme(name)
        } ?: defaultValue

    override fun putValueTo(editor: SharedPreferences.Editor) {
        editor.putString(key, getValue().name)
    }

}