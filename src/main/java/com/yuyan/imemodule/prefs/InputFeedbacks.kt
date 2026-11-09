package com.yuyan.imemodule.prefs

import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.view.HapticFeedbackConstants
import android.view.View
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.utils.audioManager
import com.yuyan.imemodule.utils.vibrator

object InputFeedbacks {

    private val soundOnKeyPress by AppPrefs.getInstance().internal.soundOnKeyPress
    private val vibrationAmplitude by AppPrefs.getInstance().internal.vibrationAmplitude

    private val vibrator = Launcher.instance.context.vibrator
    private val hasAmplitudeControl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
    private val audioManager = Launcher.instance.context.audioManager

    fun hapticFeedback(view: View) {
        if (!AppPrefs.getInstance().internal.isVibrationEnabled) return
        val duration = when (vibrationAmplitude) {
            0, 1 -> {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                0L
            }
            2 -> 5L
            3 -> 30L
            else -> 50L
        }
        if (duration != 0L) {
            if (hasAmplitudeControl) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
    }

    enum class SoundEffect {
        Standard, SpaceBar, Delete, Return
    }

    fun soundEffect(effect: SoundEffect) {
        if (!AppPrefs.getInstance().internal.isSoundEnabled) return
        val fx = when (effect) {
            SoundEffect.Standard -> AudioManager.FX_KEYPRESS_STANDARD
            SoundEffect.SpaceBar -> AudioManager.FX_KEYPRESS_SPACEBAR
            SoundEffect.Delete -> AudioManager.FX_KEYPRESS_DELETE
            SoundEffect.Return -> AudioManager.FX_KEYPRESS_RETURN
        }
        if (soundOnKeyPress <= 10) {
            audioManager.playSoundEffect(fx)
        } else {
            audioManager.playSoundEffect(fx, (soundOnKeyPress - 10) / 30f)
        }
    }

}
