package com.yuyan.imemodule.application

import android.annotation.SuppressLint
import android.content.Context
import android.app.Application
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemeManager.prefs
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.ClipboardHelper
import com.yuyan.imemodule.utils.thread.ThreadPoolUtils
import com.yuyan.inputmethod.EngineRuntime
import java.io.File

class Launcher {
    lateinit var context: Context
        private set

    fun initData(context: Context) {
        this.context = context
        currentInit()
        onInitDataChildThread()
    }

    private fun currentInit() {
        AppPrefs.init(PreferenceManager.getDefaultSharedPreferences(context))
        ThemeManager.init(context.resources.configuration)
        ClipboardHelper.init()
    }

    /**
     * 可以在子线程初始化的操作
     */
    private fun onInitDataChildThread() {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
            else File("/proc/self/cmdline").readText().substringBefore('\u0000')
        // 手写独立进程只使用自己的模型，不能重复打开主进程的 Rime 用户词库。
        if (processName != context.packageName) return
        // 建库与默认数据写入走独立调度，不排在词库复制之后，否则首次安装时
        // 侧符号栏与候选栏菜单会在整个复制期间为空
        DataBaseKT.preload()
        ThreadPoolUtils.executeSingleton {
            EngineRuntime.initialize(context)
            YuyanEmojiCompat.init(context)
            //初始化键盘主题
            val isFollowSystemDayNight = prefs.followSystemDayNightTheme.getValue()
            if (isFollowSystemDayNight) {
                // AppCompatDelegate 要求在主线程调用
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
            }
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        val instance = Launcher()
    }
}
