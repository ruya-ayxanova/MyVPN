package com.example.myvpn

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView

object C {
    val BG = Color.parseColor("#0E1018")
    val CARD = Color.parseColor("#1A1D29")
    val TEXT = Color.parseColor("#FFFFFF")
    val MUTED = Color.parseColor("#8A90A6")
    val GREEN = Color.parseColor("#2ECC71")
    val AMBER = Color.parseColor("#F5A524")
    val GRAY = Color.parseColor("#5A5F73")
    val RED = Color.parseColor("#DA3940")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.roundBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

fun Context.text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = t
    textSize = sp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

class PowerButton(c: Context) : View(c) {
    var color: Int = C.GRAY
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = C.CARD }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var anim: ObjectAnimator? = null

    fun setBusy(b: Boolean) {
        if (b && anim == null) {
            anim = ObjectAnimator.ofFloat(this, "alpha", 1f, 0.35f).apply {
                duration = 700
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        } else if (!b && anim != null) {
            anim?.cancel(); anim = null; alpha = 1f
        }
    }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - context.dp(6)
        cv.drawCircle(cx, cy, r, fill)
        ring.color = color
        ring.strokeWidth = context.dp(4).toFloat()
        cv.drawCircle(cx, cy, r, ring)
        val ir = r * 0.38f
        ring.strokeWidth = context.dp(5).toFloat()
        cv.drawArc(RectF(cx - ir, cy - ir, cx + ir, cy + ir), -60f, 300f, false, ring)
        cv.drawLine(cx, cy - ir * 1.15f, cx, cy - ir * 0.1f, ring)
    }
}
