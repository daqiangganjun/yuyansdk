package com.yuyan.imemodule.view

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.CandidatesBarAdapter
import com.yuyan.imemodule.adapter.CandidatesMenuAdapter
import com.yuyan.imemodule.callback.CandidateViewListener
import com.yuyan.imemodule.data.candidateToolbarItems
import com.yuyan.imemodule.data.menuSkbFunsPreset
import com.yuyan.imemodule.database.SkbMenuCache
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.InputBaseContainer
import com.yuyan.imemodule.manager.layout.CustomLinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.dimensions.dp

/**
 * 候选词集装箱
 */
class CandidatesBar(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs) {

    private lateinit var mCvListener: CandidateViewListener // 候选词视图监听器
    private lateinit var mRightArrowBtn: ImageView // 右边箭头按钮
    private lateinit var mMenuRightArrowBtn: ImageView
    private lateinit var mCandidatesDataContainer: LinearLayout //候选词视图
    private lateinit var mCandidatesMenuContainer: LinearLayout //控制菜单视图
    private lateinit var mRVCandidates: RecyclerView    //候选词列表
    private lateinit var mIvMenuSetting: ImageView
    private lateinit var mCandidatesAdapter: CandidatesBarAdapter
    private lateinit var mRVContainerMenu:RecyclerView   // 候选词栏菜单
    private lateinit var mCandidatesMenuAdapter: CandidatesMenuAdapter
    private lateinit var candidatesData: LinearLayout //候选词视图
    private var activeCandNo:Int = 0

    fun initialize(cvListener: CandidateViewListener) {
        mCvListener = cvListener
        // 本视图高度由内容撑开，内容上下并无留白；而键盘区首行按键上方有 keyYMargin
        // 的空隙，导致顶栏看起来偏上。补一个等值的顶部内边距使上下视觉对称。
        setPadding(0, instance.keyYMargin, 0, 0)
        initMenuView()
        initCandidateView()
    }

    // 初始化候选词界面
    private fun initCandidateView() {
        if(!::mCandidatesDataContainer.isInitialized) {
            mCandidatesDataContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = GONE
            }
            candidatesData = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mRightArrowBtn = ImageView(context).apply {
                isClickable = true
                isEnabled = true
                setImageResource(R.drawable.sdk_level_list_candidates_display)
            }
            mRVCandidates = RecyclerView(context).apply {
                setItemAnimator(null)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                layoutManager =
                    CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            mCandidatesAdapter = CandidatesBarAdapter(context)
            mCandidatesAdapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
                mCvListener.onClickChoice(position)
            }
            mRVCandidates.setAdapter(mCandidatesAdapter)
            mRVCandidates.addOnScrollListener(object : OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                        DecodingInfo.activeCandidateBar =
                            layoutManager.findLastVisibleItemPosition()
                        val itemCount = recyclerView.adapter?.itemCount
                        if (KeyboardManager.instance.currentContainer !is CandidatesContainer && itemCount != null && DecodingInfo.activeCandidateBar >= itemCount - 1) {
                            DecodingInfo.nextPageCandidates
                        }
                    }
                }
            })
            mCandidatesDataContainer.addView(candidatesData)
            this.addView(mCandidatesDataContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            (mRightArrowBtn.parent as ViewGroup).removeView(mRightArrowBtn)
            (mRVCandidates.parent as ViewGroup).removeView(mRVCandidates)
        }
        // 拼音串已移至键盘上方的浮动气泡，候选词容器独占整条候选栏，
        // 其内元素仍用候选词行高并由容器的 CENTER_VERTICAL 居中
        var candidatesHeight = instance.heightForCandidates
        mRightArrowBtn.layoutParams = LinearLayout.LayoutParams(candidatesHeight, candidatesHeight, 0f).apply { marginEnd = dp(10) }
        candidatesData.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        mRightArrowBtn.setOnClickListener { view: View ->
            when (val level = (view as ImageView).drawable.level) {
                2 -> mCvListener.onClickClearCandidate()
                else -> {
                    mCvListener.onClickMore(level)
                    view.drawable.setLevel(1 - level)
                }
            }
        }
        val oneHandedModSwitch = AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
        val oneHandedMod = AppPrefs.getInstance().keyboardSetting.oneHandedMod.getValue()
        if (oneHandedModSwitch && oneHandedMod == KeyboardOneHandedMod.LEFT) {
            candidatesData.addView(mRightArrowBtn)
            candidatesData.addView(mRVCandidates, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, candidatesHeight, 1f))
        } else {
            candidatesData.addView(mRVCandidates, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, candidatesHeight, 1f))
            candidatesData.addView(mRightArrowBtn)
        }
        mCandidatesAdapter.notifyChanged()
    }

    //初始化标题栏
    fun initMenuView() {
        if(!::mCandidatesMenuContainer.isInitialized) {
            this.removeAllViews()
            mCandidatesMenuContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mIvMenuSetting = ImageView(context).apply {
                setImageResource(R.drawable.sdk_level_candidates_menu_left)
                isClickable = true
                isEnabled = true
                setOnClickListener { mCvListener.onClickMenu(SkbMenuMode.SettingsMenu) }
            }
            mRVContainerMenu = RecyclerView(context).apply {
                setItemAnimator(null)
                layoutManager = CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, true)
            }
            mCandidatesMenuAdapter = CandidatesMenuAdapter(context)
            mCandidatesMenuAdapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, view: View?, position: Int ->
                val skbMenuMode = mCandidatesMenuAdapter.getMenuMode(position)
                if (skbMenuMode != null) onClickMenu(skbMenuMode, view)
            }
            mRVContainerMenu.setAdapter(mCandidatesMenuAdapter)
            mMenuRightArrowBtn = ImageView(context).apply {
                isClickable = true
                isEnabled = true
                setImageResource(R.drawable.ic_menu_arrow_down)
            }
            mMenuRightArrowBtn.setOnClickListener { _: View ->
                mCvListener.onClickMenu(SkbMenuMode.CloseSKB)
            }
            mCandidatesMenuContainer.addView(mIvMenuSetting)
            mCandidatesMenuContainer.addView(mRVContainerMenu)
            mCandidatesMenuContainer.addView(mMenuRightArrowBtn)
            this.addView(
                mCandidatesMenuContainer,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
        var menuHeight = (instance.heightForCandidatesArea * 0.8).toInt()
        mIvMenuSetting.layoutParams = LinearLayout.LayoutParams(menuHeight, menuHeight, 0f).apply { marginStart = dp(10) }
        mMenuRightArrowBtn.layoutParams = LinearLayout.LayoutParams(menuHeight, menuHeight, 0f).apply { marginEnd = dp(10) }
        mRVContainerMenu.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, menuHeight, 1f)
        mCandidatesMenuAdapter.notifyChanged()  // 点击下拉菜单后，需要刷新菜单栏
    }

    private fun onClickMenu(skbMenuMode: SkbMenuMode, view: View?) {
        if(skbMenuMode == SkbMenuMode.ClearClipBoard){
            val contextWrapper = ContextThemeWrapper(context, R.style.Theme_AppTheme)
            val popupMenu = PopupMenu(contextWrapper, view).apply {
                menuInflater.inflate(R.menu.clear_clipboard, menu)
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.clear -> {
                            mCvListener.onClickClearClipBoard()
                        }
                    }
                    false
                }
            }
            mCvListener.onPopupVisibilityChanged(true)
            popupMenu.setOnDismissListener { mCvListener.onPopupVisibilityChanged(false) }
            popupMenu.show()
        } else {
            mCvListener.onClickMenu(skbMenuMode)
        }
    }

    // 增加窗口防抖机制
    private var pendingMenuJob: Job? = null
    private val serviceScope = MainScope()
    fun scheduleShowCandidates() {
        if (!DecodingInfo.isCandidatesEmpty) {
            pendingMenuJob?.cancel()
            pendingMenuJob = null
            showCandidates()
            return
        }
        if (pendingMenuJob?.isActive == true) return
        pendingMenuJob = serviceScope.launch {
            delay(100)
            showCandidates()
            pendingMenuJob = null
        }
    }

    /**
     * 显示候选词
     */
    fun showCandidates() {
        val container = KeyboardManager.instance.currentContainer
        val barItems = SkbMenuCache.barMenus().mapNotNull {
            SkbMenuMode.decodeOrNull(it.name)?.let(menuSkbFunsPreset::get)
        }
        mIvMenuSetting.drawable.setLevel( if(container is InputBaseContainer) 0 else 1)
        if (container is ClipBoardContainer) {
            showViewVisibility(mCandidatesMenuContainer)
            mCandidatesMenuAdapter.items = candidateToolbarItems(barItems, container.getMenuMode())
        } else if (DecodingInfo.isCandidatesEmpty) {
            mRightArrowBtn.drawable.setLevel(0)
            showViewVisibility(mCandidatesMenuContainer)
            mCandidatesMenuAdapter.items = barItems
        } else {
            if (DecodingInfo.candidateSize > DecodingInfo.activeCandidateBar) mRVCandidates.layoutManager?.scrollToPosition(DecodingInfo.activeCandidateBar)
            showViewVisibility(mCandidatesDataContainer)
            mRightArrowBtn.drawable.setLevel(if (DecodingInfo.isAssociate) 2 else if (KeyboardManager.instance.currentContainer is CandidatesContainer) 1 else 0)
        }
        activeCandNo = 0
        mCandidatesAdapter.activeCandidates(activeCandNo)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
    }

    /**
     * 显示表情
     */
    fun showEmoji() {
        showViewVisibility(mCandidatesMenuContainer)
        mCandidatesMenuAdapter.items = listOf(menuSkbFunsPreset[SkbMenuMode.Emoticon]!!,menuSkbFunsPreset[SkbMenuMode.Emojicon]!!)
        activeCandNo = 0
        mCandidatesAdapter.activeCandidates(activeCandNo)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
    }

    /**
     * 更新激活的候选词
     */
    fun updateActiveCandidateNo(keyCode: Int) {
        if (!DecodingInfo.isCandidatesEmpty) {
            when(keyCode){
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if(--activeCandNo <= 0) activeCandNo = 0
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if(++activeCandNo > DecodingInfo.candidateSize) activeCandNo = DecodingInfo.candidateSize
                }
            }
            mCandidatesAdapter.activeCandidates(activeCandNo)
            mCandidatesAdapter.notifyChanged()
            mRVCandidates.layoutManager?.scrollToPosition(if(activeCandNo - 1 > 0) activeCandNo - 1 else 0 )
        }
    }

    /**
     * 获取激活的候选词
     */
    fun getActiveCandNo():Int {
        return if(activeCandNo > 0) activeCandNo -1 else 0
    }

    /**
     * 是否操作选词
     */
    fun isActiveCand():Boolean {
        return activeCandNo > 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMeasure = MeasureSpec.makeMeasureSpec(instance.heightForCandidatesArea, MeasureSpec.EXACTLY)
        val widthMeasure = MeasureSpec.makeMeasureSpec(instance.skbWidth, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

    private fun showViewVisibility(candidatesContainer: View) {
        if(candidatesContainer === mCandidatesMenuContainer){
            mCandidatesMenuContainer.visibility = VISIBLE
            mCandidatesDataContainer.visibility = GONE
        } else {
            mCandidatesMenuContainer.visibility = GONE
            mCandidatesDataContainer.visibility = VISIBLE
        }
    }

    // 刷新主题
    fun updateTheme(textColor: Int) {
        initMenuView()
        initCandidateView()
        mIvMenuSetting.setImageResource(R.drawable.sdk_level_candidates_menu_left)
        mRightArrowBtn.drawable.setTint(textColor)
        mMenuRightArrowBtn.drawable.setTint(textColor)
        mIvMenuSetting.drawable.setTint(textColor)
        mCandidatesAdapter.notifyChanged()
        mCandidatesMenuAdapter.notifyChanged()
    }
}
