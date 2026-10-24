package com.dawnyew.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View

/** Debug-only synthetic payment screen. No ledger, private data, or production capability. */
class CaptureFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(PaymentView(this))
    }
    class PaymentView(context: Context, private val amount: String = "12.34") : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.WHITE)
            canvas.save(); canvas.scale(width / 720f, height / 1280f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
            canvas.drawText("支付成功", 220f, 230f, paint)
            paint.textSize = 82f; canvas.drawText(amount, 235f, 400f, paint)
            paint.textSize = 42f; canvas.drawText("示例咖啡店", 180f, 570f, paint)
            canvas.drawText("支付时间", 60f, 730f, paint)
            canvas.drawText("2026-10-18 12:00:00", 60f, 820f, paint)
            canvas.restore()
        }
    }
}
