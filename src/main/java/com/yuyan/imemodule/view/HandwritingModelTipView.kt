package com.yuyan.imemodule.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.inputmethod.MlKitHandwritingModel
import splitties.dimensions.dp

/**
 * 手写模型未就绪时覆盖在手写区域上的提示。
 *
 * ML Kit 的中文模型不随安装包分发，必须联网下载一次。此前仅有一次性 Toast，
 * 用户切到手写却无从知道该做什么，故在键盘上直接给出说明与下载入口。
 *
 * 下载接口只回调成功或失败、不提供进度百分比，因此用不确定进度条表示进行中，
 * 并在失败时展示具体原因（无 Google Play 服务或网络不通都会落到这里）。
 */
@SuppressLint("ViewConstructor")
class HandwritingModelTipView(context: Context, private val onOpenSettings: () -> Unit) :
    LinearLayout(context) {

    private val messageView: TextView
    private val actionButton: Button
    private val settingsButton: Button
    private val progressBar: ProgressBar

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val theme = ThemeManager.activeTheme
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = DevicesUtils.dip2px(12).toFloat()
            setColor(theme.keyBackgroundColor)
        }

        messageView = TextView(context).apply {
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            textSize = 14f
        }
        progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = GONE
        }
        actionButton = Button(context).apply {
            setOnClickListener { MlKitHandwritingModel.download() }
        }
        settingsButton = Button(context).apply {
            text = context.getString(R.string.hw_model_board_settings)
            setOnClickListener { onOpenSettings() }
        }

        addView(messageView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(progressBar, LayoutParams(dp(28), dp(28)).apply { topMargin = dp(8) })
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(actionButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(settingsButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    private val stateListener: (MlKitHandwritingModel.State) -> Unit = { state ->
        post { render(state) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MlKitHandwritingModel.addListener(stateListener)
        MlKitHandwritingModel.refreshState()
    }

    override fun onDetachedFromWindow() {
        MlKitHandwritingModel.removeListener(stateListener)
        super.onDetachedFromWindow()
    }

    private fun render(state: MlKitHandwritingModel.State) {
        when (state) {
            MlKitHandwritingModel.State.Downloaded -> {
                visibility = GONE
                return
            }
            MlKitHandwritingModel.State.Downloading -> {
                messageView.text = context.getString(R.string.hw_model_board_downloading)
                progressBar.visibility = VISIBLE
                actionButton.visibility = GONE
            }
            is MlKitHandwritingModel.State.Failed -> {
                messageView.text = context.getString(R.string.hw_model_board_failed, state.message)
                progressBar.visibility = GONE
                actionButton.visibility = VISIBLE
                actionButton.text = context.getString(R.string.hw_model_board_retry)
            }
            else -> {
                messageView.text = context.getString(R.string.hw_model_board_not_downloaded)
                progressBar.visibility = GONE
                actionButton.visibility = VISIBLE
                actionButton.text = context.getString(R.string.hw_model_board_download)
            }
        }
        visibility = VISIBLE
    }
}
