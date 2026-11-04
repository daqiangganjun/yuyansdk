package com.yuyan.imemodule.handwriting

/** 每字保留独立请求；已结束的字按书写顺序提交，当前字才可更新候选和组合文本。 */
class HandwritingSession(
    private val recognize: (InkStrokes, (HandwritingResult) -> Unit) -> (() -> Unit),
    private val onUpdate: (HandwritingResult?, Boolean) -> Unit,
    private val onPreview: (String) -> Unit,
    private val onCommit: (String) -> Unit,
) {
    private class Character {
        val ink = InkStrokes()
        var revision = 0L
        var result: HandwritingResult? = null
        var cancel: (() -> Unit)? = null
        var stopped = false
        var sealed = false
        var selected: String? = null
    }

    private val characters = ArrayList<Character>()
    private var current: Character? = null
    private var drawing = false
    private var preview: String? = null
    val hasInput: Boolean get() = characters.isNotEmpty()
    val isStopped: Boolean get() = current?.stopped == true
    val isWriting: Boolean get() = current?.stopped == false

    fun beginStroke(x: Float, y: Float, time: Long) {
        if (current?.stopped == true) {
            current?.sealed = true
            current = null
            drain()
        }
        val character = current ?: Character().also { current = it; characters.add(it) }
        character.revision++
        character.cancel?.invoke()
        character.cancel = null
        character.result = null
        drawing = true
        character.ink.addPoint(x, y, time)
        publish()
    }

    fun addPoint(x: Float, y: Float, time: Long) {
        if (drawing) current?.ink?.addPoint(x, y, time)
    }

    fun endStroke() {
        val character = current ?: return
        if (!drawing) return
        drawing = false
        character.ink.endStroke()
        val revision = character.revision
        publish()
        val cancel = recognize(character.ink) { result ->
            if (character !in characters || character.revision != revision) return@recognize
            character.cancel = null
            character.result = if (result is HandwritingResult.Candidates && result.texts.isEmpty())
                HandwritingResult.Empty else result
            drain()
            publish()
        }
        if (character in characters && character.result == null) character.cancel = cancel
    }

    fun stopWriting() {
        if (drawing) return
        current?.stopped = true
        publish()
    }

    fun choose(index: Int): Boolean {
        val character = current ?: return false
        val text = (character.result as? HandwritingResult.Candidates)?.texts?.getOrNull(index) ?: return false
        character.selected = text
        character.sealed = true
        current = null
        drawing = false
        drain()
        publish()
        return true
    }

    /** 外部输入和生命周期边界只提交已知结果，取消其余请求，避免写入另一个输入框。 */
    fun confirmReady() {
        characters.forEach { it.sealed = true }
        current = null
        drawing = false
        drain()
        reset()
    }

    fun reset() {
        val previous = characters.toList()
        characters.clear()
        current = null
        drawing = false
        preview = null
        previous.forEach { it.cancel?.invoke() }
        publish()
    }

    private fun drain() {
        while (true) {
            val first = characters.firstOrNull() ?: break
            if (!first.sealed || first.result == null) break
            characters.removeAt(0)
            val text = first.selected ?: (first.result as? HandwritingResult.Candidates)?.texts?.firstOrNull()
            if (text != null) onCommit(text)
            preview = null
        }
    }

    private fun publish() {
        val character = current
        onUpdate(character?.result, hasInput && !drawing && character?.result == null)
        if (character != null && characters.firstOrNull() === character && character.stopped && !drawing) {
            val text = (character.result as? HandwritingResult.Candidates)?.texts?.firstOrNull()
            if (text != null && text != preview) {
                preview = text
                onPreview(text)
            }
        }
    }
}
