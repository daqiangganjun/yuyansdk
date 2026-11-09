package com.yuyan.inputmethod.dictionary

import android.content.Context
import com.yuyan.imemodule.data.transfer.SyncProtocol
import com.yuyan.imemodule.data.transfer.TransferFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class DictionaryRule(
    val id: String,
    val name: String,
    val family: String,
    val repository: String,
    val ref: String = "HEAD",
    val dictionary: String,
    val schemaPrefix: String = "",
    val updatedAt: Long = 1,
)

@Serializable
data class RuleSettings(
    val mode: String = "builtin",
    val networkUrl: String = "",
    val packageIndex: String = "",
    val selected: Map<String, String> = emptyMap(),
    val updatedAt: Long = 1,
)

@Serializable
data class RuleDocument(val format: Int = 1, val rules: List<DictionaryRule>)

@Serializable
private data class RuleState(val rules: List<DictionaryRule> = emptyList(), val settings: RuleSettings = RuleSettings())

object DictionaryRules {
    private val json get() = SyncProtocol.json
    val builtins = listOf(
        DictionaryRule("builtin_frost", "白霜官方源", "frost", "gaboolic/rime-frost", dictionary = "rime_frost", schemaPrefix = "rime_frost_"),
        DictionaryRule("builtin_ice", "雾凇官方源", "ice", "iDvel/rime-ice", dictionary = "rime_ice"),
    )
    private fun file(context: Context) = File(context.filesDir, "dictionary-rules.json")
    private fun state(context: Context): RuleState = file(context).takeIf(TransferFiles::exists)?.let {
        runCatching { json.decodeFromString<RuleState>(TransferFiles.read(it)) }.getOrNull()
    } ?: RuleState(settings = RuleSettings(mode = if (com.yuyan.inputmethod.DictionaryStore.configuredIndex(context).isBlank()) "builtin" else "package"))

    @Synchronized fun all(context: Context) = (builtins.associateBy { it.id } + state(context).rules.associateBy { it.id }).values.toList()
    @Synchronized fun settings(context: Context) = state(context).settings.copy(packageIndex = com.yuyan.inputmethod.DictionaryStore.configuredIndex(context))
    @Synchronized fun configurationHash(context: Context): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(settings(context).copy(updatedAt = 0)).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    @Synchronized fun configure(context: Context, settings: RuleSettings) {
        validateSettings(settings)
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().putString("transfer_dictionary_index", settings.packageIndex).apply()
        TransferFiles.write(file(context), json.encodeToString(state(context).copy(settings = settings.copy(updatedAt = System.currentTimeMillis()))))
    }

    fun validate(rule: DictionaryRule) {
        require(rule.id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && rule.name.length in 1..80) { "Invalid dictionary rule identity" }
        require(rule.family in setOf("frost", "ice")) { "Unsupported dictionary family" }
        require(rule.repository.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9-]{0,38}/[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}"))) { "Invalid GitHub repository" }
        require(rule.ref.length in 1..160 && rule.ref.none { it.isISOControl() }) { "Invalid source revision" }
        require(rule.dictionary.matches(Regex("[a-zA-Z0-9_-]+")) && rule.schemaPrefix.matches(Regex("[a-zA-Z0-9_-]*"))) { "Invalid dictionary source path" }
        require(rule.updatedAt >= 0) { "Invalid rule timestamp" }
    }

    private fun validateSettings(settings: RuleSettings) {
        require(settings.mode in setOf("builtin", "custom", "network", "package")) { "Invalid dictionary source mode" }
        require(settings.networkUrl.isBlank() || settings.networkUrl.startsWith("https://")) { "Rule source requires HTTPS" }
        require(settings.packageIndex.isBlank() || settings.packageIndex.startsWith("https://")) { "Dictionary index requires HTTPS" }
        require(settings.selected.keys.all { it in setOf("frost", "ice") } && settings.selected.values.all { it.matches(Regex("[a-zA-Z0-9_-]{1,64}")) }) { "Invalid selected dictionary rule" }
    }

    fun parse(text: String): List<DictionaryRule> {
        require(text.length <= 1024 * 1024) { "Rule document is too large" }
        val rules = if (org.json.JSONObject(text).has("rules")) {
            json.decodeFromString<RuleDocument>(text).also { require(it.format == 1) { "Unsupported rule format" } }.rules
        } else listOf(json.decodeFromString<DictionaryRule>(text))
        require(rules.size in 1..64 && rules.map { it.id }.distinct().size == rules.size) { "Invalid or duplicate rules" }
        rules.forEach(::validate)
        return rules
    }

    @Synchronized fun import(context: Context, text: String, select: Boolean = true) {
        val incoming = parse(text)
        val old = state(context)
        val existing = all(context).associateBy { it.id }
        val changed = incoming.map { rule ->
            if (existing[rule.id]?.copy(updatedAt = rule.updatedAt) == rule) existing.getValue(rule.id)
            else rule.copy(updatedAt = System.currentTimeMillis())
        }
        val selected = if (select) old.settings.selected + incoming.groupBy { it.family }.mapValues { (family, options) ->
            old.settings.selected[family]?.takeIf { id -> options.any { it.id == id } } ?: options.first().id
        } else old.settings.selected
        TransferFiles.write(file(context), json.encodeToString(old.copy(
            rules = (old.rules.associateBy { it.id } + changed.associateBy { it.id }).values.toList(),
            settings = old.settings.copy(selected = selected, updatedAt = if (selected == old.settings.selected) old.settings.updatedAt else System.currentTimeMillis()),
        )))
    }

    fun selected(context: Context, family: String): DictionaryRule {
        val config = settings(context)
        if (config.mode == "builtin") return builtins.single { it.family == family }
        val id = config.selected[family] ?: "builtin_$family"
        return all(context).singleOrNull { it.id == id && it.family == family } ?: error("Selected dictionary rule is missing")
    }

    fun export(context: Context) = org.json.JSONObject(json.encodeToString(RuleDocument(rules = all(context)))).toString(2)
    @Synchronized fun reset(context: Context) {
        val time = System.currentTimeMillis()
        TransferFiles.write(file(context), json.encodeToString(RuleState(rules = builtins.map { it.copy(updatedAt = time) }, settings = RuleSettings(updatedAt = time))))
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().putString("transfer_dictionary_index", "").apply()
    }
    @Synchronized fun records(context: Context): Map<String, String> = all(context).associate { "rule/${it.id}" to json.encodeToString(it) } +
        ("rule/@settings" to json.encodeToString(settings(context)))
    fun validateRecord(key: String, value: String) {
        if (key == "rule/@settings") validateSettings(json.decodeFromString(value))
        else json.decodeFromString<DictionaryRule>(value).also { validate(it); require(key == "rule/${it.id}") { "Rule identity mismatch" } }
    }
    @Synchronized fun restore(context: Context, records: Map<String, String>) {
        val values = records.filterKeys { it.startsWith("rule/") }
        if (values.isEmpty()) return
        values.forEach { (key, value) -> validateRecord(key, value) }
        val config = values["rule/@settings"]?.let { json.decodeFromString<RuleSettings>(it) } ?: RuleSettings()
        val rules = values.filterKeys { it != "rule/@settings" }.values.map { json.decodeFromString<DictionaryRule>(it) }
        require(config.selected.all { (family, id) -> (rules + builtins).any { it.family == family && it.id == id } }) { "Selected dictionary rule is missing" }
        TransferFiles.write(file(context), json.encodeToString(RuleState(rules, config)))
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().putString("transfer_dictionary_index", config.packageIndex).apply()
    }
}
