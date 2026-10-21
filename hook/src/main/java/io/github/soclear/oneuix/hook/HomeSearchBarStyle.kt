package io.github.soclear.oneuix.hook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import io.github.soclear.oneuix.common.BuildConfig
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Recolours the home screen's Google search bar: fill colour, transparency and a kept outline.
 *
 * The bar is found in two independent ways, because the launcher's class layout is not verified:
 * the Honeyspace classes report their instances, and the launcher's view tree is searched on
 * resume for a wide view whose id or class looks like a search bar. Either way only the bar's
 * background is edited (a drawable fill, never view alpha), so the Google logo, microphone and
 * Lens icon are untouched.
 *
 * A native [GradientDrawable] is edited in place. Anything else (no background, a bitmap, a blur
 * drawable) is replaced by a plain [GradientDrawable], which is also safe for launcher code that
 * casts the background. The style is checked before each draw because the launcher re-applies
 * its own colours, and written only when it differs.
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
    private val notes = mutableListOf<String>()
    private var reported = false
    private var toasted = false

    /** Facts about the hook phase, shown with the debug summary. */
    fun note(text: String) {
        if (BuildConfig.DEBUG) notes += text
    }

    fun track(view: View) {
        if (tracked.add(view)) Bar(view)
    }

    /** The launcher's search bar class is not necessarily a View: use it or the Views it holds. */
    fun trackObject(target: Any?) {
        when (target) {
            null -> Unit
            is View -> track(target)
            else -> {
                var type: Class<*>? = target.javaClass
                while (type != null && type != Any::class.java) {
                    type.declaredFields.forEach { field ->
                        if (View::class.java.isAssignableFrom(field.type)) {
                            runCatching {
                                field.isAccessible = true
                                (field.get(target) as? View)?.let(::track)
                            }
                        }
                    }
                    type = type.superclass
                }
            }
        }
    }

    /** Search the launcher's view tree for the Home search bar. Called after the launcher resumes. */
    fun discover(root: View) {
        try {
            val metrics = root.resources.displayMetrics
            val found = mutableListOf<View>()
            fun walk(view: View) {
                val type = view.javaClass.simpleName.lowercase()
                val label = (entryName(view).orEmpty() + " " + type).lowercase()
                val looksLikeSearch = "searchbar" in label || "search_bar" in label || "qsb" in label
                // The app drawer has its own bar (AppsSearchBar) that this option does not touch.
                if (looksLikeSearch && "apps" !in type && view.width >= metrics.widthPixels * 0.4f) {
                    found += view
                    return
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
            walk(root)
            found.forEach(::track)
            if (BuildConfig.DEBUG && !toasted && (found.isNotEmpty() || root.width > 0)) {
                toasted = true
                val summary = summary(found)
                dump(summary)
                Toast.makeText(root.context, summary, Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) {
            if (!reported) {
                reported = true
                report(t)
            }
        }
    }

    private inner class Bar(view: View) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val view = WeakReference(view)
        private var observer: ViewTreeObserver? = null

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
                // Small views reported by the launcher (icons) are not the bar.
                if (bar.width < bar.resources.displayMetrics.widthPixels * 0.4f || bar.height <= 0) return true
                val found = Scan()
                scanView(bar, bar, found, 0)
                if (!found.styled) installReplacement(bar)
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
    }

    /** The bar's own background, then any large child that carries the fill instead. */
    private fun scanView(view: View, bar: View, found: Scan, depth: Int) {
        view.background?.let { if (styleDrawable(it, view)) found.styled = true }
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

    /** Used when the bar has no drawable of a kind that can be recoloured (none, bitmap, blur). */
    private fun installReplacement(bar: View) {
        val drawable = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius(bar)
            setStroke(strokeWidth(bar), outlineColor(bar))
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

    private fun entryName(view: View): String? =
        if (view.id == View.NO_ID) null else runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    /** Debug builds only: shown on screen and logged, so a mismatch is visible without a log. */
    private fun summary(found: List<View>): String = buildString {
        append("OneUIX search bar debug")
        notes.forEach { append('\n').append(it) }
        append("\nmatches in view tree: ").append(found.size)
        found.take(4).forEach { view ->
            append('\n').append(view.javaClass.simpleName).append(" #").append(entryName(view) ?: "-")
                .append(' ').append(view.width).append('x').append(view.height)
                .append(" bg=").append(view.background?.let { describeDrawable(it) } ?: "none")
            if (view is ViewGroup) {
                val kids = (0 until view.childCount).take(6).joinToString(",") {
                    val child = view.getChildAt(it)
                    child.javaClass.simpleName + "(" + describeDrawable(child.background) + ")"
                }
                append("\n kids: ").append(kids)
            }
        }
    }

    private fun describeDrawable(drawable: Drawable?): String = when (drawable) {
        null -> "none"
        is GradientDrawable -> "Gradient" + (drawable.color?.defaultColor?.let { "#%08x".format(it) } ?: "")
        is LayerDrawable -> "Layer[" + (0 until drawable.numberOfLayers).joinToString("+") {
            drawable.getDrawable(it)?.javaClass?.simpleName ?: "null"
        } + "]"
        else -> drawable.javaClass.simpleName
    }
}
