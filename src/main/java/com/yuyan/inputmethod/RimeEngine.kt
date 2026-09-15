package com.yuyan.inputmethod

import android.view.KeyEvent
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Rime
import com.yuyan.inputmethod.data.InputKey
import com.yuyan.inputmethod.data.KeyRecordStack
import com.yuyan.inputmethod.util.DoublePinYinUtils
import com.yuyan.inputmethod.util.LX17PinYinUtils
import com.yuyan.inputmethod.util.QwertyPinYinUtils
import com.yuyan.inputmethod.util.T9PinYinUtils
import java.util.Locale

object RimeEngine {
    private val keyRecordStack = KeyRecordStack()
    private var pinyins: Array<String> = emptyArray() // 候选词界面的候选拼音列表
    var showCandidates: List<CandidateListItem> = emptyList() // 所有待展示的候选词
    var showComposition: String = "" // 候选词上方展示的拼音
    var preCommitText: String = "" // 待提交的文字
    private var customPhraseSize: Int = 0 // 自定义引擎候选词长度
    const val MASK_CASE_LOWER = 0
    private var charCase = 0x0000

    /**
     * 展示的候选词与引擎候选之间的映射。
     *
     * 候选栏并非引擎候选的原样呈现：前面会插入自定义短语，中间会滤掉与编码相同的字母候选，
     * 选词必须换算回引擎侧的下标，
     */
    private val rimeIndexMap = ArrayList<Int>(64)
    private var rimeCandidateCount = 0

    /**
     * 用户实际按下的键序列，供拼音编辑重放使用。
     *
     * 引擎的 preedit 会插入自动分词符，与按键序列并不等长；选词、选拼音之后引擎
     * 会消耗掉一部分输入，此时按键序列不再可靠，[rawInputValid] 置否直到本轮输入重置。
     */
    private val rawInput = StringBuilder()
    private var rawInputValid = true

    /**
     * 拼音编辑的插入点在按键序列中的位置；-1 表示不在编辑状态，插入点即串尾。
     *
     * 引擎自身的插入点始终留在串尾，编辑只改按键序列再整串同步过去，候选因而一直
     * 覆盖完整编码——引擎只翻译其插入点之前的部分，把它挪到中间候选就只剩前半段了。
     */
    private var editCaret = -1

    @Synchronized
    fun init() {
        Rime.getInstance(false)
    }

    @Synchronized
    fun selectSchema(mod: String): Boolean {
        reset()
        charCase = MASK_CASE_LOWER
        Rime.startup(Launcher.instance.context, false)
        return Rime.selectSchema(mod)
    }

    @Synchronized
    fun getCurrentRimeSchema(): String {
        return Rime.getCurrentRimeSchema()
    }

    /**
     * 是否输入完毕
     */
    @Synchronized
    fun isFinish(): Boolean {
        return keyRecordStack.isEmpty()
    }

    @Synchronized
    fun onNormalKey(event: KeyEvent) {
        if (!EngineRuntime.isReady) return
        val keyCode = event.keyCode
        val keyChar = if(keyCode == KeyEvent.KEYCODE_APOSTROPHE) if(isFinish()) '/'.code else '\''.code
            else event.unicodeChar
        // 编辑拼音时按键插在插入点处，分词由引擎按默认规则重算；分词符同样可插，
        // 与常规输入时的手动分词等效
        if (editCaret >= 0 && keyChar in 1..0x7F) {
            insertAtEditCaret(keyChar.toChar())
            updateCandidatesOrCommitText()
            return
        }
        if (keyRecordStack.pushKey(event)) {
            Rime.processKey(keyChar, 0)
            if (rawInputValid && keyChar in 1..0x7F) rawInput.append(keyChar.toChar())
        }
        updateCandidatesOrCommitText()
    }

    @Synchronized
    fun onDeleteKey() {
        if (editCaret >= 0) {
            if (!deleteAtEditCaret()) return
            updateCandidatesOrCommitText()
            return
        }
        processDelAction()
        if (rawInputValid && rawInput.isNotEmpty()) rawInput.deleteCharAt(rawInput.length - 1)
        updateCandidatesOrCommitText()
    }

    @Synchronized
    fun selectCandidate(index: Int): String? {
        editCaret = -1
        val listIndex = index - customPhraseSize
        Rime.selectCandidate(rimeIndexMap.getOrElse(listIndex) { listIndex })
        rawInputValid = false
        keyRecordStack.pushCandidateSelectAction()
        return updateCandidatesOrCommitText()
    }

    @Synchronized
    fun getNextPageCandidates(): Array<CandidateListItem> {
        return if (EngineRuntime.isReady && Rime.hasRight()) {
            Rime.processKey(getRimeKeycodeByName("Page_Down"), 0)
           val candidates = Rime.getRimeContext()?.candidates ?: emptyArray()
            // 翻页所得接在已展示候选之后，映射同步延长，否则选中后一页的词会取到错误下标
            candidates.indices.forEach {
                rimeIndexMap.add(rimeCandidateCount + it)
            }
            rimeCandidateCount += candidates.size
            when (charCase) {
                KeyEvent.META_SHIFT_ON -> {
                    for (item in candidates) {
                        if (InputModeSwitcher.isEnglish) item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    }
                }
                KeyEvent.META_CAPS_LOCK_ON -> {
                    for (item in candidates) {
                        item.text = item.text.uppercase()
                    }
                }
                else -> {
                    for (item in candidates) {
                        if (InputModeSwitcher.isEnglish) item.text = item.text.lowercase()
                    }
                }
            }
            candidates
        } else emptyArray()
    }

    @Synchronized
    fun selectPinyin(index: Int) {
        val pinyinKey = keyRecordStack.pushPinyinSelectAction(pinyins[index]) ?: return
        Rime.replaceKey(pinyinKey.posInInput, pinyinKey.t9Keys().length, pinyinKey.pinyin())
        rawInputValid = false
        editCaret = -1
        updateCandidatesOrCommitText()
    }

    @Synchronized
    fun predictAssociationWords(text: String) {
        showCandidates = emptyList()
        showComposition = ""
        preCommitText = ""
        customPhraseSize = 0
        pinyins = emptyArray()
        editCaret = -1
        rimeIndexMap.clear()
        rimeCandidateCount = 0
        if (text.isNotEmpty()) {
            showCandidates = buildList {
                val words = Rime.getAssociateList(text)
                val firstFive = words.take(5)
                addAll(firstFive.filterNotNull().map { CandidateListItem("", it) })
                addAll(CustomEngine.predictAssociationWordsChinese(text).map { CandidateListItem("", it) })
                val remaining = words.drop(5)
                addAll(remaining.filterNotNull().map { CandidateListItem("", it) })
            }
            showComposition = ""
        }
    }

    @Synchronized
    fun selectAssociation(index: Int) {
        val selected = showCandidates.getOrNull(index)?.text ?: return
        reset()
        preCommitText = selected
    }

    @Synchronized
    fun reset() {
        customPhraseSize = 0
        showCandidates = emptyList()
        pinyins = emptyArray()
        showComposition = ""
        preCommitText = ""
        keyRecordStack.clear()
        rimeIndexMap.clear()
        rimeCandidateCount = 0
        rawInput.setLength(0)
        rawInputValid = true
        editCaret = -1
        Rime.clearComposition()
        if(charCase == KeyEvent.META_SHIFT_ON) charCase = MASK_CASE_LOWER
    }

    @Synchronized
    fun destroy() = Rime.destroy()

    @Synchronized
    fun processDelAction() {
        when (val lastKey = keyRecordStack.pop()) {
            is InputKey.PinyinKey -> {
                val pinyinKey = keyRecordStack.restorePinyinToT9Key(lastKey) ?: return
                replacePinyinWithT9Keys(pinyinKey)
            }
            InputKey.SelectPinyinAction -> {
                val pinyinKey = keyRecordStack.restorePinyinToT9Key() ?: return
                replacePinyinWithT9Keys(pinyinKey)
            }
            is InputKey.Apostrophe -> {
                if (!lastKey.dummy) {
                    Rime.processKey(getRimeKeycodeByName("BackSpace"), 0)
                }
            }
            else -> {
                Rime.processKey(getRimeKeycodeByName("BackSpace"), 0)
            }
        }
    }

    private fun replacePinyinWithT9Keys(pinyinKey: InputKey.PinyinKey) {
        /**
         * 当前输入状态是“你h”时，引擎默认删除行为是“ni”（删除h并且删除“你”的选中状态）
         * 可能存在引擎操作栈与记录的操作栈不一样的问题
         * 临时方案，尝试不同长度的替换，至少保证可以把拼音回退成9键
         */
        if (!Rime.replaceKey(pinyinKey.posInInput, pinyinKey.inputKeyLength, pinyinKey.t9Keys())) {
            Rime.replaceKey(pinyinKey.posInInput, pinyinKey.pinyinLength, pinyinKey.t9Keys())
        }
    }

    /**
     * 编码显示串中插入点所在的字符下标；插入点在串尾（即常规输入状态）时为 -1。
     */
    val caretInComposition: Int
        get() {
            if (editCaret < 0) return -1
            val preedit = Rime.composition?.preedit ?: return -1
            if (!isCompositionEditable(preedit)) return -1
            return displayIndexOf(editCaret, preedit)
        }

    /**
     * 把插入点移到编码显示串的第 [position] 个字符之前。
     *
     * 只改本侧记录的插入点，引擎自身的插入点始终留在串尾：引擎只翻译插入点之前的编码，
     * 真把它挪到中间，其后的编码就不再参与候选，候选栏会只剩前半段的结果。
     * 编辑时改按键序列、整串同步给引擎，候选因而一直覆盖完整编码。
     *
     * @return 插入点是否发生变化
     */
    @Synchronized
    fun moveCaretTo(position: Int): Boolean {
        val preedit = Rime.composition?.preedit ?: return false
        if (!isCompositionEditable(preedit)) return false
        val target = inputIndexOf(position, preedit)
        // 落到串尾即回到常规输入状态，不再单列插入点
        val newCaret = if (target >= rawInput.length) -1 else target
        if (newCaret == editCaret) return false
        editCaret = newCaret
        return true
    }

    /**
     * 编码是否可编辑。
     *
     * 九键一个键对应多个字母，编码里的字母只是按键代号而非拼音本身，插改无从谈起，整个排除。
     * 其余方案要求显示串与 preedit 逐字符对应——双拼把两个码位展开成完整拼音后二者不再等长，
     * 点击位置无从落到按键上；也要求本轮尚未选词、选拼音，否则按键序列不再可信。
     */
    private fun isCompositionEditable(preedit: String): Boolean =
        rawInputValid && rawInput.isNotEmpty() && preedit.isNotEmpty() &&
                preedit.none { it.code > 0x7F } && preedit.length == showComposition.length &&
                Rime.getCurrentRimeSchema() != CustomConstant.SCHEMA_ZH_T9

    /**
     * 显示串下标换算为按键序列下标。
     *
     * 两串的差别只在引擎自动补入的分词符，故按「非分词符的个数」对齐。
     * 落点正压在手动输入的分词符上时移到其后，与点击处看到的位置一致。
     */
    private fun inputIndexOf(displayIndex: Int, preedit: String): Int {
        val letters = preedit.take(displayIndex.coerceIn(0, preedit.length)).count { it != '\'' }
        var index = 0
        var counted = 0
        while (index < rawInput.length && counted < letters) {
            if (rawInput[index] != '\'') counted++
            index++
        }
        if (index < rawInput.length && rawInput[index] == '\'') index++
        return index
    }

    /** [inputIndexOf] 的逆向换算，用于在气泡上标出插入点 */
    private fun displayIndexOf(inputIndex: Int, preedit: String): Int {
        val letters = rawInput.take(inputIndex.coerceIn(0, rawInput.length)).count { it != '\'' }
        var index = 0
        var counted = 0
        while (index < preedit.length && counted < letters) {
            if (preedit[index] != '\'') counted++
            index++
        }
        while (index < preedit.length && preedit[index] == '\'') index++
        return index
    }

    /**
     * 把按键序列整串同步给引擎，并确保引擎的插入点落在串尾。
     *
     * 整串替换若被引擎拒绝（下标越界等），退回逐键重放，至少保证两边一致。
     */
    private fun syncInputToRime(previousLength: Int) {
        val input = rawInput.toString()
        if (!Rime.replaceKey(0, previousLength, input) || !matchesRimeInput(input)) replayInput(input)
        var guard = input.length + 4
        while (guard-- > 0) {
            val composition = Rime.composition ?: return
            val preedit = composition.preedit ?: return
            if (composition.cursorPos >= preedit.length) return
            if (!Rime.processKey(getRimeKeycodeByName("Right"), 0)) return
        }
    }

    /** 引擎的编码是否与按键序列一致，二者只应差在自动补入的分词符上 */
    private fun matchesRimeInput(input: String): Boolean {
        val preedit = Rime.composition?.preedit ?: return false
        if (preedit.any { it.code > 0x7F }) return false
        return preedit.replace("'", "") == input.replace("'", "")
    }

    /** 在插入点处插入一个按键，随后插入点右移一位 */
    private fun insertAtEditCaret(char: Char) {
        val previousLength = rawInput.length
        rawInput.insert(editCaret, char)
        editCaret++
        syncInputToRime(previousLength)
        keyRecordStack.resetToPlainKeys(rawInput.toString())
    }

    /** 删除插入点之前的一个按键；插入点已在串首时无字符可删 */
    private fun deleteAtEditCaret(): Boolean {
        if (editCaret <= 0) return false
        val previousLength = rawInput.length
        rawInput.deleteCharAt(editCaret - 1)
        editCaret--
        if (rawInput.isEmpty()) {
            reset()
            return true
        }
        syncInputToRime(previousLength)
        keyRecordStack.resetToPlainKeys(rawInput.toString())
        return true
    }

    /**
     * 建立展示候选到引擎候选的映射，并滤掉与编码本身相同的单字母候选。
     *
     * 词库收录了字母条目，中文下敲单个字母时它排在首位，把真正的汉字挤到后面；
     * 要输出这个字母直接回车即可，无须占用候选位。若滤后为空则保留原样，
     * 否则上层会当作「无候选」而清掉整轮输入。
     */
    private fun collectRimeCandidates(candidates: List<CandidateListItem>, compositionText: String): List<CandidateListItem> {
        rimeIndexMap.clear()
        rimeCandidateCount = candidates.size
        val code = compositionText.filter { it.code <= 0xFF }.replace("'", "")
        val dropEcho = !InputModeSwitcher.isEnglish && code.length == 1 && StringUtils.isLetter(code) &&
                candidates.any { !it.text.equals(code, ignoreCase = true) }
        val result = ArrayList<CandidateListItem>(candidates.size)
        candidates.forEachIndexed { index, item ->
            if (dropEcho && item.text.equals(code, ignoreCase = true)) return@forEachIndexed
            rimeIndexMap.add(index)
            result.add(item)
        }
        return result
    }

    /** 清空引擎输入并按编辑后的按键串重放 */
    private fun replayInput(input: String) {
        Rime.clearComposition()
        input.forEach { Rime.processKey(it.code, 0) }
        // 重放期间引擎可能判定某段可以上屏，取走丢弃，避免污染下一次真实输入
        Rime.getRimeCommit()
    }

    private fun updateCandidatesOrCommitText(): String? {
        if (!EngineRuntime.isReady) return null
        val rimeCommit = Rime.getRimeCommit()
        if (rimeCommit != null) {
            keyRecordStack.clear()
            editCaret = -1
            preCommitText = rimeCommit.commitText
            preCommitText = if (charCase == KeyEvent.META_SHIFT_ON) {
                preCommitText.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            } else if (charCase == KeyEvent.META_CAPS_LOCK_ON) {
                preCommitText.uppercase()
            } else {
                if (InputModeSwitcher.isEnglish) preCommitText.lowercase() else preCommitText
            }
            showComposition = ""
            showCandidates = emptyList()
            return preCommitText
        }
        // processKey 已通过 updateContext() 缓存上下文，此处复用避免重复跨 JNI 编组
        val candidates = Rime.candidates.asList()
        customPhraseSize = 0
        val compositionText = Rime.compositionText
        val rimeCandidates = collectRimeCandidates(candidates, compositionText)
        showCandidates = when {
            compositionText.isNotBlank() -> {
                val phrase = CustomEngine.processPhrase(compositionText.replace("\'", ""))
                // 有编码但候选为空是可能的，不能直接取首项
                if(InputModeSwitcher.isEnglish && StringUtils.isLetter(compositionText) &&
                    !compositionText.equals(candidates.firstOrNull()?.text, ignoreCase = true) ){
                    phrase.add(0, compositionText)
                }
                customPhraseSize = phrase.size
                phrase.map { content -> CandidateListItem("📋", content) }.toMutableList().plus(rimeCandidates)
            }
            else -> rimeCandidates
        }
        var count = compositionText.count { it in 'A'..'Z' }
        if (count > 0) {
            keyRecordStack.forEachReversed { inputKey ->
                if (inputKey is InputKey.T9Key) inputKey.consumed = count-- <= 0
            }
        }
        // 方案在一次输入会话内不变，取一次供下方复用，省去重复的 JNI 调用
        val rimeSchema = Rime.getCurrentRimeSchema()
        var composition = getCurrentComposition(candidates, rimeSchema)
        when (charCase) {
            KeyEvent.META_SHIFT_ON -> {
                for (item in showCandidates) if (InputModeSwitcher.isEnglish) item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                composition = composition.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            KeyEvent.META_CAPS_LOCK_ON -> {
                for (item in showCandidates) item.text = item.text.uppercase()
                composition = composition.uppercase()
            }
            else -> {
                for (item in showCandidates) if (InputModeSwitcher.isEnglish) item.text = item.text.lowercase()
                composition = composition.lowercase()
            }
        }
        pinyins = when (rimeSchema) {
            CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.t9KeyToPinyin(compositionText.split('\'').firstOrNull { part -> part.isNotEmpty() && part.all { it.isUpperCase() } } ?: "")
            }
            CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> {
                LX17PinYinUtils.lx17KeyToPinyin(compositionText.split('\'').firstOrNull { part -> part.isNotEmpty() && part.all { it.isUpperCase() } } ?: "")
            }
            else -> {
                emptyArray()
            }
        }
        showComposition = composition
        preCommitText = ""
        return null
    }

    /**
     * 拿到候选词拼音组合
     */
    @Synchronized
    fun getPrefixs(): Array<String> {
        return pinyins
    }

    private fun getCurrentComposition(candidates: List<CandidateListItem>, rimeSchema: String): String {
        val composition = Rime.compositionText
        if(rimeSchema == CustomConstant.SCHEMA_EN) return ""
        if(composition.isEmpty()) return ""
        if(candidates.isEmpty()) return composition
        val comment = candidates.first().comment.trim().replace(Regex("\\s+"), "'")
        val result =  when {
            comment.isNotBlank() && comment.startsWith("~") -> composition
            rimeSchema == CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.getT9Composition(composition, comment)
            }
            rimeSchema.startsWith(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY) -> {
                if(!AppPrefs.getInstance().keyboardSetting.keyboardDoubleInputKey.getValue()) composition
                else DoublePinYinUtils.getDoublePinYinComposition(rimeSchema, composition, comment)
            }
            else -> {
                QwertyPinYinUtils.getQwertyComposition(composition, comment)
            }
        }
        return if (!composition.endsWith("'") && result.endsWith("'")) result.dropLast(1) else result
    }

    /**
     * 设置输入法搜索参数
     */
    @Synchronized
    fun setImeOption(option: String, value: Boolean) {
        Rime.setOption(option, value)
    }

    /**
     * 获取Rime定义键值
     */
    private fun getRimeKeycodeByName(name: String) : Int {
        return Rime.getRimeKeycodeByName(name)
    }

    @Synchronized
    fun setCharCase(charCase: Int) {
        this.charCase = charCase
    }

}
