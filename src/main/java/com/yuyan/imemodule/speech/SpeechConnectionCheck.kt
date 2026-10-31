package com.yuyan.imemodule.speech

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class SpeechCheckResult(
    val success: Boolean,
    val message: String,
    val recognizedText: String = "",
    val elapsedMs: Long = 0
)

object SpeechConnectionCheck {
    suspend fun run(context: Context): SpeechCheckResult {
        val startedAt = SystemClock.elapsedRealtime()
        val settings = SpeechSettings(context)
        val provider = SpeechProviders.get(settings.providerId)
            ?: return SpeechCheckResult(false, "当前供应商不可用，请重新选择")
        val options = runCatching { settings.options(provider) }.getOrElse {
            return SpeechCheckResult(false, "参数格式无效，请检查 JSON 内容和整数范围")
        }
        val apiKey = SpeechCredentials(context).read(provider.id)
            ?: return SpeechCheckResult(false, "尚未配置 API Key，请先填写密钥")
        var session: SpeechSession? = null
        return try {
            withTimeout(25_000) {
                val audio = withContext(Dispatchers.IO) { sample(context, provider.sampleRate) }
                val ready = CompletableDeferred<Unit>()
                val completed = CompletableDeferred<SpeechResult>()
                session = provider.createSession(apiKey, options, object : SpeechListener {
                    override fun onReady() { ready.complete(Unit) }
                    override fun onResult(result: SpeechResult) {
                        if (result.isFinal) completed.complete(result)
                    }
                    override fun onFailure(failure: SpeechFailure) {
                        val error = CheckFailure(failure)
                        ready.completeExceptionally(error)
                        completed.completeExceptionally(error)
                    }
                })
                session!!.start()
                ready.await()
                val packetSize = provider.sampleRate * 2 / 5
                var offset = 0
                while (offset < audio.size && !completed.isCompleted) {
                    currentCoroutineContext().ensureActive()
                    val end = minOf(audio.size, offset + packetSize)
                    if (!session!!.sendAudio(audio.copyOfRange(offset, end))) {
                        if (completed.isCompleted) completed.await()
                        throw CheckFailure(SpeechFailure(SpeechFailureKind.NETWORK))
                    }
                    offset = end
                    delay(200)
                }
                if (!completed.isCompleted) session!!.finish()
                val result = completed.await()
                if (result.text.isBlank()) SpeechCheckResult(false, "服务已响应，但未返回测试语音的识别文字，请检查参数后重试")
                else SpeechCheckResult(true, "连接、鉴权和当前参数检查通过", result.text)
            }
        } catch (_: TimeoutCancellationException) {
            SpeechCheckResult(false, "检查超时，请检查网络或稍后重试")
        } catch (error: CancellationException) {
            throw error
        } catch (error: CheckFailure) {
            val failure = error.failure
            val message = when (failure.kind) {
                SpeechFailureKind.AUTHENTICATION -> "鉴权失败，请检查 API Key 和资源开通状态"
                SpeechFailureKind.NETWORK -> "无法连接语音服务，请检查网络后重试"
                SpeechFailureKind.PROTOCOL -> "服务返回了无法解析的数据，请稍后重试"
                SpeechFailureKind.SERVER -> "服务拒绝请求，请检查资源开通、余额及当前参数"
                SpeechFailureKind.TIMEOUT -> "检查超时，请检查网络或稍后重试"
                SpeechFailureKind.AUDIO -> "测试音频处理失败，请重试"
            }
            SpeechCheckResult(false, message + (failure.code?.let { "（错误码：$it）" } ?: ""))
        } catch (_: Exception) {
            SpeechCheckResult(false, "检查未完成，请确认配置后重试")
        } finally {
            session?.cancel()
        }.copy(elapsedMs = SystemClock.elapsedRealtime() - startedAt)
    }

    private fun sample(context: Context, sampleRate: Int): ByteArray {
        require(sampleRate in 8000..96000) { "Unsupported speech sample rate" }
        val pcm = context.assets.open("speech/connection_check.pcm").use { it.readBytes() }
        require(pcm.isNotEmpty() && pcm.size % 2 == 0) { "Invalid speech check audio" }
        if (sampleRate == 16000) return pcm
        val source = ShortArray(pcm.size / 2) { index ->
            ((pcm[index * 2].toInt() and 255) or (pcm[index * 2 + 1].toInt() shl 8)).toShort()
        }
        return ByteArray((source.size.toLong() * sampleRate / 16000).toInt() * 2).also { output ->
            for (index in 0 until output.size / 2) {
                val position = index * 16000.0 / sampleRate
                val left = position.toInt().coerceAtMost(source.lastIndex)
                val right = minOf(left + 1, source.lastIndex)
                val value = (source[left] + (source[right] - source[left]) * (position - left)).toInt()
                output[index * 2] = value.toByte()
                output[index * 2 + 1] = (value shr 8).toByte()
            }
        }
    }

    private class CheckFailure(val failure: SpeechFailure) : Exception("Speech connection check failed: ${failure.kind}")
}
