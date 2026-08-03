package com.yuyan.imemodule.utils.thread

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 后台任务线程池。
 *
 * 原实现在类初始化时一次性创建四个线程池，其中默认池与两个定时任务池全项目无任何
 * 调用，且核心线程数各为 CPU 核数的两倍；对常驻的输入法进程是纯粹的浪费。默认池
 * 还配了无界队列，使其 maximumPoolSize 与拒绝策略永远不会生效。
 *
 * 现只保留实际在用的单线程池，惰性创建，空闲后线程可回收。
 */
class ThreadPoolUtils private constructor() {

    companion object {

        private val SINGLETON_EXECUTOR: ThreadPoolExecutor by lazy {
            ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS, LinkedBlockingQueue(),
                NamingThreadFactory("ThreadPoolUtils-singleton")
            ).apply {
                // 输入法进程常驻，空闲时不应长期占着线程
                allowCoreThreadTimeOut(true)
            }
        }

        @JvmStatic
        fun executeSingleton(runnable: Runnable?) {
            SINGLETON_EXECUTOR.execute(runnable)
        }
    }
}
