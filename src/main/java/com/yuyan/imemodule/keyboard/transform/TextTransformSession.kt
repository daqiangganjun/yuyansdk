package com.yuyan.imemodule.keyboard.transform

import android.view.inputmethod.InputConnection
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.imemodule.utils.StringUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface TextTransformer {
    val id: String
    val displayName: String
    val debounceMillis: Long get() = 250L
    suspend fun transform(source: String): String
}

object TextTransformers {
    private val providers = linkedMapOf<String, TextTransformer>()

    init {
        val names = listOf("火星文", "花藤字", "凌乱字", "发芽字", "雾霾字", "禁止查阅", "长草字", "起风了", "原文")
        FlowerTypefaceMode.entries.forEachIndexed { index, mode ->
            register(object : TextTransformer {
                override val id = "flower:${mode.name}"
                override val displayName = names[index]
                override val debounceMillis = 0L
                override suspend fun transform(source: String) = StringUtils.converted2FlowerTypeface(source, mode)
            })
        }
    }

    @Synchronized
    fun register(provider: TextTransformer) {
        require(provider.id.isNotBlank()) { "Transformer ID must not be blank" }
        providers[provider.id] = provider
    }

    @Synchronized
    fun available(): List<TextTransformer> = providers.values.toList()
}

class TextTransformSession(
    private val target: InputConnection,
    private val supportsComposing: Boolean,
    private val isCurrentTarget: () -> Boolean,
    private val onChanged: (result: String, busy: Boolean, failed: Boolean) -> Unit,
) {
    private val scope = MainScope()
    private var job: Job? = null
    private var revision = 0L
    private var closed = false
    private var result = ""
    private var busy = false
    private var failed = false
    private var originalSelection: String? = null
    val isBusy: Boolean get() = busy
    var composingActive = false
        private set
    var updatingTarget = false
        private set

    fun update(source: String, transformer: TextTransformer) {
        if (closed) return
        val request = ++revision
        job?.cancel()
        busy = true
        failed = false
        onChanged(result, true, false)
        job = scope.launch {
            try {
                if (source.isNotEmpty()) delay(transformer.debounceMillis)
                val transformed = if (source.isEmpty()) "" else transformer.transform(source)
                if (closed || request != revision || !isCurrentTarget()) return@launch
                if (supportsComposing && transformed.isNotEmpty() && originalSelection == null) {
                    val selected = withContext(Dispatchers.IO) { target.getSelectedText(0)?.toString().orEmpty() }
                    if (closed || request != revision || !isCurrentTarget()) return@launch
                    originalSelection = selected
                }
                result = transformed
                if (supportsComposing && (result.isNotEmpty() || composingActive)) {
                    updatingTarget = true
                    try {
                        composingActive = target.setComposingText(result, 1)
                    } finally {
                        updatingTarget = false
                    }
                }
                busy = false
                onChanged(result, false, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed && request == revision && isCurrentTarget()) {
                    busy = false
                    failed = true
                    onChanged(result, false, true)
                }
            }
        }
    }

    fun finish(commit: Boolean) {
        if (closed) return
        closed = true
        revision++
        job?.cancel()
        scope.cancel()
        if (!isCurrentTarget()) return
        if (commit && !busy && !failed) {
            if (composingActive) target.finishComposingText()
            else if (result.isNotEmpty()) target.commitText(result, 1)
        } else if (composingActive) {
            target.setComposingText(originalSelection.orEmpty(), 1)
            target.finishComposingText()
        }
    }
}
