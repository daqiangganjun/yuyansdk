package com.yuyan.imemodule.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.utils.AppUtil
import java.util.concurrent.atomic.AtomicBoolean
import splitties.dimensions.dp

class VoiceInputController(
    private val service: ImeService,
    private val root: RelativeLayout,
    private val prepareInput: () -> Unit,
    private val commitResult: (String) -> Unit,
    private val anchor: android.view.View = root,
) {
    private val main = Handler(Looper.getMainLooper())
    private val stopRecording = AtomicBoolean(true)
    private val muted = AtomicBoolean(false)
    private var generation = 0
    private var session: SpeechSession? = null
    private var uplink: AudioUplink? = null
    private var overlay: VoiceInputPanel? = null
    private var finishing = false
    private var finalResultReceived = false
    private var inputTarget: android.view.inputmethod.InputConnection? = null
    private var latestResult = ""
    private var coverBottomInWindow = -1
    private var worker: Thread? = null
    private val connectionTimeout = Runnable { fail(SpeechFailure(SpeechFailureKind.TIMEOUT)) }
    private val finalTimeout = Runnable { fail(SpeechFailure(SpeechFailureKind.TIMEOUT)) }
    private val recordingLimit = Runnable { finish() }

    /**
     * 按下即开始录音，不等待连接：连接建立前的音频暂存在 [AudioUplink]，就绪后按顺序连续补发。
     *
     * @param coverBottom 面板下边界在窗口中的纵坐标（通常为空格键所在行的顶部），小于 0 时覆盖整个键盘
     */
    fun start(coverBottom: Int = -1) {
        cancel()
        if (worker?.isAlive == true) {
            message("录音尚未结束，请稍后重试")
            return
        }
        val settings = SpeechSettings(service)
        val provider = SpeechProviders.get(settings.providerId)
        val key = provider?.let { SpeechCredentials(service).read(it.id) }
        if (!settings.enabled || provider == null || key.isNullOrBlank() ||
            ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message("请先启用语音输入、配置密钥并允许麦克风权限")
            AppUtil.launchSettingsToVoice(service)
            return
        }
        val options = runCatching { settings.options(provider) }.getOrElse {
            message("语音参数无效，请在语音设置中检查")
            AppUtil.launchSettingsToVoice(service)
            return
        }
        prepareInput()
        val target = service.activeInputConnection ?: return
        inputTarget = target
        coverBottomInWindow = coverBottom
        val token = ++generation
        stopRecording.set(false)
        muted.set(false)
        finishing = false
        showOverlay()
        val listener = object : SpeechListener {
            override fun onReady() = dispatch(token) {
                main.removeCallbacks(connectionTimeout)
                if (uplink?.markReady() == false) fail(SpeechFailure(SpeechFailureKind.NETWORK))
            }

            override fun onResult(result: SpeechResult) = dispatch(token) {
                if (result.text.isNotBlank()) {
                    latestResult = result.text
                    overlay?.showResult(result.text)
                }
                if (result.isFinal) {
                    finalResultReceived = true
                    stopRecording.set(true)
                    if (finishing) commitRecognition()
                }
            }

            override fun onFailure(failure: SpeechFailure) = dispatch(token) { fail(failure) }
        }
        try {
            val created = provider.createSession(key, options, listener)
            session = created
            uplink = AudioUplink(created, provider.sampleRate * 2 * MAX_PENDING_SECONDS)
            main.postDelayed(connectionTimeout, 15_000)
            main.postDelayed(recordingLimit, 60_000)
            startRecording(provider.sampleRate, token)
            created.start()
        } catch (_: Exception) {
            fail(SpeechFailure(SpeechFailureKind.NETWORK))
        }
    }

    fun finish() {
        if (session == null || finishing) return
        finishing = true
        stopRecording.set(true)
        main.removeCallbacks(recordingLimit)
        if (finalResultReceived) {
            commitRecognition()
            return
        }
        overlay?.recognizing()
        if (worker?.isAlive != true) uplink?.finish()
        main.postDelayed(finalTimeout, 15_000)
    }

    /** 上滑进入「松开取消」时暂停识别：录音不停，改为发送静音保持会话，已识别内容保留以便恢复。 */
    fun setCancelArmed(armed: Boolean) {
        if (session == null) return
        muted.set(armed)
        overlay?.cancelArmed(armed)
    }

    fun cancel() {
        generation++
        stopRecording.set(true)
        muted.set(false)
        main.removeCallbacks(connectionTimeout)
        main.removeCallbacks(finalTimeout)
        main.removeCallbacks(recordingLimit)
        session?.cancel()
        session = null
        uplink = null
        finishing = false
        finalResultReceived = false
        inputTarget = null
        latestResult = ""
        coverBottomInWindow = -1
        overlay?.let { root.removeView(it) }
        overlay = null
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(sampleRate: Int, token: Int) {
        val activeUplink = uplink ?: return
        worker = Thread({
            var recorder: AudioRecord? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Unsupported recording format" }
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 12800))
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Cannot initialize audio recorder" }
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Cannot start audio recorder" }
                val buffer = ByteArray(sampleRate * 2 / 5)
                var offset = 0
                while (!stopRecording.get()) {
                    val count = recorder.read(buffer, offset, buffer.size - offset)
                    check(count > 0 && count % 2 == 0) { "Audio recording failed" }
                    offset += count
                    if (offset == buffer.size) {
                        if (!activeUplink.send(frame(buffer, offset))) break
                        offset = 0
                    }
                }
                if (token == generation && finishing && !finalResultReceived) {
                    if (offset > 0) activeUplink.send(frame(buffer, offset))
                    activeUplink.finish()
                }
            } catch (_: Exception) {
                dispatch(token) { fail(SpeechFailure(SpeechFailureKind.AUDIO)) }
            } finally {
                runCatching { recorder?.stop() }
                recorder?.release()
            }
        }, "ime-speech-recorder").apply { start() }
    }

    private fun frame(buffer: ByteArray, length: Int) = if (muted.get()) ByteArray(length) else buffer.copyOf(length)

    private fun dispatch(token: Int, action: () -> Unit) {
        main.post { if (token == generation && session != null) action() }
    }

    private fun fail(failure: SpeechFailure) {
        val text = when (failure.kind) {
            SpeechFailureKind.AUTHENTICATION -> "语音鉴权失败，请检查密钥和资源开通状态"
            SpeechFailureKind.NETWORK -> "语音连接中断，请检查网络后重试"
            SpeechFailureKind.PROTOCOL -> "语音服务返回了无法解析的数据"
            SpeechFailureKind.SERVER -> "语音服务拒绝请求，请检查资源和参数"
            SpeechFailureKind.TIMEOUT -> "语音识别超时，请重试"
            SpeechFailureKind.AUDIO -> "录音失败，请检查麦克风权限或占用情况"
        }
        cancel()
        message(text + (failure.code?.let { "（$it）" } ?: ""))
    }

    /** 面板从键盘内容顶部开始，下边界止于 [coverBottomInWindow]（空格行之上），让空格行保持可见。 */
    private fun showOverlay() {
        val panel = VoiceInputPanel(service, onCancelArmed = ::setCancelArmed, onCancel = ::cancel)
        panel.elevation = service.dp(12f)
        val margin = service.dp(6)
        val rootTop = IntArray(2).also(root::getLocationInWindow)[1]
        val anchorTop = IntArray(2).also(anchor::getLocationInWindow)[1] - rootTop
        val top = anchorTop + anchor.paddingTop + margin
        val fullBottom = anchorTop + anchor.height - anchor.paddingBottom - margin
        val bottom = if (coverBottomInWindow > 0) minOf(coverBottomInWindow - rootTop - margin, fullBottom) else fullBottom
        val width = (anchor.width - anchor.paddingLeft - anchor.paddingRight - service.dp(16)).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(service.dp(96))
        root.addView(panel, RelativeLayout.LayoutParams(width, height)
            .apply {
                addRule(RelativeLayout.ALIGN_TOP, anchor.id)
                addRule(RelativeLayout.ALIGN_START, anchor.id)
                setMargins(anchor.paddingLeft + service.dp(8), anchor.paddingTop + margin, service.dp(8), margin)
            })
        overlay = panel
    }

    private fun commitRecognition() {
        val stillCurrent = service.activeInputConnection === inputTarget
        val text = latestResult
        cancel()
        if (stillCurrent && text.isNotBlank()) commitResult(text)
        else if (stillCurrent) message("未识别到语音，请重试")
    }

    private fun message(text: String) = Toast.makeText(service, text, Toast.LENGTH_LONG).show()

    /** 连接就绪前把录音按顺序暂存在本地，就绪后连续补发，此后直接实时发送；结束请求同样延后到补发之后。 */
    private class AudioUplink(private val session: SpeechSession, private val maxPendingBytes: Int) {
        private val pending = ArrayDeque<ByteArray>()
        private var pendingBytes = 0
        private var ready = false
        private var finishRequested = false

        @Synchronized
        fun send(pcm: ByteArray): Boolean {
            if (ready) return session.sendAudio(pcm)
            if (pendingBytes + pcm.size > maxPendingBytes) return false
            pending.addLast(pcm)
            pendingBytes += pcm.size
            return true
        }

        @Synchronized
        fun markReady(): Boolean {
            if (ready) return true
            ready = true
            while (pending.isNotEmpty()) if (!session.sendAudio(pending.removeFirst())) return false
            pendingBytes = 0
            if (finishRequested) session.finish()
            return true
        }

        @Synchronized
        fun finish() {
            finishRequested = true
            if (ready) session.finish()
        }
    }

    private companion object {
        /** 本地暂存上限略大于连接超时，正常情况下先触发超时提示而不是截断录音。 */
        const val MAX_PENDING_SECONDS = 20
    }
}
