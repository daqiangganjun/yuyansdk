package com.yuyan.imemodule.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.KeyEvent
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.data.theme.ThemeManager.prefs
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyToggle
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import com.yuyan.imemodule.prefs.behavior.SkbStyleMode

/**
 * 软件盘视图
 */
open class TextKeyboard(context: Context?) : BaseKeyboardView(context){
    private var mKeyboardChanged = false
    private var mBuffer: Bitmap? = null
    private var mCanvas: Canvas? = null
    private var mNormalKeyTextSize = 0   //正常按键的文本大小
    private var mNormalKeyTextSizeSmall = 0  //正常按键的文本大小(小值)
    private val mPaint: Paint = Paint()   //绘制按键的画笔
    private var isKeyBorder = false // 启用按键边框
    protected lateinit var mActiveTheme: Theme
    private var keyRadius = 0
    private var keyboardFontBold = false
    private var keyboardSymbol = false
    private var keyboardMnemonic = false
    private var skbStyleMode: SkbStyleMode = prefs.skbStyleMode.getValue()

    // 按键背景复用同一实例，避免在绘制循环中为每个按键新建 Drawable
    private val mKeyBackground = GradientDrawable()

    /**
     * 构造方法
     */
    init {
        mPaint.isAntiAlias = true
        keyboardFontBold = prefs.keyboardFontBold.getValue()
        keyboardSymbol = prefs.keyboardSymbol.getValue()
        keyboardMnemonic = AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.getValue()
    }

    /**
     * 设置键盘实体
     *
     * @param softSkb 键盘
     */
    override fun setSoftKeyboard(softSkb: SoftKeyboard) {
        super.setSoftKeyboard(softSkb)
        isKeyBorder = prefs.keyBorder.getValue()
        keyRadius = prefs.keyRadius.getValue()
        mActiveTheme = activeTheme
        mPaint.color = mActiveTheme.keyTextColor
        mKeyboardChanged = true
        invalidateView()
    }

    /**
     * 刷新按键状态
     */
    fun updateStates() {
        val enterKey = mSoftKeyboard?.getKeyByCode(KeyEvent.KEYCODE_ENTER) as? SoftKeyToggle
        val enterChanged = enterKey?.enableToggleState( if(mService!!.isAddPhrases)4 else InputModeSwitcher.mToggleStates.imeAction) == true
        val shiftKey = mSoftKeyboard?.getKeyByCode(KeyEvent.KEYCODE_SHIFT_LEFT) as? SoftKeyToggle
        val isEnglishCell = AppPrefs.getInstance().input.abcSearchEnglishCell.getValue()
        val shiftChanged = shiftKey?.enableToggleState(InputModeSwitcher.mToggleStates.modifiers + if(isEnglishCell) 3 else 0) == true
        // 无论哪种情况都不必重新测量整棵视图树：onMeasure 的结果只取决于 EnvironmentSingleton。
        // Shift 会切换全部字母键的大小写显示，故需整块重绘；仅回车键变化时只重绘该键。
        when {
            shiftChanged -> invalidateKey()
            enterChanged -> invalidateKeys(enterKey, null)
        }
    }

    /**
     * 重置主题
     */
    open fun setTheme(theme: Theme) {
        isKeyBorder = prefs.keyBorder.getValue()
        keyRadius = prefs.keyRadius.getValue()
        mActiveTheme = theme
        mPaint.color = mActiveTheme.keyTextColor
        invalidateView()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var measuredWidth = 0
        var measuredHeight = 0
        if (null != mSoftKeyboard) {
            measuredWidth = instance.skbWidth +  paddingLeft + paddingRight
            measuredHeight = instance.skbHeight + paddingTop + paddingBottom
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    private fun invalidateView() {
        requestLayout()
        invalidateKey()
    }

    public override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mBuffer = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mDrawPending || mBuffer == null || mKeyboardChanged) {
            onBufferDraw()
        }
        canvas.drawBitmap(mBuffer!!, 0f, 0f, null)
    }

    override fun onBufferDraw() {
        if (mBuffer == null || mKeyboardChanged) {
            if (mBuffer == null || mBuffer!!.width != width || mBuffer!!.height != height) {
                val width = max(1.0, width.toDouble()).toInt()
                val height = max(1.0, height.toDouble()).toInt()
                mBuffer = createBitmap(width, height)
                mCanvas = Canvas(mBuffer!!)
            }
            // 缓冲区重建后内容全部失效，直接标脏而不再触发一次 invalidate
            mDirtyRect.union(0, 0, width, height)
            mKeyboardChanged = false
        }
        if (mSoftKeyboard == null) return
        if (mDirtyRect.isEmpty) mDirtyRect.union(0, 0, width, height)
        mCanvas!!.withSave {
            val canvas = mCanvas
            canvas?.clipRect(mDirtyRect)
            canvas?.drawColor(0x00000000, PorterDuff.Mode.CLEAR)
            val env = instance
            mNormalKeyTextSize = env.keyTextSize
            mNormalKeyTextSizeSmall = env.keyTextSmallSize
            val keyXMargin = mSoftKeyboard!!.keyXMargin
            val keyYMargin = if(skbStyleMode == SkbStyleMode.Google && InputModeSwitcher.isQwert) mSoftKeyboard!!.keyYMargin * 1.5
                else mSoftKeyboard!!.keyYMargin
            for (softKeys in mSoftKeyboard!!.mKeyRows) {
                for (softKey in softKeys) {
                    // 跳过完全落在脏区之外的按键
                    if (softKey.mRight < mDirtyRect.left || softKey.mLeft > mDirtyRect.right ||
                        softKey.mBottom < mDirtyRect.top || softKey.mTop > mDirtyRect.bottom) continue
                    canvas?.let { drawSoftKey(it, softKey, keyXMargin, keyYMargin.toInt()) }
                }
            }
            mCanvas!!
        }
        mDrawPending = false
        mDirtyRect.setEmpty()
    }

    /**
     * 在画布上画一个按键
     *
     * @param canvas     画布
     * @param softKey    需绘制的按键
     * @param keyXMargin 按键左右边间距
     * @param keyYMargin 按键上下边间距
     */
    private fun drawSoftKey(canvas: Canvas, softKey: SoftKey, keyXMargin: Int, keyYMargin: Int) {
        val bg = mKeyBackground
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = keyRadius.toFloat() // 设置圆角半径
        bg.setBounds(softKey.mLeft + keyXMargin, softKey.mTop + keyYMargin, softKey.mRight - keyXMargin, softKey.mBottom - keyYMargin)
        if (softKey.pressed || (mService?.hasSelection == true && softKey.code == InputModeSwitcher.USER_KEYCODE_SELECT_MODE)) {
            bg.setColor(mActiveTheme.keyPressHighlightColor)
            bg.draw(canvas)
        } else if (isKeyBorder) {
            val background = when (softKey.code) {
                KeyEvent.KEYCODE_ENTER -> mActiveTheme.accentKeyBackgroundColor
                KeyEvent.KEYCODE_SPACE-> mActiveTheme.functionKeyBackgroundColor
                else  -> mActiveTheme.keyBackgroundColor
            }
            bg.setColor(background)
            bg.draw(canvas)
        } else if(softKey.code == KeyEvent.KEYCODE_ENTER) {
               bg.setColor(mActiveTheme.accentKeyBackgroundColor)
               bg.shape = GradientDrawable.OVAL
               val bgWidth = softKey.width() -  keyXMargin
               val bgHeight = softKey.height() - keyYMargin
               val radius = min(bgWidth, bgHeight)*3/4
               val keyMarginX = (bgWidth - radius)/2
               val keyMarginY = (bgHeight - radius)/2
                bg.setBounds(softKey.mLeft + keyMarginX, softKey.mTop + keyMarginY, softKey.mRight - keyMarginX, softKey.mBottom - keyMarginY)
                bg.draw(canvas)
        }
        var keyLabel = if(skbStyleMode == SkbStyleMode.Google){
            if(InputModeSwitcher.isLower) softKey.keyLabel.lowercase() else softKey.keyLabel
        } else {
            if(InputModeSwitcher.isLower && InputModeSwitcher.isEnglish) softKey.keyLabel.lowercase() else softKey.keyLabel
        }
        val keyLabelSmall = softKey.getmKeyLabelSmall()
        val keyMnemonic = softKey.keyMnemonic
        var keyIcon = if(skbStyleMode == SkbStyleMode.Google && softKey.code == KeyEvent.KEYCODE_SPACE) null
            else if(skbStyleMode == SkbStyleMode.Google && softKey.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION && !DecodingInfo.isCandidatesEmpty) null
            else softKey.keyIcon
        val weightHeigth = softKey.height() / 4f
        val textColor = mActiveTheme.keyTextColor
        if(softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT && InputModeSwitcher.isChinese && !DecodingInfo.isEngineFinish){
            keyLabel = "分词"
            keyIcon = null
        }
        if (keyboardSymbol && !TextUtils.isEmpty(keyLabelSmall)) {
            // 附带符号统一以右上角灰色角标呈现，避免与主标签争夺视觉重心
            mPaint.color = textColor
            mPaint.setTypeface(Typeface.DEFAULT)
            mPaint.alpha = SMALL_LABEL_ALPHA
            mPaint.textSize = mNormalKeyTextSizeSmall.toFloat()
            val x = softKey.mRight - mPaint.measureText(keyLabelSmall) - keyXMargin * 2
            val y = softKey.mTop + weightHeigth * 1.1f
            canvas.drawText(keyLabelSmall, x, y, mPaint)
        }
        if (null != keyIcon) {
            var  intrinsicWidth = keyIcon.intrinsicWidth
            var  intrinsicHeight = keyIcon.intrinsicHeight
            while(softKey.width() < intrinsicWidth || softKey.height() < intrinsicHeight){
                intrinsicWidth /= 2
                intrinsicHeight /= 2
            }
            val marginLeft = (softKey.width() - intrinsicWidth) / 2
            val marginRight = softKey.width() - intrinsicWidth - marginLeft
            val marginTop = (softKey.height() - intrinsicHeight) / 2
            val marginBottom = softKey.height() - intrinsicHeight - marginTop
            keyIcon.setTint(mActiveTheme.keyTextColor)
            keyIcon.setBounds(softKey.mLeft + marginLeft, softKey.mTop + marginTop, softKey.mRight - marginRight, softKey.mBottom - marginBottom)
            keyIcon.draw(canvas)
        } else if (!TextUtils.isEmpty(keyLabel)) { //Label位于中间
            mPaint.color = textColor
            if(keyboardFontBold) mPaint.typeface = Typeface.DEFAULT_BOLD
            // 多字符标签属功能键（符号、123、分词、去往等），按比例缩小，
            // 否则在放大字号后会明显盖过字母键
            mPaint.textSize = if (keyLabel.length > 1) mNormalKeyTextSize * FUNCTION_KEY_TEXT_SCALE
                else mNormalKeyTextSize.toFloat()
            val x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyLabel)) / 2.0f
            // 按当前字号的度量做垂直居中；附带符号已移至右上角，不再占用中部空间
            val fm = mPaint.fontMetrics
            val y = (softKey.mTop + softKey.mBottom) / 2.0f - (fm.ascent + fm.descent) / 2.0f
            canvas.drawText(keyLabel, x, y, mPaint)
        }
        if (keyboardMnemonic && !TextUtils.isEmpty(keyMnemonic)) {  //助记符位于中下方
            mPaint.color = textColor
            mPaint.typeface = Typeface.DEFAULT
            mPaint.textSize = mNormalKeyTextSizeSmall.toFloat() * 0.7f
            val x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyMnemonic)) / 2.0f
            val y = softKey.mTop + weightHeigth * 3 + weightHeigth / 2.0f
            canvas.drawText(keyMnemonic, x, y, mPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    companion object {
        /** 功能键（多字符标签）相对字母键的字号比例 */
        private const val FUNCTION_KEY_TEXT_SCALE = 0.75f
        /** 按键右上角附带符号的透明度 */
        private const val SMALL_LABEL_ALPHA = 128
    }

    override fun closing() {
        super.closing()
        mBuffer = null
        mCanvas = null
    }
}
