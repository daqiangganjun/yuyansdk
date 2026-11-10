package com.yuyan.imemodule.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.libs.pinyin4j.PinyinHelper
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.inputmethod.util.LX17PinYinUtils
import com.yuyan.inputmethod.util.T9PinYinUtils
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

/**
 * 常用语编辑器。编辑期间经 [inputConnection] 充当键盘的本地输入目标，
 * 输入、退格、方向键、选择与粘贴都落在当前获得焦点的输入框，不再触及宿主。
 */
class EditPhrasesView(context: Context, private val onSubmit: () -> Unit) : LinearLayout(context){

    private var mEtPhrases: ImeSourceEditText
    private var mEtPhrasesQuickCode: ImeSourceEditText
    private var mEtPhrasesTips:TextView
    init {
        orientation = VERTICAL
        mEtPhrasesTips = TextView(context).apply {
            setText(R.string.add_phrases_input_tips)
            setPadding(dp(5))
            textSize = 12f
        }
        mEtPhrasesQuickCode = ImeSourceEditText(context).apply {
            setText(R.string.add_phrases_input_tips)
            setPadding(dp(5))
            background = null
            isCursorVisible = true
            isFocusable = true
            isFocusableInTouchMode = true
            localEditing = true
            textSize = 12f
        }
       val llTipContainer = LinearLayout(context)
        llTipContainer.add(mEtPhrasesTips, lParams(weight = 0f))
        llTipContainer.add(mEtPhrasesQuickCode, lParams(weight = 1f))
        mEtPhrases = ImeSourceEditText(context).apply {
            background = null
            isCursorVisible = true
            isFocusable = true
            isFocusableInTouchMode = true
            localEditing = true
            gravity = Gravity.CENTER_VERTICAL
            textSize = 16f
            minLines = 3
            setPadding(dp(10))
            setHint(R.string.add_phrases_input_hint)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(editable: Editable) {
                    val pinYinHeadChar  = PinyinHelper.getPinYinHeadChar(editable.toString().take(4))
                    if(pinYinHeadChar.isNotBlank()) {
                        mEtPhrasesQuickCode.setText(pinYinHeadChar)
                        mEtPhrasesQuickCode.setSelection(pinYinHeadChar.length)
                    }
                }
            })
        }
        // 切换输入框时清掉原框的组合态，否则下划线会一直留在原框中
        for (editor in listOf(mEtPhrases, mEtPhrasesQuickCode)) {
            editor.setOnFocusChangeListener { _, focused -> if (!focused) BaseInputConnection.removeComposingSpans(editor.editableText) }
        }
        add(llTipContainer, lParams(width = matchParent,height = wrapContent, weight = 0f))
        add(mEtPhrases, lParams(width = matchParent,height = wrapContent, weight = 1f))
    }

    private val focusedEditor: ImeSourceEditText
        get() = if (mEtPhrasesQuickCode.isFocused) mEtPhrasesQuickCode else mEtPhrases

    val inputConnection: InputConnection = object : BaseInputConnection(mEtPhrases, true) {
        override fun getEditable(): Editable = focusedEditor.editableText
        override fun sendKeyEvent(event: KeyEvent): Boolean = focusedEditor.dispatchKeyEvent(event)
        override fun performContextMenuAction(id: Int): Boolean = focusedEditor.onTextContextMenuItem(id)
        override fun performEditorAction(actionCode: Int): Boolean { onSubmit(); return true }
    }

    fun requestEditorFocus() {
        mEtPhrases.requestFocus()
    }

    fun clearPhrasesContent() {
        mEtPhrases.setText("")
        mEtPhrasesQuickCode.setText("")
    }

    fun addPhrasesHandle() {
        inputConnection.finishComposingText()
        val content = mEtPhrases.text.toString()
        if(content.isNotBlank()) {
            val pinYinHeadChar  = mEtPhrasesQuickCode.text.toString()
            val pinYinHeadT9 = pinYinHeadChar.map { T9PinYinUtils.pinyin2T9Key(it)}.joinToString("")
            val pinYinHeadLX17 = pinYinHeadChar.map { LX17PinYinUtils.pinyin2Lx17Key(it)}.joinToString("")
            val phrase =  Phrase(content = content, t9 = pinYinHeadT9, qwerty = pinYinHeadChar, lx17 = pinYinHeadLX17)
            DataBaseKT.instance.phraseDao().insert(phrase)
            KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
        }
    }

    fun setExtraData(extra:Phrase) {
        val content = extra.content
        val qwerty = extra.qwerty
        mEtPhrases.setText(content)
        mEtPhrases.setSelection(content.length)
        mEtPhrasesQuickCode.setText(qwerty)
        mEtPhrasesQuickCode.setSelection(qwerty.length)
    }

    fun updateTheme(theme: Theme) {
        val keyTextColor = ThemeManager.activeTheme.keyTextColor
        setBackgroundColor(theme.barColor)
        mEtPhrases.background = GradientDrawable().apply {
            setColor(ThemeManager.activeTheme.keyBackgroundColor)
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ThemeManager.prefs.keyRadius.getValue().toFloat()
        }
        mEtPhrases.setTextColor(keyTextColor)
        mEtPhrases.setHintTextColor(keyTextColor)
        mEtPhrasesTips.setTextColor(keyTextColor)
        mEtPhrasesQuickCode.setTextColor(keyTextColor)
    }
}
