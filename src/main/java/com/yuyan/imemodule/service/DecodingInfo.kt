package com.yuyan.imemodule.service

import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong
import androidx.lifecycle.MutableLiveData
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Kernel

/**
 * 词库解码操作对象
 */
object DecodingInfo {
    private val candidateRevision = AtomicLong()
    private val main = Handler(Looper.getMainLooper())

    // 翻页累积的候选词上限，超出部分对用户已无意义，只会长期占用内存
    private const val MAX_CACHED_CANDIDATES = 2000

    var activeCandidate = 0  //当前显示候选词位置
    var activeCandidateBar = 0  //当前显示候选词位置
    // 候选词列表
    val candidatesLiveData = MutableLiveData<List<CandidateListItem>>()
    // 是否是联想词
    var isAssociate = false

    /**
     * 重置
     */
    fun reset() {
        candidateRevision.incrementAndGet()
        isAssociate = false
        activeCandidate = 0
        activeCandidateBar = 0
        candidatesLiveData.value = emptyList()
        Kernel.reset()
    }

    val isCandidatesEmpty: Boolean
        // 候选词列表是否为空
        get() = candidatesLiveData.value.isNullOrEmpty()

    val candidateSize: Int
        // 候选词列表是否为空
        get() = if(isCandidatesEmpty) 0 else candidatesLiveData.value!!.size


    val candidates: List<CandidateListItem>
        // 候选词列表是否为空
        get() = candidatesLiveData.value?:emptyList()

    // 增加拼写字符
    fun inputAction(event: KeyEvent) {
        candidateRevision.incrementAndGet()
        activeCandidate = 0
        activeCandidateBar = 0
        Kernel.inputKeyCode(event)
        isAssociate = false
    }

    /**
     * 选择拼音
     * @param position 选择的position
     */
    fun selectPrefix(position: Int) {
        candidateRevision.incrementAndGet()
        activeCandidate = 0
        activeCandidateBar = 0
        Kernel.selectPrefix(position)
    }

    val prefixs: Array<String>  //获取拼音组合
        get() = Kernel.prefixs

    /**
     * 删除
     */
    fun deleteAction() {
        candidateRevision.incrementAndGet()
        activeCandidate = 0
        activeCandidateBar = 0
        if(!isEngineFinish)Kernel.deleteAction()
        else reset()
    }


    val isEngineFinish: Boolean
        get() = Kernel.isFinish

    val composingStrForDisplay: String   //获取显示的拼音字符串/
        get() = Kernel.wordsShowPinyin

    val caretInComposition: Int   // 拼音串中插入点的字符下标，-1 表示在末尾
        get() = Kernel.caretInComposition

    /**
     * 把插入点移到拼音串的指定位置，供点击拼音气泡进入编辑用。
     *
     * 引擎侧不受影响，候选仍覆盖完整编码，故无须重查——只有气泡上的插入点标记需要刷新。
     * @return 位置是否发生变化
     */
    fun moveCaretTo(position: Int): Boolean {
        if (isAssociate || isCandidatesEmpty) return false
        return Kernel.moveCaretTo(position)
    }

    val composingStrForCommit: String   // 获取输入的拼音字符串
        get() = Kernel.wordsShowPinyin.replace("'", "").ifEmpty { getCandidate(0)?.text?:""}

    val nextPageCandidates: Int   // 获取下一页的候选词
        get() {
            val revision = candidateRevision.get()
            val current = candidatesLiveData.value
            val cands = Kernel.nextPageCandidates
            if (candidateRevision.get() != revision) return 0
            if (cands.isNotEmpty()) {
                // plus 每翻一页都复制整个列表再追加，翻到底会累积成 O(n²) 的分配；
                // 且累积量无上限，就地追加并限制总量
                val merged = ArrayList<CandidateListItem>((current?.size ?: 0) + cands.size)
                if (current != null) merged.addAll(current)
                merged.addAll(cands)
                main.post {
                    if (candidateRevision.get() == revision) {
                        candidatesLiveData.value = if (merged.size > MAX_CACHED_CANDIDATES)
                            merged.subList(0, MAX_CACHED_CANDIDATES).toList() else merged
                    }
                }
                return cands.size
            }
            return 0
        }

    /**
     * 选择一个候选词，且重新获取候选词列表
     */
    fun chooseDecodingCandidate(candId: Int): String {
        candidateRevision.incrementAndGet()
        activeCandidate = 0
        activeCandidateBar = 0
        var candidate: String
        if(!isEngineFinish || isAssociate) { // Rime和联想
            if (candId >= 0) Kernel.getWordSelectedWord(candId)
            val newCandidates = Kernel.candidates
            candidate = if (newCandidates.isNotEmpty()) Kernel.commitText
            else if (candId in 0..<candidateSize) Kernel.commitText.ifEmpty { candidatesLiveData.value!![candId].text }
            else ""
            candidatesLiveData.value = newCandidates
        } else {  // 手写
            candidate = if (candId in 0..<candidateSize) candidatesLiveData.value!![candId].text  else ""
            reset()
        }
        return candidate
    }

    /**
     * 对输入的拼音进行查询。
     */
    fun updateDecodingCandidate() {
        activeCandidate = 0
        activeCandidateBar = 0
        candidatesLiveData.value = Kernel.candidates
    }

    /**
     * 获得指定的候选词
     */
    fun getCandidate(candId: Int): CandidateListItem? {
        return candidatesLiveData.value?.getOrNull(candId)
    }

    // 更新候选词
    fun cacheCandidates(words: Array<CandidateListItem>, associate: Boolean = false) {
        candidateRevision.incrementAndGet()
        isAssociate = associate
        activeCandidate = 0
        activeCandidateBar = 0
        candidatesLiveData.value = words.asList()
    }

    /**
     * 根据输入的字符查询候选词
     */
    fun getAssociateWord(words: String) {
        candidateRevision.incrementAndGet()
        isAssociate = true
        Kernel.getAssociateWord(words)
    }
}
