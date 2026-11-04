package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.yuyan.imemodule.R
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.view.preference.ManagedPreference
import splitties.dimensions.dp
import splitties.views.bottomPadding
import splitties.views.rightPadding
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * 软键盘View集装箱
 * 所有软键盘（输入、符号、设置等）父容器View。
 */
@SuppressLint("ViewConstructor")
open class BaseContainer(@JvmField var mContext: Context, @JvmField protected var inputView: InputView) : ConstraintLayout(mContext) {
    private lateinit var mRightPaddingKey: ManagedPreference.PInt
    private lateinit var mBottomPaddingKey: ManagedPreference.PInt
    private var heightAdjustmentView: View? = null

    /**
     * 更新软键盘布局
     */
    open fun updateSkbLayout(){
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = EnvironmentSingleton.instance.skbWidth
        val measuredHeight = EnvironmentSingleton.instance.skbHeight
        val widthMeasure = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val heightMeasure = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

    /**
     * 设置键盘高度
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setKeyboardHeight() {
        heightAdjustmentView?.let { it.bringToFront(); return }
        val adjustmentView = LayoutInflater.from(context).inflate(R.layout.layout_ime_keyboard_height_shadow, this, false)
        heightAdjustmentView = adjustmentView
        addView(adjustmentView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        adjustmentView.findViewById<View>(R.id.ll_keyboard_height_reset).setOnClickListener {
            applyKeyboardHeight(0.3f)
        }
        adjustmentView.findViewById<View>(R.id.ll_keyboard_height_sure).setOnClickListener {
            removeView(adjustmentView)
            heightAdjustmentView = null
        }
        adjustmentView.findViewById<View>(R.id.iv_keyboard_height_Top).setOnTouchListener { handle, event -> onModifyKeyboardHeightEvent(handle, event) }
        if(EnvironmentSingleton.instance.keyboardModeFloat){
            mBottomPaddingKey = if(EnvironmentSingleton.instance.isLandscape) AppPrefs.getInstance().internal.keyboardBottomPaddingLandscapeFloat
            else AppPrefs.getInstance().internal.keyboardBottomPaddingFloat
            mRightPaddingKey = if(EnvironmentSingleton.instance.isLandscape) AppPrefs.getInstance().internal.keyboardRightPaddingLandscapeFloat
            else AppPrefs.getInstance().internal.keyboardRightPaddingFloat
        } else {
            mBottomPaddingKey = AppPrefs.getInstance().internal.keyboardBottomPadding
            mRightPaddingKey = AppPrefs.getInstance().internal.keyboardRightPadding
        }
        adjustmentView.findViewById<View>(R.id.iv_keyboard_move).setOnTouchListener { _, event -> onMoveKeyboardEvent(event) }
    }

    private var resizeStartY = 0f
    private var resizeStartRatio = 0f
    private var resizeRatioPerPixel = 0f
    private var resizing = false

    private fun applyKeyboardHeight(ratio: Float) {
        val env = EnvironmentSingleton.instance
        val boundedRatio = ratio.coerceIn(0.15f, 0.75f)
        if (boundedRatio == env.keyBoardHeightRatio) return
        env.keyBoardHeightRatio = boundedRatio
        env.initData()
        KeyboardManager.instance.refreshKeyboardSize()
        heightAdjustmentView?.bringToFront()
    }

    private fun onModifyKeyboardHeightEvent(handle: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartY = event.rawY
                resizeStartRatio = EnvironmentSingleton.instance.keyBoardHeightRatio
                resizeRatioPerPixel = resizeStartRatio / EnvironmentSingleton.instance.skbHeight.coerceAtLeast(1)
                resizing = false
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = resizeStartY - event.rawY
                if (resizing || distance.absoluteValue > dp(10)) {
                    resizing = true
                    val ratio = resizeStartRatio + distance * resizeRatioPerPixel
                    applyKeyboardHeight((ratio * 100).roundToInt() / 100f)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!resizing) handle.performClick()
                resizing = false
            }
            MotionEvent.ACTION_CANCEL -> resizing = false
        }
        return true
    }

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var rightPaddingValue = 0  // 右侧边距
    private var bottomPaddingValue = 0  // 底部边距
    private var mSkbRootHeight = 0  // 键盘高度
    private var mSkbRootWidth = 0  // 键盘宽度
    private fun onMoveKeyboardEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                bottomPaddingValue = mBottomPaddingKey.getValue()
                rightPaddingValue = mRightPaddingKey.getValue()
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                mSkbRootHeight = inputView.mSkbRoot.height
                mSkbRootWidth = inputView.mSkbRoot.width
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx: Float = event.rawX - initialTouchX
                val dy: Float = event.rawY - initialTouchY
                if(dx.absoluteValue > 10) {
                    rightPaddingValue -= dx.toInt()
                    rightPaddingValue = if(rightPaddingValue < 0) 0
                    else if(rightPaddingValue > inputView.width - mSkbRootWidth) {
                        inputView.width - mSkbRootWidth
                    } else rightPaddingValue
                    initialTouchX = event.rawX
                    if(EnvironmentSingleton.instance.keyboardModeFloat) {
                        inputView.rightPadding = rightPaddingValue
                    } else {
                        inputView.mSkbRoot.rightPadding = rightPaddingValue
                    }
                }
                if(dy.absoluteValue > 10 ) {
                    // 上界不能用 inputView.height：其为 wrap_content，高度恒等于
                    // mSkbRoot.height + bottomPadding，会使上界恒等于当前值而无法上移
                    val maxBottom = (EnvironmentSingleton.instance.mScreenHeight - mSkbRootHeight).coerceAtLeast(0)
                    bottomPaddingValue = (bottomPaddingValue - dy.toInt()).coerceIn(0, maxBottom)
                    initialTouchY = event.rawY
                    if(EnvironmentSingleton.instance.keyboardModeFloat) {
                        inputView.bottomPadding = bottomPaddingValue
                    } else {
                        inputView.mSkbRoot.bottomPadding = bottomPaddingValue
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mRightPaddingKey.setValue(rightPaddingValue)
                mBottomPaddingKey.setValue(bottomPaddingValue)
            }
        }
        return false
    }
}
