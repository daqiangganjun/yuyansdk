package com.yuyan.imemodule.data.transfer

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object TransferOperation {
    private val lock = ReentrantLock()
    fun <T> run(action: () -> T): T = lock.withLock(action)
}
