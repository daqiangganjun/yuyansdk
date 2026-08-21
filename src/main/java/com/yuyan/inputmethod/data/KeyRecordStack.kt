package com.yuyan.inputmethod.data

import android.view.KeyEvent
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.inputmethod.RimeEngine.processDelAction
import com.yuyan.inputmethod.core.Rime
import com.yuyan.inputmethod.util.LX17PinYinUtils
import com.yuyan.inputmethod.util.T9PinYinUtils
import java.util.LinkedList

class KeyRecordStack {
    private val keyRecords = ArrayList<InputKey>(20)

    fun pop(): InputKey? = keyRecords.removeLastOrNull()

    fun clear() = keyRecords.clear()

    fun isEmpty() = keyRecords.isEmpty()

    fun forEachReversed(action: (InputKey) -> Unit) {
        for (i in keyRecords.indices.reversed()) {
            action(keyRecords[i])
        }
    }

    fun pushKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val lastKey = keyRecords.lastOrNull()
        if (lastKey is InputKey.Apostrophe && keyRecords.size == 1) {
            processDelAction()
        }else if (keyCode == KeyEvent.KEYCODE_APOSTROPHE) {
            // 连续分词没有意义
            if (lastKey is InputKey.Apostrophe) return false
            // 选择拼音之后分词没有意义，但是需要把分词操作入栈
            if (lastKey == InputKey.SelectPinyinAction) {
                keyRecords.add(InputKey.Apostrophe(true))
                return false
            }
        }
        // 选择拼音只是记录其是不是最后一个操作，如果不是在选择之后立即删除，则不需记录
        if (lastKey == InputKey.SelectPinyinAction) {
            keyRecords.removeLastOrNull()
        }
        when (keyCode) {
            KeyEvent.KEYCODE_APOSTROPHE -> {
                keyRecords.add(InputKey.Apostrophe())
            }
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                if('A'.code <= keyChar && 'Z'.code >= keyChar){
                    keyRecords.add(InputKey.T9Key(keyChar))
                } else {
                    keyRecords.add(InputKey.QwertKey(keyChar))
                }
            } else -> {
                keyRecords.add(InputKey.DefaultAction)
            }
        }
        return true
    }

    fun pushPinyinSelectAction(pinyin: String?): InputKey.PinyinKey? {
        pinyin ?: return null
        val keys = LinkedList<InputKey.T9Key>()
        val rimeSchema = Rime.getCurrentRimeSchema()
        when (rimeSchema) {
            CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.pinyin2Key(pinyin).forEach {
                    keys.add(InputKey.T9Key(it))
                }
            }
            CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> {
                LX17PinYinUtils.pinyin2Key(pinyin).forEach {
                    keys.add(InputKey.T9Key(it))
                }
            }
        }
        val index = (0..keyRecords.size - keys.size).indexOfFirst { start ->
            keys.indices.all { j ->
                val record = keyRecords[start + j]
                record.toString() == keys[j].toString() && record is InputKey.T9Key && !record.consumed
            }
        }
        // 未匹配到对应按键序列时 index 为 -1，继续执行会在 removeAt 处越界
        if (index < 0) return null
        repeat(keys.size) {
            keyRecords.removeAt(index)
        }
        keyRecords.add(InputKey.SelectPinyinAction)
        keyRecords.add(index, InputKey.PinyinKey(pinyin))
        val posInInput = keyRecords.subList(0, index).fold(0) { acc, inputKey ->
            acc + when (inputKey) {
                is InputKey.T9Key, is InputKey.Apostrophe -> 1
                is InputKey.PinyinKey -> inputKey.inputKeyLength
                else -> 0
            }
        }
        keyRecords[index] = (keyRecords[index] as InputKey.PinyinKey).copy(posInInput)
        return keyRecords.getOrNull(index) as? InputKey.PinyinKey
    }

    /**
     * 按给定的按键序列重建按键栈。
     *
     * 编辑拼音、选中模糊音候选时引擎的输入串被整体换过，按键栈须一并对齐，
     * 否则随后的退格会按原来的序列还原，与引擎实际状态对不上。
     *
     * 大小写的分工与 [pushKey] 一致：九键与乱序方案下按的是大写字母，记为 [InputKey.T9Key]，
     * 拼音选择栏正是按它来匹配按键序列的，记错类型会让选择栏点击失效。
     */
    fun resetToPlainKeys(input: String) {
        keyRecords.clear()
        input.forEach { ch ->
            keyRecords.add(
                when {
                    ch == '\'' -> InputKey.Apostrophe()
                    ch in 'A'..'Z' -> InputKey.T9Key(ch)
                    else -> InputKey.QwertKey(ch)
                }
            )
        }
    }

    fun pushCandidateSelectAction() {
        if (keyRecords.lastOrNull() == InputKey.SelectPinyinAction) {
            keyRecords.removeLastOrNull()
        }
        keyRecords.add(InputKey.DefaultAction)
    }

    fun restorePinyinToT9Key(pinyinKey: InputKey.PinyinKey? = null): InputKey.PinyinKey? {
        if (pinyinKey != null) {
            keyRecords.add(pinyinKey)
        }
        val index = keyRecords.indexOfLast { it is InputKey.PinyinKey }
        val inputKey = keyRecords.getOrNull(index) as? InputKey.PinyinKey
        if (index >= 0) {
            keyRecords.replaceAt(index, inputKey!!.restoreToT9key())
        }
        return inputKey
    }

    private fun <T> ArrayList<T>.replaceAt(index: Int, elements: List<T>) {
        if (index == lastIndex) {
            removeAt(index)
            addAll(elements)
        } else {
            val heads = take(index)
            val tails = takeLast(size - index - 1)
            clear()
            addAll(heads)
            addAll(elements)
            addAll(tails)
        }
    }
}

interface InputKey {
    class Apostrophe(val dummy: Boolean = false) : InputKey

    object DefaultAction : InputKey

    object SelectPinyinAction : InputKey
    class T9Key(private val keyChar: Char, var consumed: Boolean = false) : InputKey {
        constructor(keyCode: Int) : this(keyCode.toChar())

        override fun toString(): String = keyChar.toString()
    }

    class QwertKey(private val keyChar: Char) : InputKey {
        constructor(keyCode: Int) : this(keyCode.toChar())

        override fun toString(): String = keyChar.toString()
    }

    class PinyinKey(private val pinyin: String, val posInInput: Int = 0) : InputKey {
        val pinyinLength: Int = pinyin.length
        val inputKeyLength: Int = pinyinLength + 1
        fun t9Keys(): String {
            val rimeSchema = Rime.getCurrentRimeSchema()
            return when (rimeSchema) {
                CustomConstant.SCHEMA_ZH_T9 -> {
                    T9PinYinUtils.pinyin2Key(pinyin)
                }
                CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> {
                    LX17PinYinUtils.pinyin2Key(pinyin)
                }
                else -> ""
            }
        }

        fun restoreToT9key(): List<T9Key> {
            val keys = LinkedList<T9Key>()
            val rimeSchema = Rime.getCurrentRimeSchema()
            when (rimeSchema) {
                CustomConstant.SCHEMA_ZH_T9 -> {
                    T9PinYinUtils.pinyin2Key(pinyin).forEach {
                        keys.add(T9Key(it))
                    }
                }
                CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> {
                    LX17PinYinUtils.pinyin2Key(pinyin).forEach {
                        keys.add(T9Key(it))
                    }
                }
                else -> pinyin
            }

            return keys
        }

        fun copy(posInInput: Int) = PinyinKey(pinyin, posInInput)

        fun pinyin() = "${pinyin.lowercase()}'"
    }
}