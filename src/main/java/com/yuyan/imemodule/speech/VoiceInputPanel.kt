package com.yuyan.imemodule.speech

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.view.KeyboardUiStyle
import splitties.dimensions.dp

@SuppressLint("ViewConstructor")
class VoiceInputPanel(
    context: Context,
    private val onCancelArmed: (Boolean) -> Unit = {},
    private val onCancel: () -> Unit,
) : LinearLayout(context), ThemeManager.OnThemeChangeListener {
    private var touchX = 0f
    private var touchY = 0f
    private var swipeArmed = false
    private var recognizing = false
    private var cancelArmed = false
    private val microphone = ImageView(context).apply {
        setImageResource(R.drawable.ic_panel_mic)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val status = TextView(context).apply {
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        setText(R.string.voice_panel_listening)
    }
    private val preview = TextView(context).apply {
        textSize = 18f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.TOP
        setPadding(0, dp(10), 0, dp(6))
        setText(R.string.voice_panel_ready)
    }
    private val hint = TextView(context).apply { textSize = 12f; setText(R.string.voice_panel_release_hint) }
    private val cancel = TextView(context).apply {
        setText(android.R.string.cancel)
        textSize = 14f
        gravity = Gravity.CENTER
        isFocusable = false
        setOnClickListener { onCancel() }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(8))
        isClickable = true
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(microphone, LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        header.addView(status, LayoutParams(0, -2, 1f))
        addView(header, LayoutParams(-1, -2))
        addView(preview, LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(hint, LayoutParams(0, -2, 1f))
        footer.addView(cancel, LayoutParams(dp(64), dp(48)))
        addView(footer, LayoutParams(-1, -2))
        onThemeChange(ThemeManager.activeTheme)
    }

    fun recognizing() {
        recognizing = true
        if (!cancelArmed) refreshState()
    }

    fun showResult(text: String) {
        if (preview.text.toString() != text) preview.text = text
    }

    /** 「松开取消」提示：识别已暂停但内容保留，下滑回到阈值以下即恢复。 */
    fun cancelArmed(armed: Boolean) {
        if (cancelArmed == armed) return
        cancelArmed = armed
        refreshState()
    }

    private fun refreshState() {
        when {
            cancelArmed -> {
                status.setText(R.string.voice_panel_cancel_armed)
                hint.setText(R.string.voice_panel_resume_hint)
            }
            recognizing -> {
                status.setText(R.string.voice_panel_recognizing)
                hint.setText(R.string.voice_panel_commit_hint)
            }
            else -> {
                status.setText(R.string.voice_panel_listening)
                hint.setText(R.string.voice_panel_release_hint)
            }
        }
        preview.alpha = if (cancelArmed) 0.45f else 1f
        onThemeChange(ThemeManager.activeTheme)
    }

    /** 与空格长按一致：上滑超过阈值先进入取消提示，回落到较低阈值以下恢复，松手时仍处于提示状态才取消。 */
    private fun trackSwipe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.rawX
                touchY = event.rawY
                swipeArmed = false
            }
            MotionEvent.ACTION_MOVE -> {
                val up = touchY - event.rawY
                val armed = if (swipeArmed) up >= dp(VOICE_RESUME_DP) else up >= dp(VOICE_CANCEL_DP) && up > kotlin.math.abs(event.rawX - touchX)
                if (armed != swipeArmed) {
                    swipeArmed = armed
                    onCancelArmed(armed)
                }
            }
            MotionEvent.ACTION_UP -> if (swipeArmed) {
                swipeArmed = false
                onCancel()
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (swipeArmed) {
                swipeArmed = false
                onCancelArmed(false)
            }
        }
        return swipeArmed
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = trackSwipe(event) || super.onInterceptTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        trackSwipe(event)
        return true
    }

    override fun onThemeChange(theme: Theme) {
        background = KeyboardUiStyle.rounded(KeyboardUiStyle.surface(theme), dp(20f))
        status.setTextColor(if (cancelArmed) CANCEL_COLOR else theme.keyTextColor)
        preview.setTextColor(theme.keyTextColor)
        hint.setTextColor(KeyboardUiStyle.secondaryText(theme))
        microphone.background = KeyboardUiStyle.rounded(if (cancelArmed) CANCEL_COLOR else theme.accentKeyBackgroundColor, dp(12f))
        microphone.drawable.setTint(if (cancelArmed) Color.WHITE else theme.accentKeyTextColor)
        cancel.setTextColor(theme.keyTextColor)
        cancel.background = KeyboardUiStyle.buttonBackground(theme, dp(12f), theme.functionKeyBackgroundColor)
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

    companion object {
        /** 进入取消提示所需的上滑距离（dp），空格长按手势共用 */
        const val VOICE_CANCEL_DP = 48
        /** 已进入取消提示后，回落到该距离（dp）以下即恢复识别，留出回差避免在阈值附近来回抖动 */
        const val VOICE_RESUME_DP = 32
        private val CANCEL_COLOR = Color.rgb(229, 72, 77)
    }
}
