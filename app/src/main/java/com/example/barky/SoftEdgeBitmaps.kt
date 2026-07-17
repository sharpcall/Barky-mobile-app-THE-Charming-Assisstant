package com.example.barky

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader

/**
 * Soft **rectangular** perimeter transparency (not rounded corners).
 *
 * Erases a few pixels of alpha along all four edges so the self-picture
 * blends into whatever is behind it. Outer edge is fully transparent;
 * after [edgePx] the image is fully opaque.
 */
object SoftEdgeBitmaps {

    /**
     * @param src camera / preview frame (any config)
     * @param edgePx width of the transparent band on each side (pixels)
     * @return ARGB_8888 bitmap with soft perimeter; caller owns it
     */
    fun applyPerimeterFade(src: Bitmap, edgePx: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 2 || h <= 2) return src.config?.let { src.copy(it, false) } ?: src

        val e = edgePx.coerceIn(1, minOf(w, h) / 3)
        val out = if (src.config == Bitmap.Config.ARGB_8888 && src.isMutable) {
            // Work on a copy so we never mutate the TextureView snapshot in place wrongly
            src.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            src.copy(Bitmap.Config.ARGB_8888, true)
        } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { cpy ->
            Canvas(cpy).drawBitmap(src, 0f, 0f, null)
        }

        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        // DST_OUT: black (alpha 255) removes destination; transparent leaves it
        val solid = 0xFF000000.toInt()
        val clear = 0x00000000
        val wf = w.toFloat()
        val hf = h.toFloat()
        val ef = e.toFloat()

        // Top edge: fully gone at y=0, full image at y=e
        paint.shader = LinearGradient(0f, 0f, 0f, ef, solid, clear, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, wf, ef, paint)

        // Bottom
        paint.shader = LinearGradient(0f, hf, 0f, hf - ef, solid, clear, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, hf - ef, wf, hf, paint)

        // Left
        paint.shader = LinearGradient(0f, 0f, ef, 0f, solid, clear, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, ef, hf, paint)

        // Right
        paint.shader = LinearGradient(wf, 0f, wf - ef, 0f, solid, clear, Shader.TileMode.CLAMP)
        canvas.drawRect(wf - ef, 0f, wf, hf, paint)

        paint.shader = null
        paint.xfermode = null
        return out
    }
}
