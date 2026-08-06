package com.yuyan.imemodule.handwriting

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.digitalink.Ink
import com.yuyan.inputmethod.MlKitHandwritingModel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 承载 ML Kit 手写识别的独立进程服务（:hw）。
 *
 * ML Kit 一经加载便会连带 GMS dynamite、WorkManager 与遥测框架常驻，且这些内存
 * 在同一进程内无法归还。将其收拢到本进程后，主进程不再触碰任何 ML Kit 类型，
 * 用户切离手写键盘即解绑，进程随之终止，占用一次性释放。
 */
class HandwritingService : Service() {

    private val clients = CopyOnWriteArrayList<Messenger>()

    private val stateListener: (MlKitHandwritingModel.State) -> Unit = { state ->
        broadcast(stateMessage(state))
    }

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        handle(msg)
        true
    })

    override fun onCreate() {
        super.onCreate()
        // MlKitInitProvider 与 WorkManagerInitializer 均已在 manifest 中移除，
        // 二者的初始化收敛到此处，从而只发生在本进程。
        runCatching { MlKit.initialize(this) }
        runCatching { WorkManager.initialize(this, Configuration.Builder().build()) }
        MlKitHandwritingModel.addListener(stateListener)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        MlKitHandwritingModel.removeListener(stateListener)
        clients.clear()
        super.onDestroy()
        // 解绑后进程仅是变为空进程滞留于 LRU，内存并不归还。本进程只承载手写识别，
        // 主动终止方能让 ML Kit 与 GMS 占用的数十 MB 立即回到系统。
        Process.killProcess(Process.myPid())
    }

    private fun handle(msg: Message) {
        when (msg.what) {
            HandwritingProtocol.MSG_REGISTER -> {
                val client = msg.replyTo ?: return
                clients.addIfAbsent(client)
                send(client, stateMessage(MlKitHandwritingModel.state))
                MlKitHandwritingModel.refreshState()
            }
            HandwritingProtocol.MSG_UNREGISTER -> clients.remove(msg.replyTo)
            HandwritingProtocol.MSG_RECOGNIZE -> recognize(msg.data, msg.replyTo, msg.arg1)
            HandwritingProtocol.MSG_DOWNLOAD -> MlKitHandwritingModel.download()
            HandwritingProtocol.MSG_DELETE -> MlKitHandwritingModel.delete()
            HandwritingProtocol.MSG_REFRESH -> MlKitHandwritingModel.refreshState()
        }
    }

    private fun recognize(data: Bundle?, client: Messenger?, requestId: Int) {
        if (data == null || client == null) return
        val recognizer = MlKitHandwritingModel.recognizerOrNull() ?: return
        val ink = buildInk(data) ?: return
        recognizer.recognize(ink).addOnSuccessListener { result ->
            val texts = result.candidates.mapNotNull { it.text?.takeIf(String::isNotEmpty) }
            if (texts.isEmpty()) return@addOnSuccessListener
            val reply = Message.obtain(null, HandwritingProtocol.MSG_RESULT, requestId, 0)
            reply.data = Bundle().apply {
                putStringArray(HandwritingProtocol.KEY_TEXTS, texts.toTypedArray())
            }
            send(client, reply)
        }
    }

    /** 由平铺的点序列还原 ML Kit 笔迹。拼音标注留给主进程，本进程只回文本。 */
    private fun buildInk(data: Bundle): Ink? {
        val xs = data.getFloatArray(HandwritingProtocol.KEY_X) ?: return null
        val ys = data.getFloatArray(HandwritingProtocol.KEY_Y) ?: return null
        val times = data.getLongArray(HandwritingProtocol.KEY_TIME) ?: return null
        val ends = data.getIntArray(HandwritingProtocol.KEY_STROKE_END) ?: return null
        if (ends.isEmpty() || xs.size != ys.size || xs.size != times.size) return null
        val builder = Ink.builder()
        var start = 0
        var strokeCount = 0
        for (end in ends) {
            if (end <= start || end > xs.size) {
                start = end
                continue
            }
            val stroke = Ink.Stroke.builder()
            for (i in start until end) stroke.addPoint(Ink.Point.create(xs[i], ys[i], times[i]))
            builder.addStroke(stroke.build())
            strokeCount++
            start = end
        }
        return if (strokeCount == 0) null else builder.build()
    }

    private fun stateMessage(state: MlKitHandwritingModel.State): Message {
        val code = when (state) {
            MlKitHandwritingModel.State.Unknown -> HandwritingProtocol.STATE_UNKNOWN
            MlKitHandwritingModel.State.NotDownloaded -> HandwritingProtocol.STATE_NOT_DOWNLOADED
            MlKitHandwritingModel.State.Downloading -> HandwritingProtocol.STATE_DOWNLOADING
            MlKitHandwritingModel.State.Downloaded -> HandwritingProtocol.STATE_DOWNLOADED
            is MlKitHandwritingModel.State.Failed -> HandwritingProtocol.STATE_FAILED
        }
        return Message.obtain(null, HandwritingProtocol.MSG_STATE, code, 0).apply {
            if (state is MlKitHandwritingModel.State.Failed) {
                data = Bundle().apply {
                    putString(HandwritingProtocol.KEY_MESSAGE, state.message)
                }
            }
        }
    }

    private fun send(client: Messenger, message: Message) {
        try {
            client.send(message)
        } catch (_: RemoteException) {
            clients.remove(client)
        }
    }

    private fun broadcast(message: Message) {
        // Message 投递后即被回收，需为每个客户端各复制一份
        clients.forEach { send(it, Message.obtain(message)) }
    }
}
