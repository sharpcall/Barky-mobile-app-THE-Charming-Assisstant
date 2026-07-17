package com.example.barky

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Soft rectangular perimeter fade for PiP self-view.
 *
 * When drawn as a sibling of a TextureView inside a parent with
 * [LAYER_TYPE_HARDWARE], [PorterDuff.Mode.DST_OUT] punches alpha into the
 * parent layer so the **full rectangular perimeter** of the video fades out
 * (remote video shows through). Corners stay square — not rounded.
 */
class SoftRectEdgeFadeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Fraction of width/height used for the soft fade on each side. */
    var edgeFraction: Float = 0.14f
        set(value) {
            field = value.coerceIn(0.04f, 0.35f)
            invalidate()
        }

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    init {
        // Must not draw an opaque background — only DST_OUT erasers.
        // Do NOT give this view its own layer: DST_OUT must punch the parent
        // hardware layer (walkie_pip_box) so the TextureView edges go transparent.
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_NONE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val ex = (w * edgeFraction).coerceAtLeast(1f)
        val ey = (h * edgeFraction).coerceAtLeast(1f)
        // Full erase at outer edge (black alpha 255), none toward interior
        val solid = 0xFF000000.toInt()
        val clear = 0x00000000

        // Top
        edgePaint.shader = LinearGradient(0f, 0f, 0f, ey, solid, clear, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, ey, edgePaint)

        // Bottom
        edgePaint.shader = LinearGradient(0f, h, 0f, h - ey, solid, clear, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h - ey, w, h, edgePaint)

        // Left
        edgePaint.shader = LinearGradient(0f, 0f, ex, 0f, solid, clear, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, ex, h, edgePaint)

        // Right
        edgePaint.shader = LinearGradient(w, 0f, w - ex, 0f, solid, clear, Shader.TileMode.CLAMP)
        canvas.drawRect(w - ex, 0f, w, h, edgePaint)

        edgePaint.shader = null
    }
}
