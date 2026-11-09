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
        content.addView(feedbackCard(R.string.button_sound_volume, prefs.soundOnKeyPress,
            prefs.soundEnabled, prefs.isSoundEnabled, 30,
            { (it - 10).coerceIn(0, 30) }, { if (it == 0) 0 else it + 10 }, { value ->
                if (value == 0) "跟随系统" else "音量 ${(value * 100f / 30).roundToInt()}%"
            }) { DevicesUtils.tryPlayKeyDown() })
        val vibrationLabels = resources.getStringArray(R.array.vibrate_labels)
        content.addView(feedbackCard(R.string.button_vibration_amplitude, prefs.vibrationAmplitude,
            prefs.vibrationEnabled, prefs.isVibrationEnabled, 3,
            { (it - 1).coerceIn(0, 3) }, { if (it == 0) 0 else it + 1 },
            { vibrationLabels[it.coerceIn(vibrationLabels.indices)] }) { DevicesUtils.tryVibrate(it) })
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
        enabledPreference: ManagedPreference.PBool,
        initiallyEnabled: Boolean,
        maximum: Int,
        fromStored: (Int) -> Int,
        toStored: (Int) -> Int,
        label: (Int) -> String,
        preview: (View) -> Unit,
    ): View {
        val context = requireContext()
        val initial = fromStored(preference.getValue())
        val toggle = MaterialSwitch(context).apply {
            setText(title)
            textSize = 16f
            minHeight = dp(48)
            isChecked = initiallyEnabled
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
            isEnabled = initiallyEnabled
            contentDescription = getString(title)
            setLabelFormatter { label(it.roundToInt()) }
        }
        var trackingTouch = false
        fun refreshEnabled(checked: Boolean) {
            slider.isEnabled = checked
            valueLabel.isEnabled = checked
            valueLabel.alpha = if (checked) 1f else 0.38f
        }
        fun save(value: Int) {
            if (!toggle.isChecked) return
            preference.setValue(toStored(value))
            enabledPreference.setValue(true)
            preview(slider)
        }
        toggle.setOnCheckedChangeListener { _, checked ->
            preference.setValue(toStored(slider.value.roundToInt()))
            enabledPreference.setValue(checked)
            refreshEnabled(checked)
            if (checked) preview(slider)
        }
        refreshEnabled(initiallyEnabled)
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
