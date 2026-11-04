package com.yuyan.imemodule.handwriting

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 手写进程的主进程侧代理。
 *
 * 采用引用计数绑定：手写键盘与手写设置页均可持有，最后一方释放时才解绑，
 * 解绑即导致 :hw 进程终止。唯一的例外是模型下载期间——此时断开会中断下载，
 * 故延后到下载有结果之后。
 *
 * 本类不引用任何 ML Kit 类型，以确保主进程始终不加载 ML Kit。
 */
object HandwritingClient {

    private const val PREF_NAME = "handwriting_model"
    private const val KEY_DOWNLOADED = "downloaded"

    @Volatile
    var state: HandwritingState = HandwritingState.Unknown
        private set

    val isReady: Boolean get() = state == HandwritingState.Downloaded

    private val listeners = CopyOnWriteArrayList<(HandwritingState) -> Unit>()

    private var appContext: Context? = null
    private var service: Messenger? = null
    private var refCount = 0
    private var bound = false
    private var releasePending = false
    private var requestId = 0
    private val main = Handler(Looper.getMainLooper())
    private class Recognition(val callback: (HandwritingResult) -> Unit, val timeout: Runnable)
    private val requests = HashMap<Int, Recognition>()

    /** 绑定完成前发起的请求暂存于此，连接建立后补发 */
    private val pending = ArrayList<Message>()

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            HandwritingProtocol.MSG_STATE -> updateState(parseState(msg))
            HandwritingProtocol.MSG_RESULT -> {
                val texts = msg.data.getStringArray(HandwritingProtocol.KEY_TEXTS).orEmpty().filter(String::isNotBlank).distinct()
                complete(msg.arg1, when (msg.arg2) {
                    HandwritingProtocol.RESULT_UNAVAILABLE -> HandwritingResult.Unavailable
                    HandwritingProtocol.RESULT_FAILED -> HandwritingResult.Failed
                    else -> if (texts.isEmpty()) HandwritingResult.Empty else HandwritingResult.Candidates(texts)
                })
            }
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder ?: return)
            dispatch(Message.obtain(null, HandwritingProtocol.MSG_REGISTER))
            val queued = pending.toList()
            pending.clear()
            queued.forEach { dispatch(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 进程已终止（正常解绑或被系统回收），状态回落待下次绑定重新查询
            service = null
            failRequests()
            updateState(HandwritingState.Unknown)
        }
    }

    fun addListener(listener: (HandwritingState) -> Unit) {
        listeners.addIfAbsent(listener)
        listener(state)
    }

    fun removeListener(listener: (HandwritingState) -> Unit) {
        listeners.remove(listener)
    }

    /** 进入手写场景。多方持有时只绑定一次 */
    fun acquire(context: Context) {
        appContext = context.applicationContext
        // 以上次查询结果作为初值：绑定与查询需数百毫秒，期间若停留在 Unknown，
        // 手写区域的提示视图无从判断该不该显示下载入口
        if (state == HandwritingState.Unknown && readCachedDownloaded()) {
            state = HandwritingState.Downloaded
        }
        refCount++
        releasePending = false
        if (bound) return
        bound = true
        val connected = appContext?.bindService(
            Intent(appContext, HandwritingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        ) == true
        if (!connected) {
            bound = false
            failRequests()
            updateState(HandwritingState.Failed("Unable to bind handwriting service"))
        }
    }

    /** 离开手写场景。计数归零即解绑，:hw 进程随之终止 */
    fun release() {
        if (refCount > 0) refCount--
        if (refCount > 0) return
        if (state == HandwritingState.Downloading) {
            releasePending = true
            return
        }
        unbind()
    }

    fun recognize(strokes: InkStrokes, callback: (Array<String>) -> Unit) {
        requestRecognition(strokes) { result ->
            callback((result as? HandwritingResult.Candidates)?.texts?.toTypedArray() ?: emptyArray())
        }
    }

    fun requestRecognition(strokes: InkStrokes, callback: (HandwritingResult) -> Unit): () -> Unit {
        val id = ++requestId
        val timeout = Runnable {
            cancelRemote(id)
            complete(id, HandwritingResult.TimedOut)
        }
        requests[id] = Recognition(callback, timeout)
        main.postDelayed(timeout, 10_000)
        if (strokes.isEmpty || !bound) {
            main.post { complete(id, if (strokes.isEmpty) HandwritingResult.Empty else HandwritingResult.Unavailable) }
            return { cancel(id) }
        }
        val message = Message.obtain(null, HandwritingProtocol.MSG_RECOGNIZE, id, 0)
        message.data = strokes.toBundle()
        dispatch(message)
        return { cancel(id) }
    }

    private fun complete(id: Int, result: HandwritingResult) {
        val request = requests.remove(id) ?: return
        main.removeCallbacks(request.timeout)
        request.callback(result)
    }

    private fun cancel(id: Int) {
        val request = requests.remove(id) ?: return
        main.removeCallbacks(request.timeout)
        cancelRemote(id)
    }

    private fun cancelRemote(id: Int) {
        pending.removeAll { it.what == HandwritingProtocol.MSG_RECOGNIZE && it.arg1 == id }
        service?.let { target ->
            runCatching { target.send(Message.obtain(null, HandwritingProtocol.MSG_CANCEL, id, 0).apply { replyTo = incoming }) }
        }
    }

    private fun failRequests() {
        pending.removeAll { it.what == HandwritingProtocol.MSG_RECOGNIZE }
        requests.keys.toList().forEach { complete(it, HandwritingResult.Unavailable) }
    }

    fun download() = dispatch(Message.obtain(null, HandwritingProtocol.MSG_DOWNLOAD))

    fun delete() = dispatch(Message.obtain(null, HandwritingProtocol.MSG_DELETE))

    fun refresh() = dispatch(Message.obtain(null, HandwritingProtocol.MSG_REFRESH))

    private fun unbind() {
        releasePending = false
        failRequests()
        if (!bound) return
        bound = false
        service?.let {
            runCatching {
                it.send(Message.obtain(null, HandwritingProtocol.MSG_UNREGISTER).apply {
                    replyTo = incoming
                })
            }
        }
        service = null
        pending.clear()
        runCatching { appContext?.unbindService(connection) }
        updateState(HandwritingState.Unknown)
    }

    private fun dispatch(message: Message) {
        message.replyTo = incoming
        val target = service
        if (target != null) {
            try {
                target.send(message)
                return
            } catch (_: RemoteException) {
                service = null
                failRequests()
                updateState(HandwritingState.Unknown)
                return
            }
        }
        pending.add(message)
    }

    private fun updateState(newState: HandwritingState) {
        state = newState
        if (newState == HandwritingState.Downloaded || newState == HandwritingState.NotDownloaded) {
            writeCachedDownloaded(newState == HandwritingState.Downloaded)
        }
        listeners.forEach { it(newState) }
        if (releasePending && refCount <= 0 && newState != HandwritingState.Downloading) unbind()
    }

    private fun readCachedDownloaded(): Boolean =
        appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_DOWNLOADED, false) == true

    private fun writeCachedDownloaded(downloaded: Boolean) {
        appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_DOWNLOADED, downloaded)?.apply()
    }

    private fun parseState(msg: Message): HandwritingState = when (msg.arg1) {
        HandwritingProtocol.STATE_NOT_DOWNLOADED -> HandwritingState.NotDownloaded
        HandwritingProtocol.STATE_DOWNLOADING -> HandwritingState.Downloading
        HandwritingProtocol.STATE_DOWNLOADED -> HandwritingState.Downloaded
        HandwritingProtocol.STATE_FAILED ->
            HandwritingState.Failed(msg.data?.getString(HandwritingProtocol.KEY_MESSAGE).orEmpty())
        else -> HandwritingState.Unknown
    }
}
