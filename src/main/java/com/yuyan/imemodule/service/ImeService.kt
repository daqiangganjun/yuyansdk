package com.yuyan.imemodule.service

import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Region
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import com.yuyan.imemodule.candidate.CandidateView
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager.OnThemeChangeListener
import com.yuyan.imemodule.data.theme.ThemeManager.addOnChangedListener
import com.yuyan.imemodule.data.theme.ThemeManager.onSystemDarkModeChange
import com.yuyan.imemodule.data.theme.ThemeManager.removeOnChangedListener
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.speech.VoiceInputController
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.view.popup.PopupComponent
import com.yuyan.imemodule.utils.isDarkMode
import com.yuyan.imemodule.view.preference.ManagedPreference
import com.yuyan.inputmethod.core.CandidateListItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag

/**
 * Main class of the Pinyin input method. 输入法服务
 */
class ImeService : InputMethodService() {
    private var voiceInput: VoiceInputController? = null

    fun startVoiceInput(coverBottomInWindow: Int = -1) {
        val view = mInputView ?: return
        if (voiceInput == null) {
            voiceInput = VoiceInputController(this, view,
                prepareInput = { view.auxiliaryPanel.cancelFunctionKeyInput(); view.resetToIdleState() },
                commitResult = view::commitExternalText,
                anchor = view.mSkbRoot)
        }
        voiceInput?.start(coverBottomInWindow)
    }

    fun finishVoiceInput() { voiceInput?.finish() }
    fun cancelVoiceInput() { voiceInput?.cancel() }
    fun setVoiceCancelArmed(armed: Boolean) { voiceInput?.setCancelArmed(armed) }
    private var isHardwareKeyboard = false
    private var isSoftKeyboard = false
    // 视图由系统按需创建，键盘事件可能早于视图创建到达，故使用可空类型统一防护
    private var mInputView: InputView? = null
    val activeInputConnection: InputConnection?
        get() = mInputView?.localInputConnection ?: currentInputConnection
    private var mCandidateView: CandidateView? = null
    // 当前交给输入法窗口的输入视图：软键盘为 InputView，物理键盘为 CandidateView
    private var modeInputView: View? = null
    private var watchedInputWindow: Window? = null
    private val hardwareCandidatesObserver = Observer<List<CandidateListItem>> { syncHardwareCandidatesWindow() }
    private val onThemeChangeListener = OnThemeChangeListener { _: Theme? -> if (isHardwareKeyboard) mCandidateView?.updateTheme() else mInputView?.updateTheme()}
    private val clipboardUpdateContent = getInstance().internal.clipboardUpdateContent
    private val clipboardUpdateContentListener = ManagedPreference.OnChangeListener<String> { _, value ->
        if(isSoftKeyboard && getInstance().clipboard.clipboardSuggestion.getValue()){
            if(value.isNotBlank()) {
                if(KeyboardManager.instance.currentContainer is ClipBoardContainer
                    && (KeyboardManager.instance.currentContainer as ClipBoardContainer).getMenuMode() == SkbMenuMode.ClipBoard ){
                    (KeyboardManager.instance.currentContainer as ClipBoardContainer).showClipBoardView(SkbMenuMode.ClipBoard)
                } else {
                    mInputView?.showSymbols(arrayOf(value))
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        InputModeSwitcher.reset()
        addOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.registerOnChangeListener(clipboardUpdateContentListener)
        DecodingInfo.candidatesLiveData.observeForever(hardwareCandidatesObserver)
        suppressDefaultFocusHighlight()
    }

    /**
     * 宿主让屏幕退出触摸模式后（如 Termius 处理退格键），系统会把焦点交给输入法窗口中的首个可聚焦控件，
     * 并为其绘制默认焦点框。输入法窗口从不获得窗口焦点，无法自行回到触摸模式，焦点框会一直留着。
     * 全局焦点变化先于 onFocusChanged 分发，在此关闭默认焦点框即可避免它被创建。
     */
    private fun suppressDefaultFocusHighlight() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        window?.window?.decorView?.viewTreeObserver?.addOnGlobalFocusChangeListener { _, newFocus ->
            newFocus?.defaultFocusHighlightEnabled = false
        }
    }

    override fun onCreateInputView(): View {
        watchOutsideTouches()
        return createModeInputView()
    }

    /**
     * 按当前模式取输入视图。视图从窗口移除后生命周期即为 DESTROYED、观察者已被清空，
     * 因此切换模式或系统重建窗口时都要新建，不能复用旧实例。
     */
    private fun createModeInputView(): View = if (isHardwareKeyboard) hardwareCandidateView() else {
        mInputView?.finishAuxiliaryInput()
        voiceInput?.cancel()
        voiceInput = null
        InputView(baseContext, this).also { mInputView = it }
    }

    /**
     * 物理键盘的候选界面。窗口要等出现候选才显示，而产生候选的按键须先经它处理，
     * 故进入物理键盘模式即创建，尚未挂到窗口上的实例留待系统取输入视图时交出。
     */
    private fun hardwareCandidateView(): CandidateView =
        mCandidateView?.takeIf { it.lifecycle.currentState != Lifecycle.State.DESTROYED }
            ?: CandidateView(baseContext, this).also {
                mCandidateView = it
                it.onStartInput(currentInputEditorInfo, false)
            }

    private fun watchOutsideTouches() {
        val inputWindow = window?.window ?: return
        if (watchedInputWindow === inputWindow) return
        val original = inputWindow.callback ?: return
        inputWindow.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        inputWindow.callback = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) mInputView?.onExternalEditorTouch()
                return original.dispatchTouchEvent(event)
            }
        }
        watchedInputWindow = inputWindow
    }

    /**
     * 与按键路由共用 handleHardwareKeyboard 的判定，避免显示软键盘却按物理键盘分发按键。
     * 物理键盘模式只在有候选时显示候选栏：Android 11 起窗口可见性由宿主的 IME insets 控制，
     * 仅靠 setCandidatesViewShown 的候选窗口不会被绘制，故改作输入视图并按需显式请求显示。
     */
    override fun onEvaluateInputViewShown(): Boolean = isSoftKeyboard || isHardwareKeyboard && !DecodingInfo.isCandidatesEmpty

    private fun syncHardwareCandidatesWindow() {
        if (!isHardwareKeyboard || currentInputConnection == null) return
        val wanted = !DecodingInfo.isCandidatesEmpty
        if (wanted == isInputViewShown) return
        if (!wanted) requestHideSelf(0)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) requestShowSelf(0)
        else window?.window?.attributes?.token?.let {
            @Suppress("DEPRECATION")
            getSystemService(InputMethodManager::class.java).showSoftInputFromInputMethod(it, 0)
        }
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        cancelVoiceInput()
        val view = mInputView
        val resumeClipboardSearch = restarting && view?.isClipboardSearchActive == true
        val resumeFlowerInput = restarting && !resumeClipboardSearch && view?.wantsFlowerSourceInput == true
        val routeRevision = view?.flowerInputRouteRevision ?: 0L
        if (resumeFlowerInput) view?.resetToIdleState()
        mInputView?.finishAuxiliaryInput(preserveClipboardSearch = resumeClipboardSearch)
        YuyanEmojiCompat.setEditorInfo(editorInfo)
        handleHardwareKeyboard()
        if (isHardwareKeyboard)mCandidateView?.onStartInput(editorInfo, restarting)
        super.onStartInput(editorInfo, restarting)
        if (resumeFlowerInput && view != null) {
            val target = currentInputConnection
            view.post {
                if (mInputView === view && isInputViewShown && currentInputConnection === target) {
                    view.resumeFlowerInputAfterRestart(routeRevision)
                }
            }
        }
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        if (isSoftKeyboard)mInputView?.onStartInputView(editorInfo, restarting)
        super.onStartInputView(editorInfo, restarting)
    }

    /**
     * 一次输入会话结束。输入法进程常驻，此处不释放的数据会一直保留到下次输入，
     * 其中残留的编辑框内容还会跨应用留存。
     */
    override fun onFinishInput() {
        cancelVoiceInput()
        // 离开输入框时落定尚未确定的手写文本，否则其状态会残留到下一个输入框
        mInputView?.finishHandwritingComposing()
        mInputView?.finishAuxiliaryInput()
        DecodingInfo.reset()
        YuyanEmojiCompat.setEditorInfo(null)
        mInputView?.clearTextBeforeCursors()
        super.onFinishInput()
    }

    override fun onDestroy() {
        mInputView?.finishAuxiliaryInput()
        voiceInput?.cancel()
        voiceInput = null
        super.onDestroy()
        removeOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.unregisterOnChangeListener(clipboardUpdateContentListener)
        DecodingInfo.candidatesLiveData.removeObserver(hardwareCandidatesObserver)
        serviceScope.cancel()
        // 单例持有整棵输入视图树与本 Service，不解除引用则进程常驻期间无法回收
        KeyboardManager.instance.release()
        PopupComponent.get().release()
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        mInputView = null
        mCandidateView = null
        modeInputView = null
    }

    /**
     * 横竖屏切换
     */
    // 绑定到 Service 的作用域，避免每次配置变更都新建一个无人取消的 scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var reloadKeyboardJob: Job? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        cancelVoiceInput()
        super.onConfigurationChanged(newConfig)
        handleHardwareKeyboard()
        // 连续旋转时只保留最后一次重建，否则会叠加多次全量重建
        reloadKeyboardJob?.cancel()
        reloadKeyboardJob = serviceScope.launch {
            delay(200) //延时，解决获取屏幕尺寸不准确。
            EnvironmentSingleton.instance.initData(baseContext)
            if (isSoftKeyboard) {
                KeyboardLoaderUtil.instance.clearKeyboardMap()
                KeyboardManager.instance.clearKeyboard()
                KeyboardManager.instance.switchKeyboard()
            } else if(isHardwareKeyboard){
                mCandidateView?.initView()
            }
        }
        onSystemDarkModeChange(newConfig.isDarkMode())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 0 != event.getRepeatCount()  长按物理按键或 Shift/Meta/Ctrl的组合按键时，交由系统处理;有个特殊组合键：Ctrl+SPACE切换语言
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyDown(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyDown(keyCode, event)
        else if (isSoftKeyboard) mInputView?.processKeyDown(keyCode, event) == true || super.onKeyDown(keyCode, event)
        else if (isHardwareKeyboard) mCandidateView?.processKeyDown(keyCode, event) == true || super.onKeyDown(keyCode, event)
        else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyUp(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyUp(keyCode, event)
        else if (isSoftKeyboard) mInputView?.processKeyUp(event) == true || super.onKeyUp(keyCode, event)
        else if (isHardwareKeyboard) mCandidateView?.processKeyUp(event) == true || super.onKeyUp(keyCode, event)
        else super.onKeyUp(keyCode, event)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        modeInputView = view
        // 候选栏按内容高度停靠在窗口底部，靠底部留白跟随光标，不能撑满窗口
        if (view !is InputView) return
        val layoutParams = view.layoutParams
        if (layoutParams != null && layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.setLayoutParams(layoutParams)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false //修复横屏之后输入框遮挡问题


    // 复用同一个矩形，onComputeInsets 每帧都会被调用
    private val composingBubbleRect = Rect()

    private fun unionComposingBubble(region: Region, inputView: InputView) {
        if (inputView.composingBubbleBounds(composingBubbleRect)) region.union(composingBubbleRect)
    }

    override fun onComputeInsets(outInsets: Insets) {
        val inputView = mInputView
        val candidateView = mCandidateView
        val (x, y) = if (isSoftKeyboard && inputView != null) intArrayOf(0, 0).also {if(inputView.isAddPhrases) inputView.mAddPhrasesLayout.getLocationInWindow(it) else inputView.mSkbRoot.getLocationInWindow(it) }
        else if (isHardwareKeyboard && candidateView != null) intArrayOf(0, 0).also {candidateView.mSkbRoot.getLocationInWindow(it) }
        else intArrayOf(0, 0)
        outInsets.apply {
            if(isSoftKeyboard || !isHardwareKeyboard){
                if(EnvironmentSingleton.instance.keyboardModeFloat && inputView != null) {
                    contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                    visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    touchableRegion.set(x, y, x + inputView.mSkbRoot.width, y + inputView.mSkbRoot.height)
                    unionComposingBubble(touchableRegion, inputView)
                } else {
                    contentTopInsets = y
                    visibleTopInsets = y
                    // 拼音气泡浮在键盘上方，不在 contentTopInsets 之内；要让它可点，
                    // 就得改用显式区域，把键盘原有的可触摸范围与气泡合并。区域下边与右边
                    // 取屏幕长边，超出窗口的部分由系统裁掉，比按视图尺寸推算更不易算错
                    if (inputView != null && inputView.composingBubbleBounds(composingBubbleRect)) {
                        val extent = maxOf(EnvironmentSingleton.instance.mScreenWidth, EnvironmentSingleton.instance.mScreenHeight)
                        touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                        touchableRegion.set(0, y, extent, extent)
                        touchableRegion.union(composingBubbleRect)
                    } else {
                        touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
                        touchableRegion.setEmpty()
                    }
                }
            } else {
                contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                if (candidateView != null) touchableRegion.set(x, y, x + candidateView.mSkbRoot.width, y + candidateView.mSkbRoot.height)
                else touchableRegion.setEmpty()
            }
        }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) cancelVoiceInput()
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (isSoftKeyboard) mInputView?.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd)
    }

    private val cursorAnchorPosition = FloatArray(2)
    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (!isHardwareKeyboard || cursorAnchorInfo == null) return
        cursorAnchorPosition[0] = cursorAnchorInfo.insertionMarkerHorizontal
        cursorAnchorPosition[1] = cursorAnchorInfo.insertionMarkerBottom
        val matrix = cursorAnchorInfo.getMatrix()
        if (matrix != null) {
            matrix.mapPoints(cursorAnchorPosition)
        }
        // 宿主未提供插入点时坐标为 NaN，按 0 处理会把候选栏推到屏幕顶部，保持原位置
        if (cursorAnchorPosition[0].isNaN() || cursorAnchorPosition[1].isNaN()) return
        mCandidateView?.updatePosition(cursorAnchorPosition)
    }

    override fun onWindowShown() {
        if (isSoftKeyboard) mInputView?.onWindowShown()
        super.onWindowShown()
        if (isSoftKeyboard) {
            com.yuyan.imemodule.data.transfer.TransferCoordinator.onKeyboardShown(this)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val coordinator = com.yuyan.imemodule.data.transfer.TransferCoordinator
                val details = coordinator.updateDetails(this)
                val token = window?.window?.decorView?.windowToken
                if (coordinator.keyboardVisible && com.yuyan.inputmethod.EngineRuntime.isReady && details != null && token != null) {
                    val dialog = android.app.AlertDialog.Builder(this).setTitle("词库已更新")
                        .setMessage(details).setPositiveButton("知道了", null).create()
                    dialog.window?.apply {
                        attributes = attributes.apply { this.token = token }
                        setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
                        addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    }
                    runCatching { dialog.show(); coordinator.acknowledgeDetails(this) }
                }
            }, 300)
        }
    }

    override fun onWindowHidden() {
        com.yuyan.imemodule.data.transfer.TransferCoordinator.onKeyboardHidden(this)
        cancelVoiceInput()
        if(isSoftKeyboard) mInputView?.onWindowHidden()
        super.onWindowHidden()
    }

    /**
     * 模拟Enter按键点击
     */
    fun sendEnterKeyEvent() {
        if (mInputView?.isTransformingText == true) {
            activeInputConnection?.commitText("\n", 1)
            return
        }
        // 回车动作取自宿主 EditorInfo，须作用于宿主连接而非输入法内部的本地编辑框
        val inputConnection = currentInputConnection ?: return
        YuyanEmojiCompat.mEditorInfo?.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL || imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                inputConnection.performEditorAction(actionId)
            } else when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> inputConnection.performEditorAction(action)
            }
        }
    }

    fun sendCombinationKeyEvents(keyEventCode: Int, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        activeInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        activeInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    /**
     * 向输入框提交预选词
     */
    fun setComposingText(text: CharSequence) {
        activeInputConnection?.setComposingText(text, 1)
    }


    /**
     * 结束提交预选词
     */
    fun finishComposingText() {
        // onFinishInput 等时机下连接可能已断开
        activeInputConnection?.finishComposingText()
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String) {
        activeInputConnection?.commitText(text, 1)
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String, newCursorPosition: Int) {
        activeInputConnection?.commitText(text, newCursorPosition)
    }

    fun getTextBeforeCursor(length:Int) : String {
        return activeInputConnection?.getTextBeforeCursor(length, 0)?.toString().orEmpty()
    }

    fun getTextAfterCursor(length:Int) : String {
        return activeInputConnection?.getTextAfterCursor(length, 0)?.toString() ?: ""
    }

    fun commitTextEditMenu(id:Int) {
        activeInputConnection?.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction:Int) {
        activeInputConnection?.performEditorAction(editorAction)
    }

    fun deleteSurroundingText(length:Int) {
        activeInputConnection?.deleteSurroundingText(length, 0)
    }

    fun setSelection(start: Int, end: Int) {
        activeInputConnection?.setSelection(start, end)
    }

    /**
     * 输入框中全部文本的长度，取不到时为 -1。
     *
     * 用于直接设选区实现全选，绕开会惊动应用选择态的 performContextMenuAction。
     * 密码框一类会拒绝提供上下文，此时返回 -1 由调用方回落。
     */
    fun getAllTextLength(): Int = runCatching {
        activeInputConnection?.getExtractedText(ExtractedTextRequest(), 0)?.text?.length ?: -1
    }.getOrDefault(-1)

    /**
     * 判定软键盘或物理键盘模式。基类判定兼顾系统「使用实体键盘时显示屏幕键盘」开关与键盘盖合状态，
     * 读取的是当前 Resources 配置；onConfigurationChanged 回调时它已更新为新配置。
     */
    fun handleHardwareKeyboard() {
        val wasHardwareKeyboard = isHardwareKeyboard
        isSoftKeyboard = getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue() || super.onEvaluateInputViewShown()
        isHardwareKeyboard = !isSoftKeyboard
        InputModeSwitcher.setHardwareKeyboard(isHardwareKeyboard)
        if (isHardwareKeyboard) hardwareCandidateView()
        // 窗口已建过输入视图时才需替换；尚未创建的由系统在需要时经 onCreateInputView 按模式创建
        if (wasHardwareKeyboard != isHardwareKeyboard && modeInputView != null) {
            setInputView(createModeInputView())
            syncHardwareCandidatesWindow()
        }
        // 未绑定输入连接时（如息屏后切换深色模式）为 null
        currentInputConnection?.requestCursorUpdates(if (isHardwareKeyboard)
            InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR else 0)
    }

}
