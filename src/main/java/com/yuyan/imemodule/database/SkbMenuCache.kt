package com.yuyan.imemodule.database

import com.yuyan.imemodule.database.entry.SkbFun

/**
 * 候选栏菜单配置（skbfun 表中 isKeep = 1 的记录）的进程内缓存。
 *
 * 该配置只在设置界面被修改，却要在每次候选栏刷新时读取；直接查库意味着
 * 每输入一个字符就落一次 SQLite。任何写入 skbfun 表的地方都必须调用
 * [invalidate]。
 */
object SkbMenuCache {

    @Volatile
    private var barMenus: List<SkbFun>? = null

    @Volatile
    private var barMenuNames: Set<String>? = null

    fun barMenus(): List<SkbFun> {
        barMenus?.let { return it }
        return synchronized(this) {
            barMenus ?: run {
                val loaded = DataBaseKT.instance.skbFunDao().getALlBarMenu()
                // 首次安装时默认菜单可能尚未写入，空结果不入缓存，
                // 否则这一瞬时状态会被固化到进程结束
                if (loaded.isNotEmpty()) {
                    barMenus = loaded
                    barMenuNames = loaded.mapTo(HashSet(loaded.size)) { menu -> menu.name }
                }
                loaded
            }
        }
    }

    /**
     * 菜单项是否已固定在候选栏上。供列表绑定时判断，避免逐项查库。
     */
    fun isBarMenu(name: String): Boolean {
        barMenuNames?.let { return it.contains(name) }
        // 尚未建立缓存（含默认数据未写入的情况），直接以本次查询结果为准
        return barMenus().any { it.name == name }
    }

    fun invalidate() {
        synchronized(this) {
            barMenus = null
            barMenuNames = null
        }
    }
}
