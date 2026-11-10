package com.yuyan.imemodule.manager.layout

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 网格布局管理。数据与布局不一致时清空子视图并重新布局，避免列表停在残缺状态。
 */
class CustomGridLayoutManager(context: Context, spanCount: Int) : GridLayoutManager(context, spanCount) {
    private var relayoutRetryCount = 0

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
        return try {
            super.scrollVerticallyBy(dy, recycler, state)
        } catch (_: IndexOutOfBoundsException) {
            recoverFromInconsistency(recycler)
            0
        }
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
            relayoutRetryCount = 0
        } catch (_: IndexOutOfBoundsException) {
            recoverFromInconsistency(recycler)
        }
    }

    // 布局与滚动过程中 RecyclerView 会拦截并丢弃 requestLayout，须延到下一帧再请求
    private fun recoverFromInconsistency(recycler: RecyclerView.Recycler?) {
        if (recycler != null) runCatching { removeAndRecycleAllViews(recycler) }
        if (relayoutRetryCount++ >= MAX_RELAYOUT_RETRY) {
            relayoutRetryCount = 0
            return
        }
        postOnAnimation { requestLayout() }
    }

    private companion object {
        const val MAX_RELAYOUT_RETRY = 2
    }
}
