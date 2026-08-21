package com.yuyan.imemodule.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.utils.StringUtils.sbc2dbcCase
import com.yuyan.imemodule.view.popup.AutoScaleTextView

/**
 * 拼音选择
 *
 * [itemHeight] 大于 0 时逐项固定高度，用于让符号栏内容均分栏高、铺满而不在末尾留下大片空白；
 * 为 0 则退回按内容自适应，供项数过多需要滚动的场景使用。
 */
class PrefixAdapter(context: Context?, private val mDatas: Array<String>, private val itemHeight: Int = 0) :
    RecyclerView.Adapter<PrefixAdapter.SymbolTypeHolder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val textColor: Int = activeTheme.keyTextColor

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolTypeHolder {
        val view = inflater.inflate(R.layout.sdk_item_list_alpha_symbol_noraml, parent, false)
        return SymbolTypeHolder(view)
    }

    override fun onBindViewHolder(holder: SymbolTypeHolder, position: Int) {
        holder.tvSymbolType.setText(sbc2dbcCase(mDatas[position]))
        if (itemHeight > 0) {
            holder.itemView.layoutParams = holder.itemView.layoutParams?.apply { height = itemHeight }
                ?: RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, itemHeight)
        }
    }

    override fun getItemCount(): Int {
        return mDatas.size
    }

    inner class SymbolTypeHolder(view: View) : RecyclerView.ViewHolder(view) {
        var tvSymbolType: AutoScaleTextView = view.findViewById(android.R.id.text1)
        init {
            tvSymbolType.scaleMode = AutoScaleTextView.Mode.Proportional
            tvSymbolType.setTextColor(textColor)
        }
    }
}
