package com.yuyan.imemodule.view

import android.content.Context
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.terminal.TerminalInputDispatcher
import com.yuyan.imemodule.keyboard.terminal.TerminalModifiers
import com.yuyan.imemodule.keyboard.transform.TextTransformers
import com.yuyan.imemodule.keyboard.transform.TextTransformSession
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.utils.toast
import splitties.dimensions.dp

class KeyboardAuxiliaryPanel(
    context: Context,
    private val service: ImeService,
    private val dispatcher: TerminalInputDispatcher,
    private val beforeAction: () -> Unit,
    private val beforeSourceEdit: () -> Unit,
    private val hasPendingInput: () -> Boolean,
    private val onInputRouteChanged: () -> Unit,
    private val onModeChanged: () -> Unit,
    private val onExitTerminal: () -> Unit,
) : LinearLayout(context) {
    val terminalKeys = LinearLayout(context).apply { orientation = VERTICAL }
    private val inputStrip = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val source = object : EditText(context) {
        private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onCheckIsTextEditor() = false
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val textLayout = layout ?: return
            if (!hasWindowFocus() && isEditing && isFocused && selectionStart >= 0 && selectionStart == selectionEnd) {
                val line = textLayout.getLineForOffset(selectionStart)
                val x = totalPaddingLeft + textLayout.getPrimaryHorizontal(selectionStart) - scrollX
                val top = totalPaddingTop + textLayout.getLineTop(line) - scrollY
                val bottom = totalPaddingTop + textLayout.getLineBottom(line) - scrollY
                caretPaint.color = currentTextColor
                canvas.drawRect(x, top.toFloat(), x + dp(2f), bottom.toFloat(), caretPaint)
            }
        }
    }.apply {
        hint = "输入原文"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        showSoftInputOnFocus = false
        minLines = 1
        maxLines = 3
        textSize = 16f
        setPadding(dp(8), dp(4), dp(4), dp(4))
        contentDescription = "花漾字原文"
        tag = "flower_source"
    }
    private val sourceConnection = object : BaseInputConnection(source, true) {
        override fun getEditable(): Editable = source.editableText
        override fun sendKeyEvent(event: KeyEvent): Boolean = source.dispatchKeyEvent(event)
        override fun performContextMenuAction(id: Int): Boolean = source.onTextContextMenuItem(id)
    }
    private val preview = TextView(context).apply {
        textSize = 14f
        maxLines = 2
        setPadding(dp(8), dp(3), dp(8), dp(3))
        contentDescription = "花漾字预览"
        visibility = GONE
    }
    private val providerButton = key("花漾字") { showProviders(it) }
    private val actionButton = key("取消") { completeFlowerInput() }.apply { tag = "flower_action" }
    private val clearButton = ImageView(context).apply {
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        contentDescription = "清空原文"
        isFocusable = false
        setPadding(dp(7), dp(7), dp(7), dp(7))
        setOnClickListener { beforeAction(); source.text.clear(); source.requestFocus() }
    }
    private val modifierButtons = mutableMapOf<Int, TextView>()
    private val functionKeyButton = key("F(n)") { toggleFunctionKeyInput() }.apply {
        tag = "terminal_function_key"
        setOnLongClickListener { cancelFunctionKeyInput(); true }
    }
    private var keyboardBeforeFunctionKey: KeyboardManager.KeyboardType? = null
    private var functionKeyDigits = ""
    val isChoosingFunctionKey get() = keyboardBeforeFunctionKey != null
    private val moreRows = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private var session: TextTransformSession? = null
    private var previewOnly = false
    private var observedHostComposition = false
    private var changingFocus = false
    private var sourceRequested = true
    private var internalPopupCount = 0
    var inputRouteRevision = 0L
        private set

    val isFlowerEnabled get() = CustomConstant.flowerTypeface != FlowerTypefaceMode.Disabled
    val isEditing get() = session != null && isFlowerEnabled
    val wantsSourceInput get() = isFlowerEnabled && sourceRequested
    val hasDraft get() = source.text.isNotEmpty() || isEditing && hasPendingInput()
    val inputConnection: InputConnection? get() = if (isEditing) sourceConnection else null
    private val provider get() = TextTransformers.available().first { it.id == "flower:${CustomConstant.flowerTypeface.name}" }

    init {
        orientation = VERTICAL
        isFocusableInTouchMode = true
        val editorRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        inputStrip.addView(providerButton, LayoutParams(dp(78), dp(44)))
        inputStrip.addView(source, LayoutParams(0, -2, 1f))
        inputStrip.addView(clearButton, LayoutParams(dp(34), dp(40)))
        editorRow.addView(inputStrip, LayoutParams(0, -2, 1f))
        editorRow.addView(actionButton, LayoutParams(dp(60), dp(48)))
        addView(editorRow, LayoutParams(-1, -2))
        addView(preview, LayoutParams(-1, -2))
        val first = row(terminalKeys)
        addKey(first, "TAB", KeyEvent.KEYCODE_TAB)
        addKey(first, "ESC", KeyEvent.KEYCODE_ESCAPE)
        addModifier(first, "CTRL", KeyEvent.KEYCODE_CTRL_LEFT)
        addModifier(first, "ALT", KeyEvent.KEYCODE_ALT_LEFT)
        addKey(first, "PGUP", KeyEvent.KEYCODE_PAGE_UP)
        addKey(first, "PGDN", KeyEvent.KEYCODE_PAGE_DOWN)
        first.addView(key("更多") { showMore(it) }, cell())
        terminalKeys.addView(moreRows, LayoutParams(-1, -2))
        val functions = row(moreRows)
        addModifier(functions, "SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT)
        addKey(functions, "DEL", KeyEvent.KEYCODE_FORWARD_DEL, true)
        functions.addView(functionKeyButton, cell())
        listOf("^C" to KeyEvent.KEYCODE_C, "^D" to KeyEvent.KEYCODE_D, "^Z" to KeyEvent.KEYCODE_Z, "^L" to KeyEvent.KEYCODE_L).forEach { (label, code) ->
            functions.addView(key(label) { cancelFunctionKeyInput(); beforeAction(); dispatcher.sendKey(code, KeyEvent.META_CTRL_ON) }, cell())
        }
        source.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isEditing) session?.update(s?.toString().orEmpty(), provider)
                refreshAction()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        source.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && isFlowerEnabled) {
                if (isEditing) beforeSourceEdit() else {
                    beforeAction()
                    onInputRouteChanged()
                    sourceRequested = true
                    activateSource()
                }
            }
            false
        }
        source.setOnFocusChangeListener { _, focused ->
            if (!changingFocus && isFlowerEnabled) {
                if (focused && !isEditing) activateSource()
            }
        }
        val editorActions = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                onPopupVisibilityChanged(true)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) = onPopupVisibilityChanged(false)
        }
        source.customSelectionActionModeCallback = editorActions
        source.customInsertionActionModeCallback = editorActions
        refresh()
    }

    fun toggleFlowerMode() {
        if (isFlowerEnabled) {
            if (isEditing) beforeAction()
            val wasEditing = isEditing
            val finalText = if (wasEditing && !previewOnly) StringUtils.converted2FlowerTypeface(source.text.toString()) else null
            closeDraft(commit = !previewOnly, finalText = finalText)
            CustomConstant.flowerTypeface = FlowerTypefaceMode.Disabled
            sourceRequested = false
            if (wasEditing) onInputRouteChanged()
        } else {
            beforeAction()
            val last = AppPrefs.getInstance().internal.lastFlowerTypefaceMode.getValue()
            CustomConstant.flowerTypeface = FlowerTypefaceMode.entries.firstOrNull { it.name == last && it != FlowerTypefaceMode.Disabled }
                ?: FlowerTypefaceMode.Mars
            sourceRequested = true
            onInputRouteChanged()
            activateSource()
        }
        onModeChanged()
        refresh()
    }

    fun completeFlowerInput() {
        if (!isFlowerEnabled) return
        if (source.text.isEmpty() && !(isEditing && hasPendingInput())) {
            val wasEditing = isEditing
            closeDraft(false)
            CustomConstant.flowerTypeface = FlowerTypefaceMode.Disabled
            sourceRequested = false
            if (wasEditing) onInputRouteChanged()
            onModeChanged()
            refresh()
            return
        }
        beforeAction()
        closeDraft(true, StringUtils.converted2FlowerTypeface(source.text.toString()))
        onInputRouteChanged()
        sourceRequested = true
        activateSource()
        refresh()
    }

    private fun activateSource() {
        if (!isFlowerEnabled) return
        if (session != null) { source.requestFocus(); return }
        val target = service.currentInputConnection ?: return
        previewOnly = dispatcher.isTermux || service.currentInputEditorInfo?.inputType == InputType.TYPE_NULL
        observedHostComposition = false
        session = TextTransformSession(target, !previewOnly, { service.currentInputConnection === target }) { result, _, failed ->
            preview.text = if (failed) "转换失败，请重试" else result
            preview.visibility = if (failed || previewOnly && result.isNotEmpty()) VISIBLE else GONE
            refreshAction()
        }
        source.requestFocus()
        session?.update(source.text.toString(), provider)
        onModeChanged()
    }

    private fun closeDraft(commit: Boolean, finalText: String? = null) {
        val previous = session ?: return
        session = null
        sourceConnection.finishComposingText()
        previous.finish(commit, finalText)
        source.text.clear()
        preview.text = ""
        preview.visibility = GONE
        observedHostComposition = false
        changingFocus = true
        source.clearFocus()
        requestFocus()
        changingFocus = false
    }

    fun activateRawInput(userInitiated: Boolean = false) {
        if (internalPopupCount > 0) return
        if (userInitiated && isFlowerEnabled) {
            sourceRequested = false
            inputRouteRevision++
        }
        if (!isEditing) return
        closeDraft(commit = !previewOnly)
        sourceRequested = false
        onInputRouteChanged()
        refresh()
    }

    fun onStartInput(restarting: Boolean) {
        if (!restarting) sourceRequested = true
        if (isFlowerEnabled && sourceRequested) activateSource()
        refresh()
    }

    fun finishInput() {
        cancelFunctionKeyInput()
        val finalText = if (isEditing && !previewOnly && source.text.isNotEmpty()) StringUtils.converted2FlowerTypeface(source.text.toString()) else null
        closeDraft(commit = !previewOnly, finalText = finalText)
        dispatcher.modifiers.clear()
        moreRows.visibility = GONE
        internalPopupCount = 0
        refresh()
    }

    fun onPopupVisibilityChanged(visible: Boolean) {
        internalPopupCount = (internalPopupCount + if (visible) 1 else -1).coerceAtLeast(0)
        if (!visible && internalPopupCount == 0 && isEditing) source.requestFocus()
    }

    fun onHostSelectionChanged(candidatesEnd: Int, selectionStart: Int, selectionEnd: Int) {
        val active = session ?: return
        if (!active.composingActive || active.updatingTarget) return
        if (candidatesEnd >= 0) {
            observedHostComposition = true
            if (selectionStart == candidatesEnd && selectionEnd == candidatesEnd) return
        } else if (!observedHostComposition) return
        closeDraft(commit = !previewOnly)
        onInputRouteChanged()
        if (sourceRequested) activateSource()
        refresh()
    }

    private fun refreshAction() {
        val label = if (hasDraft) "完成" else "取消"
        if (actionButton.text.toString() != label) {
            actionButton.text = label
            onModeChanged()
        }
        clearButton.visibility = if (source.text.isNotEmpty()) VISIBLE else GONE
    }

    fun refresh() {
        visibility = if (isFlowerEnabled) VISIBLE else GONE
        terminalKeys.visibility = if (InputModeSwitcher.isTerminalMode) VISIBLE else GONE
        providerButton.text = if (isFlowerEnabled) provider.displayName else "花漾字"
        providerButton.contentDescription = "花漾字样式：${providerButton.text}"
        refreshAction()
        functionKeyButton.text = "F(${functionKeyDigits.ifEmpty { "n" }})"
        functionKeyButton.isSelected = isChoosingFunctionKey
        functionKeyButton.contentDescription = if (isChoosingFunctionKey) {
            "功能键编号：${functionKeyDigits.ifEmpty { "未输入" }}，输入1到12，再次点击发送，长按取消"
        } else "输入功能键编号，范围1到12"
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
            button.contentDescription = "$label，${if (state == TerminalModifiers.State.Off) "未启用" else if (state == TerminalModifiers.State.Once) "单次" else "锁定"}，长按切换锁定"
        }
        updateTheme()
    }

    fun updateTheme() {
        val theme = ThemeManager.activeTheme
        setBackgroundColor(theme.barColor)
        terminalKeys.setBackgroundColor(theme.barColor)
        inputStrip.background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(theme.keyBackgroundColor) }
        source.background = null
        source.setTextColor(theme.keyTextColor)
        source.setHintTextColor(theme.keyTextColor)
        clearButton.drawable?.setTint(theme.keyTextColor)
        providerButton.setTextColor(theme.keyTextColor)
        val indicator = context.getDrawable(R.drawable.ic_menu_arrow_down)?.mutate()?.apply {
            setBounds(0, 0, dp(12), dp(12))
            setTint(theme.keyTextColor)
        }
        providerButton.compoundDrawablePadding = dp(2)
        providerButton.setCompoundDrawablesRelative(null, null, indicator, null)
        providerButton.background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(theme.functionKeyBackgroundColor) }
        actionButton.setTextColor(theme.keyTextColor)
        actionButton.background = null
        preview.setTextColor(theme.keyTextColor)
        fun style(view: View) {
            if (view is TextView) {
                view.setTextColor(if (view.isSelected) theme.accentKeyTextColor else theme.keyTextColor)
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(5f)
                    setColor(if (view.isSelected) theme.accentKeyBackgroundColor else theme.keyBackgroundColor)
                }
            }
            if (view is ViewGroup) repeat(view.childCount) { style(view.getChildAt(it)) }
        }
        style(terminalKeys)
    }

    private fun showProviders(anchor: View) {
        val choices = FlowerTypefaceMode.entries.filter { it != FlowerTypefaceMode.Disabled }
        val popup = PopupMenu(context, anchor).apply {
            choices.forEachIndexed { index, mode ->
                val name = TextTransformers.available().first { it.id == "flower:${mode.name}" }.displayName
                menu.add(0, index, index, name)
            }
            setOnMenuItemClickListener {
                beforeAction()
                CustomConstant.flowerTypeface = choices[it.itemId]
                session?.update(source.text.toString(), provider)
                onModeChanged()
                refresh()
                true
            }
        }
        onPopupVisibilityChanged(true)
        popup.setOnDismissListener { onPopupVisibilityChanged(false) }
        popup.show()
    }

    private fun showMore(anchor: View) {
        val popup = PopupMenu(context, anchor).apply {
            listOf("功能键", "清除修饰键", "退出终端模式").forEachIndexed { index, title -> menu.add(0, index, index, title) }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    0 -> {
                        cancelFunctionKeyInput()
                        moreRows.visibility = if (moreRows.visibility == VISIBLE) GONE else VISIBLE
                    }
                    1 -> dispatcher.modifiers.clear()
                    2 -> onExitTerminal()
                }
                true
            }
        }
        onPopupVisibilityChanged(true)
        popup.setOnDismissListener { onPopupVisibilityChanged(false) }
        popup.show()
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
        val action = {
            if (!consumeFunctionKeyInput(code)) { beforeAction(); dispatcher.sendKey(code) }
        }
        val button = key(label) { action() }
        if (repeat) {
            val repeater = object : Runnable { override fun run() { action(); button.postDelayed(this, 65) } }
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

    private fun toggleFunctionKeyInput() {
        if (!isChoosingFunctionKey) {
            beforeAction()
            keyboardBeforeFunctionKey = KeyboardManager.instance.currentKeyboardType ?: return
            // 编号输入只临时切换视图，保留语言、中文方案及修饰键状态。
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.NUMBER)
            refresh()
            return
        }
        val number = functionKeyDigits.toIntOrNull()
        if (functionKeyDigits.isNotEmpty() && (number == null || number !in 1..12)) {
            context.toast("功能键编号请输入1到12")
            return
        }
        cancelFunctionKeyInput()
        if (number != null) {
            beforeAction()
            dispatcher.sendKey(KeyEvent.KEYCODE_F1 + number - 1)
        }
    }

    fun consumeFunctionKeyInput(code: Int = KeyEvent.KEYCODE_UNKNOWN, text: String = ""): Boolean {
        if (!isChoosingFunctionKey) return false
        val digits = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (code - KeyEvent.KEYCODE_0).toString()
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> (code - KeyEvent.KEYCODE_NUMPAD_0).toString()
            else -> text
        }
        when {
            code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL -> functionKeyDigits = functionKeyDigits.dropLast(1)
            code == KeyEvent.KEYCODE_ESCAPE || code == InputModeSwitcher.USER_KEYCODE_RETURN -> cancelFunctionKeyInput()
            digits.isNotEmpty() && digits.all { it in '0'..'9' } -> {
                if (functionKeyDigits.length + digits.length <= 2) functionKeyDigits += digits
                else context.toast("功能键编号请输入1到12")
            }
            else -> { cancelFunctionKeyInput(); return false }
        }
        refresh()
        return true
    }

    fun cancelFunctionKeyInput() {
        val previous = keyboardBeforeFunctionKey ?: return
        keyboardBeforeFunctionKey = null
        functionKeyDigits = ""
        if (KeyboardManager.instance.currentKeyboardType == KeyboardManager.KeyboardType.NUMBER) {
            KeyboardManager.instance.switchKeyboard(previous)
        }
        refresh()
    }
    override fun onDetachedFromWindow() { finishInput(); super.onDetachedFromWindow() }
}
