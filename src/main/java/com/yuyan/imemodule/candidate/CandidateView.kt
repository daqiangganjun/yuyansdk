package com.yuyan.imemodule.candidate

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.RelativeLayout
import android.widget.Toast
import android.widget.TextView
import android.graphics.Color
import androidx.core.text.isDigitsOnly
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yuyan.imemodule.R
import com.yuyan.imemodule.callback.CandidateViewListener
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.view.widget.LifecycleRelativeLayout
import com.yuyan.inputmethod.EngineRuntime
import com.yuyan.inputmethod.core.Kernel
import splitties.dimensions.dp
import splitties.views.bottomPadding
import splitties.views.leftPadding
import kotlin.math.max

/**
 * 物理键盘输入界面。
 * 包含拼音显示、候选词栏、键盘界面等。
 */
@SuppressLint("ViewConstructor")
class CandidateView(context: Context, private val service: ImeService) : LifecycleRelativeLayout(context) {

    private var mHorizontalCutoutWidth: Int = 0
    private var mFloatCandidateBarWidth: Int = 0
    private val appPrefs = getInstance()
    private val mChoiceNotifier = ChoiceNotifier()
    var mSkbRoot: RelativeLayout
    var mSkbCandidatesBarView: FloatCandidateBar

    init {
        initDisplayCutout(service)
        mFloatCandidateBarWidth = (if(instance.isLandscape)instance.mScreenHeight else instance.mScreenWidth) - dp(40)
        mSkbRoot = LayoutInflater.from(context).inflate(R.layout.sdk_candidate_container, this, false) as RelativeLayout
        addView(mSkbRoot)
        mSkbCandidatesBarView = mSkbRoot.findViewById(R.id.candidates_bar)
        DecodingInfo.candidatesLiveData.observe(this) {
            mSkbCandidatesBarView.showCandidates()
        }
        val engineStatus = TextView(context).apply {
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(255, 244, 207))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                if (EngineRuntime.status.value?.phase == EngineRuntime.Phase.Failed)
                    EngineRuntime.prepare(context, force = true)
            }
        }
        mSkbRoot.addView(engineStatus, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // 本视图在首个按键产生候选后才挂到窗口，观察者此时激活会收到未变的状态，不能借此清掉刚开始的组合
        var observedStatus: EngineRuntime.Status? = null
        EngineRuntime.status.observe(this) { state ->
            engineStatus.text = state.message
            engineStatus.visibility = if (state.phase == EngineRuntime.Phase.Ready) GONE else VISIBLE
            engineStatus.bringToFront()
            if (observedStatus != null && observedStatus != state) DecodingInfo.reset()
            observedStatus = state
        }
        initView()
    }

    private fun initDisplayCutout(service: ImeService) {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp: WindowManager.LayoutParams? = service.window.window?.attributes
                lp?.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                service.window.window?.setAttributes(lp)
            }
            val safeLeft = insets.getInsets(WindowInsetsCompat.Type.systemBars()).left
            val safeRight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).right
            val cutout = insets.displayCutout
            val displayCutoutWidth = if(cutout!=null) max(cutout.safeInsetLeft, cutout.safeInsetRight) else 0
            mHorizontalCutoutWidth = resources.displayMetrics.widthPixels - safeLeft - safeRight - displayCutoutWidth
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initView() {
        mSkbCandidatesBarView.initialize(mChoiceNotifier)
        mSkbRoot.layoutParams.width = mFloatCandidateBarWidth
        updateTheme()
    }

    fun updateTheme() {
        setBackgroundResource(android.R.color.transparent)
        val activeTheme = ThemeManager.activeTheme
        val keyTextColor = activeTheme.keyTextColor
        mSkbCandidatesBarView.updateTheme(keyTextColor)
    }

    fun updatePosition(anchor: FloatArray) {
        val bottom = instance.mScreenHeight - anchor[1].toInt()
        val diffHight = (instance.heightForCandidatesArea * 1.5).toInt()
        leftPadding = if(!instance.isLandscape) 0
         else if(mHorizontalCutoutWidth - anchor[0] > mFloatCandidateBarWidth)anchor[0].toInt() - dp(20)
        else mHorizontalCutoutWidth - mFloatCandidateBarWidth
        bottomPadding = if(bottom > diffHight) bottom - diffHight else bottom + instance.heightForCandidatesArea
    }

    // Ctrl+Space 的按下已被消费，抬起时据此切换语言，与先松开哪个键无关
    private var languageSwitchPending = false
    // 组合中按下 Esc 或返回键：抬起时取消组合，而非由系统收起候选窗口而留下看不见的组合
    private var cancelPending = false

    fun processKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        languageSwitchPending = keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed
        cancelPending = (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) && !DecodingInfo.isCandidatesEmpty
        return languageSwitchPending || cancelPending || isChineseInputKey(event)
    }

    fun processKeyUp(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_SPACE && languageSwitchPending) {
            languageSwitchPending = false
            switchLanguage()
            return true
        }
        if ((keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) && cancelPending) {
            cancelPending = false
            resetToIdleState()
            return true
        }
        if (!isChineseInputKey(event)) return false
        if (!EngineRuntime.isReady && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) return true
        InputModeSwitcher.resetCharCase()
        // 按下已被消费，未处理的按键（如 Tab）需原样转发，否则宿主收不到
        if (!processFunctionKeys(event) && !processInput(event)) sendKeyEvent(keyCode)
        return true
    }

    /**
     * 本界面只在中文模式下处理按键，Alt 组合键与 Ctrl、Shift 一样交给宿主。按下与抬起须按同一判定决定是否消费：
     * 只吞按下会丢键，按下已交给宿主而抬起再转发则重复输入
     */
    private fun isChineseInputKey(event: KeyEvent): Boolean {
        if (!InputModeSwitcher.isChinese || event.isAltPressed) return false
        val keyCode = event.keyCode
        return keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z || keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER || when (keyCode) {
                // KEYCODE_SEMICOLON在双拼中使用
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_SEMICOLON,
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_CLEAR -> true
                else -> false
            }
    }

    private fun switchLanguage() {
        if (!DecodingInfo.isCandidatesEmpty && !(DecodingInfo.isAssociate && !mSkbCandidatesBarView.isActiveCand())) chooseAndUpdate()
        InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_LANG)
        resetToIdleState()
        Toast.makeText(context, if(InputModeSwitcher.isEnglish)"语燕SelfOpt-英文" else "语燕SelfOpt-拼音", Toast.LENGTH_LONG).show()
    }

    private fun processFunctionKeys(event: KeyEvent): Boolean {
        return when (val keyCode = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                if (DecodingInfo.isCandidatesEmpty || (DecodingInfo.isAssociate && !mSkbCandidatesBarView.isActiveCand())) {
                    sendKeyEvent(keyCode)
                    resetToIdleState()
                } else {
                    chooseAndUpdate()
                }
                true
            }
            KeyEvent.KEYCODE_CLEAR -> {
                if (DecodingInfo.isCandidatesEmpty) sendKeyEvent(keyCode) else resetToIdleState()
                true
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) sendKeyEvent(keyCode)
                else commitDecInfoText(DecodingInfo.composingStrForCommit)
                resetToIdleState()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!DecodingInfo.isCandidatesEmpty) {
                    mSkbCandidatesBarView.updateActiveCandidateNo(keyCode)
                } else {
                    sendKeyEvent(keyCode)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!DecodingInfo.isCandidatesEmpty) {
                    mSkbCandidatesBarView.pageChange(keyCode)
                } else {
                    sendKeyEvent(keyCode)
                }
                true
            }
            else -> false
        }
    }

    private fun processInput(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()
        return when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) {
                    sendKeyEvent(keyCode)
                } else {
                    DecodingInfo.deleteAction()
                    updateCandidate()
                }
                true
            }
            label.isDigitsOnly() ->{
                val position = mSkbCandidatesBarView.getVisibleCandPosition(label.toInt())
                if (position >= 0) {
                    chooseAndUpdate(position)
                } else if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) {
                    // 没有可选的候选时数字照常输入；组合中按到无对应候选的数字则忽略，避免误上屏
                    if (DecodingInfo.isAssociate) resetToIdleState()
                    sendKeyEvent(keyCode)
                }
                true
            }
            Character.isLetter(keyChar) || keyCode == KeyEvent.KEYCODE_APOSTROPHE || keyCode == KeyEvent.KEYCODE_SEMICOLON -> {
                DecodingInfo.inputAction(event)
                if (DecodingInfo.isEngineFinish && Kernel.commitText.isNotEmpty()) {
                    commitDecInfoText(Kernel.commitText)
                    resetToIdleState()
                } else {
                    updateCandidate()
                }
                true
            }
            else -> {
                if (!DecodingInfo.isCandidatesEmpty && !DecodingInfo.isAssociate) chooseAndUpdate()
                false
            }
        }
    }

    fun resetToIdleState() {
        DecodingInfo.reset()
    }

    fun chooseAndUpdate(candId: Int = mSkbCandidatesBarView.getActiveCandNo()) {
        val choice = DecodingInfo.chooseDecodingCandidate(candId)
        if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) {
            commitDecInfoText(choice)
            resetToIdleState()
        }
    }

    private fun updateCandidate() {
        DecodingInfo.updateDecodingCandidate()
        if (DecodingInfo.isCandidatesEmpty) resetToIdleState()
    }

    inner class ChoiceNotifier internal constructor() : CandidateViewListener {
        override fun onClickChoice(choiceId: Int) {
            DevicesUtils.tryPlayKeyDown()
            DevicesUtils.tryVibrate(KeyboardManager.instance.currentContainer)
            chooseAndUpdate(choiceId)
        }

        override fun onClickMore(level: Int) {}

        override fun onClickMenu(skbMenuMode: SkbMenuMode){}

        override fun onClickClearCandidate() {}

        override fun onClickClearClipBoard() {}
    }

    fun requestHideSelf() = service.requestHideSelf(0)

    private fun sendKeyEvent(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> service.sendEnterKeyEvent()
            in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> {
                service.sendCombinationKeyEvents(keyCode)
            }
            else -> service.sendCombinationKeyEvents(keyCode)
        }
    }

    // 花漾字转换统一在 ImeService.commitText 中进行，此处不可重复转换
    private fun commitDecInfoText(resultText: String?) {
        resultText ?: return
        service.commitText(resultText)
        if (InputModeSwitcher.isEnglish){
            service.finishComposingText()
            if(appPrefs.input.abcSpaceAuto.getValue()) service.commitText(" ")
            resetToIdleState()
        }
    }

    fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        if(editorInfo != null)InputModeSwitcher.requestInputWithSkb(editorInfo)
        if (!restarting) resetToIdleState()
    }

}
