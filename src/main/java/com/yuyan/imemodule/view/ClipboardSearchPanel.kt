package com.yuyan.imemodule.view

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import splitties.dimensions.dp

/** 剪贴板与常用语的搜索栏，输入框与花漾字处理栏共用 [ImeInlineEditor]，行高也与之一致。 */
class ClipboardSearchPanel(context: Context, onSearch: () -> Unit, onCancel: () -> Unit) :
    LinearLayout(context), ThemeManager.OnThemeChangeListener {
    private val editor = ImeInlineEditor(context, "清空搜索内容")
    private val source = editor.source.apply {
        tag = "clipboard_search_query"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
        localEditing = true
        setOnFocusChangeListener { _, focused -> editor.isActivated = focused }
    }
    private val connection = object : BaseInputConnection(source, true) {
        override fun getEditable(): Editable = source.editableText
        override fun sendKeyEvent(event: KeyEvent): Boolean = source.dispatchKeyEvent(event)
        override fun performContextMenuAction(id: Int): Boolean = source.onTextContextMenuItem(id)
        override fun performEditorAction(actionCode: Int): Boolean { onSearch(); return true }
    }
    private val search = button("搜索", "clipboard_search_submit", onSearch)
    private val cancel = button("取消", "clipboard_search_cancel", onCancel)
    var isSearching = false
        private set
    val inputConnection: InputConnection? get() = if (isSearching) connection else null
    val query: String get() = source.text?.toString().orEmpty()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        setPadding(dp(6), dp(2), dp(6), dp(2))
        addView(editor, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(search, LayoutParams(dp(52), LayoutParams.MATCH_PARENT).apply { marginStart = dp(6) })
        addView(cancel, LayoutParams(dp(52), LayoutParams.MATCH_PARENT).apply { marginStart = dp(4) })
        onThemeChange(ThemeManager.activeTheme)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rowHeight = (EnvironmentSingleton.instance.heightForCandidatesArea - paddingTop - paddingBottom).coerceAtLeast(dp(32))
        if (source.minimumHeight != rowHeight) source.minimumHeight = rowHeight
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun button(label: String, viewTag: String, action: () -> Unit) = TextView(context).apply {
        text = label
        tag = viewTag
        textSize = 14f
        includeFontPadding = false
        gravity = Gravity.CENTER
        isFocusable = false
        setOnClickListener { action() }
    }

    fun open(text: String, hint: String) {
        source.setText(text)
        source.hint = hint
        source.contentDescription = hint
        source.setSelection(source.length())
        isSearching = true
        visibility = VISIBLE
        source.requestFocus()
        editor.isActivated = source.isFocused
    }

    fun close() {
        connection.finishComposingText()
        isSearching = false
        source.clearFocus()
        editor.isActivated = false
        visibility = GONE
    }

    override fun onThemeChange(theme: Theme) {
        setBackgroundColor(theme.barColor)
        editor.applyTheme(theme)
        for (button in listOf(search, cancel)) {
            button.setTextColor(theme.keyTextColor)
            button.background = KeyboardUiStyle.buttonBackground(theme, dp(10f), theme.functionKeyBackgroundColor)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onThemeChange(ThemeManager.activeTheme)
        ThemeManager.addOnChangedListener(this)
    }

    override fun onDetachedFromWindow() {
        ThemeManager.removeOnChangedListener(this)
        super.onDetachedFromWindow()
    }
}
