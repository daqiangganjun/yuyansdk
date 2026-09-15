package com.yuyan.inputmethod.util

import com.yuyan.imemodule.prefs.AppPrefs

/**
 * 模糊音规则。
 *
 * 模糊音写入各方案的 speller/algebra，在编码映射之前生成等价拼写。
 */
object FuzzyPinYinUtils {

    /** [prefix] 为真表示按声母（前缀）替换，否则按韵母（后缀）替换 */
    private data class Rule(val from: String, val to: String, val prefix: Boolean)

    /** 内置规则与其设置项键名，顺序即候选补充时的尝试顺序 */
    private val PRESET: List<Pair<String, Rule>> = listOf(
        "fuzzy_pinyin_zh_z" to Rule("zh", "z", true),
        "fuzzy_pinyin_ch_c" to Rule("ch", "c", true),
        "fuzzy_pinyin_sh_s" to Rule("sh", "s", true),
        "fuzzy_pinyin_n_l" to Rule("n", "l", true),
        "fuzzy_pinyin_r_l" to Rule("r", "l", true),
        "fuzzy_pinyin_f_h" to Rule("f", "h", true),
        "fuzzy_pinyin_k_g" to Rule("k", "g", true),
        "fuzzy_pinyin_ang_an" to Rule("ang", "an", false),
        "fuzzy_pinyin_eng_en" to Rule("eng", "en", false),
        "fuzzy_pinyin_ing_in" to Rule("ing", "in", false),
        "fuzzy_pinyin_iang_ian" to Rule("iang", "ian", false),
        "fuzzy_pinyin_uang_uan" to Rule("uang", "uan", false),
    )

    private val INITIALS = setOf(
        "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
        "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s", "y", "w"
    )

    val isEnabled: Boolean
        get() = AppPrefs.getInstance().fuzzyPinyin.fuzzyPinyinEnable.getValue()

    fun algebraRules(): List<String> {
        if (!isEnabled) return emptyList()
        return activeRules().flatMap { rule ->
            listOf(rule.from to rule.to, rule.to to rule.from).map { (from, to) ->
                // z→zh 等规则只匹配声母后的元音，避免把已有 zh 再展开成 zhh。
                if (rule.prefix) "derive/^$from(?=[aeiouv])/$to/"
                else "derive/$from\$/$to/"
            }
        }.distinct()
    }

    private fun activeRules(): List<Rule> {
        val prefs = AppPrefs.getInstance().fuzzyPinyin
        val rules = ArrayList<Rule>(PRESET.size + 4)
        PRESET.forEach { (key, rule) ->
            if (prefs.fuzzyPinyinSwitches[key]?.getValue() == true) rules.add(rule)
        }
        parseCustom(prefs.fuzzyPinyinCustom.getValue(), rules)
        return rules
    }

    /** 自定义规则形如 `zh=z,ang=an`，两侧只接受小写字母 */
    private fun parseCustom(raw: String, into: MutableList<Rule>) {
        if (raw.isBlank()) return
        raw.split(',', '，', ';', '；', '\n').forEach { item ->
            val parts = item.split('=', '＝')
            if (parts.size != 2) return@forEach
            val from = parts[0].trim().lowercase()
            val to = parts[1].trim().lowercase()
            if (from.isEmpty() || to.isEmpty() || from == to) return@forEach
            if (!from.all { it in 'a'..'z' } || !to.all { it in 'a'..'z' }) return@forEach
            val rule = Rule(from, to, INITIALS.contains(from) || INITIALS.contains(to))
            if (into.none { it.from == rule.from && it.to == rule.to }) into.add(rule)
        }
    }

}
