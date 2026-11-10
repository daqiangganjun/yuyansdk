package com.yuyan.imemodule.adapter

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.callback.OnRecyclerItemClickListener
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import kotlin.math.ceil

/**
 * 物理键盘候选词适配器，序号按当前可见页编排，与数字键选词一一对应
 */
class FloatCandidatesBarAdapter(context: Context?) :
    RecyclerView.Adapter<FloatCandidatesBarAdapter.SymbolHolder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private var mOnItemClickListener: OnRecyclerItemClickListener? = null
    private var firstVisiblePosition = 0
    private var activeCandidatePosition = RecyclerView.NO_POSITION

    fun setOnItemClickLitener(mOnItemClickLitener: OnRecyclerItemClickListener?) {
        mOnItemClickListener = mOnItemClickLitener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val holder = SymbolHolder(inflater.inflate(R.layout.sdk_item_recyclerview_float_candidates_bar, parent, false))
        holder.itemView.setOnClickListener { view: View? ->
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) mOnItemClickListener?.onItemClick(this, view, position)
        }
        return holder
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val candidate = DecodingInfo.getCandidate(position) ?: return
        holder.textViewCandidate.text = candidate.text
        holder.textViewCandidate.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.candidateTextSize)
        holder.textViewIndex.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.candidateTextSize * INDEX_TEXT_SCALE)
        // 序号列固定宽度，滚动中改写序号不改变条目宽度，避免反复重排
        val indexWidth = ceil(holder.textViewIndex.paint.measureText(INDEX_WIDTH_SAMPLE)).toInt()
        if (holder.textViewIndex.minWidth != indexWidth) holder.textViewIndex.minWidth = indexWidth
        bindCandidateAppearance(holder, position)
    }

    override fun getItemCount(): Int {
        return DecodingInfo.candidateSize
    }

    fun updateCandidateState(firstVisiblePosition: Int, activeCandidatePosition: Int) {
        this.firstVisiblePosition = firstVisiblePosition
        this.activeCandidatePosition = activeCandidatePosition
    }

    /**
     * 只刷新已显示条目的序号与高亮，避免滚动中整表重绑
     */
    fun updateVisibleIndices(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? SymbolHolder ?: continue
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) bindCandidateAppearance(holder, position)
        }
    }

    // 只有数字键能选中的 1~9 显示序号
    private fun bindCandidateAppearance(holder: SymbolHolder, position: Int) {
        val visibleNo = position - firstVisiblePosition + 1
        val indexText = if (visibleNo in 1..9) "$visibleNo. " else ""
        if (holder.textViewIndex.text.toString() != indexText) holder.textViewIndex.text = indexText
        val activePosition = if (activeCandidatePosition != RecyclerView.NO_POSITION) activeCandidatePosition else firstVisiblePosition
        val textColor = if (activePosition == position) activeTheme.accentKeyBackgroundColor else activeTheme.keyTextColor
        holder.textViewIndex.setTextColor(textColor)
        holder.textViewCandidate.setTextColor(textColor)
    }

    fun notifyChanged() {
        notifyDataSetChanged()
    }

    class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textViewIndex: TextView = view.findViewById(R.id.tv_candidates_index)
        val textViewCandidate: EmojiTextView = view.findViewById(R.id.gv_candidates_bar_item)
    }

    private companion object {
        const val INDEX_TEXT_SCALE = 0.7f
        const val INDEX_WIDTH_SAMPLE = "8. "
    }
}
