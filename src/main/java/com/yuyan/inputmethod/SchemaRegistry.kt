package com.yuyan.inputmethod

/** 兼容已有设置中的方案标识，键盘布局与具体词库相互独立。 */
object SchemaRegistry {
    val chineseSchemas = setOf(
        "pinyin", "t9_pinyin", "double_pinyin_ls17", "double_pinyin_flypy",
        "double_pinyin_natural", "double_pinyin_abc", "double_pinyin_mspy",
        "double_pinyin_sogou", "double_pinyin_ziguang"
    )

    fun physical(logical: String, family: String): String = when (logical) {
        "english" -> "selfopt_english"
        "stroke" -> "selfopt_stroke"
        "handwriting" -> "selfopt_${family}_pinyin"
        in chineseSchemas -> "selfopt_${family}_$logical"
        else -> "selfopt_${family}_pinyin"
    }

    fun normalize(logical: String): String =
        if (logical in chineseSchemas || logical in setOf("english", "stroke", "handwriting")) logical else "pinyin"
}
