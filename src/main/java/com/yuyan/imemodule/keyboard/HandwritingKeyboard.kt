package com.yuyan.imemodule.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.view.MotionEvent
import androidx.core.graphics.createBitmap
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.handwriting.HandwritingClient
import com.yuyan.imemodule.handwriting.HandwritingSession
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class HandwritingKeyboard(context: Context?) : TextKeyboard(context) {

    private var mLastUpTime = 0L
    private var handwritingGesture = false
    private var activePointer = -1
    private val pauseMillis: Long
        get() = getInstance().handwriting.handWritingSpeed.getValue().toLong().coerceIn(300, 1300)
    private val session = HandwritingSession(
        recognize = HandwritingClient::requestRecognition,
        onUpdate = { result, pending -> mService?.showHandwritingResult(result, pending) },
        onPreview = { mService?.previewHandwriting(it) },
        onCommit = { mService?.commitHandwriting(it) },
    )
    val hasHandwritingInput: Boolean get() = session.hasInput
    private val stopWriting = Runnable {
        session.stopWriting()
        clear()
    }

    /*
     * 笔迹渲染：按事件时间测速定笔宽，三点平滑位置，笔宽变化按行进距离限速，起笔预热、收笔出锋。
     * 当前笔画先以白色叠加到印章层得到一张不透明的形状蒙版，再整体着色合入墨迹层，
     * 半透明的主题色在一笔之内也保持均匀，不会因采样点重叠而出现深浅不一的珠串。
     */
    private val density = getContext().resources.displayMetrics.density
    private var mInkBitmap: Bitmap? = null
    private var mInkCanvas: Canvas? = null
    private var mStampBitmap: Bitmap? = null
    private var mStampCanvas: Canvas? = null
    private val mStampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val mStampPath = Path()
    private val mInkCoverPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(ThemeManager.activeTheme.keyTextColor, PorterDuff.Mode.SRC_IN)
    }
    private val mInkX = FloatList()
    private val mInkY = FloatList()
    private val mInkWidth = FloatList()
    private var mRenderedCount = 0  // 已画入印章层的采样点数，书写中只增量绘制新点
    private val mPrevPoint = InputPoint()
    private val mLastPoint = InputPoint()
    private var mPointCount = 0
    private val mRawX = FloatArray(RAW_BUFFER_SIZE)
    private val mRawY = FloatArray(RAW_BUFFER_SIZE)
    private val mRawTime = LongArray(RAW_BUFFER_SIZE)
    private var mRawHead = 0
    private var mRawSize = 0
    private var mDrawing = false
    private var mMaxWidth = 0f
    private var mMinWidth = 0f
    private var mCurrentWidth = 0f  // 当前实际笔宽，受渐变速率限制
    private var mLastInkWidth = 0f
    private var mLastVelocity = 0f
    private var mStrokeLength = 0f
    private var mVelocityReady = false  // 起笔预热阶段是否已结束
    private val mSampleStep = max(1.5f * density, 2f)
    private val mWidthRampLength = 20f * density   // 笔宽从最细变到最粗所需的行进距离
    private val mWarmupDistance = 6f * density     // 起笔预热距离，这段不按速度定笔宽
    private val mEndTaperLength = 6f * density     // 收笔出锋长度
    private val mTailClampLength = 14f * density   // 收笔前限制笔宽回涨的长度
    private val mMinPointDistance = 0.7f * density

    init {
        clear()
    }

    override fun setTheme(theme: Theme) {
        super.setTheme(theme)
        mInkCoverPaint.colorFilter = PorterDuffColorFilter(mActiveTheme.keyTextColor, PorterDuff.Mode.SRC_IN)
    }

    fun clear() {
        mDrawing = false
        mPointCount = 0
        mRawSize = 0
        mRenderedCount = 0
        mInkX.clear()
        mInkY.clear()
        mInkWidth.clear()
        mInkBitmap?.eraseColor(Color.TRANSPARENT)
        mStampBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    /** 停笔前的后续落笔属于同一个字；即使定时消息尚未执行，也按事件时间结束书写。 */
    fun canContinueWriting(eventTime: Long): Boolean {
        if (!isEnabled || !InputModeSwitcher.isChineseHandWriting || !session.isWriting) return false
        if (!handwritingGesture && mLastUpTime != 0L && eventTime - mLastUpTime >= pauseMillis) {
            removeCallbacks(stopWriting)
            stopWriting.run()
        }
        return session.isWriting
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent): Boolean {
        if (me.actionMasked == MotionEvent.ACTION_DOWN) {
            val continuing = canContinueWriting(me.eventTime)
            handwritingGesture = isEnabled && InputModeSwitcher.isChineseHandWriting &&
                (continuing || mSoftKeyboard?.mapToKey(me.x.toInt(), me.y.toInt()) == null)
            if (handwritingGesture) {
                removeCallbacks(stopWriting)
                if (session.isStopped) clear()
                activePointer = me.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)
                session.beginStroke(me.x, me.y, me.eventTime)
                beginStroke()
                addInkPoint(me.x, me.y, me.eventTime)
            }
        }
        if (!handwritingGesture) return super.onTouchEvent(me)
        val pointer = me.findPointerIndex(activePointer)
        when (me.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (pointer < 0) {
                    cancelHandwriting()
                    return true
                }
                if (me.actionMasked == MotionEvent.ACTION_POINTER_UP && me.actionIndex != pointer) return true
                for (index in 0 until me.historySize) {
                    recordPoint(me.getHistoricalX(pointer, index), me.getHistoricalY(pointer, index), me.getHistoricalEventTime(index))
                }
                val x = me.getX(pointer)
                val y = me.getY(pointer)
                recordPoint(x, y, me.eventTime)
                if (me.actionMasked != MotionEvent.ACTION_MOVE) {
                    finishStroke()
                    session.endStroke()
                    handwritingGesture = false
                    activePointer = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    mLastUpTime = me.eventTime
                    postDelayed(stopWriting, pauseMillis)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelHandwriting()
        }
        invalidate()
        return true
    }

    private fun recordPoint(x: Float, y: Float, time: Long) {
        addInkPoint(x, y, time)
        session.addPoint(x, y, time)
    }

    private fun cancelHandwriting() {
        mService?.cancelHandwritingInput()
        resetForNewChar()
    }

    fun chooseCandidate(index: Int): Boolean {
        if (!session.choose(index)) return false
        clearGesture()
        return true
    }

    fun confirmReady() {
        session.confirmReady()
        clearGesture()
    }

    fun resetForNewChar() {
        session.reset()
        clearGesture()
    }

    private fun clearGesture() {
        removeCallbacks(stopWriting)
        handwritingGesture = false
        activePointer = -1
        mLastUpTime = 0
        parent?.requestDisallowInterceptTouchEvent(false)
        clear()
    }

    override fun onDetachedFromWindow() {
        mService?.finishHandwritingComposing()
        resetForNewChar()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            mInkBitmap = null
            mInkCanvas = null
            mStampBitmap = null
            mStampCanvas = null
            mRenderedCount = 0
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        mInkBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (mDrawing) {
            renderStroke()
            mStampBitmap?.let { canvas.drawBitmap(it, 0f, 0f, mInkCoverPaint) }
        }
    }

    private fun beginStroke() {
        mDrawing = true
        mPointCount = 0
        mRawHead = 0
        mRawSize = 0
        mRenderedCount = 0
        mInkX.clear()
        mInkY.clear()
        mInkWidth.clear()
        mStampBitmap?.eraseColor(Color.TRANSPARENT)
        mLastVelocity = 0f
        mLastInkWidth = 0f
        mStrokeLength = 0f
        mVelocityReady = false
        val widthDp = getInstance().handwriting.handWritingWidth.getValue().coerceAtLeast(1) * 0.4f
        mMaxWidth = max(widthDp * density, 2f)
        mMinWidth = mMaxWidth * MIN_WIDTH_RATIO
        mCurrentWidth = mMinWidth
    }

    /** 原始触点进三点环形缓冲，取加权平均后作为平滑点输出，过近的点直接丢弃 */
    private fun addInkPoint(x: Float, y: Float, time: Long) {
        if (!mDrawing) return
        if (mRawSize > 0) {
            val last = (mRawHead + mRawSize - 1) % RAW_BUFFER_SIZE
            val dx = x - mRawX[last]
            val dy = y - mRawY[last]
            if (dx * dx + dy * dy < mMinPointDistance * mMinPointDistance) return
        }
        if (mRawSize < RAW_BUFFER_SIZE) {
            val index = (mRawHead + mRawSize) % RAW_BUFFER_SIZE
            mRawX[index] = x
            mRawY[index] = y
            mRawTime[index] = time
            mRawSize++
        } else {
            mRawX[mRawHead] = x
            mRawY[mRawHead] = y
            mRawTime[mRawHead] = time
            mRawHead = (mRawHead + 1) % RAW_BUFFER_SIZE
        }
        when (mRawSize) {
            1 -> emitPoint(x, y, time)
            RAW_BUFFER_SIZE -> {
                val a = mRawHead
                val b = (mRawHead + 1) % RAW_BUFFER_SIZE
                val c = (mRawHead + 2) % RAW_BUFFER_SIZE
                emitPoint(
                    mRawX[a] * SMOOTH_WEIGHT + mRawX[b] * (1 - 2 * SMOOTH_WEIGHT) + mRawX[c] * SMOOTH_WEIGHT,
                    mRawY[a] * SMOOTH_WEIGHT + mRawY[b] * (1 - 2 * SMOOTH_WEIGHT) + mRawY[c] * SMOOTH_WEIGHT,
                    mRawTime[b]
                )
            }
        }
    }

    private fun flushPoint() {
        if (mRawSize < 2) {
            mRawSize = 0
            return
        }
        val last = (mRawHead + mRawSize - 1) % RAW_BUFFER_SIZE
        emitPoint(mRawX[last], mRawY[last], mRawTime[last])
        mRawSize = 0
    }

    private fun emitPoint(x: Float, y: Float, time: Long) {
        if (mPointCount > 0 && distance(mLastPoint.x, mLastPoint.y, x, y) < MIN_EMIT_DISTANCE) return
        if (mPointCount > 0) {
            val velocity = distance(mLastPoint.x, mLastPoint.y, x, y) / max(1L, time - mLastPoint.time).toFloat() / density
            if (mVelocityReady) {
                mLastVelocity = VELOCITY_FILTER_WEIGHT * velocity + (1 - VELOCITY_FILTER_WEIGHT) * mLastVelocity
            } else if (mStrokeLength >= mWarmupDistance) {
                mLastVelocity = velocity * VELOCITY_START_COMPENSATION
                mVelocityReady = true
            }
        }
        val width = if (mVelocityReady) widthFor(mLastVelocity) else mMinWidth
        when (mPointCount) {
            0 -> appendSample(x, y, mMinWidth)
            1 -> appendQuad(mLastPoint.x, mLastPoint.y, mLastPoint.x, mLastPoint.y, (mLastPoint.x + x) * 0.5f, (mLastPoint.y + y) * 0.5f, width)
            else -> appendQuad(
                (mPrevPoint.x + mLastPoint.x) * 0.5f, (mPrevPoint.y + mLastPoint.y) * 0.5f,
                mLastPoint.x, mLastPoint.y,
                (mLastPoint.x + x) * 0.5f, (mLastPoint.y + y) * 0.5f, width
            )
        }
        mPrevPoint.set(mLastPoint)
        mLastPoint.set(x, y, time)
        mPointCount++
    }

    private fun appendQuad(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, endWidth: Float) {
        val startWidth = mLastInkWidth
        val length = distance(x0, y0, cx, cy) + distance(cx, cy, x1, y1)
        val steps = max(1, ceil(length / mSampleStep).toInt())
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val u = 1f - t
            val x = u * u * x0 + 2f * u * t * cx + t * t * x1
            val y = u * u * y0 + 2f * u * t * cy + t * t * y1
            val smooth = t * t * (3f - 2f * t)
            appendSample(x, y, startWidth + (endWidth - startWidth) * smooth)
        }
    }

    private fun appendSample(x: Float, y: Float, width: Float) {
        val count = mInkX.size
        var travel = 0f
        if (count > 0) {
            travel = distance(mInkX[count - 1], mInkY[count - 1], x, y)
            mStrokeLength += travel
        }
        val finalWidth = if (count == 0) {
            width
        } else {
            val maxDelta = (mMaxWidth - mMinWidth) * travel / mWidthRampLength
            mCurrentWidth + (width - mCurrentWidth).coerceIn(-maxDelta, maxDelta)
        }.coerceAtLeast(1f)
        mCurrentWidth = finalWidth
        mInkX.add(x)
        mInkY.add(y)
        mInkWidth.add(finalWidth)
        mLastInkWidth = finalWidth
    }

    /**
     * 把尚未绘制的采样点画入印章层。书写中采样点只增不改，增量绘制即可，长笔画不会逐帧变慢；
     * 收笔出锋会回改末段笔宽，届时以 [full] 清空重画。
     */
    private fun renderStroke(full: Boolean = false) {
        val canvas = ensureStampCanvas() ?: return
        if (full) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            mRenderedCount = 0
        }
        val count = mInkX.size
        if (mRenderedCount >= count) return
        for (i in mRenderedCount until count) {
            canvas.drawCircle(mInkX[i], mInkY[i], inkRadius(i), mStampPaint)
            if (i > 0) stampSegment(canvas, i - 1, i)
        }
        mRenderedCount = count
    }

    private fun stampSegment(canvas: Canvas, from: Int, to: Int) {
        val x0 = mInkX[from]
        val y0 = mInkY[from]
        val r0 = inkRadius(from)
        val x1 = mInkX[to]
        val y1 = mInkY[to]
        val r1 = inkRadius(to)
        val dx = x1 - x0
        val dy = y1 - y0
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.0001f) return
        val nx = -dy / length
        val ny = dx / length
        mStampPath.reset()
        mStampPath.moveTo(x0 + nx * r0, y0 + ny * r0)
        mStampPath.lineTo(x1 + nx * r1, y1 + ny * r1)
        mStampPath.lineTo(x1 - nx * r1, y1 - ny * r1)
        mStampPath.lineTo(x0 - nx * r0, y0 - ny * r0)
        mStampPath.close()
        canvas.drawPath(mStampPath, mStampPaint)
    }

    private fun inkRadius(index: Int): Float = max(mInkWidth[index] * 0.5f, 0.5f)

    private fun commitStroke() {
        if (mInkX.size == 0) return
        val target = ensureInkCanvas() ?: return
        renderStroke(full = true)
        val stamp = mStampBitmap ?: return
        target.drawBitmap(stamp, 0f, 0f, mInkCoverPaint)
        stamp.eraseColor(Color.TRANSPARENT)
        mRenderedCount = 0
    }

    private fun ensureInkCanvas(): Canvas? {
        if (width <= 0 || height <= 0) return null
        val bitmap = mInkBitmap
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            val newBitmap = createBitmap(width, height)
            mInkBitmap = newBitmap
            mInkCanvas = Canvas(newBitmap)
        }
        return mInkCanvas
    }

    private fun ensureStampCanvas(): Canvas? {
        if (width <= 0 || height <= 0) return null
        val bitmap = mStampBitmap
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            val newBitmap = createBitmap(width, height)
            mStampBitmap = newBitmap
            mStampCanvas = Canvas(newBitmap)
            mRenderedCount = 0
        }
        return mStampCanvas
    }

    /** 抬笔：补齐缓冲中的尾点、做收笔出锋，并把整笔合入墨迹层 */
    private fun finishStroke() {
        if (!mDrawing) return
        mDrawing = false
        flushPoint()
        if (mPointCount >= 2) {
            appendQuad((mPrevPoint.x + mLastPoint.x) * 0.5f, (mPrevPoint.y + mLastPoint.y) * 0.5f, mLastPoint.x, mLastPoint.y, mLastPoint.x, mLastPoint.y, mCurrentWidth)
        }
        taperStrokeEnd()
        commitStroke()
        invalidate()
    }

    private fun taperStrokeEnd() {
        val count = mInkX.size
        if (count < 2) return
        var index = count - 1
        var remain = mTailClampLength
        while (index > 1 && remain > 0f) {
            remain -= distance(mInkX[index - 1], mInkY[index - 1], mInkX[index], mInkY[index])
            index--
        }
        if (index > 1) {
            val refWidth = mInkWidth[index]
            for (i in index until count) {
                if (mInkWidth[i] > refWidth) mInkWidth[i] = refWidth
            }
        }
        remain = mEndTaperLength
        index = count - 1
        while (index > 0 && remain > 0f) {
            remain -= distance(mInkX[index - 1], mInkY[index - 1], mInkX[index], mInkY[index])
            val progress = ((mEndTaperLength - remain) / mEndTaperLength).coerceIn(0f, 1f)
            val factor = END_WIDTH_RATIO + (1f - END_WIDTH_RATIO) * progress
            mInkWidth[index] = max(mInkWidth[index] * factor, 1f)
            index--
        }
    }

    private fun widthFor(velocityDpPerMs: Float): Float {
        val ratio = ((velocityDpPerMs - VELOCITY_SLOW) / (VELOCITY_FAST - VELOCITY_SLOW)).coerceIn(0f, 1f)
        val eased = ratio.toDouble().pow(0.9).toFloat()
        return mMaxWidth - (mMaxWidth - mMinWidth) * eased
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private class InputPoint {
        var x = 0f
        var y = 0f
        var time = 0L

        fun set(point: InputPoint) {
            x = point.x
            y = point.y
            time = point.time
        }

        fun set(x: Float, y: Float, time: Long) {
            this.x = x
            this.y = y
            this.time = time
        }
    }

    private class FloatList(initialCapacity: Int = 256) {
        private var mData = FloatArray(initialCapacity)
        var size = 0
            private set

        fun add(value: Float) {
            if (size == mData.size) mData = mData.copyOf(mData.size * 2)
            mData[size++] = value
        }

        operator fun get(index: Int): Float = mData[index]

        operator fun set(index: Int, value: Float) {
            mData[index] = value
        }

        fun clear() {
            size = 0
        }
    }

    companion object {
        private const val MIN_WIDTH_RATIO = 0.32f      // 最快时笔宽约占最大笔宽的比例
        private const val END_WIDTH_RATIO = 0.25f      // 收笔出锋处的笔宽比例
        private const val VELOCITY_FILTER_WEIGHT = 0.4f
        private const val VELOCITY_START_COMPENSATION = 1.35f // 起笔速度偏低的补偿系数
        private const val SMOOTH_WEIGHT = 0.25f        // 三点位置平滑权重
        private const val VELOCITY_SLOW = 0.02f        // dp/ms，慢写
        private const val VELOCITY_FAST = 0.30f        // dp/ms，快写
        private const val RAW_BUFFER_SIZE = 3
        private const val MIN_EMIT_DISTANCE = 0.5f
    }
}
