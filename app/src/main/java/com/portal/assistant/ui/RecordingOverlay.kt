package com.portal.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.portal.commons.DebugLog

/**
 * An orange bar along the bottom edge, drawn **over any app** (`TYPE_APPLICATION_OVERLAY`), shown **only
 * while we are recording the mic for the assistant** — a privacy/feedback indicator that the mic is live.
 * The glow pulses with the audio level. Pass-through (not focusable/touchable), so it never steals input.
 *
 * No-ops gracefully if `SYSTEM_ALERT_WINDOW` isn't granted. All window ops are marshalled to the main thread.
 */
class RecordingOverlay(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: BarView? = null

    /** Show (recording=true) or hide the bar; [level] is the 0..1 audio level driving the glow. */
    fun setRecording(recording: Boolean, level: Float = 0f) {
        main.post {
            if (recording) show(level) else hide()
        }
    }

    fun dismiss() = main.post { hide() }

    private fun show(level: Float) {
        if (!Settings.canDrawOverlays(context)) {
            return // overlay permission not granted — silently skip
        }

        var v = view
        if (v == null) {
            v = BarView(context)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                BAR_HEIGHT_PX,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM }
            runCatching { wm.addView(v, lp) }.onFailure { return }
            view = v
            // The bar is now actually in the WindowManager (main thread) — the true "ready & listening"
            // instant. Logged here (not from the engine's capture-thread setRecording call, which only
            // posts here) so the latency marker times the real add, not the first forwarded frame.
            DebugLog.log("overlay shown — listening")
        }
        v.setLevel(level)
    }

    private fun hide() {
        val v = view ?: return
        view = null
        runCatching { wm.removeView(v) }
    }

    /** Bottom bar: a solid edge line with a level-driven gradient glow above it. */
    private class BarView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var level = 0f

        // Preallocated to avoid per-frame allocations in onDraw (see DrawAllocation lint). A unit-height
        // vertical gradient (solid COLOR at y=1 → transparent at y=0); onDraw stretches it to the current
        // glow height with a reused local matrix instead of rebuilding the gradient each frame.
        private val glowGradient =
            LinearGradient(0f, 1f, 0f, 0f, COLOR, COLOR_TRANSPARENT, Shader.TileMode.CLAMP)
        private val glowMatrix = Matrix()

        fun setLevel(l: Float) {
            level = l.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val edge = h * 0.10f
            val glow = edge + (h - edge) * (0.30f + 0.70f * level)
            // Map the unit gradient (y 0..1) onto the screen span [h-glow, h].
            glowMatrix.setScale(1f, glow)
            glowMatrix.postTranslate(0f, h - glow)
            glowGradient.setLocalMatrix(glowMatrix)
            paint.shader = glowGradient
            canvas.drawRect(0f, h - glow, w, h, paint)
            paint.shader = null
            paint.color = COLOR
            canvas.drawRect(0f, h - edge, w, h, paint)
        }

        private companion object {
            const val COLOR = 0xFFFF8A00.toInt() // orange = recording
            const val COLOR_TRANSPARENT = 0x00FF8A00 // COLOR with zero alpha (gradient fade-out)
        }
    }

    private companion object {
        const val BAR_HEIGHT_PX = 56
    }
}
