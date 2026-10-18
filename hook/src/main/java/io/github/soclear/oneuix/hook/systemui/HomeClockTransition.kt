package io.github.soclear.oneuix.hook.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Fade the clock with opacity while notification icons move into its space through the native layout.
 *
 * The clock keeps its full size and is never clipped while it moves, so no wipe edge is visible.
 * Only the space it occupies in its parent shrinks (through a negative end margin), which is what
 * lets the icons slide. Once fully hidden its width is set to zero, so it cannot take touches.
 */
internal class HomeClockTransition(private val clock: View) {
    private val originalAlpha = transitionAlpha(clock)
    private val originalAccessibility = clock.importantForAccessibility
    private var originalWidth = clock.layoutParams.width
    private var originalEnd = (clock.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0
    private var appliedWidth: Int? = null
    private var appliedEnd: Int? = null
    private var naturalWidth = 0
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
            adoptNativeLayout()
            // Follow text/font changes while the clock still has its natural width.
            if (progress > 0f && progress < 1f && clock.width > 0 && appliedWidth == originalWidth) {
                naturalWidth = clock.width
            }
            applyLayout()
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
        if (appliedWidth == null) captureNativeLayout() else adoptNativeLayout()
        // Expanded size of the current text/font, measured before the first frame of a transition.
        if (progress <= 0f && appliedWidth == null && clock.width > 0) naturalWidth = clock.width
        else if (progress >= 1f || naturalWidth <= 0) naturalWidth = measureExpandedWidth()
        val start = progress
        val end = if (value) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled() || !clock.isShown || !clock.isLaidOut) {
            render(end)
            finish()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (250L * abs(end - start)).roundToLong().coerceAtLeast(1L)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { render(start + (end - start) * it.animatedValue as Float) }
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

    private fun captureNativeLayout() {
        val params = clock.layoutParams
        originalWidth = params.width
        originalEnd = (params as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0
    }

    private fun adoptNativeLayout() {
        val params = clock.layoutParams
        if (appliedWidth != null && params.width != appliedWidth) originalWidth = params.width
        val margins = params as? ViewGroup.MarginLayoutParams ?: return
        if (appliedEnd != null && margins.marginEnd != appliedEnd) originalEnd = margins.marginEnd
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

    private fun render(value: Float) {
        progress = value.coerceIn(0f, 1f)
        // Squared, so the clock is mostly gone before the icons arrive and appears after they leave.
        val visible = 1f - progress
        setTransitionAlpha(clock, originalAlpha * visible * visible)
        applyLayout()
        updateSpacing()
        notifications?.let {
            // The same progress curve is retraced on reversal, without a fade or position jump.
            val fade = 1f - 0.55f * 4f * progress * (1f - progress)
            setTransitionAlpha(it, notificationAlpha * fade)
        }
    }

    /** Give the space back to the parent without resizing or clipping what the clock draws. */
    private fun applyLayout() {
        val params = clock.layoutParams
        val margins = params as? ViewGroup.MarginLayoutParams
        val gone = progress >= 1f
        val width = when {
            gone -> 0
            margins != null || progress <= 0f -> originalWidth
            // Without margins the only way to release space is to narrow the clock itself.
            else -> (naturalWidth * (1f - progress)).roundToInt()
        }
        val end = if (gone || margins == null) originalEnd else originalEnd - (naturalWidth * progress).roundToInt()
        appliedWidth = width
        appliedEnd = end
        var changed = false
        if (params.width != width) {
            params.width = width
            changed = true
        }
        if (margins != null && margins.marginEnd != end) {
            margins.marginEnd = end
            changed = true
        }
        if (changed) clock.layoutParams = params
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
            restoreLayout()
            restoreSpacing()
        }
    }

    private fun restoreLayout() {
        val params = clock.layoutParams
        val margins = params as? ViewGroup.MarginLayoutParams
        var changed = false
        if (appliedWidth != null && params.width == appliedWidth) {
            params.width = originalWidth
            changed = true
        }
        if (margins != null && appliedEnd != null && margins.marginEnd == appliedEnd) {
            margins.marginEnd = originalEnd
            changed = true
        }
        if (changed) clock.layoutParams = params
        appliedWidth = null
        appliedEnd = null
    }

    fun release() {
        cancelAnimation()
        clock.removeOnLayoutChangeListener(layoutListener)
        parent?.removeOnLayoutChangeListener(layoutListener)
        restoreLayout()
        restoreSpacing()
        setTransitionAlpha(clock, originalAlpha)
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
