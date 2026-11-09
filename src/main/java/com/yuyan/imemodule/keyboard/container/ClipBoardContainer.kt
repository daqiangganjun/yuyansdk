package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.yuyan.imemodule.R
import com.yuyan.imemodule.adapter.ClipBoardAdapter
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.libs.recyclerview.SwipeMenu
import com.yuyan.imemodule.libs.recyclerview.SwipeMenuBridge
import com.yuyan.imemodule.libs.recyclerview.SwipeMenuItem
import com.yuyan.imemodule.libs.recyclerview.SwipeRecyclerView
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.ClipboardLayoutMode
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.manager.layout.CustomGridLayoutManager
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.view.KeyboardUiStyle
import splitties.dimensions.dp
import splitties.views.textResource
import kotlin.math.ceil

/**
 * 粘贴板列表键盘容器
 *
 * 使用RecyclerView实现垂直ListView列表布局。
 */
@SuppressLint("ViewConstructor")
class ClipBoardContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    private val mPaint : Paint = Paint() // 测量字符串长度
    private val mRVSymbolsView: SwipeRecyclerView = SwipeRecyclerView(context)
    private var mTVLable: TextView? = null
    private var itemMode:SkbMenuMode? = null
    private var searchQuery = ""
    val hasSearchQuery get() = searchQuery.isNotBlank()
    private val searchLabel = TextView(context).apply {
        tag = "clipboard_search_label"
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), 0, dp(8), 0)
        setOnClickListener { openSearch() }
    }
    private val clearSearch = TextView(context).apply {
        tag = "clipboard_search_clear"
        text = "清除"
        contentDescription = "清除搜索条件"
        gravity = Gravity.CENTER
        textSize = 13f
        setOnClickListener {
            itemMode?.let { showClipBoardView(it, "") }
            inputView.updateCandidateBar()
        }
    }
    private val filterBar = LinearLayout(context).apply {
        id = View.generateViewId()
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        addView(searchLabel, LinearLayout.LayoutParams(0, -1, 1f))
        addView(clearSearch, LinearLayout.LayoutParams(dp(56), -1))
    }
    /** 锁定剪贴板后连续粘贴发送用：走与键盘回车相同的路径，终端、编辑器动作与花漾字处理栏均按原规则响应。 */
    private val floatingEnter = ImageView(context).apply {
        tag = "clipboard_floating_enter"
        contentDescription = "回车"
        setImageResource(R.drawable.sdk_skb_key_enter_icon)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(14), dp(14), dp(14), dp(14))
        elevation = dp(4f)
        setOnClickListener {
            DevicesUtils.tryPlayKeyDown(KeyEvent.KEYCODE_ENTER)
            DevicesUtils.tryVibrate(this)
            inputView.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_ENTER))
        }
    }

    init {
        mPaint.textSize = dp(22f)
        initView(context)
    }

    private fun initView(context: Context) {
        mTVLable = TextView(context).apply {
            id = View.generateViewId()
            textResource = R.string.clipboard_empty_ltip
            gravity = Gravity.CENTER
            setTextColor(activeTheme.keyTextColor)
            textSize = instance.candidateTextSize
        }
        mRVSymbolsView.setItemAnimator(null)
        mRVSymbolsView.id = View.generateViewId()
        addView(filterBar, LayoutParams(0, dp(32)).apply {
            topToTop = LayoutParams.PARENT_ID
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
        })
        val layoutParams2 = LayoutParams(0, 0).apply {
            topToBottom = filterBar.id
            bottomToBottom = LayoutParams.PARENT_ID
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
        }
        mRVSymbolsView.layoutParams = layoutParams2
        this.addView(mRVSymbolsView)
        mTVLable?.layoutParams = LayoutParams(0, 0).apply {
            topToBottom = filterBar.id
            bottomToBottom = LayoutParams.PARENT_ID
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
        }
        addView(floatingEnter, LayoutParams(dp(52), dp(52)).apply {
            bottomToBottom = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            setMargins(0, 0, dp(14), dp(14))
        })
    }

    fun openSearch() {
        val mode = itemMode ?: return
        val previousQuery = searchQuery
        inputView.startClipboardSearch(searchQuery, if (mode == SkbMenuMode.ClipBoard) "搜索剪贴板" else "搜索常用语") { result ->
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.ClipBoard)
            (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.showClipBoardView(mode, result?.trim() ?: previousQuery)
            inputView.updateCandidateBar()
        }
    }

    private fun applyChromeTheme() {
        val theme = activeTheme
        searchLabel.setTextColor(theme.keyTextColor)
        clearSearch.setTextColor(theme.keyTextColor)
        clearSearch.background = KeyboardUiStyle.buttonBackground(theme, dp(8f), Color.TRANSPARENT)
        floatingEnter.background = KeyboardUiStyle.buttonBackground(theme, dp(16f), theme.accentKeyBackgroundColor)
        floatingEnter.drawable?.mutate()?.setTint(theme.accentKeyTextColor)
    }

    /**
     * 显示候选词界面 , 点击候选词时执行
     */
    fun showClipBoardView(item: SkbMenuMode, query: String? = null) {
        CustomConstant.lockClipBoardEnable = false
        if (itemMode != item) searchQuery = ""
        if (query != null) searchQuery = query
        itemMode = item
        searchLabel.text = "搜索：$searchQuery"
        searchLabel.contentDescription = "修改搜索条件：$searchQuery"
        filterBar.visibility = if (searchQuery.isBlank()) GONE else VISIBLE
        val floatingEnterEnabled = AppPrefs.getInstance().clipboard.clipboardFloatingEnter.getValue()
        floatingEnter.visibility = if (floatingEnterEnabled) VISIBLE else GONE
        // 末尾条目要能滚到悬浮回车键上方，不被它挡住
        mRVSymbolsView.clipToPadding = false
        mRVSymbolsView.setPadding(0, 0, 0, if (floatingEnterEnabled) dp(66) else 0)
        applyChromeTheme()
        mRVSymbolsView.setHasFixedSize(true)
        val allContents : MutableList<Clipboard> =
            if(itemMode == SkbMenuMode.ClipBoard) {
                DataBaseKT.instance.clipboardDao().getAll().toMutableList()
            } else {
                DataBaseKT.instance.phraseDao().getAll().map { line -> Clipboard(line.content) }.toMutableList()
            }
        val copyContents = allContents.filter { searchQuery.isBlank() || it.content.contains(searchQuery, ignoreCase = true) }.toMutableList()
        val manager =  when (AppPrefs.getInstance().clipboard.clipboardLayoutCompact.getValue()){
            ClipboardLayoutMode.ListView ->  LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            ClipboardLayoutMode.GridView -> CustomGridLayoutManager(context, 2)
            ClipboardLayoutMode.FlexboxView -> {
                calculateColumn(copyContents)
                CustomGridLayoutManager(context, 6).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(i: Int) = mHashMapSymbols[i] ?: 1
                    }
                }
            }
        }
        mRVSymbolsView.setLayoutManager(manager)
        val viewParent = mTVLable?.parent
        if (viewParent != null) {
            (viewParent as ViewGroup).removeView(mTVLable)
        }
        if(copyContents.isEmpty()){
            mTVLable?.text = if (searchQuery.isNotBlank()) "没有匹配的内容" else if (item == SkbMenuMode.ClipBoard)
                context.getString(R.string.clipboard_empty_ltip) else "暂无常用语"
            this.addView(mTVLable)
        }
        val adapter = ClipBoardAdapter(context, copyContents)
        mRVSymbolsView.setAdapter(null)
        mRVSymbolsView.setOnItemClickListener{ _: View?, position: Int ->
            inputView.responseLongKeyEvent(Pair(PopupMenuMode.Text, copyContents[position].content))
            if(!CustomConstant.lockClipBoardEnable)KeyboardManager.instance.switchKeyboard()
        }
        mRVSymbolsView.setSwipeMenuCreator{ _: SwipeMenu, rightMenu: SwipeMenu, position: Int ->
            val topItem = SwipeMenuItem(mContext).apply {
                setImage(if(itemMode == SkbMenuMode.ClipBoard) {
                    if(copyContents[position].isKeep == 1)R.drawable.ic_baseline_untop_circle_32 else R.drawable.ic_baseline_top_circle_32 }
                else R.drawable.ic_menu_edit)
                image.setTint(activeTheme.keyTextColor)
            }
            rightMenu.addMenuItem(topItem)
            val deleteItem = SwipeMenuItem(mContext).apply {
                setImage(R.drawable.ic_menu_delete)
                image.setTint(activeTheme.keyTextColor)
            }
            rightMenu.addMenuItem(deleteItem)
        }
        mRVSymbolsView.setOnItemMenuClickListener { menuBridge: SwipeMenuBridge, position: Int ->
            menuBridge.closeMenu()
            if(itemMode == SkbMenuMode.ClipBoard){
                if(menuBridge.position == 0) {
                    val data: Clipboard = copyContents[position]
                    data.isKeep = 1 - data.isKeep
                    DataBaseKT.instance.clipboardDao().update(data)
                    showClipBoardView(SkbMenuMode.ClipBoard)
                } else if(menuBridge.position == 1){
                    val data: Clipboard = copyContents[position]
                    DataBaseKT.instance.clipboardDao().deleteByContent(data.content)
                    showClipBoardView(SkbMenuMode.ClipBoard)
                }
            } else {
                val content = copyContents[position].content
                if(menuBridge.position == 0) {
                    inputView.onSettingsMenuClick(SkbMenuMode.AddPhrases, DataBaseKT.instance.phraseDao().queryByContent(content))
                } else if(menuBridge.position == 1){
                    DataBaseKT.instance.phraseDao().deleteByContent(content)
                    showClipBoardView(SkbMenuMode.Phrases)
                }
            }
        }
        mRVSymbolsView.setAdapter(adapter)
    }

    private val mHashMapSymbols = HashMap<Int, Int>() //候选词索引列数对应表
    private fun calculateColumn(data : MutableList<Clipboard>) {
        mHashMapSymbols.clear()
        val itemWidth = instance.skbWidth/6 - dp(10)
        var mCurrentColumn = 0
        val contents = data.map { it.content }
        contents.forEachIndexed { position, candidate ->
            var count = getSymbolsCount(candidate, itemWidth)
            var nextCount = 0
            if (contents.size > position + 1) {
                val nextCandidate = contents[position + 1]
                nextCount = getSymbolsCount(nextCandidate, itemWidth)
            }
            mCurrentColumn = if (mCurrentColumn + count + nextCount > 6) {
                count = 6 - mCurrentColumn
                0
            } else  (mCurrentColumn + count) % 6
            mHashMapSymbols[position] = count
        }
    }

    private fun getSymbolsCount(data: String, itemWidth:Int): Int {
        return if (!TextUtils.isEmpty(data)) ceil(mPaint.measureText(data).div(itemWidth)).toInt() else 0
    }
    fun getMenuMode():SkbMenuMode? {
       return itemMode
    }
}
