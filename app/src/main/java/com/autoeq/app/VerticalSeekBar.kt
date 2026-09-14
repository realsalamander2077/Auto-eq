package com.autoeq.app

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar

/**
 * A genuine vertical slider: internally still lays out and measures as
 * a normal horizontal SeekBar (so all of Android's built-in thumb/track
 * drawing logic keeps working unmodified), but the canvas is rotated at
 * draw time and touch input is remapped directly from Y coordinates.
 *
 * This replaces an earlier approach that used the `android:rotation`
 * view attribute on a plain SeekBar, which renders correctly but does
 * NOT reliably remap touch hit-testing on every Android version/OEM
 * skin - sliders looked right but didn't respond to touch, which is
 * exactly the bug this class exists to fix.
 *
 * The standard OnSeekBarChangeListener is intercepted here (not passed
 * to the superclass) so we can guarantee `fromUser = true` is reported
 * accurately for every real touch-driven change.
 */
class VerticalSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SeekBar(context, attrs) {

    private var externalListener: OnSeekBarChangeListener? = null

    override fun setOnSeekBarChangeListener(l: OnSeekBarChangeListener?) {
        externalListener = l
        // Deliberately not forwarded to super - we drive all callbacks
        // ourselves from onTouchEvent below, where we know for certain
        // whether a change came from real touch input.
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredHeight, measuredWidth)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(h, w, oldh, oldw)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.rotate(-90f)
        canvas.translate(-height.toFloat(), 0f)
        super.onDraw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                externalListener?.onStartTrackingTouch(this)
                updateProgressFromTouch(event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                updateProgressFromTouch(event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                externalListener?.onStopTrackingTouch(this)
            }
        }
        return true
    }

    private fun updateProgressFromTouch(y: Float) {
        if (height <= 0) return
        // Top of the view = max value, bottom = min value (fader convention)
        val ratio = 1f - (y / height.toFloat()).coerceIn(0f, 1f)
        val newProgress = (ratio * max).toInt().coerceIn(0, max)
        if (newProgress != progress) {
            super.setProgress(newProgress)
            externalListener?.onProgressChanged(this, newProgress, true)
            invalidate()
        }
    }
}
