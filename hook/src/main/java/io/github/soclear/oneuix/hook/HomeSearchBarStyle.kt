package io.github.soclear.oneuix.hook

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.Toast
import io.github.soclear.oneuix.common.BuildConfig
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fill colour, transparency and outline of One UI Home's search bar (`HomeSearchBar`).
 *
 * How the launcher paints that bar (read from its code): the bar's own background is a rounded
 * [GradientDrawable] with a stroke, set through `HomeSearchBar.setBackground`. On top of it a
 * blurred wallpaper image (`QSBBlurBackground`), a dim layer (`QSBDimBackground`) and optionally a
 * real-time blur are drawn. So the visible fill is recoloured by editing the drawable passed to
 * `setBackground`, and the blur and dim layers are hidden so they do not cover it.
 *
 * Nothing here runs per frame and no view tree is scanned. Only the bar's background changes
 * (never view alpha), so the Google logo, microphone and Lens icon are untouched.
 */
internal class HomeSearchBarStyle(
    colorLevel: Float,
    transparency: Float,
    private val dump: (String) -> Unit = {},
    private val report: (Throwable) -> Unit = {},
) {
    private val fill: Int
    private val fullyTransparent = transparency >= 0.99f
    private val gray: Int

    init {
        gray = (colorLevel.coerceIn(0f, 1f) * 255f).roundToInt()
        val alpha = ((1f - transparency.coerceIn(0f, 1f)) * 255f).roundToInt()
        fill = Color.argb(alpha, gray, gray, gray)
    }

    /** The drawable the launcher wants as the bar background, restyled. Null stays null. */
    fun background(bar: View, original: Drawable?): Drawable? {
        if (original == null) return null
        val drawable = original as? GradientDrawable ?: GradientDrawable().apply {
            cornerRadius = dimension(bar, "homescreen_search_bar_bg_radius") ?: (bar.height / 2f)
        }
        drawable.mutate()
        drawable.setColor(fill)
        if (fullyTransparent) {
            // A transparent bar keeps a thin outline, light on dark fills and dark on light ones.
            val outline = if (gray < 128) Color.argb(120, 255, 255, 255) else Color.argb(120, 0, 0, 0)
            val width = dimension(bar, "homescreen_search_bar_outline_width")?.roundToInt()
                ?: bar.resources.displayMetrics.density.roundToInt()
            drawable.setStroke(max(1, width), outline)
        }
        return drawable
    }

    /** The launcher's blur and dim layers sit above the background, so they are switched off. */
    fun hideLayer(layer: View) {
        if (layer.visibility != View.GONE) layer.visibility = View.GONE
    }

    // ---- The Google search widget (WidgetSearchBar) draws its own pill ----------------------
    //
    // WidgetSearchBar hosts the Google app's search widget, inflated in the launcher process. The
    // pill behind the Google logo, microphone and Lens icons belongs to that widget, so it is
    // restyled here: any large background or image in the widget's views. Small views (logo,
    // microphone, Lens) are never touched, and nothing is applied as view alpha.

    // Identity only: a value referring to the view or drawable would keep it alive forever.
    private val tracked = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val prepared = Collections.newSetFromMap(WeakHashMap<Drawable, Boolean>())

    fun trackWidgetBar(bar: View) {
        if (tracked.add(bar)) WidgetBar(bar)
    }

    private inner class WidgetBar(view: View) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val view = WeakReference(view)
        private var observer: ViewTreeObserver? = null
        private var summaryScheduled = false
        private var reported = false

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
            val bar = view.get() as? ViewGroup ?: return true
            if (bar.width <= 0 || bar.height <= 0 || bar.childCount == 0) return true
            try {
                restyle(bar, bar, 0)
                if (BuildConfig.DEBUG && !summaryScheduled && bar.getChildAt(0) is ViewGroup) {
                    summaryScheduled = true
                    // Give the widget time to inflate before describing it.
                    bar.postDelayed({
                        val text = "OneUIX search bar debug\n" + describe(bar)
                        dump(text)
                        Toast.makeText(bar.context, text, Toast.LENGTH_LONG).show()
                    }, 2500)
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

    private fun restyle(view: View, bar: View, depth: Int) {
        val large = view.width >= bar.width * 0.6f && view.height >= bar.height * 0.6f
        if (large && view !== bar) {
            view.background?.let { current ->
                if (current !in prepared) {
                    if (current is RippleDrawable && !styleDrawable(current, view)) return@let
                    if (!styleDrawable(current, view)) view.background = pill(view)
                } else {
                    styleDrawable(current, view)
                }
            }
            if (view is ImageView) {
                view.drawable?.let { current ->
                    if (current !in prepared && !styleDrawable(current, view)) view.setImageDrawable(pill(view))
                    else styleDrawable(current, view)
                }
            }
        }
        if (view is ViewGroup && depth < 6) {
            for (index in 0 until view.childCount) restyle(view.getChildAt(index), bar, depth + 1)
        }
    }

    /** Recolours drawables that can be recoloured in place. Returns false for any other kind. */
    private fun styleDrawable(drawable: Drawable, owner: View): Boolean = when (drawable) {
        is GradientDrawable -> {
            if (prepared.add(drawable)) {
                drawable.mutate()
                if (fullyTransparent) drawable.setStroke(outlineWidth(owner), outlineColor())
            }
            if (drawable.color?.defaultColor != fill) drawable.setColor(fill)
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

    /** Replacement for a pill whose drawable cannot be recoloured (bitmap, vector, state list). */
    private fun pill(view: View): Drawable = GradientDrawable().apply {
        cornerRadius = view.height / 2f
        setColor(fill)
        if (fullyTransparent) setStroke(outlineWidth(view), outlineColor())
        prepared.add(this)
    }

    private fun outlineWidth(view: View) =
        max(1, dimension(view, "homescreen_search_bar_outline_width")?.roundToInt()
            ?: view.resources.displayMetrics.density.roundToInt())

    /** A transparent bar keeps a thin outline, light on dark fills and dark on light ones. */
    private fun outlineColor() =
        if (gray < 128) Color.argb(120, 255, 255, 255) else Color.argb(120, 0, 0, 0)

    private fun describe(root: View): String {
        val lines = mutableListOf<String>()
        fun walk(view: View, depth: Int) {
            if (lines.size >= 28) return
            val bg = view.background?.javaClass?.simpleName
            val src = (view as? ImageView)?.drawable?.javaClass?.simpleName
            lines += "  ".repeat(depth) + view.javaClass.simpleName + " " + view.width + "x" + view.height +
                (bg?.let { " bg=$it" } ?: "") + (src?.let { " src=$it" } ?: "")
            if (view is ViewGroup && depth < 6) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return lines.joinToString("\n")
    }

    private fun dimension(view: View, name: String): Float? {
        val id = view.resources.getIdentifier(name, "dimen", view.context.packageName)
        return if (id == 0) null else runCatching { view.resources.getDimension(id) }.getOrNull()
    }
}
