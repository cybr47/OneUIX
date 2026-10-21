package io.github.soclear.oneuix.hook

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.soclear.oneuix.common.BuildConfig
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Recolours the fill of the launcher's home search bar and keeps its outline.
 *
 * The bar's own background drawable is edited in place, never replaced, so launcher code that
 * expects Samsung's drawable keeps working, and the bar's children (Google logo, microphone,
 * Lens) are never touched: no view alpha is set. Only the fill colour changes, which also carries
 * the transparency, so an existing stroke stays. When the bar is fully transparent a thin outline
 * is made sure to exist.
 *
 * The launcher re-applies its own colours (wallpaper, theme, blur changes), so the style is
 * checked before each draw and written only when it differs, which keeps this cheap.
 */
internal class HomeSearchBarStyle(
    colorLevel: Float,
    transparency: Float,
    private val report: (Throwable) -> Unit,
    private val dump: (String) -> Unit,
) {
    private val fill: Int
    private val fullyTransparent = transparency >= 0.99f

    init {
        val gray = (colorLevel.coerceIn(0f, 1f) * 255f).roundToInt()
        val alpha = ((1f - transparency.coerceIn(0f, 1f)) * 255f).roundToInt()
        fill = Color.argb(alpha, gray, gray, gray)
    }

    // Identity only: a value referring to the view or drawable would keep it alive forever.
    private val tracked = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val prepared = Collections.newSetFromMap(WeakHashMap<Drawable, Boolean>())
    private var reported = false

    fun track(bar: View) {
        if (tracked.add(bar)) Bar(bar)
    }

    private inner class Bar(view: View) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val view = WeakReference(view)
        private var observer: ViewTreeObserver? = null
        private var dumped = false

        init {
            view.addOnAttachStateChangeListener(this)
            if (view.isAttachedToWindow) onViewAttachedToWindow(view)
        }

        override fun onViewAttachedToWindow(v: View) {
            if (observer == null) observer = v.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }

        override fun onViewDetachedFromWindow(v: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
        }

        override fun onPreDraw(): Boolean {
            val bar = view.get() ?: return true
            try {
                val found = Scan()
                scanView(bar, bar, found, 0)
                if (!found.styled && found.unknown == null && bar.background == null && bar.height > 0) {
                    installFallback(bar)
                }
                if (BuildConfig.DEBUG && !dumped && bar.width > 0 && bar.height > 0) {
                    dumped = true
                    dump(describeBar(bar, found))
                }
            } catch (t: Throwable) {
                if (!reported) {
                    reported = true
                    report(t)
                }
            }
            return true
        }
    }

    private class Scan {
        var styled = false
        var unknown: String? = null
    }

    /** The bar's own background, then any large child that carries the fill instead. */
    private fun scanView(view: View, bar: View, found: Scan, depth: Int) {
        view.background?.let {
            if (styleDrawable(it, view)) found.styled = true else found.unknown = it.javaClass.name
        }
        if (view !is ViewGroup || depth >= 3) return
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            // Small children are the logo, microphone and Lens buttons: leave them alone.
            if (child.width >= bar.width * 0.6f && child.height >= bar.height * 0.6f) {
                scanView(child, bar, found, depth + 1)
            }
        }
    }

    private fun styleDrawable(drawable: Drawable, owner: View): Boolean = when (drawable) {
        is GradientDrawable -> {
            recolor(drawable, owner)
            true
        }
        is LayerDrawable -> {
            // The first filled shape is the fill; later ones are normally the outline.
            var styled = false
            for (index in 0 until drawable.numberOfLayers) {
                if (drawable.getId(index) == android.R.id.mask) continue
                val layer = drawable.getDrawable(index) ?: continue
                if (styleDrawable(layer, owner)) {
                    styled = true
                    break
                }
            }
            styled
        }
        is DrawableWrapper -> drawable.drawable?.let { styleDrawable(it, owner) } ?: false
        else -> false
    }

    private fun recolor(drawable: GradientDrawable, owner: View) {
        if (prepared.add(drawable)) {
            // Own copy of the state, so drawables shared with other views are not changed.
            drawable.mutate()
            if (fullyTransparent) drawable.setStroke(strokeWidth(owner), outlineColor(owner))
        }
        if (drawable.color?.defaultColor != fill) drawable.setColor(fill)
    }

    private fun installFallback(bar: View) {
        val drawable = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius(bar)
            if (fullyTransparent) setStroke(strokeWidth(bar), outlineColor(bar))
        }
        prepared.add(drawable)
        bar.background = drawable
    }

    private fun strokeWidth(view: View) = max(1, view.resources.displayMetrics.density.roundToInt())

    private fun outlineColor(view: View): Int {
        val id = view.resources.getIdentifier("homescreen_search_bar_outline_color", "color", view.context.packageName)
        return (if (id != 0) runCatching { view.context.getColor(id) }.getOrNull() else null)
            ?: Color.argb(200, 190, 190, 195)
    }

    private fun radius(view: View): Float {
        val id = view.resources.getIdentifier("homescreen_search_bar_bg_radius", "dimen", view.context.packageName)
        return (if (id != 0) runCatching { view.resources.getDimension(id) }.getOrNull() else null)
            ?: (view.height / 2f)
    }

    /** Debug builds only: what the bar is made of, so a mismatch with a launcher build is visible. */
    private fun describeBar(bar: View, found: Scan): String = buildString {
        append("Home search bar ").append(bar.javaClass.name).append(" (super ")
            .append(bar.javaClass.superclass?.name).append(") ")
            .append(bar.width).append('x').append(bar.height)
            .append(" styled=").append(found.styled).append(" unknownBackground=").append(found.unknown)
        var lines = 0
        fun walk(view: View, depth: Int) {
            if (lines++ >= 80) return
            val name = if (view.id == View.NO_ID) "-" else runCatching {
                view.resources.getResourceEntryName(view.id)
            }.getOrDefault("?")
            append('\n').append("  ".repeat(depth)).append(name).append(" : ").append(view.javaClass.name)
                .append(' ').append(view.width).append('x').append(view.height)
                .append(" bg=").append(view.background?.let { describeDrawable(it) } ?: "none")
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(bar, 1)
    }

    private fun describeDrawable(drawable: Drawable): String = buildString {
        append(drawable.javaClass.name)
        if (drawable is GradientDrawable) {
            append(" color=").append(drawable.color?.defaultColor?.let { "#%08x".format(it) } ?: "none")
        }
        if (drawable is LayerDrawable) {
            append(" layers=[")
            for (i in 0 until drawable.numberOfLayers) {
                if (i > 0) append(", ")
                drawable.getDrawable(i)?.let { append(it.javaClass.simpleName) }
            }
            append(']')
        }
    }
}
