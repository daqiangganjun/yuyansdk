package com.yuyan.imemodule.libs.pinyin4j

import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinCaseType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinOutputFormat
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinToneType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinVCharType

/**
 * 汉字读音反查，用于手写识别结果的注音显示。
 *
 * 不直接使用 pinyin4j 的多音输出：它收录的是字书级别的全部异读，含古音与罕用音，
 * 例如「吃」会给出 chī 与古音 qī，反而让人怀疑识别有误。此处改以《现代常用多音字表》
 * 为准，表内字取其全部现代读音，表外字（即单音字）回落到 pinyin4j 的首选读音。
 */
object PolyphoneReading {

    private const val ASSET_PATH = "pinyindb/polyphone.txt"

    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITH_TONE_MARK
        vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }

    /** 首次注音时才加载，未使用手写的会话不必为此付出解析开销 */
    private val table: Map<Char, String> by lazy {
        runCatching {
            buildMap {
                Launcher.instance.context.assets.open(ASSET_PATH).bufferedReader().forEachLine { line ->
                    if (line.isEmpty() || line[0] == '#') return@forEachLine
                    val sep = line.indexOf('\t')
                    if (sep != 1) return@forEachLine
                    put(line[0], line.substring(sep + 1))
                }
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * 取一段文本的读音标注。
     *
     * 单字若为常用多音字，列出其全部现代读音（逗号分隔），如「哈」→「hā,hǎ,hà」；
     * 其余情形每字只取一个读音，多字之间以空格分隔。
     */
    fun of(text: String): String {
        if (text.isEmpty()) return ""
        if (text.length == 1) {
            table[text[0]]?.let { return it }
        }
        return PinyinHelper.toHanYuPinyin(text, format, " ")
    }
}
