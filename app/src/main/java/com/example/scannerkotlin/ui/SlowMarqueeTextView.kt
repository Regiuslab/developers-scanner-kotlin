package com.example.scannerkotlin.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * Single-line title with slow horizontal auto-scroll for long names.
 * Uses scrollX (clipped to bounds) so the start of the name stays readable
 * and siblings are never shifted by translation.
 */
class SlowMarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var scrollAnimator: ObjectAnimator? = null
    private var pendingRestart = false
    private var loopGeneration = 0

    init {
        isSingleLine = true
        ellipsize = null
        setHorizontallyScrolling(true)
        // No fading edge: on small TSD widths it eats the first letters of the name.
        isHorizontalFadingEdgeEnabled = false
        // Avoid focus stealing from inputs on TSD.
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        pendingRestart = true
        scheduleRestart()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pendingRestart = true
        scheduleRestart()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleRestart()
    }

    override fun onDetachedFromWindow() {
        stopMarquee()
        super.onDetachedFromWindow()
    }

    private fun scheduleRestart() {
        removeCallbacks(restartRunnable)
        post(restartRunnable)
    }

    private val restartRunnable = Runnable {
        if (pendingRestart || scrollAnimator == null) {
            pendingRestart = false
            startMarqueeIfNeeded()
        }
    }

    private fun startMarqueeIfNeeded() {
        stopMarquee()
        scrollTo(0, 0)

        val content = text?.toString().orEmpty()
        if (content.isEmpty() || width <= 0) return

        val textWidth = paint.measureText(content)
        val viewWidth = (width - paddingLeft - paddingRight).toFloat()
        val overflow = textWidth - viewWidth
        if (overflow <= 4f) return

        val durationMs = (overflow / 24f * 1000f).toLong().coerceIn(4000L, 24000L)
        val generation = loopGeneration

        val restartLoop = object : Runnable {
            override fun run() {
                if (scrollAnimator == null || loopGeneration != generation) return
                scrollTo(0, 0)
                postDelayed({
                    val animator = scrollAnimator
                    if (animator == null || loopGeneration != generation) return@postDelayed
                    animator.start()
                }, 1100L)
            }
        }

        scrollAnimator = ObjectAnimator.ofInt(this, "scrollX", 0, overflow.toInt()).apply {
            duration = durationMs
            startDelay = 2500L
            interpolator = LinearInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (scrollAnimator !== animation || loopGeneration != generation) return
                    postDelayed(restartLoop, 800L)
                }
            })
            start()
        }
    }

    private fun stopMarquee() {
        loopGeneration += 1
        scrollAnimator?.removeAllListeners()
        scrollAnimator?.cancel()
        scrollAnimator = null
        removeCallbacks(restartRunnable)
        scrollTo(0, 0)
    }
}
