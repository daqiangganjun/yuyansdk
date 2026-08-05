package com.yuyan.inputmethod

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions

/**
 * ML Kit 中文手写模型的下载与状态管理。
 *
 * 该模型不能随安装包分发（Google 未提供打包进 APK 的方式），只能在运行时下载一次，
 * 约 20MB，之后识别全程离线。下载通道依赖 Google Play 服务，无 GMS 或无法连通
 * Google 服务器的设备会失败，故此处把失败原因如实上报给设置界面展示。
 */
object MlKitHandwritingModel {

    /** 简体中文手写模型 */
    private const val LANGUAGE_TAG = "zh-Hani-CN"

    sealed interface State {
        /** 尚未查询过 */
        data object Unknown : State
        data object NotDownloaded : State
        data object Downloading : State
        data object Downloaded : State
        data class Failed(val message: String) : State
    }

    @Volatile
    var state: State = State.Unknown
        private set

    /** 状态观察者。设置界面与手写键盘会同时监听，故用集合而非单个回调 */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()

    fun addListener(listener: (State) -> Unit) {
        listeners.addIfAbsent(listener)
        listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    private val identifier: DigitalInkRecognitionModelIdentifier? = runCatching {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGUAGE_TAG)
    }.getOrNull()

    private val model: DigitalInkRecognitionModel? =
        identifier?.let { DigitalInkRecognitionModel.builder(it).build() }

    /** 识别器只在模型就绪后创建，失败时为 null */
    @Volatile
    private var recognizer: DigitalInkRecognizer? = null

    private fun update(newState: State) {
        state = newState
        listeners.forEach { it(newState) }
    }

    /**
     * 查询模型是否已下载。结果异步回调到 [onStateChanged]。
     */
    fun refreshState() {
        val model = model ?: run {
            update(State.Failed("当前设备不支持该语言模型"))
            return
        }
        if (state == State.Downloading) return
        RemoteModelManager.getInstance().isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    ensureRecognizer()
                    update(State.Downloaded)
                } else {
                    update(State.NotDownloaded)
                }
            }
            .addOnFailureListener { e ->
                update(State.Failed(e.message ?: "查询失败"))
            }
    }

    /**
     * 手动触发下载。允许计费网络，由用户在设置界面主动发起。
     */
    fun download() {
        val model = model ?: run {
            update(State.Failed("当前设备不支持该语言模型"))
            return
        }
        if (state == State.Downloading) return
        update(State.Downloading)
        RemoteModelManager.getInstance()
            .download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                ensureRecognizer()
                update(State.Downloaded)
            }
            .addOnFailureListener { e ->
                // 无 Google Play 服务或无法连通 Google 服务器时会走到这里
                update(State.Failed(e.message ?: "下载失败，请检查网络与 Google Play 服务"))
            }
    }

    fun delete() {
        val model = model ?: return
        RemoteModelManager.getInstance().deleteDownloadedModel(model)
            .addOnSuccessListener {
                recognizer?.close()
                recognizer = null
                update(State.NotDownloaded)
            }
            .addOnFailureListener { e ->
                update(State.Failed(e.message ?: "删除失败"))
            }
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        val model = model ?: return
        recognizer = runCatching {
            DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        }.getOrNull()
    }

    /**
     * 取用识别器。模型未就绪时返回 null，并顺带触发一次状态查询，
     * 以便设置界面与键盘提示能反映真实状态。
     */
    fun recognizerOrNull(): DigitalInkRecognizer? {
        if (recognizer == null && state != State.Downloading) refreshState()
        return recognizer
    }

    val isReady: Boolean get() = recognizer != null
}
