package com.yuyan.imemodule.keyboard.container

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.utils.DevicesUtils

/**
 * 侧边符号栏的共用排布规则。
 *
 * 九宫格、候选词界面与手写三处各有一条符号栏，排布要求相同：按项数均分栏高铺满，
 * 末尾的「符号设置」与符号占同样一格。规则集中在此，避免三处各写一份、改一处漏两处。
 */
object SideSymbolBar {

    /** 单项高度的下限，低于此值就点不准了 */
    private const val MIN_ITEM_HEIGHT_DP = 28

    /** 齿轮图标的边长。图标固有尺寸 35dp，不收窄会比旁边的符号大出一圈 */
    private const val ICON_SIZE_DP = 18

    /**
     * 计算各项应有的高度：均分栏高，但不低于可点下限。
     *
     * 项数少时正好铺满，项数多到均分已不足以点按时改用下限、让列表滚动。
     * 这里不做「均分与自适应」的二选一——一旦按某个阈值整体退回自适应，
     * 符号项按内容撑开而「符号设置」只有图标那么高，末尾那格就会明显塌下去；
     * 且阈值会卡在临界点上，符号增删一个就翻转，表现很难预料。
     *
     * @param areaHeight 符号栏可用高度
     * @param itemCount 符号个数
     * @param withFooter 末尾是否带「符号设置」
     */
    fun evenItemHeight(areaHeight: Int, itemCount: Int, withFooter: Boolean): Int {
        val total = itemCount + if (withFooter) 1 else 0
        if (total <= 0 || areaHeight <= 0) return 0
        return maxOf(areaHeight / total, DevicesUtils.dip2px(MIN_ITEM_HEIGHT_DP))
    }

    /**
     * 建「符号设置」项。
     *
     * 它将作为 RecyclerView 的一项使用，故 layoutParams 直接给 [RecyclerView.LayoutParams]：
     * RecyclerView 会把 itemView 的 layoutParams 强转成自己的类型，给别的类型会在
     * setAdapter 时抛 ClassCastException。图标另行显式定尺寸，否则按 35dp 的固有尺寸铺开。
     */
    fun createSettingsEntry(context: Context, tint: Boolean, onClick: () -> Unit): LinearLayout {
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                DevicesUtils.dip2px(ICON_SIZE_DP), DevicesUtils.dip2px(ICON_SIZE_DP)
            )
            setImageResource(R.drawable.ic_menu_setting)
            if (tint) drawable.setTint(ThemeManager.activeTheme.keyTextColor)
        }
        return LinearLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            addView(icon)
            setOnClickListener { onClick() }
        }
    }

    /**
     * 让「符号设置」与符号占一样高。
     *
     * 只改现有 layoutParams 的高度、不替换其类型——RecyclerView 会把 itemView 的 layoutParams
     * 强转成 [RecyclerView.LayoutParams]，一旦在它接管之后换成别的类型，下次 setAdapter 即崩。
     * 同时设 minimumHeight：它在测量阶段直接生效，不经过 layoutParams 这一层，
     * footer 复用时高度也能跟上。
     */
    fun applyFooterHeight(footer: View, itemHeight: Int) {
        footer.minimumHeight = if (itemHeight > 0) itemHeight else 0
        val height = if (itemHeight > 0) itemHeight else ViewGroup.LayoutParams.WRAP_CONTENT
        val params = footer.layoutParams
        if (params == null) {
            footer.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, height)
        } else if (params.height != height) {
            params.height = height
            footer.layoutParams = params
        }
    }
}
