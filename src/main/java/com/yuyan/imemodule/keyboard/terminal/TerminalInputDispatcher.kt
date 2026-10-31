package com.yuyan.imemodule.keyboard.terminal

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class TerminalModifiers(private val onChanged: () -> Unit = {}) {
    enum class State { Off, Once, Locked }

    var ctrl = State.Off
        private set
    var alt = State.Off
        private set
    var shift = State.Off
        private set

    val metaState: Int
        get() = (if (ctrl != State.Off) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
            (if (alt != State.Off) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0) or
            (if (shift != State.Off) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)

    fun toggle(keyCode: Int, lock: Boolean = false) {
        fun next(current: State) = if (lock) {
            if (current == State.Locked) State.Off else State.Locked
        } else if (current == State.Off) State.Once else State.Off
        when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT -> ctrl = next(ctrl)
            KeyEvent.KEYCODE_ALT_LEFT -> alt = next(alt)
            KeyEvent.KEYCODE_SHIFT_LEFT -> shift = next(shift)
        }
        onChanged()
    }

    fun consume() {
        if (ctrl != State.Once && alt != State.Once && shift != State.Once) return
        if (ctrl == State.Once) ctrl = State.Off
        if (alt == State.Once) alt = State.Off
        if (shift == State.Once) shift = State.Off
        onChanged()
    }

    fun clear() {
        if (metaState == 0) return
        ctrl = State.Off
        alt = State.Off
        shift = State.Off
        onChanged()
    }
}

class TerminalInputDispatcher(
    private val connection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
    onModifiersChanged: () -> Unit = {},
) {
    val modifiers = TerminalModifiers(onModifiersChanged)

    val isTermux: Boolean
        get() = editorInfo()?.packageName.let { it == "com.termux" || it?.startsWith("com.termux.") == true }

    fun sendKey(keyCode: Int, extraMetaState: Int = 0, consumeModifiers: Boolean = true): Boolean {
        val target = connection() ?: return false
        val meta = KeyEvent.normalizeMetaState(extraMetaState or if (consumeModifiers) modifiers.metaState else 0)
        val pressed = mutableListOf<Int>()
        val downTime = SystemClock.uptimeMillis()
        fun send(code: Int, action: Int, state: Int) = target.sendKeyEvent(KeyEvent(
            downTime, SystemClock.uptimeMillis(), action, code, 0, state,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
        ))
        try {
            listOf(
                KeyEvent.META_CTRL_ON to KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.META_ALT_ON to KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.META_SHIFT_ON to KeyEvent.KEYCODE_SHIFT_LEFT,
            ).forEach { (mask, code) ->
                if (meta and mask != 0) {
                    pressed.add(code)
                    send(code, KeyEvent.ACTION_DOWN, meta)
                }
            }
            val handled = send(keyCode, KeyEvent.ACTION_DOWN, meta)
            send(keyCode, KeyEvent.ACTION_UP, meta)
            return handled
        } finally {
            pressed.asReversed().forEach { send(it, KeyEvent.ACTION_UP, 0) }
            if (consumeModifiers) modifiers.consume()
        }
    }

    fun sendText(text: String): Boolean {
        if (text.isEmpty()) return true
        if (modifiers.metaState != 0 && text.length == 1) {
            val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            val key = events?.firstOrNull {
                it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode)
            }
            if (key != null) return sendKey(key.keyCode, key.metaState)
        }
        return try {
            connection()?.commitText(text, 1) == true
        } finally {
            modifiers.consume()
        }
    }

    fun paste(text: String, nativePaste: Boolean = true): Boolean {
        modifiers.clear()
        val target = connection() ?: return false
        if (nativePaste && isTermux) {
            return sendKey(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        }
        if (nativePaste && target.performContextMenuAction(android.R.id.paste)) return true
        return target.commitText(text, 1)
    }
}
