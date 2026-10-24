package com.dawnyew.app.capture

/** Serializes cancellation with result publication, even while pixel copying blocks the worker. */
internal class CaptureRequestGate {
    private var finished = false
    private var delivered: String? = null

    @Synchronized fun isFinished(): Boolean = finished

    @Synchronized fun finish(publish: () -> String?): Boolean {
        if (finished) return false
        delivered = publish()
        finished = true
        return true
    }

    /** Explicit cancellation also retracts a result published just before the cancel event. */
    @Synchronized fun cancel(discard: (String) -> Unit, report: () -> Unit) {
        finished = true
        delivered?.let(discard)
        delivered = null
        report()
    }
}
