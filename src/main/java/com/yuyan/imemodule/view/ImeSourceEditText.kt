package com.yuyan.imemodule.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatEditText
import kotlin.math.abs
import kotlin.math.ceil

class ImeSourceEditText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatEditText(context, attrs) {
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caretBounds = Rect()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialized = false
    private var caretVisible = true
    private var blinkScheduled = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    var localEditing = false
        set(value) {
            if (field == value) return
            field = value
            restartCaret()
        }

    private val blink = object : Runnable {
        override fun run() {
            blinkScheduled = false
            if (!needsLocalCaret()) return
            caretVisible = !caretVisible
            invalidateCaret()
            scheduleBlink()
        }
    }

    init { initialized = true }

    // 原文框由本地 InputConnection 编辑，不向系统注册第二个输入目标。
    override fun onCheckIsTextEditor() = false

    private fun needsLocalCaret() = localEditing && isFocused && !hasWindowFocus() && isCursorVisible &&
        isAttachedToWindow && isShown && windowVisibility == VISIBLE && selectionStart >= 0 && selectionStart == selectionEnd

    private fun scheduleBlink() {
        if (blinkScheduled || !needsLocalCaret()) return
        blinkScheduled = postDelayed(blink, 500L)
    }

    private fun restartCaret() {
        if (!initialized) return
        removeCallbacks(blink)
        blinkScheduled = false
        caretVisible = true
        invalidate()
        scheduleBlink()
    }

    private fun updateCaretBounds(): Boolean {
        val textLayout = layout ?: return false
        val offset = selectionStart
        if (offset !in 0..length()) return false
        val line = textLayout.getLineForOffset(offset)
        val x = totalPaddingLeft + textLayout.getPrimaryHorizontal(offset) - scrollX
        val top = totalPaddingTop + textLayout.getLineTop(line) - scrollY
        val bottom = totalPaddingTop + textLayout.getLineBottom(line) - scrollY
        caretBounds.set(x.toInt(), top, ceil(x + resources.displayMetrics.density * 2).toInt(), bottom)
        return true
    }

    private fun invalidateCaret() {
        if (updateCaretBounds()) invalidate(caretBounds)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (needsLocalCaret() && caretVisible && updateCaretBounds()) {
            caretPaint.color = currentTextColor
            canvas.drawRect(caretBounds, caretPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; moved = false }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) moved = true
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> moved = true
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && localEditing && !hasWindowFocus() && !moved &&
            event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()) {
            requestFocus()
            setSelection(getOffsetForPosition(event.x, event.y).coerceIn(0, length()))
            restartCaret()
            performClick()
            return true
        }
        return handled
    }

    override fun performClick() = super.performClick()

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        restartCaret()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        restartCaret()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        restartCaret()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        restartCaret()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        restartCaret()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartCaret()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        blinkScheduled = false
        super.onDetachedFromWindow()
    }
}
