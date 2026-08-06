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
import com.yuyan.imemodule.handwriting.HandwritingClient
import com.yuyan.imemodule.handwriting.HandwritingState
import com.yuyan.imemodule.utils.DevicesUtils
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
        // 默认隐藏：挂载到容器后、首次 render 之前会先绘制一帧，若沿用 VISIBLE
        // 便会闪出一个内容尚未填充的空框。是否显示一律由 render 决定
        visibility = GONE
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
            setOnClickListener { HandwritingClient.download() }
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

    private val stateListener: (HandwritingState) -> Unit = { state ->
        post { render(state) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        HandwritingClient.addListener(stateListener)
        HandwritingClient.refresh()
    }

    override fun onDetachedFromWindow() {
        HandwritingClient.removeListener(stateListener)
        super.onDetachedFromWindow()
    }

    private fun render(state: HandwritingState) {
        when (state) {
            // Unknown 表示查询尚未返回（:hw 进程冷启动需数百毫秒）。此时若按「未下载」
            // 渲染，已下载的用户会看到提示框一闪而过，故与已下载一同隐藏，待状态明确再定
            HandwritingState.Downloaded, HandwritingState.Unknown -> {
                visibility = GONE
                return
            }
            HandwritingState.Downloading -> {
                messageView.text = context.getString(R.string.hw_model_board_downloading)
                progressBar.visibility = VISIBLE
                actionButton.visibility = GONE
            }
            is HandwritingState.Failed -> {
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
