package com.yuyan.inputmethod.util

import com.yuyan.imemodule.prefs.AppPrefs

/**
 * 模糊音规则。
 *
 * Rime 的模糊音靠方案的 speller/algebra 在编译 prism 时生成等价拼写，随包分发的是编译产物，
 * 设备上没有方案与词典源文件，无法重建。这里改在输入串层面处理：按规则改写出等价拼音串，
 * 由 [com.yuyan.inputmethod.RimeEngine] 另查一遍并入候选栏。
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
        get() = AppPrefs.getInstance().input.fuzzyPinyinEnable.getValue()

    private fun activeRules(): List<Rule> {
        val input = AppPrefs.getInstance().input
        val rules = ArrayList<Rule>(PRESET.size + 4)
        PRESET.forEach { (key, rule) ->
            if (input.fuzzyPinyinSwitches[key]?.getValue() == true) rules.add(rule)
        }
        parseCustom(input.fuzzyPinyinCustom.getValue(), rules)
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

    /**
     * 按模糊规则改写音节序列，得到若干等价输入串（以分词符连接）。
     *
     * 先逐个改写单个音节（由后往前，最近输入的音节优先），再给出整串一并改写的版本；
     * 组合数会随音节数指数增长，故以 [limit] 截断——每个变体都要重放一遍按键，代价不低。
     */
    fun variantsOf(syllables: List<String>, limit: Int): List<String> {
        if (syllables.isEmpty() || limit <= 0) return emptyList()
        val rules = activeRules()
        if (rules.isEmpty()) return emptyList()
        val original = syllables.joinToString("'")
        val result = LinkedHashSet<String>()
        for (i in syllables.indices.reversed()) {
            for (alt in alternatives(syllables[i], rules)) {
                val copy = syllables.toMutableList()
                copy[i] = alt
                result.add(copy.joinToString("'"))
                if (result.size >= limit + 1) break
            }
            if (result.size >= limit + 1) break
        }
        if (syllables.size > 1 && result.size < limit + 1) {
            result.add(syllables.joinToString("'") { alternatives(it, rules).firstOrNull() ?: it })
        }
        result.remove(original)
        return result.take(limit)
    }

    /** 单个音节按规则可改写成的其它形式，两个方向都试 */
    private fun alternatives(syllable: String, rules: List<Rule>): List<String> {
        val out = LinkedHashSet<String>(4)
        for (rule in rules) {
            if (rule.prefix) {
                if (syllable.startsWith(rule.from)) out.add(rule.to + syllable.substring(rule.from.length))
                else if (syllable.startsWith(rule.to)) out.add(rule.from + syllable.substring(rule.to.length))
            } else {
                if (syllable.endsWith(rule.from)) out.add(syllable.dropLast(rule.from.length) + rule.to)
                else if (syllable.endsWith(rule.to)) out.add(syllable.dropLast(rule.to.length) + rule.from)
            }
        }
        out.remove(syllable)
        return out.toList()
    }
}
