package com.yuyan.inputmethod

import com.google.mlkit.vision.digitalink.Ink
import com.yuyan.imemodule.callback.IHandWritingCallBack
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinCaseType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinOutputFormat
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinToneType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinVCharType
import com.yuyan.inputmethod.core.CandidateListItem

/**
 * 基于 ML Kit 数字墨水的手写识别。
 *
 * 取代原先的搜狗商业库：后者的授权仅绑定原包名 com.yuyan.pinyin.*，本分支更名后
 * license 校验失败，引擎无法初始化。ML Kit 识别过程完全离线，但模型需先行下载，
 * 见 [MlKitHandwritingModel]。
 */
object MlKitHandwritingEngine {

    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITH_TONE_MARK
        vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }

    /**
     * 识别整段笔迹。模型未就绪时静默返回，由键盘层负责提示。
     */
    fun recognize(ink: Ink, callback: IHandWritingCallBack) {
        val recognizer = MlKitHandwritingModel.recognizerOrNull() ?: return
        recognizer.recognize(ink)
            .addOnSuccessListener { result ->
                val items = result.candidates.mapNotNull { candidate ->
                    val text = candidate.text
                    if (text.isNullOrEmpty()) null
                    else CandidateListItem(
                        PinyinHelper.toHanYuPinyin(text, pinyinFormat, "'").ifEmpty { text },
                        text
                    )
                }
                if (items.isNotEmpty()) callback.onSucess(items.toTypedArray())
            }
    }
}
