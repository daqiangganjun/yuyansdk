package com.yuyan.inputmethod

import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.callback.IHandWritingCallBack
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinCaseType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinOutputFormat
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinToneType
import com.yuyan.imemodule.libs.pinyin4j.format.HanyuPinyinVCharType
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.HandWriting

object HWEngine {
    private var mHanyuPinyinOutputFormat: HanyuPinyinOutputFormat

    /** 手写引擎是否已就绪。词库尚未复制完成时初始化会失败，需要在后续调用中重试。 */
    private var engineReady = false

    init {
        mHanyuPinyinOutputFormat = HanyuPinyinOutputFormat()
        mHanyuPinyinOutputFormat.caseType = HanyuPinyinCaseType.LOWERCASE
        mHanyuPinyinOutputFormat.toneType = HanyuPinyinToneType.WITH_TONE_MARK
        mHanyuPinyinOutputFormat.vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }

    /**
     * 确保引擎已初始化。
     *
     * 原实现把初始化放在 object 的 init 块中且不检查返回值——Kotlin object 的 init
     * 只在首次访问时执行一次，一旦当时词库尚未复制完成（首次安装场景）导致
     * initWithDirectory 返回 false，此后便再无重试机会，而识别照常调用未就绪的
     * native，getCandidates() 返回 null，每次抬笔都会崩溃且持续到进程重启。
     */
    private fun ensureEngineReady(): Boolean {
        if (engineReady) return true
        if (!HandWriting.init(Launcher.instance.context)) return false
        HandWriting.setProperties()
        HandWriting.selectInputMode(5)
        engineReady = true
        return true
    }

    fun recognitionData(strokes: MutableList<Short?>, recogResult: IHandWritingCallBack){
        if (!ensureEngineReady()) return
        HandWriting.reset()
        val strokesData = strokes.toMutableList()
        val intArray = strokesData.filterNotNull().map { it.toInt() }.toIntArray()
        if (!HandWriting.inputHWPoints(intArray)) return
        val candidates = HandWriting.getCandidatesPyComposition()?.firstOrNull()
        if (candidates.isNullOrEmpty()) return
        val recogResultItems = ArrayList<CandidateListItem>(candidates.size)
        for (candidate in candidates) {
            // native 返回的数组可能含 null 元素，逐个跳过而非整体崩溃
            if (candidate.isNullOrEmpty()) continue
            recogResultItems.add(
                CandidateListItem(
                    PinyinHelper.toHanYuPinyin(candidate, mHanyuPinyinOutputFormat, "'").ifEmpty { candidate }, candidate
                )
            )
        }
        if (recogResultItems.isNotEmpty()) recogResult.onSucess(recogResultItems.toTypedArray())
    }
}