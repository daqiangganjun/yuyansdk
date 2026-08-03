package com.yuyan.imemodule.service

import android.content.ClipboardManager.OnPrimaryClipChangedListener
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.clipboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 剪切板监听
 * 移除使用广播监听方式，解决部分手机后台无法启动监听服务异常(API level 31)。
 */
object ClipboardHelper : OnPrimaryClipChangedListener {

    // 单例与进程同生命周期，无需取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init() {
        Launcher.instance.context.clipboardManager.addPrimaryClipChangedListener(this)
    }

    /**
     * 回调运行在主线程，任何应用的复制动作都会触发。
     * 入库涉及一次写事务、一次计数与一次带排序子查询的删除，放在后台执行，
     * 完成后再切回主线程更新偏好——该偏好的监听方会直接操作候选栏视图，
     * 且需要读到已落库的内容，故顺序不可颠倒。
     */
    override fun onPrimaryClipChanged() {
        if (!AppPrefs.getInstance().clipboard.clipboardListening.getValue()) return
        val item = Launcher.instance.context.clipboardManager.primaryClip?.getItemAt(0) ?: return
        if (item.text?.isNotBlank() != true) return
        val data = item.text.toString().take(20000)
        scope.launch {
            withContext(Dispatchers.IO) {
                val dao = DataBaseKT.instance.clipboardDao()
                dao.insert(Clipboard(content = data))
                val overflow = max(dao.getCount() - AppPrefs.getInstance().clipboard.clipboardHistoryLimit.getValue(), 0)
                if (overflow > 0) dao.deleteOldest(overflow)
            }
            if (AppPrefs.getInstance().clipboard.clipboardSuggestion.getValue()) {
                AppPrefs.getInstance().internal.clipboardUpdateTime.setValue(System.currentTimeMillis())
                AppPrefs.getInstance().internal.clipboardUpdateContent.setValue(data)
            }
        }
    }
}
