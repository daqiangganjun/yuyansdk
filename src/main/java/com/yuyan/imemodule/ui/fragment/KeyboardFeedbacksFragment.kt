package com.yuyan.imemodule.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.yuyan.imemodule.R
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.view.KeyboardUiStyle
import com.yuyan.imemodule.view.preference.ManagedPreference
import splitties.dimensions.dp
import kotlin.math.roundToInt

class KeyboardFeedbacksFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        val prefs = AppPrefs.getInstance().internal
        content.addView(feedbackCard(R.string.button_sound_volume, prefs.soundOnKeyPress, 40, 10,
            { it < 5 || it > 10 }, { value ->
                when {
                    value < 5 -> "跟随系统"
                    value <= 10 -> "已关闭"
                    else -> "音量 ${((value - 10) * 100f / 30).roundToInt()}%"
                }
            }) { DevicesUtils.tryPlayKeyDown() })
        val vibrationLabels = resources.getStringArray(R.array.vibrate_labels)
        content.addView(feedbackCard(R.string.button_vibration_amplitude, prefs.vibrationAmplitude, 4, 1,
            { it != 1 }, { vibrationLabels[it.coerceIn(vibrationLabels.indices)] }) { DevicesUtils.tryVibrate(it) })
        return NestedScrollView(context).apply {
            clipToPadding = false
            addView(content)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
                insets
            }
        }
    }

    private fun feedbackCard(
        title: Int,
        preference: ManagedPreference.PInt,
        maximum: Int,
        disabledValue: Int,
        enabled: (Int) -> Boolean,
        label: (Int) -> String,
        preview: (View) -> Unit,
    ): View {
        val context = requireContext()
        val initial = preference.getValue().coerceIn(0, maximum)
        val toggle = MaterialSwitch(context).apply {
            setText(title)
            textSize = 16f
            minHeight = dp(48)
            isChecked = enabled(initial)
        }
        val valueLabel = TextView(context).apply {
            textSize = 14f
            text = label(initial)
            setTextColor(ContextCompat.getColor(context, R.color.settings_secondary_text))
        }
        val slider = Slider(context).apply {
            valueFrom = 0f
            valueTo = maximum.toFloat()
            stepSize = 1f
            value = initial.toFloat()
            isEnabled = enabled(initial)
            contentDescription = getString(title)
            setLabelFormatter { label(it.roundToInt()) }
        }
        var updating = false
        var trackingTouch = false
        fun save(value: Int) {
            preference.setValue(value)
            updating = true
            toggle.isChecked = enabled(value)
            slider.isEnabled = enabled(value)
            updating = false
            if (enabled(value)) preview(slider)
        }
        toggle.setOnCheckedChangeListener { _, checked ->
            if (!updating) {
                val value = if (checked) 0 else disabledValue
                slider.value = value.toFloat()
                slider.isEnabled = checked
                preference.setValue(value)
            }
        }
        slider.addOnChangeListener { view, value, fromUser ->
            valueLabel.text = label(value.roundToInt())
            if (fromUser && !trackingTouch) save(value.roundToInt())
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { trackingTouch = true }
            override fun onStopTrackingTouch(slider: Slider) {
                trackingTouch = false
                save(slider.value.roundToInt())
            }
        })
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
            background = KeyboardUiStyle.rounded(ContextCompat.getColor(context, R.color.settings_surface), dp(16f))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            addView(toggle, LinearLayout.LayoutParams(-1, -2))
            addView(valueLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(slider, LinearLayout.LayoutParams(-1, -2))
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as AppCompatActivity).supportActionBar?.setTitle(R.string.keyboard_feedback)
    }

    override fun onStop() {
        super.onStop()
        KeyboardManager.instance.clearKeyboard()
    }
}
