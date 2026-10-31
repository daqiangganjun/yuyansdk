package com.yuyan.imemodule.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.utils.AppUtil
import java.util.concurrent.atomic.AtomicBoolean

class VoiceInputController(
    private val service: ImeService,
    private val root: RelativeLayout,
    private val prepareInput: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val stopRecording = AtomicBoolean(true)
    private var generation = 0
    private var session: SpeechSession? = null
    private var overlay: LinearLayout? = null
    private var status: TextView? = null
    private var preview: TextView? = null
    private var finishing = false
    private var worker: Thread? = null
    private val connectionTimeout = Runnable { fail(SpeechFailure(SpeechFailureKind.TIMEOUT)) }
    private val finalTimeout = Runnable { fail(SpeechFailure(SpeechFailureKind.TIMEOUT)) }
    private val recordingLimit = Runnable { finish() }

    fun start() {
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
        val target = service.currentInputConnection ?: return
        prepareInput()
        val token = ++generation
        stopRecording.set(false)
        finishing = false
        showOverlay()
        status?.text = "正在连接${provider.displayName}，松开空格结束"
        val listener = object : SpeechListener {
            override fun onReady() = dispatch(token) {
                main.removeCallbacks(connectionTimeout)
                if (finishing) session?.finish() else {
                    status?.text = "正在聆听，松开空格结束"
                    startRecording(provider.sampleRate, token)
                    main.postDelayed(recordingLimit, 60_000)
                }
            }

            override fun onResult(result: SpeechResult) = dispatch(token) {
                if (result.text.isNotBlank()) preview?.text = result.text
                if (result.isFinal) {
                    val stillCurrent = service.currentInputConnection === target
                    cancel()
                    if (stillCurrent && result.text.isNotBlank()) target.commitText(result.text, 1)
                    else if (stillCurrent) message("未识别到语音，请重试")
                }
            }

            override fun onFailure(failure: SpeechFailure) = dispatch(token) { fail(failure) }
        }
        try {
            session = provider.createSession(key, options, listener)
            main.postDelayed(connectionTimeout, 15_000)
            session?.start()
        } catch (_: Exception) {
            fail(SpeechFailure(SpeechFailureKind.NETWORK))
        }
    }

    fun finish() {
        if (session == null || finishing) return
        finishing = true
        stopRecording.set(true)
        main.removeCallbacks(recordingLimit)
        status?.text = "正在等待最终识别结果"
        if (worker?.isAlive != true) session?.finish()
        main.postDelayed(finalTimeout, 15_000)
    }

    fun cancel() {
        generation++
        stopRecording.set(true)
        main.removeCallbacks(connectionTimeout)
        main.removeCallbacks(finalTimeout)
        main.removeCallbacks(recordingLimit)
        session?.cancel()
        session = null
        finishing = false
        overlay?.let { root.removeView(it) }
        overlay = null
        status = null
        preview = null
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(sampleRate: Int, token: Int) {
        val activeSession = session ?: return
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
                        if (!activeSession.sendAudio(buffer.copyOf())) break
                        offset = 0
                    }
                }
                if (offset > 0) activeSession.sendAudio(buffer.copyOf(offset))
                activeSession.finish()
            } catch (_: Exception) {
                dispatch(token) { fail(SpeechFailure(SpeechFailureKind.AUDIO)) }
            } finally {
                runCatching { recorder?.stop() }
                recorder?.release()
            }
        }, "ime-speech-recorder").apply { start() }
    }

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

    private fun showOverlay() {
        val padding = (12 * service.resources.displayMetrics.density).toInt()
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(35, 35, 35))
            elevation = padding.toFloat()
            isClickable = true
        }
        status = TextView(service).apply { setTextColor(Color.WHITE); textSize = 14f }
        preview = TextView(service).apply { setTextColor(Color.WHITE); textSize = 18f; maxLines = 3; text = "请说话…" }
        panel.addView(status)
        panel.addView(preview)
        panel.addView(Button(service).apply {
            text = "取消"
            setOnClickListener { cancel() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
        })
        root.addView(panel, RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT)
            .apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) })
        overlay = panel
    }

    private fun message(text: String) = Toast.makeText(service, text, Toast.LENGTH_LONG).show()
}
