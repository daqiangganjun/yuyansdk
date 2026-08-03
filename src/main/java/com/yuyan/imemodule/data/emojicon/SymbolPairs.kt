package com.yuyan.imemodule.data.emojicon

/**
 * 成对符号表。
 *
 * 该表在每次输入符号时都要查询，原先与 emoji、颜文字等大表同属 EmojiconData，
 * 而 Kotlin object 的成员在首次访问任意成员时全部初始化——即便用户从不打开表情
 * 面板，敲一次符号也会把近四千个字符串以及逐个 emoji 的字体可用性检测全部拉起。
 * 故单独成类，与大表解耦。
 */
object SymbolPairs {
    val SymbolPreset: Map<String, String> = hashMapOf(
        "(" to ")", "[" to "]", "{" to "}", "（" to "）", "［" to "］", "｛" to "｝", "❨" to "❩", "❲" to "❳", "❴" to "❵", "‘" to "’", "“" to "”", "❛" to "❜", "❝" to "❞", "<" to ">", "〈" to "〉", "《" to "》", "〔" to "〕", "【" to "】", "〘" to "〙", "「" to "」", "『" to "』", "︵" to "︶", "︷" to "︸", "︹" to "︺", "︻" to "︼", "︽" to "︾", "︿" to "﹀", "﹁" to "﹂",)
}
