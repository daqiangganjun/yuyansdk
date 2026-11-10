package com.yuyan.imemodule.candidate

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import com.yuyan.imemodule.adapter.FloatCandidatesBarAdapter
import com.yuyan.imemodule.callback.CandidateViewListener
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.manager.layout.CustomLinearLayoutManager
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.inputmethod.core.CandidateListItem
import splitties.dimensions.dp

/**
 * 物理键盘候选词集装箱
 */
class FloatCandidateBar(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs) {
    private var mFloatCandidateBarWidth: Int = 0

    private lateinit var mCvListener: CandidateViewListener // 候选词视图监听器
    private lateinit var mCandidatesDataContainer: LinearLayout //候选词视图
    private lateinit var mComposingView: TextView // 组成字符串的View，用于显示输入的拼音。
    private lateinit var mRVCandidates: RecyclerView    //候选词列表
    private lateinit var mCandidatesAdapter: FloatCandidatesBarAdapter
    private lateinit var candidatesData: LinearLayout //候选词视图
    // 方向键或翻页选中的候选；未选中时高亮与空格落在当前页首个候选
    private var activeCandidatePosition = RecyclerView.NO_POSITION
    private var firstVisiblePosition = 0
    private val pageStarts = ArrayDeque<Int>()   // 已翻过各页的起点，供上翻回退
    private var displayedCandidates: List<CandidateListItem> = emptyList()
    private var displayedComposing = ""
    private var nextPageRequested = false

    fun initialize(cvListener: CandidateViewListener) {
        mCvListener = cvListener
        mFloatCandidateBarWidth = (if(instance.isLandscape)instance.mScreenHeight else instance.mScreenWidth) - dp(40)
        initCandidateView()
    }

    // 初始化候选词界面
    private fun initCandidateView() {
        if(!::mCandidatesDataContainer.isInitialized) {
            mCandidatesDataContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            mComposingView = TextView(context).apply {
                includeFontPadding = false
                setPadding(dp(10), 0, dp(10), 0)
            }
            candidatesData = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mRVCandidates = RecyclerView(context).apply {
                setItemAnimator(null)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                layoutManager =
                    CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            mCandidatesAdapter = FloatCandidatesBarAdapter(context)
            mCandidatesAdapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
                mCvListener.onClickChoice(position)
            }
            mRVCandidates.setAdapter(mCandidatesAdapter)
            mRVCandidates.addOnScrollListener(object : OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    refreshVisibleCandidates()
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        pageStarts.clear()
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                        DecodingInfo.activeCandidateBar =
                            layoutManager.findLastVisibleItemPosition()
                        val itemCount = recyclerView.adapter?.itemCount ?: 0
                        if (itemCount > 0 && DecodingInfo.activeCandidateBar >= itemCount - 1) requestNextPage()
                    }
                }
            })
            mRVCandidates.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                refreshVisibleCandidates()
            }
            mCandidatesDataContainer.addView(mComposingView)
            mCandidatesDataContainer.addView(candidatesData)
            this.addView(mCandidatesDataContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            (mRVCandidates.parent as ViewGroup).removeView(mRVCandidates)
        }
        val candidatesHeight = instance.heightForCandidates
        mComposingView.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, instance.heightForcomposing)
        candidatesData.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, candidatesHeight)
        candidatesData.addView(mRVCandidates, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, candidatesHeight, 1f))
        mComposingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.composingTextSize)
        mCandidatesAdapter.notifyChanged()
    }

    /**
     * 显示候选词
     */
    fun showCandidates() {
        val candidates = DecodingInfo.candidates
        val composing = DecodingInfo.composingStrForDisplay
        // 下一页异步追加，且总量截断后长度可能不变：只要前缀未变就视为同一轮，保留滚动位置与选中项
        val isSameRound = nextPageRequested && displayedCandidates.isNotEmpty() &&
            candidates.size >= displayedCandidates.size && composing == displayedComposing &&
            candidates.subList(0, displayedCandidates.size) == displayedCandidates
        nextPageRequested = false
        displayedCandidates = candidates
        displayedComposing = composing
        mComposingView.text = composing
        if (!isSameRound) {
            activeCandidatePosition = RecyclerView.NO_POSITION
            firstVisiblePosition = 0
            pageStarts.clear()
            (mRVCandidates.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        }
        this.visibility = if (candidates.isEmpty()) GONE else VISIBLE
        mCandidatesAdapter.updateCandidateState(firstVisiblePosition, activeCandidatePosition)
        mCandidatesAdapter.notifyChanged()
    }

    private fun requestNextPage() {
        if (!nextPageRequested) nextPageRequested = DecodingInfo.nextPageCandidates > 0
    }

    private fun refreshVisibleCandidates() {
        val lm = mRVCandidates.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstCompletelyVisibleItemPosition().takeIf { it != RecyclerView.NO_POSITION }
            ?: lm.findFirstVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION) firstVisiblePosition = first
        mCandidatesAdapter.updateCandidateState(firstVisiblePosition, activeCandidatePosition)
        mCandidatesAdapter.updateVisibleIndices(mRVCandidates)
    }

    private fun selectCandidate(position: Int) {
        activeCandidatePosition = position
        mCandidatesAdapter.updateCandidateState(firstVisiblePosition, activeCandidatePosition)
        mCandidatesAdapter.updateVisibleIndices(mRVCandidates)
    }

    private val displayedActivePosition: Int
        get() = if (isActiveCand()) activeCandidatePosition else firstVisiblePosition

    /**
     * 更新激活的候选词
     */
    fun updateActiveCandidateNo(keyCode: Int) {
        if (DecodingInfo.isCandidatesEmpty) return
        val step = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return
        }
        val lastIndex = DecodingInfo.candidateSize - 1
        val target = (displayedActivePosition + step).coerceIn(0, lastIndex)
        selectCandidate(target)
        mRVCandidates.layoutManager?.scrollToPosition(target)
        if (target == lastIndex) requestNextPage()
    }

    /**
     * 翻页功能
     */
    fun pageChange(keyCode: Int) {
        if (DecodingInfo.isCandidatesEmpty) return
        val lm = mRVCandidates.layoutManager as? LinearLayoutManager ?: return
        val itemCount = mCandidatesAdapter.itemCount
        val target = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val first = lm.findFirstVisibleItemPosition()
                if (first == RecyclerView.NO_POSITION) return
                val last = lm.findLastCompletelyVisibleItemPosition().let { if (it == RecyclerView.NO_POSITION) first else it }
                val next = last + 1
                // 预估下一页会触底时提前加载，到底仍无新候选则停在末页
                if (next + (last - first) >= itemCount - 1) requestNextPage()
                if (next >= itemCount) return
                pageStarts.addLast(firstVisiblePosition)
                next
            }
            KeyEvent.KEYCODE_DPAD_UP -> pageStarts.removeLastOrNull() ?: if (firstVisiblePosition > 0) 0 else return
            else -> return
        }
        firstVisiblePosition = target
        selectCandidate(target)
        lm.scrollToPositionWithOffset(target, 0)
    }

    /**
     * 数字键对应的候选位置，序号以当前页首个完整显示的候选为 1；不在可见范围内返回 -1
     */
    fun getVisibleCandPosition(visibleNo: Int): Int {
        if (visibleNo !in 1..9 || DecodingInfo.isCandidatesEmpty) return -1
        val position = firstVisiblePosition + visibleNo - 1
        if (position >= DecodingInfo.candidateSize) return -1
        val lastVisible = (mRVCandidates.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
        return if (lastVisible == RecyclerView.NO_POSITION || position <= lastVisible) position else -1
    }

    /**
     * 获取激活的候选词
     */
    fun getActiveCandNo(): Int {
        if (DecodingInfo.isCandidatesEmpty) return 0
        return displayedActivePosition.coerceIn(0, DecodingInfo.candidateSize - 1)
    }

    /**
     * 是否操作选词
     */
    fun isActiveCand(): Boolean {
        return activeCandidatePosition in 0 until DecodingInfo.candidateSize
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMeasure = MeasureSpec.makeMeasureSpec(instance.heightForCandidatesArea, MeasureSpec.EXACTLY)
        val widthMeasure = MeasureSpec.makeMeasureSpec(mFloatCandidateBarWidth, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

    // 刷新主题
    fun updateTheme(textColor: Int) {
        initCandidateView()
        mComposingView.setTextColor(textColor)
        val drawable = GradientDrawable()
        drawable.setShape(GradientDrawable.RECTANGLE)
        drawable.setColor(ThemeManager.activeTheme.keyboardColor)
        val cornerRadiusInPx = 20f
        drawable.setCornerRadius(cornerRadiusInPx)
        background = drawable
        mCandidatesAdapter.notifyChanged()
    }
}
