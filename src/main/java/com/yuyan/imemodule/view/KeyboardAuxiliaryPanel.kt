package com.yuyan.imemodule.view

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.keyboard.terminal.TerminalInputDispatcher
import com.yuyan.imemodule.keyboard.terminal.TerminalModifiers
import com.yuyan.imemodule.keyboard.transform.TextTransformer
import com.yuyan.imemodule.keyboard.transform.TextTransformers
import com.yuyan.imemodule.keyboard.transform.TextTransformSession
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.service.ImeService
import splitties.dimensions.dp

class KeyboardAuxiliaryPanel(
    context: Context,
    private val service: ImeService,
    private val dispatcher: TerminalInputDispatcher,
    private val beforeAction: () -> Unit,
    private val onExitTerminal: () -> Unit,
    private val paste: (Boolean) -> Unit,
) : LinearLayout(context) {
    private val shortcuts = LinearLayout(context).apply { orientation = VERTICAL }
    private val editor = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val source = object : EditText(context) {
        override fun onCheckIsTextEditor() = false
    }.apply {
        hint = "输入原文，实时预览转换结果"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        showSoftInputOnFocus = false
        minLines = 1
        maxLines = 2
        textSize = 15f
        setPadding(dp(8), dp(4), dp(8), dp(4))
        contentDescription = "转换原文"
    }
    private val preview = TextView(context).apply {
        textSize = 14f
        maxLines = 2
        setPadding(dp(8), dp(3), dp(8), dp(3))
        contentDescription = "转换结果预览"
    }
    private val status = TextView(context).apply { textSize = 11f; setPadding(dp(8), 0, dp(8), dp(2)) }
    private val providerButton = key("花漾字") { showProviders(it) }
    private val confirmButton = key("确认") { beforeAction(); closeEditor(true) }
    private val modifierButtons = mutableMapOf<Int, TextView>()
    private var session: TextTransformSession? = null
    private var previewOnly = false
    private var confirmRequested = false
    private var observedHostComposition = false
    private var localConnection: InputConnection? = null
    private var provider: TextTransformer = TextTransformers.available().first { it.id == "flower:FlowerVine" }
    private val moreRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val moreScroll = ScrollView(context).apply { visibility = GONE; addView(moreRows) }

    val isEditing: Boolean get() = session != null
    val inputConnection: InputConnection? get() = if (isEditing) localConnection else null

    init {
        orientation = VERTICAL
        visibility = GONE
        addView(shortcuts, LayoutParams(-1, -2))
        val first = row(shortcuts)
        addKey(first, "ESC", KeyEvent.KEYCODE_ESCAPE)
        addKey(first, "TAB", KeyEvent.KEYCODE_TAB)
        addModifier(first, "CTRL", KeyEvent.KEYCODE_CTRL_LEFT)
        addModifier(first, "ALT", KeyEvent.KEYCODE_ALT_LEFT)
        addKey(first, "PGUP", KeyEvent.KEYCODE_PAGE_UP)
        addKey(first, "PGDN", KeyEvent.KEYCODE_PAGE_DOWN)
        first.addView(key("更多") { showMore(it) }, cell())
        shortcuts.addView(moreScroll, LayoutParams(-1, dp(132)))
        val functionRow = row(moreRows)
        addModifier(functionRow, "SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT)
        addKey(functionRow, "DEL", KeyEvent.KEYCODE_FORWARD_DEL, true)
        addKey(functionRow, "INS", KeyEvent.KEYCODE_INSERT)
        listOf("^C" to KeyEvent.KEYCODE_C, "^D" to KeyEvent.KEYCODE_D, "^Z" to KeyEvent.KEYCODE_Z, "^L" to KeyEvent.KEYCODE_L).forEach { (label, code) ->
            functionRow.addView(key(label) { beforeAction(); dispatcher.sendKey(code, KeyEvent.META_CTRL_ON) }, cell())
        }
        (0 until 2).forEach { group ->
            val functions = row(moreRows)
            repeat(6) { index -> addKey(functions, "F${group * 6 + index + 1}", KeyEvent.KEYCODE_F1 + group * 6 + index) }
        }
        listOf(listOf("/", "\\", "|", "~", "`", "-", "_"), listOf("{", "}", "[", "]", "<", ">", "="),
            listOf("\"", "'", ":", ";", "$", "&", "+"), listOf("!", "@", "#", "%", "^", "?", "*")).forEach { symbols ->
            val symbolsRow = row(moreRows)
            symbols.forEach { symbol -> symbolsRow.addView(key(symbol) { beforeAction(); dispatcher.sendText(symbol) }, cell()) }
        }
        addView(editor, LayoutParams(-1, -2))
        val actions = row(editor)
        actions.addView(providerButton, cell())
        actions.addView(key("清空") { source.text.clear() }, cell())
        actions.addView(key("取消") { beforeAction(); closeEditor(false) }, cell())
        actions.addView(confirmButton, cell())
        editor.addView(source, LayoutParams(-1, -2))
        editor.addView(preview, LayoutParams(-1, -2))
        editor.addView(status, LayoutParams(-1, -2))
        source.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                session?.update(s?.toString().orEmpty(), provider)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        source.setOnFocusChangeListener { _, focused ->
            if (!focused && isEditing) post { if (!source.hasFocus() && isEditing) closeEditor(!previewOnly) }
        }
        updateTheme()
    }

    fun refresh() {
        shortcuts.visibility = if (InputModeSwitcher.isTerminalMode) VISIBLE else GONE
        visibility = if (InputModeSwitcher.isTerminalMode || isEditing) VISIBLE else GONE
        modifierButtons.forEach { (code, button) ->
            val state = when (code) {
                KeyEvent.KEYCODE_CTRL_LEFT -> dispatcher.modifiers.ctrl
                KeyEvent.KEYCODE_ALT_LEFT -> dispatcher.modifiers.alt
                else -> dispatcher.modifiers.shift
            }
            val label = when (code) {
                KeyEvent.KEYCODE_CTRL_LEFT -> "CTRL"
                KeyEvent.KEYCODE_ALT_LEFT -> "ALT"
                else -> "SHIFT"
            }
            button.text = label + when (state) {
                TerminalModifiers.State.Off -> ""
                TerminalModifiers.State.Once -> "*"
                TerminalModifiers.State.Locked -> "锁"
            }
            button.isSelected = state != TerminalModifiers.State.Off
            button.contentDescription = "$label，${when (state) {
                TerminalModifiers.State.Off -> "未启用"
                TerminalModifiers.State.Once -> "下次按键生效"
                TerminalModifiers.State.Locked -> "已锁定"
            }}，长按切换锁定"
        }
        updateTheme()
    }

    fun openEditor() {
        if (isEditing) { closeEditor(true); return }
        beforeAction()
        dispatcher.modifiers.clear()
        source.setText("")
        val target = service.currentInputConnection ?: return
        previewOnly = dispatcher.isTermux || InputModeSwitcher.isTerminalMode
        confirmRequested = false
        observedHostComposition = false
        localConnection = object : BaseInputConnection(source, true) {
            override fun getEditable(): Editable = source.editableText
            override fun sendKeyEvent(event: KeyEvent): Boolean = source.dispatchKeyEvent(event)
            override fun performContextMenuAction(id: Int): Boolean = source.onTextContextMenuItem(id)
        }
        session = TextTransformSession(target, !previewOnly, { service.currentInputConnection === target }) { result, busy, failed ->
            preview.text = result
            confirmButton.isEnabled = !busy && !failed
            status.text = when {
                failed -> "转换失败，请修改原文或重新选择转换方式"
                busy -> "正在转换"
                previewOnly -> "终端内仅在此处预览，点击确认后发送"
                else -> "光标处为预输入，确认后落定，取消可撤销"
            }
            if (confirmRequested && !busy) {
                val active = session
                post {
                    if (session === active && confirmRequested) {
                        confirmRequested = false
                        if (!failed) closeEditor(true)
                    }
                }
            }
        }
        providerButton.text = provider.displayName
        editor.visibility = VISIBLE
        refresh()
        source.requestFocus()
        session?.update("", provider)
    }

    fun closeEditor(commit: Boolean, waitForResult: Boolean = true) {
        val previous = session ?: return
        if (commit && waitForResult && previous.isBusy) {
            confirmRequested = true
            return
        }
        confirmRequested = false
        session = null
        localConnection?.finishComposingText()
        localConnection = null
        previous.finish(commit)
        source.setText("")
        source.clearFocus()
        editor.visibility = GONE
        refresh()
    }

    fun finishInput() {
        closeEditor(!previewOnly, waitForResult = false)
        dispatcher.modifiers.clear()
        moreScroll.visibility = GONE
    }

    fun onHostSelectionChanged(candidatesEnd: Int) {
        val active = session ?: return
        if (candidatesEnd >= 0) observedHostComposition = true
        else if (observedHostComposition && active.composingActive && !active.updatingTarget) closeEditor(true)
    }

    fun updateTheme() {
        val theme = ThemeManager.activeTheme
        setBackgroundColor(theme.barColor)
        fun style(view: View) {
            if (view is TextView) {
                view.setTextColor(if (view.isSelected) theme.accentKeyTextColor else theme.keyTextColor)
                view.setHintTextColor(theme.keyTextColor)
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(5f)
                    setColor(if (view.isSelected) theme.accentKeyBackgroundColor else theme.keyBackgroundColor)
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) style(view.getChildAt(index))
        }
        style(this)
    }

    private fun showProviders(anchor: View) {
        val choices = TextTransformers.available()
        PopupMenu(context, anchor).apply {
            choices.forEachIndexed { index, item -> menu.add(0, index, index, item.displayName) }
            setOnMenuItemClickListener {
                provider = choices[it.itemId]
                providerButton.text = provider.displayName
                session?.update(source.text.toString(), provider)
                true
            }
        }.show()
    }

    private fun showMore(anchor: View) {
        PopupMenu(context, anchor).apply {
            listOf("代码符号与功能键", "花漾字与转换", "按文本粘贴", "清除修饰键", "退出终端模式").forEachIndexed { index, title ->
                menu.add(0, index, index, title)
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    0 -> moreScroll.visibility = if (moreScroll.visibility == VISIBLE) GONE else VISIBLE
                    1 -> openEditor()
                    2 -> { beforeAction(); paste(false) }
                    3 -> dispatcher.modifiers.clear()
                    4 -> onExitTerminal()
                }
                true
            }
        }.show()
    }

    private fun row(parent: LinearLayout) = LinearLayout(context).also { parent.addView(it, LayoutParams(-1, -2)) }

    private fun cell() = LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }

    private fun key(label: String, action: (View) -> Unit) = TextView(context).apply {
        text = label
        contentDescription = label
        textSize = 11f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = false
        setOnClickListener(action)
    }

    private fun addModifier(parent: LinearLayout, label: String, code: Int) {
        val button = key(label) { dispatcher.modifiers.toggle(code) }
        button.setOnLongClickListener { dispatcher.modifiers.toggle(code, lock = true); true }
        modifierButtons[code] = button
        parent.addView(button, cell())
    }

    private fun addKey(parent: LinearLayout, label: String, code: Int, repeat: Boolean = false) {
        val action = { beforeAction(); dispatcher.sendKey(code) }
        val button = key(label) { action() }
        if (repeat) {
            val repeater = object : Runnable {
                override fun run() { action(); button.postDelayed(this, 65) }
            }
            button.setOnLongClickListener { repeater.run(); true }
            button.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) button.removeCallbacks(repeater)
                false
            }
            button.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { button.removeCallbacks(repeater) }
            })
        }
        parent.addView(button, cell())
    }

    override fun onDetachedFromWindow() {
        finishInput()
        super.onDetachedFromWindow()
    }
}
