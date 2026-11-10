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

    /** 固定菜单项到候选栏末位；列表为反向布局，末位显示在最左 */
    fun addBarMenu(name: String) {
        val dao = DataBaseKT.instance.skbFunDao()
        dao.insert(SkbFun(name = name, isKeep = 1, position = dao.getMaxBarMenuPosition() + 1))
        invalidate()
    }

    fun removeBarMenu(name: String) {
        DataBaseKT.instance.skbFunDao().deleteBarMenu(name)
        invalidate()
    }

    /**
     * 按 [names] 的顺序重排候选栏菜单，整批放进单个事务。只更新已有记录，
     * 不在表中的名称（如剪贴板页临时拼入的操作项）不会因此被写入。
     */
    fun persistBarOrder(names: List<String>) {
        val dao = DataBaseKT.instance.skbFunDao()
        DataBaseKT.instance.runInTransaction {
            names.forEachIndexed { index, name -> dao.update(SkbFun(name = name, isKeep = 1, position = index)) }
        }
        invalidate()
    }

    fun invalidate() {
        synchronized(this) {
            barMenus = null
            barMenuNames = null
        }
    }
}
