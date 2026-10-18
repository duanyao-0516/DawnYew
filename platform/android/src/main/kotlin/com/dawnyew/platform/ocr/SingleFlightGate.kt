package com.dawnyew.platform.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class OcrBusyException : IllegalStateException("已有截图正在识别，请稍后重试")

/** A cancelled JNI call still owns the gate until the actual native work returns. */
internal class SingleFlightGate {
    private val mutex = Mutex()
    private val worker = Dispatchers.Default.limitedParallelism(1)

    suspend fun <T> recognize(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!mutex.tryLock()) throw OcrBusyException()
        return try {
            withContext(worker) {
                currentCoroutineContext().ensureActive()
                val result = block()
                currentCoroutineContext().ensureActive()
                result
            }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun release(block: suspend () -> Unit) = withContext(NonCancellable) {
        mutex.withLock { withContext(worker) { block() } }
    }
}
