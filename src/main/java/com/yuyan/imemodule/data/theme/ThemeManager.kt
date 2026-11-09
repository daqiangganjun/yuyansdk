
package com.yuyan.imemodule.data.theme

import android.content.res.Configuration
import androidx.annotation.Keep
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.WeakHashSet
import com.yuyan.imemodule.utils.isDarkMode
import com.yuyan.imemodule.view.preference.ManagedPreference

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    val BuiltinThemes = listOf(
        ThemePreset.MaterialLight,
        ThemePreset.MaterialDark,
        ThemePreset.PixelLight,
        ThemePreset.PixelDark,
        ThemePreset.NordLight,
        ThemePreset.NordDark,
        ThemePreset.CustomRed,
        ThemePreset.AMOLEDBlack,
        ThemePreset.CustomBlue,
        ThemePreset.Monokai,
        ThemePreset.CustomPink,
        ThemePreset.CustomCrimson,
        ThemePreset.CustomYellow,
        ThemePreset.CustomPurple,
    )

    val DefaultTheme = ThemePreset.MaterialLight

    private val customThemes: MutableList<Theme.Custom> = ThemeFilesManager.listThemes()

    fun getTheme(name: String) =
        customThemes.find { it.name == name } ?: BuiltinThemes.find { it.name == name }

    fun getAllThemes() = customThemes + BuiltinThemes

    fun refreshThemes() {
        customThemes.clear()
        customThemes.addAll(ThemeFilesManager.listThemes())
        _activeTheme = evaluateActiveTheme()
        fireChange()
    }

    /**
     * [backing property](https://kotlinlang.org/docs/properties.html#backing-properties)
     * of [activeTheme]; holds the [Theme] object currently in use
     */
    private lateinit var _activeTheme: Theme

    var activeTheme: Theme
        get() = _activeTheme
        private set(value) {
            if (_activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private var isDarkMode = false
    private var nightOverride: Boolean? = null
    val isNightMode: Boolean
        get() = if (prefs.followSystemDayNightTheme.getValue()) nightOverride ?: isDarkMode
            else prefs.manualNightMode ?: prefs.normalModeTheme.getValue().isDark

    fun toggleNightMode() {
        val target = !isNightMode
        val theme = (if (target) prefs.darkModeTheme else prefs.lightModeTheme).getValue()
        prefs.manualNightMode = target
        if (prefs.followSystemDayNightTheme.getValue()) nightOverride = target
        else prefs.normalModeTheme.setValue(theme)
        _activeTheme = theme
        fireChange()
    }

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onThemeChange(_activeTheme) }
    }

    val prefs = AppPrefs.getInstance().registerProvider(::ThemePrefs)

    fun saveTheme(theme: Theme.Custom) {
        // 同名主题可能换了背景图，缓存中的旧图必须失效
        theme.backgroundImage?.croppedFilePath?.let { ThemeBackgroundCache.invalidate(it) }
        theme.backgroundImage?.srcFilePath?.let { ThemeBackgroundCache.invalidate(it) }
        ThemeFilesManager.saveThemeFiles(theme)
        customThemes.indexOfFirst { it.name == theme.name }.also {
            if (it >= 0) customThemes[it] = theme else customThemes.add(0, theme)
        }
        if (activeTheme.name == theme.name) {
            activeTheme = theme
        }
    }

    fun deleteTheme(name: String) {
        customThemes.find { it.name == name }?.also {
            it.backgroundImage?.croppedFilePath?.let { path -> ThemeBackgroundCache.invalidate(path) }
            it.backgroundImage?.srcFilePath?.let { path -> ThemeBackgroundCache.invalidate(path) }
            ThemeFilesManager.deleteThemeFiles(it)
            customThemes.remove(it)
        }
        if (activeTheme.name == name) {
            activeTheme = evaluateActiveTheme()
        }
    }

    fun setNormalModeTheme(theme: Theme) {
        // `normalModeTheme.setValue(theme)` would trigger `onThemePrefsChange` listener,
        // which calls `fireChange()`.
        // `activateTheme`'s setter would also trigger `fireChange()` when theme actually changes.
        // write to backing property directly to avoid unnecessary `fireChange()`
        _activeTheme = theme
        prefs.manualNightMode = theme.isDark
        prefs.normalModeTheme.setValue(theme)
    }

    private fun evaluateActiveTheme(): Theme {
        return if (prefs.followSystemDayNightTheme.getValue()) {
            if (isNightMode) prefs.darkModeTheme else prefs.lightModeTheme
        } else {
            prefs.normalModeTheme
        }.getValue()
    }

    @Keep
    private val onThemePrefsChange = ManagedPreference.OnChangeListener<Any> { key, _ ->
        if (prefs.dayNightModePrefNames.contains(key)) {
            nightOverride = null
            _activeTheme = evaluateActiveTheme()
            fireChange()
        } else {
            fireChange()
        }
    }

    fun init(configuration: Configuration) {
        isDarkMode = configuration.isDarkMode()
        nightOverride = null
        // fire all `OnThemeChangedListener`s on theme preferences change
        prefs.managedPreferences.values.forEach {
            it.registerOnChangeListener(onThemePrefsChange)
        }
        _activeTheme = evaluateActiveTheme()
    }

    fun onSystemDarkModeChange(isDark: Boolean) {
        val changed = isDarkMode != isDark
        isDarkMode = isDark
        if (changed) {
            nightOverride = null
            _activeTheme = evaluateActiveTheme()
            fireChange()
        }
    }

}
