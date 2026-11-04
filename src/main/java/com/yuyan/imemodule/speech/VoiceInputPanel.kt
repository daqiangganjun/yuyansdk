package com.yuyan.imemodule.speech

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.view.KeyboardUiStyle
import splitties.dimensions.dp

@SuppressLint("ViewConstructor")
class VoiceInputPanel(context: Context, onCancel: () -> Unit) : LinearLayout(context), ThemeManager.OnThemeChangeListener {
    private val microphone = ImageView(context).apply {
        setImageResource(R.drawable.ic_panel_mic)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val status = TextView(context).apply {
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val preview = TextView(context).apply {
        textSize = 18f
        setLines(3)
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.TOP
        setPadding(0, dp(10), 0, dp(6))
        setText(R.string.voice_panel_waiting)
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
        addView(preview, LayoutParams(-1, -2))
        val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(hint, LayoutParams(0, -2, 1f))
        footer.addView(cancel, LayoutParams(dp(64), dp(48)))
        addView(footer, LayoutParams(-1, -2))
        onThemeChange(ThemeManager.activeTheme)
    }

    fun connecting(providerName: String) { status.text = context.getString(R.string.voice_panel_connecting, providerName) }

    fun listening() {
        status.setText(R.string.voice_panel_listening)
        preview.setText(R.string.voice_panel_ready)
    }

    fun recognizing() {
        status.setText(R.string.voice_panel_recognizing)
        hint.setText(R.string.voice_panel_commit_hint)
    }

    fun showResult(text: String) {
        if (preview.text.toString() != text) preview.text = text
    }

    override fun onThemeChange(theme: Theme) {
        background = KeyboardUiStyle.rounded(KeyboardUiStyle.surface(theme), dp(20f))
        status.setTextColor(theme.keyTextColor)
        preview.setTextColor(theme.keyTextColor)
        hint.setTextColor(KeyboardUiStyle.secondaryText(theme))
        microphone.background = KeyboardUiStyle.rounded(theme.accentKeyBackgroundColor, dp(12f))
        microphone.drawable.setTint(theme.accentKeyTextColor)
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
}
