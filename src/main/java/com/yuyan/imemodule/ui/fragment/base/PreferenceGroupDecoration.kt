package com.yuyan.imemodule.ui.fragment.base

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R

@SuppressLint("RestrictedApi")
class PreferenceGroupDecoration(list: RecyclerView) : RecyclerView.ItemDecoration() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(list.context, R.color.settings_surface)
    }
    private val radius = 16f * list.resources.displayMetrics.density
    private val gap = (8f * list.resources.displayMetrics.density).toInt()

    private fun PreferenceGroupAdapter.isHeading(position: Int) =
        position !in 0 until itemCount || getItem(position) is PreferenceCategory

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        if (!adapter.isHeading(position) && adapter.isHeading(position + 1)) outRect.bottom = gap
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || adapter.isHeading(position)) continue
            val left = child.left.toFloat()
            val right = child.right.toFloat()
            val top = child.top + child.translationY
            val bottom = child.bottom + child.translationY
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
            if (!adapter.isHeading(position - 1)) canvas.drawRect(left, top, right, top + radius, paint)
            if (!adapter.isHeading(position + 1)) canvas.drawRect(left, bottom - radius, right, bottom, paint)
        }
    }
}
