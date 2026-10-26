package com.dawnyew.app.capture

/** Screenshots may arrive after cancellation, or twice; every stage belongs to one request. */
internal class FloatingCaptureRequestGate {
    private var sequence = 0L
    private var current: Long? = null
    private var screenshotClaimed = false

    @Synchronized fun begin(): Long? {
        if (current != null) return null
        return (++sequence).also { current = it; screenshotClaimed = false }
    }
    @Synchronized fun isCurrent(id: Long): Boolean = current == id
    @Synchronized fun claimScreenshot(id: Long): Boolean {
        if (current != id || screenshotClaimed) return false
        screenshotClaimed = true
        return true
    }
    @Synchronized fun finish(id: Long): Boolean {
        if (current != id) return false
        current = null
        return true
    }
    @Synchronized fun cancel() { current = null }
}

internal data class FloatingBallPosition(val x: Int, val y: Int)

internal object FloatingBallGeometry {
    fun position(width: Int, height: Int, size: Int, top: Int, bottom: Int, right: Boolean, fraction: Float): FloatingBallPosition {
        val safeTop = top.coerceIn(0, (height - size).coerceAtLeast(0))
        val travel = (height - safeTop - bottom - size).coerceAtLeast(0)
        val normalized = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else .5f
        return FloatingBallPosition(if (right) (width - size).coerceAtLeast(0) else 0, safeTop + (travel * normalized).toInt())
    }
    fun verticalFraction(y: Int, height: Int, size: Int, top: Int, bottom: Int): Float {
        val travel = (height - top - bottom - size).coerceAtLeast(0)
        return if (travel == 0) .5f else ((y - top).toFloat() / travel).coerceIn(0f, 1f)
    }
}
