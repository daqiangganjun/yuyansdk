package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.handwriting.HandwritingClient
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.keyboard.container.BaseContainer
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.HandwritingContainer
import com.yuyan.imemodule.keyboard.container.InputBaseContainer
import com.yuyan.imemodule.keyboard.container.InputViewParent
import com.yuyan.imemodule.keyboard.container.QwertyContainer
import com.yuyan.imemodule.keyboard.container.SettingsContainer
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.imemodule.keyboard.container.T9TextContainer
import com.yuyan.imemodule.prefs.AppPrefs

/**
 * 键盘显示管理类
 */
class KeyboardManager {
    enum class KeyboardType {
        T9, QWERTY, LX17, QWERTYABC, NUMBER, SYMBOL, SETTINGS, HANDWRITING, CANDIDATES, ClipBoard, TEXTEDIT
    }
    // 单例为进程级存活，若持有视图则 Service 销毁后整棵视图树无法回收，故用可空类型以便释放
    private var mInputView: InputView? = null
    private var mKeyboardRootView: InputViewParent? = null
    private val keyboards = HashMap<KeyboardType, BaseContainer?>()
    private lateinit var mCurrentKeyboardName: KeyboardType
    // 手写进程是否处于绑定状态。识别所需的 ML Kit 只在该进程加载，切离手写即解绑销毁
    private var handwritingBound = false
    var currentContainer: BaseContainer? = null
        private set

    fun setData(keyboardRootView: InputViewParent, inputView: InputView) {
        keyboards.clear() // TODO 清空缓存界面，发现调用 PinyinService.onCreateInputView时，原输入界面全部会失效。
        mKeyboardRootView = keyboardRootView
        mInputView = inputView
    }

    fun clearKeyboard() {
        keyboards.clear()
        mInputView?.let { it.initView(it.context) }
    }

    /**
     * 释放对视图与 Service 的引用。输入法进程常驻，Service 销毁后若不解除引用，
     * 整棵输入视图树连同其持有的 Service Context 都无法回收。
     */
    fun release() {
        syncHandwritingBinding(false)
        keyboards.clear()
        currentContainer = null
        mInputView = null
        mKeyboardRootView = null
    }

    /**
     * 手写键盘进出时绑定与解绑 :hw 进程。
     *
     * 解绑会终止该进程，ML Kit 及其连带的 GMS、WorkManager 占用一并释放；
     * 因此只要用户不在手写键盘上，这些开销就不存在于任何进程中。
     */
    private fun syncHandwritingBinding(needed: Boolean) {
        if (needed == handwritingBound) return
        handwritingBound = needed
        if (needed) HandwritingClient.acquire(Launcher.instance.context) else HandwritingClient.release()
    }

    fun switchKeyboard(layout: Int = InputModeSwitcher.skbLayout) {
        val keyboardName = when (layout) {
            0x1000 -> KeyboardType.QWERTY
            0x4000 -> KeyboardType.QWERTYABC
            0x3000 -> KeyboardType.HANDWRITING
            0x5000 -> KeyboardType.NUMBER
            0x6000 -> KeyboardType.LX17
            0x8000 -> KeyboardType.TEXTEDIT
            else -> KeyboardType.T9
        }
        switchKeyboard(keyboardName)
        mInputView?.updateCandidateBar()
    }

    fun switchKeyboard(keyboardName: KeyboardType) {
        val rootView = mKeyboardRootView ?: return
        val inputView = mInputView ?: return
        // 先于容器创建，使 HandwritingContainer 初始化时绑定已在建立
        syncHandwritingBinding(keyboardName == KeyboardType.HANDWRITING)
        var container = keyboards[keyboardName]
        if (container == null) {
            container = when (keyboardName) {
                KeyboardType.CANDIDATES ->  CandidatesContainer(Launcher.instance.context, inputView)
                KeyboardType.HANDWRITING -> HandwritingContainer(Launcher.instance.context, inputView)
                KeyboardType.NUMBER -> T9TextContainer(Launcher.instance.context, inputView, InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER)
                KeyboardType.QWERTY -> QwertyContainer(Launcher.instance.context, inputView, InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN)
                KeyboardType.SETTINGS -> SettingsContainer(Launcher.instance.context, inputView)
                KeyboardType.SYMBOL -> SymbolContainer(Launcher.instance.context, inputView)
                KeyboardType.QWERTYABC -> QwertyContainer(Launcher.instance.context, inputView, InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC)
                KeyboardType.LX17 -> T9TextContainer(Launcher.instance.context, inputView, InputModeSwitcher.MASK_SKB_LAYOUT_LX17)
                KeyboardType.ClipBoard -> ClipBoardContainer(Launcher.instance.context, inputView)
                KeyboardType.TEXTEDIT -> QwertyContainer(Launcher.instance.context, inputView, InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT)
                else ->  T9TextContainer(Launcher.instance.context, inputView, AppPrefs.getInstance().internal.inputDefaultMode.getValue() and InputModeSwitcher.MASK_SKB_LAYOUT)
            }
            container.updateSkbLayout()
            keyboards[keyboardName] = container
        }
        rootView.showView(container)
        mCurrentKeyboardName = keyboardName
        currentContainer = container
    }

    val isInputKeyboard: Boolean
        get() = currentContainer is InputBaseContainer

    companion object {
        private var mInstance: KeyboardManager? = null
        @JvmStatic
        val instance: KeyboardManager
            get() {
                if (null == mInstance) {
                    mInstance = KeyboardManager()
                }
                return mInstance!!
            }
    }
}