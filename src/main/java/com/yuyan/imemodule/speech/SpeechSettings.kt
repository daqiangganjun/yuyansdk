package com.yuyan.imemodule.speech

import android.content.Context
import org.json.JSONObject

class SpeechSettings(context: Context) {
    val preferences = context.getSharedPreferences("speech_settings", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }
    var providerId: String
        get() = preferences.getString("provider", "doubao") ?: "doubao"
        set(value) { preferences.edit().putString("provider", value).apply() }

    fun key(provider: SpeechProvider, option: SpeechOption) = "${provider.id}.${option.key}"

    fun options(provider: SpeechProvider): JSONObject = JSONObject().apply {
        provider.options.forEach { option ->
            val prefKey = key(provider, option)
            val value = when (option.type) {
                SpeechOptionType.SWITCH -> preferences.getBoolean(prefKey, option.defaultValue as Boolean)
                else -> preferences.getString(prefKey, option.defaultValue.toString()) ?: option.defaultValue.toString()
            }
            when (option.type) {
                SpeechOptionType.INTEGER -> {
                    if (value.toString().isBlank()) return@forEach
                    val number = value.toString().toIntOrNull()
                    require(number != null && (option.range == null || number in option.range)) {
                        "Invalid integer option: ${option.key}"
                    }
                    put(option.key, number)
                }
                SpeechOptionType.JSON_OBJECT -> if (value.toString().isNotBlank()) put(option.key, JSONObject(value.toString()))
                SpeechOptionType.JSON_STRING -> if (value.toString().isNotBlank()) {
                    put(option.key, JSONObject(value.toString()).toString())
                }
                SpeechOptionType.CHOICE -> {
                    require(value.toString() in option.choices) { "Invalid choice: ${option.key}" }
                    put(option.key, value)
                }
                else -> put(option.key, value)
            }
        }
        provider.validate(this)
    }
}
