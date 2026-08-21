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
import com.yuyan.inputmethod.util.FuzzyPinYinUtils
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
     * 开启模糊音后还会插入由其它输入串查得的候选。选词必须换算回引擎侧的下标，
     * 模糊音候选还需先把输入串切换到其来源串，否则选中的会是另一个词。
     */
    private val rimeIndexMap = ArrayList<Int>(64)
    private val fuzzySourceMap = ArrayList<String?>(64)
    private var rimeCandidateCount = 0

    /**
     * 用户实际按下的键序列，模糊音查询后据此还原输入状态。
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

    private const val MAX_FUZZY_VARIANTS = 4        // 单轮补查的变体串上限
    private const val MAX_FUZZY_PER_VARIANT = 8     // 每条变体串取用的候选上限
    private const val MAX_FUZZY_INPUT_LENGTH = 12   // 编码过长时重放代价过高，不再补查
    private const val FUZZY_INTERLEAVE_COUNT = 6    // 与原候选交替排列的模糊音候选个数
    fun init() {
        Rime.getInstance(false)
    }

    fun selectSchema(mod: String): Boolean {
        keyRecordStack.clear()
        charCase = MASK_CASE_LOWER
        Rime.startup(Launcher.instance.context, false)
        return Rime.selectSchema(mod)
    }

    fun getCurrentRimeSchema(): String {
        return Rime.getCurrentRimeSchema()
    }

    /**
     * 是否输入完毕
     */
    fun isFinish(): Boolean {
        return keyRecordStack.isEmpty()
    }

    fun onNormalKey(event: KeyEvent) {
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
            Rime.processKey(keyChar, event.action)
            if (rawInputValid && keyChar in 1..0x7F) rawInput.append(keyChar.toChar())
        }
        updateCandidatesOrCommitText()
    }

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

    fun selectCandidate(index: Int): String? {
        editCaret = -1
        val listIndex = index - customPhraseSize
        // 模糊音候选来自另一条输入串，需先把引擎切过去，选中后的后续输入才接得上
        fuzzySourceMap.getOrNull(listIndex)?.let { source ->
            replayInput(source)
            keyRecordStack.resetToPlainKeys(source)
        }
        Rime.selectCandidate(rimeIndexMap.getOrElse(listIndex) { listIndex })
        rawInputValid = false
        keyRecordStack.pushCandidateSelectAction()
        return updateCandidatesOrCommitText()
    }

    @Synchronized
    fun getNextPageCandidates(): Array<CandidateListItem> {
        return if (Rime.hasRight()) {
            Rime.processKey(getRimeKeycodeByName("Page_Down"), 0)
           val candidates = Rime.getRimeContext()!!.candidates
            // 翻页所得接在已展示候选之后，映射同步延长，否则选中后一页的词会取到错误下标
            candidates.indices.forEach {
                rimeIndexMap.add(rimeCandidateCount + it)
                fuzzySourceMap.add(null)
            }
            rimeCandidateCount += candidates.size
            when (charCase) {
                KeyEvent.META_SHIFT_ON -> {
                    for (item in candidates) {
                        item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    }
                }
                KeyEvent.META_CAPS_LOCK_ON -> {
                    for (item in candidates) {
                        item.text = item.text.uppercase()
                    }
                }
                else -> {
                    for (item in candidates) {
                        item.text = item.text.lowercase()
                    }
                }
            }
            candidates
        } else emptyArray()
    }

    fun selectPinyin(index: Int) {
        val pinyinKey = keyRecordStack.pushPinyinSelectAction(pinyins[index]) ?: return
        Rime.replaceKey(pinyinKey.posInInput, pinyinKey.t9Keys().length, pinyinKey.pinyin())
        rawInputValid = false
        editCaret = -1
        updateCandidatesOrCommitText()
    }

    fun predictAssociationWords(text: String) {
        pinyins = emptyArray()
        editCaret = -1
        rimeIndexMap.clear()
        fuzzySourceMap.clear()
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

    fun selectAssociation(index: Int) {
        val indexReal = index - customPhraseSize
        Rime.chooseAssociate(indexReal)
        updateCandidatesOrCommitText()
        preCommitText = showCandidates.getOrNull(indexReal)?.text?:""
    }

    fun reset() {
        showCandidates = emptyList()
        pinyins = emptyArray()
        showComposition = ""
        preCommitText = ""
        keyRecordStack.clear()
        rimeIndexMap.clear()
        fuzzySourceMap.clear()
        rimeCandidateCount = 0
        rawInput.setLength(0)
        rawInputValid = true
        editCaret = -1
        Rime.clearComposition()
        if(charCase == KeyEvent.META_SHIFT_ON) charCase = MASK_CASE_LOWER
    }

    fun destroy() = Rime.destroy()

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
        fuzzySourceMap.clear()
        rimeCandidateCount = candidates.size
        val code = compositionText.filter { it.code <= 0xFF }.replace("'", "")
        val dropEcho = !InputModeSwitcher.isEnglish && code.length == 1 && StringUtils.isLetter(code) &&
                candidates.any { !it.text.equals(code, ignoreCase = true) }
        val result = ArrayList<CandidateListItem>(candidates.size)
        candidates.forEachIndexed { index, item ->
            if (dropEcho && item.text.equals(code, ignoreCase = true)) return@forEachIndexed
            rimeIndexMap.add(index)
            fuzzySourceMap.add(null)
            result.add(item)
        }
        return result
    }

    /** 清空引擎输入并按给定串逐键重放，用于在模糊音的各条输入串之间切换 */
    private fun replayInput(input: String) {
        Rime.clearComposition()
        input.forEach { Rime.processKey(it.code, 0) }
        // 重放期间引擎可能判定某段可以上屏，取走丢弃，避免污染下一次真实输入
        Rime.getRimeCommit()
    }

    /**
     * 把模糊音候选与原候选交替排列。
     *
     * 引擎的候选质量分不对外暴露，跨输入串无从比较高下，做不到真正的同台竞技；
     * 但平翘舌打错时，正确的整句往往就是模糊串的首选，固定插在某个靠后的位置要翻页才找得到。
     * 交替排列让两边的第 n 名紧挨着，模糊串的首选落在第二位，一眼可见，
     * 同时原串首选仍占第一，打得准时不受干扰。交替只进行前几名，其余接在末尾。
     */
    private fun interleaveFuzzyCandidates(collected: List<Triple<CandidateListItem, String, Int>>) {
        val head = showCandidates.subList(0, customPhraseSize).toList()
        val main = showCandidates.subList(customPhraseSize, showCandidates.size).toList()
        val items = ArrayList<CandidateListItem>(main.size + collected.size)
        val indexes = ArrayList<Int>(main.size + collected.size)
        val sources = ArrayList<String?>(main.size + collected.size)
        var mainAt = 0
        var fuzzyAt = 0
        fun takeMain() {
            items.add(main[mainAt])
            indexes.add(rimeIndexMap[mainAt])
            sources.add(fuzzySourceMap[mainAt])
            mainAt++
        }
        fun takeFuzzy() {
            val (item, variant, index) = collected[fuzzyAt]
            items.add(item)
            indexes.add(index)
            sources.add(variant)
            fuzzyAt++
        }
        while (fuzzyAt < collected.size && fuzzyAt < FUZZY_INTERLEAVE_COUNT) {
            if (mainAt < main.size) takeMain()
            takeFuzzy()
        }
        while (mainAt < main.size) takeMain()
        while (fuzzyAt < collected.size) takeFuzzy()
        showCandidates = head + items
        rimeIndexMap.clear()
        rimeIndexMap.addAll(indexes)
        fuzzySourceMap.clear()
        fuzzySourceMap.addAll(sources)
    }

    /**
     * 用模糊音变体串补查候选并并入候选栏。
     *
     * 每个变体都要重放整串按键，开销不低，故由上层在输入停顿后调用一次，而非每次按键都做。
     * 仅在全键拼音、且本轮尚未选词（编码全为 ASCII）时进行：九键与双拼的按键本身已把
     * 平翘舌归并到一处，双拼的模糊需在双拼码层面另作映射，均不适用于此。
     *
     * @return 候选栏是否有补充
     */
    @Synchronized
    fun appendFuzzyCandidates(): Boolean {
        if (!FuzzyPinYinUtils.isEnabled) return false
        if (Rime.getCurrentRimeSchema() != CustomConstant.SCHEMA_ZH_QWERTY) return false
        if (!rawInputValid || rawInput.isEmpty() || rawInput.length > MAX_FUZZY_INPUT_LENGTH) return false
        val original = rawInput.toString()
        val preedit = Rime.compositionText
        if (preedit.isEmpty() || preedit.any { it.code > 0x7F }) return false
        // 自检：按键序列与引擎编码不一致说明中途有过选词、选拼音等操作，此时不宜重放
        if (preedit.replace("'", "") != original.replace("'", "")) return false
        val syllables = preedit.split('\'').filter { it.isNotEmpty() }
        val variants = FuzzyPinYinUtils.variantsOf(syllables, MAX_FUZZY_VARIANTS)
        if (variants.isEmpty()) return false

        val seen = showCandidates.mapTo(HashSet()) { it.text }
        val collected = ArrayList<Triple<CandidateListItem, String, Int>>()
        for (variant in variants) {
            replayInput(variant)
            var taken = 0
            for ((index, item) in Rime.candidates.withIndex()) {
                if (taken >= MAX_FUZZY_PER_VARIANT) break
                if (item.text.isEmpty() || !seen.add(item.text)) continue
                collected.add(Triple(item, variant, index))
                taken++
            }
        }
        replayInput(original)
        if (collected.isEmpty()) return false
        for ((item, _, _) in collected) {
            item.text = when (charCase) {
                KeyEvent.META_SHIFT_ON -> item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                KeyEvent.META_CAPS_LOCK_ON -> item.text.uppercase()
                else -> item.text.lowercase()
            }
        }
        interleaveFuzzyCandidates(collected)
        return true
    }

    private fun updateCandidatesOrCommitText(): String? {
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
                preCommitText.lowercase()
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
                for (item in showCandidates) item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                composition = composition.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            KeyEvent.META_CAPS_LOCK_ON -> {
                for (item in showCandidates) item.text = item.text.uppercase()
                composition = composition.uppercase()
            }
            else -> {
                for (item in showCandidates) item.text = item.text.lowercase()
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
    fun getPrefixs(): Array<String> {
        return pinyins
    }

    private fun getCurrentComposition(candidates: List<CandidateListItem>, rimeSchema: String): String {
        val composition = Rime.compositionText
        if(rimeSchema == CustomConstant.SCHEMA_EN) return ""
        if(composition.isEmpty()) return ""
        if(candidates.isEmpty()) return composition
        val comment = candidates.first().comment
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
    fun setImeOption(option: String, value: Boolean) {
        Rime.setOption(option, value)
    }

    /**
     * 获取Rime定义键值
     */
    private fun getRimeKeycodeByName(name: String) : Int {
        return Rime.getRimeKeycodeByName(name)
    }

    fun setCharCase(charCase: Int) {
        this.charCase = charCase
    }

}