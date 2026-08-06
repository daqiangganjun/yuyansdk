package com.yuyan.imemodule.handwriting

import android.os.Bundle

/**
 * 主进程与手写进程（:hw）之间的 Messenger 协议。
 *
 * 手写识别所依赖的 ML Kit 会连带拉起 GMS dynamite、WorkManager 与遥测框架，
 * 这些在单进程下一经加载便无法卸载。将其隔离到独立进程后，切离手写键盘即可
 * 解绑并销毁该进程，内存由内核一次性回收。
 *
 * 因此本协议只允许传递原始数值：主进程不得引用任何 ML Kit 类型，否则类加载
 * 会重新发生在主进程，隔离即失去意义。
 */
internal object HandwritingProtocol {

    /** 注册客户端，服务端随即回送当前模型状态 */
    const val MSG_REGISTER = 1
    const val MSG_UNREGISTER = 2
    /** 提交一段笔迹请求识别，arg1 为请求序号 */
    const val MSG_RECOGNIZE = 3
    const val MSG_DOWNLOAD = 4
    const val MSG_DELETE = 5
    const val MSG_REFRESH = 6

    /** 模型状态变更，arg1 为状态码，失败原因置于 [KEY_MESSAGE] */
    const val MSG_STATE = 10
    /** 识别结果，arg1 为对应的请求序号，候选文本置于 [KEY_TEXTS] */
    const val MSG_RESULT = 11

    const val KEY_X = "x"
    const val KEY_Y = "y"
    const val KEY_TIME = "t"
    const val KEY_STROKE_END = "se"
    const val KEY_TEXTS = "texts"
    const val KEY_MESSAGE = "msg"

    const val STATE_UNKNOWN = 0
    const val STATE_NOT_DOWNLOADED = 1
    const val STATE_DOWNLOADING = 2
    const val STATE_DOWNLOADED = 3
    const val STATE_FAILED = 4
}

/**
 * 手写模型状态。与 ML Kit 的内部状态一一对应，但不引用其类型，供主进程 UI 使用。
 */
sealed interface HandwritingState {
    data object Unknown : HandwritingState
    data object NotDownloaded : HandwritingState
    data object Downloading : HandwritingState
    data object Downloaded : HandwritingState
    data class Failed(val message: String) : HandwritingState
}

/**
 * 待识别的笔迹。所有点按书写顺序平铺存放，[strokeEnds] 记录每一笔的结束位置
 * （不含），据此可还原笔划划分。这种平铺结构可直接装入 Bundle 跨进程传递，
 * 无需自定义 Parcelable。
 */
class InkStrokes {
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val times = ArrayList<Long>()
    private val strokeEnds = ArrayList<Int>()

    val isEmpty: Boolean get() = strokeEnds.isEmpty()

    fun addPoint(x: Float, y: Float, time: Long) {
        xs.add(x)
        ys.add(y)
        times.add(time)
    }

    /** 一笔书写完毕。空笔划不予记录，避免产生无点的 Stroke */
    fun endStroke() {
        val end = xs.size
        if (end == 0 || (strokeEnds.isNotEmpty() && strokeEnds.last() == end)) return
        strokeEnds.add(end)
    }

    fun clear() {
        xs.clear()
        ys.clear()
        times.clear()
        strokeEnds.clear()
    }

    fun toBundle(): Bundle = Bundle().apply {
        // 未收尾的笔划不参与识别，故按 strokeEnds 的最后位置截断
        val count = strokeEnds.lastOrNull() ?: 0
        putFloatArray(HandwritingProtocol.KEY_X, FloatArray(count) { xs[it] })
        putFloatArray(HandwritingProtocol.KEY_Y, FloatArray(count) { ys[it] })
        putLongArray(HandwritingProtocol.KEY_TIME, LongArray(count) { times[it] })
        putIntArray(HandwritingProtocol.KEY_STROKE_END, strokeEnds.toIntArray())
    }
}
