package com.yuyan.imemodule.view

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
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
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.terminal.TerminalInputDispatcher
import com.yuyan.imemodule.keyboard.terminal.TerminalModifiers
import com.yuyan.imemodule.keyboard.transform.TextTransformers
import com.yuyan.imemodule.keyboard.transform.TextTransformSession
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbStyleMode
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.singleton.EnvironmentSingleton
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
    val terminalKeys = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            syncTerminalKeySize(this)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply { orientation = VERTICAL }
    private var appliedTheme: Theme? = null
    private val inputStrip = ImeInlineEditor(context, "清空原文") { beforeAction() }
    private val source = inputStrip.source.apply {
        hint = "输入原文"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 1
        maxLines = 3
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
    private val providerButton = key("花漾字") { showProviders(it) }.apply { tag = "flower_provider" }
    private val actionButton = key("取消") { completeFlowerInput() }.apply { tag = "flower_action" }
    private val modifierButtons = mutableMapOf<Int, TextView>()
    private val functionKeyButton = key("F(n)") { toggleFunctionKeyInput() }.apply {
        tag = "terminal_function_key"
        setOnLongClickListener { cancelFunctionKeyInput(); true }
    }
    private var keyboardBeforeFunctionKey: KeyboardManager.KeyboardType? = null
    private var functionKeyDigits = ""
    val isChoosingFunctionKey get() = keyboardBeforeFunctionKey != null
    private val panelPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    private val moreRows = LinearLayout(context).apply { orientation = VERTICAL; visibility = if (panelPrefs.getBoolean("terminal_functions_expanded", false)) VISIBLE else GONE }
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
    val hasDraft get() = source.editableText.isNotEmpty() || isEditing && hasPendingInput()
    val inputConnection: InputConnection? get() = if (isEditing) sourceConnection else null
    private val provider get() = TextTransformers.available().first { it.id == "flower:${CustomConstant.flowerTypeface.name}" }

    init {
        orientation = VERTICAL
        isFocusableInTouchMode = true
        setPadding(dp(6), dp(2), dp(6), dp(2))
        val editorRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        providerButton.textSize = 14f
        actionButton.textSize = 14f
        providerButton.includeFontPadding = false
        actionButton.includeFontPadding = false
        providerButton.setPadding(dp(6), 0, dp(6), 0)
        editorRow.addView(providerButton, LayoutParams(dp(82), -1).apply { marginEnd = dp(6) })
        editorRow.addView(inputStrip, LayoutParams(0, -2, 1f))
        editorRow.addView(actionButton, LayoutParams(dp(52), -1).apply { marginStart = dp(6) })
        addView(editorRow, LayoutParams(-1, -2))
        addView(preview, LayoutParams(-1, -2))
        val first = row(terminalKeys)
        addKey(first, "ESC", KeyEvent.KEYCODE_ESCAPE)
        addKey(first, "TAB", KeyEvent.KEYCODE_TAB)
        addModifier(first, "CTRL", KeyEvent.KEYCODE_CTRL_LEFT)
        addModifier(first, "ALT", KeyEvent.KEYCODE_ALT_LEFT)
        addKey(first, "PGUP", KeyEvent.KEYCODE_PAGE_UP)
        addKey(first, "PGDN", KeyEvent.KEYCODE_PAGE_DOWN)
        first.addView(key(if (moreRows.visibility == VISIBLE) "收起" else "更多") { showMore(it) }.apply { isSelected = moreRows.visibility == VISIBLE }, cell())
        terminalKeys.addView(moreRows, LayoutParams(-1, -2))
        val functions = row(moreRows)
        addModifier(functions, "SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT)
        addKey(functions, "DEL", KeyEvent.KEYCODE_FORWARD_DEL, true)
        functions.addView(functionKeyButton, cell())
        listOf("^Z" to KeyEvent.KEYCODE_Z, "^C" to KeyEvent.KEYCODE_C, "^L" to KeyEvent.KEYCODE_L).forEach { (label, code) ->
            functions.addView(key(label) { cancelFunctionKeyInput(); beforeAction(); dispatcher.sendKey(code, KeyEvent.META_CTRL_ON) }, cell())
        }
        functions.addView(key("退出") { onExitTerminal() }, cell())
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
            inputStrip.isActivated = focused && isEditing
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rowHeight = (EnvironmentSingleton.instance.heightForCandidatesArea - paddingTop - paddingBottom).coerceAtLeast(dp(32))
        if (source.minimumHeight != rowHeight) source.minimumHeight = rowHeight
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun toggleFlowerMode() {
        if (isFlowerEnabled) {
            if (isEditing) beforeAction()
            val wasEditing = isEditing
            val finalText = if (wasEditing && !previewOnly) StringUtils.converted2FlowerTypeface(source.editableText.toString()) else null
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
        if (source.editableText.isEmpty() && !(isEditing && hasPendingInput())) {
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
        closeDraft(true, StringUtils.converted2FlowerTypeface(source.editableText.toString()))
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
        source.localEditing = true
        source.requestFocus()
        inputStrip.isActivated = source.isFocused
        session?.update(source.editableText.toString(), provider)
        onModeChanged()
    }

    private fun closeDraft(commit: Boolean, finalText: String? = null) {
        val previous = session ?: return
        session = null
        source.localEditing = false
        inputStrip.isActivated = false
        sourceConnection.finishComposingText()
        previous.finish(commit, finalText)
        source.editableText.clear()
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
        val finalText = if (isEditing && !previewOnly && source.editableText.isNotEmpty()) StringUtils.converted2FlowerTypeface(source.editableText.toString()) else null
        closeDraft(commit = !previewOnly, finalText = finalText)
        dispatcher.modifiers.clear()
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
        actionButton.isSelected = hasDraft
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
        if (appliedTheme === theme) return
        appliedTheme = theme
        setBackgroundColor(theme.barColor)
        terminalKeys.setBackgroundColor(theme.barColor)
        inputStrip.applyTheme(theme)
        providerButton.setTextColor(theme.keyTextColor)
        val indicator = context.getDrawable(R.drawable.ic_menu_arrow_down)?.mutate()?.apply {
            setBounds(0, 0, dp(12), dp(12))
            setTint(theme.keyTextColor)
        }
        providerButton.compoundDrawablePadding = dp(2)
        providerButton.setCompoundDrawablesRelative(null, null, indicator, null)
        providerButton.background = KeyboardUiStyle.buttonBackground(theme, dp(10f), theme.functionKeyBackgroundColor)
        actionButton.setTextColor(KeyboardUiStyle.buttonText(theme))
        actionButton.background = KeyboardUiStyle.buttonBackground(theme, dp(10f), theme.functionKeyBackgroundColor)
        preview.setTextColor(KeyboardUiStyle.secondaryText(theme))
        fun style(view: View) {
            if (view is TextView) {
                view.setTextColor(KeyboardUiStyle.buttonText(theme))
                view.background = KeyboardUiStyle.buttonBackground(theme, dp(8f))
            }
            if (view is ViewGroup) repeat(view.childCount) { style(view.getChildAt(it)) }
        }
        style(terminalKeys)
    }

    private fun showProviders(anchor: View) {
        val choices = FlowerTypefaceMode.entries.filter { it != FlowerTypefaceMode.Disabled }
        val providers = TextTransformers.available()
        val popup = PopupMenu(context, anchor).apply {
            choices.forEachIndexed { index, mode ->
                val name = providers.first { it.id == "flower:${mode.name}" }.displayName
                val sample = StringUtils.converted2FlowerTypeface("你好", mode)
                val label = SpannableString("$name  $sample").apply {
                    setSpan(RelativeSizeSpan(0.85f), name.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                menu.add(0, index, index, label).apply {
                    isCheckable = true
                    isChecked = mode == CustomConstant.flowerTypeface
                }
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener {
                beforeAction()
                CustomConstant.flowerTypeface = choices[it.itemId]
                session?.update(source.editableText.toString(), provider)
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
        cancelFunctionKeyInput()
        val expanded = moreRows.visibility != VISIBLE
        moreRows.visibility = if (expanded) VISIBLE else GONE
        panelPrefs.edit().putBoolean("terminal_functions_expanded", expanded).apply()
        (anchor as? TextView)?.text = if (expanded) "收起" else "更多"
        anchor.isSelected = expanded
    }
    private fun row(parent: LinearLayout) = LinearLayout(context).also { parent.addView(it, LayoutParams(-1, -2)) }
    private fun cell() = LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }

    /** 终端按键的行高与间距取自普通按键：每行占键盘区高度的四分之一，上下左右留白与普通按键一致。 */
    private fun syncTerminalKeySize(group: ViewGroup) {
        val env = EnvironmentSingleton.instance
        val marginY = (if (ThemeManager.prefs.skbStyleMode.getValue() == SkbStyleMode.Google && InputModeSwitcher.isQwert)
            env.keyYMargin * 1.5f else env.keyYMargin.toFloat()).toInt()
        val marginX = env.keyXMargin
        val height = (env.skbHeight / 4 - 2 * marginY).coerceAtLeast(dp(24))
        fun sync(parent: ViewGroup) {
            repeat(parent.childCount) {
                val child = parent.getChildAt(it)
                if (child is ViewGroup) sync(child)
                else (child.layoutParams as? LayoutParams)?.let { params ->
                    params.height = height
                    params.setMargins(marginX, marginY, marginX, marginY)
                }
            }
        }
        sync(group)
    }
    private fun key(label: String, action: (View) -> Unit) = TextView(context).apply {
        text = label
        contentDescription = label
        textSize = 11f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
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
