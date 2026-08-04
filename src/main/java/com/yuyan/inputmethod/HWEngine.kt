package com.yuyan.inputmethod

import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.callback.IHandWritingCallBack
import com.yuyan.imemodule.utils.AssetUtils
import com.yuyan.imemodule.utils.thread.ThreadPoolUtils
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

    /** 是否已尝试过修复性复制词库，仅执行一次 */
    private var dictRepairAttempted = false

    init {
        mHanyuPinyinOutputFormat = HanyuPinyinOutputFormat()
        mHanyuPinyinOutputFormat.caseType = HanyuPinyinCaseType.LOWERCASE
        mHanyuPinyinOutputFormat.toneType = HanyuPinyinToneType.WITH_TONE_MARK
        mHanyuPinyinOutputFormat.vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }

    /**
     * 确保引擎已初始化。
     *
     * 原实现把初始化放在 object 的 init 块中——Kotlin object 的 init 只在首次访问时
     * 执行一次，一旦当时词库尚未复制完成（首次安装场景）导致 initWithDirectory 返回
     * false，此后便再无重试机会，识别会一直调用未就绪的 native。
     *
     * init 的返回值仅用于决定是否需要重试，不作为阻断识别的条件：其具体语义由预编译
     * 的 .so 决定，返回 false 未必代表引擎完全不可用，据此提前返回会让手写彻底失效。
     */
    private fun ensureEngineReady() {
        if (engineReady) return
        val initialized = HandWriting.init(Launcher.instance.context)
        HandWriting.setProperties()
        HandWriting.selectInputMode(5)
        // 失败时不缓存，留待下次调用重试
        engineReady = initialized
        if (!initialized) repairDictOnce()
    }

    /**
     * 初始化失败通常意味着手写词库缺失或不完整。词库仅在 dataDictVersion 落后时才会
     * 复制，覆盖升级不会触发，缺失会一直保留，故在此补一次修复性复制（约 8.5MB）。
     * 只执行一次，避免每次识别失败都反复复制。
     */
    private fun repairDictOnce() {
        if (dictRepairAttempted) return
        dictRepairAttempted = true
        ThreadPoolUtils.executeSingleton {
            AssetUtils.copyFileOrDir(Launcher.instance.context, "hw", "", CustomConstant.HW_DICT_PATH, true)
        }
    }

    fun recognitionData(strokes: MutableList<Short?>, recogResult: IHandWritingCallBack){
        ensureEngineReady()
        HandWriting.reset()
        val strokesData = strokes.toMutableList()
        val intArray = strokesData.filterNotNull().map { it.toInt() }.toIntArray()
        // 返回值语义未知，与原实现一致不作判断，仅对识别结果做空防护
        HandWriting.inputHWPoints(intArray)
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