package com.yuyan.imemodule.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.callback.OnRecyclerItemClickListener
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.SkbMenuCache
import com.yuyan.imemodule.entity.SkbFunItem
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.data.theme.ThemeControls
import splitties.dimensions.dp

class MenuAdapter (context: Context?, val data: MutableList<SkbFunItem>) : RecyclerView.Adapter<MenuAdapter.SymbolHolder>() {
    private val inflater: LayoutInflater
    private var mOnItemClickListener: OnRecyclerItemClickListener? = null
    private val textColor: Int
    private var mTheme: Theme
    private var background: GradientDrawable
    fun setOnItemClickLitener(mOnItemClickLitener: OnRecyclerItemClickListener?) {
        mOnItemClickListener = mOnItemClickLitener
    }

    var dragOverListener: DragOverListener? = null
    init {
        val theme = ThemeManager.activeTheme
        textColor = theme.keyTextColor
        mTheme = ThemeManager.activeTheme
        inflater = LayoutInflater.from(context)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ThemeManager.activeTheme.keyBackgroundColor)
        }
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        var entranceNameTextView: TextView? = null
        var entranceIconImageView: ImageView? = null
        var entranceOption: ImageView? = null
        init {
            entranceIconImageView = itemView.findViewById(R.id.entrance_image)
            entranceNameTextView = itemView.findViewById(R.id.entrance_name)
            entranceOption = itemView.findViewById(R.id.entrance_option)
            entranceOption?.background = background
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val view = inflater.inflate(R.layout.sdk_item_skb_menu_entrance, parent, false)
        return SymbolHolder(view)
    }

    override fun getItemCount(): Int {
        return data.size
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val item = data[position]
        holder.entranceNameTextView?.text = item.funName
        holder.entranceIconImageView?.setImageResource(item.funImgResource)
        val selected = isSettingsMenuSelect(item)
        holder.itemView.isSelected = selected
        val color = ThemeControls.foreground(mTheme, selected)
        val backing = selected || mTheme is Theme.Custom && (mTheme as Theme.Custom).backgroundImage != null
        holder.entranceIconImageView?.apply {
            layoutParams = layoutParams.apply { width = context.dp(48); height = context.dp(48) }
            setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
            background = if (backing) ThemeControls.background(mTheme, selected, 0f, oval = true) else null
        }
        holder.entranceNameTextView?.apply {
            setPadding(context.dp(6), context.dp(3), context.dp(6), context.dp(3))
            background = if (backing) ThemeControls.background(mTheme, selected, context.dp(6f)) else null
        }
        holder.entranceNameTextView?.setTextColor(color)
        holder.entranceIconImageView?.getDrawable()?.setTint(color)
        if (dragOverListener != null) {
            holder.entranceOption?.visibility = View.VISIBLE
            // 原先每绑定一项查一次库（N+1），改查缓存的名称集合
            if(SkbMenuCache.isBarMenu(item.skbMenuMode.name)){
                holder.entranceOption?.setImageResource(R.drawable.ic_menu_minus)
            } else {
                holder.entranceOption?.setImageResource(R.drawable.ic_menu_plus)
            }
            holder.entranceIconImageView?.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    dragOverListener?.startDragItem(holder)
                }
                return@setOnTouchListener true
            }
            holder.entranceOption?.getDrawable()?.setTint(color)
            holder.entranceOption?.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val current = holder.bindingAdapterPosition
                    data.getOrNull(current)?.let { dragOverListener?.onOptionClick(this, it, current) }
                }
                return@setOnTouchListener true
            }
            holder.itemView.setOnClickListener(null)
        } else {
            holder.entranceIconImageView?.setOnTouchListener(null)
            holder.entranceOption?.setOnTouchListener(null)
            holder.entranceOption?.visibility = View.GONE
            if (mOnItemClickListener != null) {
                holder.itemView.setOnClickListener { v: View? ->
                    val current = holder.bindingAdapterPosition
                    if (current in data.indices) mOnItemClickListener?.onItemClick(this, v, current)
                }
            }
        }
    }

    private fun isSettingsMenuSelect(data: SkbFunItem): Boolean {
        val rimeValue = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
        val result: Boolean = when (data.skbMenuMode) {
            // Setting Menu
            SkbMenuMode.DarkTheme -> ThemeManager.isNightMode
            SkbMenuMode.JianFan -> AppPrefs.getInstance().input.chineseFanTi.getValue()
            SkbMenuMode.LockEnglish -> AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.getValue()
            SkbMenuMode.SymbolShow -> ThemeManager.prefs.keyboardSymbol.getValue()
            SkbMenuMode.Mnemonic -> AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.getValue()
            SkbMenuMode.EmojiInput -> AppPrefs.getInstance().input.emojiInput.getValue()
            SkbMenuMode.OneHanded -> AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
            SkbMenuMode.FlowerTypeface -> CustomConstant.flowerTypeface != FlowerTypefaceMode.Disabled
            SkbMenuMode.FloatKeyboard -> EnvironmentSingleton.instance.keyboardModeFloat
            // Keyboard Menu
            SkbMenuMode.PinyinT9 -> rimeValue == CustomConstant.SCHEMA_ZH_T9
            SkbMenuMode.Pinyin26Jian -> rimeValue == CustomConstant.SCHEMA_ZH_QWERTY
            SkbMenuMode.PinyinHandWriting -> rimeValue == CustomConstant.SCHEMA_ZH_HANDWRITING
            SkbMenuMode.PinyinLx17 -> rimeValue == CustomConstant.SCHEMA_ZH_DOUBLE_LX17
            SkbMenuMode.Pinyin26Double -> rimeValue.startsWith(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY) && rimeValue != CustomConstant.SCHEMA_ZH_DOUBLE_LX17
            SkbMenuMode.PinyinStroke -> rimeValue == CustomConstant.SCHEMA_ZH_STROKE
            SkbMenuMode.TextEdit -> InputModeSwitcher.isTextEditSkb
            SkbMenuMode.TerminalMode -> InputModeSwitcher.isTerminalMode
            SkbMenuMode.SwitchKeyboard -> (KeyboardManager.instance.currentContainer as? com.yuyan.imemodule.keyboard.container.SettingsContainer)?.isKeyboardSelectionVisible == true
            else -> false
        }
        return result
    }

    interface DragOverListener {
        fun startDragItem(holder: RecyclerView.ViewHolder)
        fun onOptionClick(parent: RecyclerView.Adapter<*>?, v: SkbFunItem, position: Int)
    }
}
