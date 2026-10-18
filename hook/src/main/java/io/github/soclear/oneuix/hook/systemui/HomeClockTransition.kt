package io.github.soclear.oneuix.hook.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Collapse the clock through the native layout so notification capacity grows with its space. */
internal class HomeClockTransition(private val clock: View) {
    private val originalAlpha = transitionAlpha(clock)
    private val originalAccessibility = clock.importantForAccessibility
    private val originalClip = clock.clipBounds
    private var originalWidth = clock.layoutParams.width
    private var appliedWidth: Int? = null
    private var progress = 0f
    private var hidden = false
    private var animator: ValueAnimator? = null
    private var parent: ViewGroup? = null
    private var spacing: Spacing? = null
    private var notifications: View? = null
    private var notificationAlpha = 1f
    private val leftClockId = clock.resources.getIdentifier("left_clock_container", "id", "com.android.systemui")
    private val notificationId = clock.resources.getIdentifier("notification_icon_area", "id", "com.android.systemui")
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        bindParent()
        if (appliedWidth != null) {
            val params = clock.layoutParams
            if (params.width != appliedWidth) {
                originalWidth = params.width
                params.width = appliedWidth!!
                clock.layoutParams = params
            }
            clipClock(minOf(clock.width, appliedWidth!!))
            updateSpacing()
        }
    }

    private class Spacing(val view: ViewGroup, var start: Int, var end: Int) {
        var appliedStart: Int? = null
        var appliedEnd: Int? = null
    }

    init {
        clock.addOnLayoutChangeListener(layoutListener)
        bindParent()
    }

    fun setHidden(value: Boolean) {
        bindParent()
        if (hidden == value) return
        hidden = value
        cancelAnimation()
        clock.importantForAccessibility = if (value) View.IMPORTANT_FOR_ACCESSIBILITY_NO else originalAccessibility
        // Native callers may replace LayoutParams during a configuration or clock-position change.
        val params = clock.layoutParams
        if (appliedWidth == null || params.width != appliedWidth) originalWidth = params.width
        val startWidth = appliedWidth ?: clock.width
        val endWidth = if (value) 0 else measureExpandedWidth()
        val start = progress
        val end = if (value) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled() || !clock.isShown || !clock.isLaidOut) {
            render(end, endWidth)
            finish()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (250L * abs(end - start)).roundToLong().coerceAtLeast(1L)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                render(start + (end - start) * fraction,
                    (startWidth + (endWidth - startWidth) * fraction).roundToInt())
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    finish()
                }
            })
            start()
        }
    }

    private fun cancelAnimation() {
        animator?.apply {
            removeAllListeners()
            cancel()
            removeAllUpdateListeners()
        }
        animator = null
    }

    private fun measureExpandedWidth(): Int {
        val host = parent ?: return clock.measuredWidth
        val params = clock.layoutParams
        val margins = params as? ViewGroup.MarginLayoutParams
        // The clock's wrap-content parent can itself be zero-width while hidden.
        val available = (host.parent as? View)?.width?.takeIf { it > 0 }
            ?: clock.rootView.width
        val width = ViewGroup.getChildMeasureSpec(
            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.EXACTLY),
            host.paddingLeft + host.paddingRight + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0),
            originalWidth
        )
        val height = ViewGroup.getChildMeasureSpec(
            View.MeasureSpec.makeMeasureSpec(host.height, View.MeasureSpec.EXACTLY),
            host.paddingTop + host.paddingBottom + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0),
            params.height
        )
        // Measure the current text/font once on return, including changes while the clock was hidden.
        clock.measure(width, height)
        return clock.measuredWidth
    }

    private fun render(value: Float, width: Int) {
        progress = value.coerceIn(0f, 1f)
        setTransitionAlpha(clock, originalAlpha * (1f - progress))
        val params = clock.layoutParams
        val nextWidth = width.coerceAtLeast(0)
        appliedWidth = nextWidth
        if (params.width != nextWidth) {
            params.width = nextWidth
            clock.layoutParams = params
        }
        // Text can draw beyond its bounds because Samsung disables clipChildren on this container.
        // Keep its fading glyphs inside the space that the icons have not yet reclaimed.
        clipClock(minOf(clock.width, nextWidth))
        updateSpacing()
        notifications?.let {
            // The same progress curve is retraced on reversal, without a fade or position jump.
            val fade = 1f - 0.55f * 4f * progress * (1f - progress)
            setTransitionAlpha(it, notificationAlpha * fade)
        }
    }

    private fun clipClock(width: Int) {
        val clip = Rect(0, 0, width.coerceAtLeast(0), clock.height)
        originalClip?.let { if (!clip.intersect(it)) clip.setEmpty() }
        clock.clipBounds = clip
    }

    private fun bindParent() {
        val next = clock.parent as? ViewGroup
        if (next === parent) return
        restoreSpacing()
        parent?.removeOnLayoutChangeListener(layoutListener)
        notifications?.let { setTransitionAlpha(it, notificationAlpha) }
        notifications = null
        spacing = null
        parent = next
        next?.addOnLayoutChangeListener(layoutListener)
        // QuickStar can move the clock to the centre/right. Only the left clock shares this space.
        if (leftClockId != 0 && next?.id == leftClockId) {
            (next.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                spacing = Spacing(next, it.marginStart, it.marginEnd)
            }
            if (notificationId != 0) {
                notifications = clock.rootView.findViewById(notificationId)
                notificationAlpha = notifications?.let(::transitionAlpha) ?: 1f
            }
        }
    }

    private fun updateSpacing() {
        val state = spacing ?: return
        val params = state.view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        // Keep density/QuickStar updates made by native code while we own the temporary margins.
        if (state.appliedStart == null || params.marginStart != state.appliedStart) state.start = params.marginStart
        if (state.appliedEnd == null || params.marginEnd != state.appliedEnd) state.end = params.marginEnd
        val hasChip = (0 until state.view.childCount).any {
            val child = state.view.getChildAt(it)
            child !== clock && child.visibility != View.GONE
        }
        // Never remove spacing around a live activity chip that shares Samsung's clock container.
        val factor = if (hasChip) 1f else 1f - progress
        val start = (state.start * factor).roundToInt()
        val end = (state.end * factor).roundToInt()
        state.appliedStart = start
        state.appliedEnd = end
        if (params.marginStart != start || params.marginEnd != end) {
            params.marginStart = start
            params.marginEnd = end
            state.view.layoutParams = params
        }
    }

    private fun restoreSpacing() {
        val state = spacing ?: return
        val params = state.view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (state.appliedStart != null && params.marginStart == state.appliedStart) params.marginStart = state.start
        if (state.appliedEnd != null && params.marginEnd == state.appliedEnd) params.marginEnd = state.end
        state.appliedStart = null
        state.appliedEnd = null
        state.view.layoutParams = params
    }

    private fun finish() {
        notifications?.let { setTransitionAlpha(it, notificationAlpha) }
        if (!hidden) {
            restoreWidth()
            clock.clipBounds = originalClip
            restoreSpacing()
        }
    }

    private fun restoreWidth() {
        val params = clock.layoutParams
        if (params.width == appliedWidth) {
            params.width = originalWidth
            clock.layoutParams = params
        }
        appliedWidth = null
    }

    fun release() {
        cancelAnimation()
        clock.removeOnLayoutChangeListener(layoutListener)
        parent?.removeOnLayoutChangeListener(layoutListener)
        restoreWidth()
        restoreSpacing()
        setTransitionAlpha(clock, originalAlpha)
        clock.clipBounds = originalClip
        clock.importantForAccessibility = originalAccessibility
        notifications?.let { setTransitionAlpha(it, notificationAlpha) }
        notifications = null
    }

    private companion object {
        val setAlpha = View::class.java.getDeclaredMethod("setTransitionAlpha", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val getAlpha = View::class.java.getDeclaredMethod("getTransitionAlpha").apply { isAccessible = true }
        fun transitionAlpha(view: View) = getAlpha.invoke(view) as Float
        fun setTransitionAlpha(view: View, alpha: Float) { setAlpha.invoke(view, alpha) }
    }
}
