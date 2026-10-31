package com.yuyan.imemodule.speech

import org.json.JSONObject

enum class SpeechOptionType { SWITCH, INTEGER, TEXT, CHOICE, JSON_OBJECT, JSON_STRING }

data class SpeechOption(
    val key: String,
    val title: String,
    val description: String,
    val type: SpeechOptionType,
    val defaultValue: Any,
    val choices: Map<String, String> = emptyMap(),
    val range: IntRange? = null,
    val details: String = description
)

data class SpeechUtterance(
    val text: String,
    val startTimeMs: Int,
    val endTimeMs: Int,
    val definite: Boolean,
    val speakerId: String?,
    val additions: String
)

data class SpeechResult(
    val text: String,
    val isFinal: Boolean,
    val utterances: List<SpeechUtterance> = emptyList(),
    val rawPayload: String = ""
)

enum class SpeechFailureKind { AUTHENTICATION, NETWORK, PROTOCOL, SERVER, TIMEOUT, AUDIO }

data class SpeechFailure(val kind: SpeechFailureKind, val code: Int? = null)

interface SpeechListener {
    fun onReady()
    fun onResult(result: SpeechResult)
    fun onFailure(failure: SpeechFailure)
}

/** 会话方法可从录音线程调用；回调线程由供应商决定，调用方负责切换到主线程。 */
interface SpeechSession {
    fun start()
    fun sendAudio(pcm: ByteArray): Boolean
    fun finish()
    fun cancel()
}

interface SpeechProvider {
    val id: String
    val displayName: String
    val options: List<SpeechOption>
    val sampleRate: Int get() = 16000
    fun validate(options: JSONObject)
    fun createSession(apiKey: String, options: JSONObject, listener: SpeechListener): SpeechSession
}

object SpeechProviders {
    private val providers = linkedMapOf<String, SpeechProvider>("doubao" to DoubaoSpeechProvider())

    @Synchronized
    fun register(provider: SpeechProvider) {
        require(provider.id.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid speech provider ID" }
        providers[provider.id] = provider
    }

    @Synchronized
    fun get(id: String): SpeechProvider? = providers[id]

    @Synchronized
    fun all(): List<SpeechProvider> = providers.values.toList()
}
