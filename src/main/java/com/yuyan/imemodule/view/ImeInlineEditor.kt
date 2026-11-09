package com.yuyan.imemodule.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.Theme
import splitties.dimensions.dp

/**
 * 键盘内的原文输入条：圆角底框、本地光标与清空按钮，花漾字处理栏与剪贴板搜索共用。
 * 输入框获得焦点时由调用方设置 [isActivated]，以加深边框表示正在编辑。
 */
@SuppressLint("ViewConstructor")
class ImeInlineEditor(context: Context, clearDescription: String, private val beforeClear: () -> Unit = {}) : LinearLayout(context) {
    val source = ImeSourceEditText(context).apply {
        showSoftInputOnFocus = false
        isFocusable = true
        isFocusableInTouchMode = true
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        textSize = 16f
        setPadding(dp(8), dp(4), dp(4), dp(4))
        background = null
    }
    private val clearButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_panel_close)
        contentDescription = clearDescription
        isFocusable = false
        visibility = INVISIBLE
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { beforeClear(); source.editableText.clear(); source.requestFocus() }
    }

    init {
        gravity = Gravity.CENTER_VERTICAL
        addView(source, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(clearButton, LayoutParams(dp(32), LayoutParams.MATCH_PARENT))
        source.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                clearButton.visibility = if (s.isNullOrEmpty()) INVISIBLE else VISIBLE
            }
        })
    }

    fun applyTheme(theme: Theme) {
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), KeyboardUiStyle.rounded(theme.keyBackgroundColor, dp(10f)).apply {
                setStroke(dp(1), ColorUtils.setAlphaComponent(theme.keyTextColor, 100))
            })
            addState(intArrayOf(), KeyboardUiStyle.rounded(theme.keyBackgroundColor, dp(10f)).apply {
                setStroke(dp(1), ColorUtils.setAlphaComponent(theme.keyTextColor, 32))
            })
        }
        source.background = null
        source.setTextColor(theme.keyTextColor)
        source.setHintTextColor(KeyboardUiStyle.secondaryText(theme))
        clearButton.drawable?.setTint(KeyboardUiStyle.secondaryText(theme))
        clearButton.background = KeyboardUiStyle.buttonBackground(theme, dp(10f), Color.TRANSPARENT)
    }
}
