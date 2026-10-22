package io.github.soclear.oneuix.hook

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import io.github.soclear.oneuix.common.BuildConfig
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fill colour, transparency and outline of One UI Home's Google search bar.
 *
 * From the launcher's log and a dump of its view tree: with the Google search widget the widget is
 * transparent (QSBWidgetInfo backgroundColor unset, blur mode SAMSUNG_UX) and `WidgetSearchBar`
 * paints the pill itself with its own children: a blurred wallpaper image (QSBBlurBackground) and
 * a dim layer (QSBDimBackground) next to the widget host. (`HomeSearchBar`, the Samsung finder
 * bar, is hidden in this setup.) The Google logo, microphone and
 * Lens icons are the widget's own views and are never touched.
 *
 * HomeSearchBar is found when it attaches to a window (a framework call, so the hook is reliable;
 * constructor and small private methods can be inlined by the runtime and silently skipped). Its
 * state is then checked before each draw, because the launcher re-applies its own look, and only
 * written when it differs. The view's alpha is never changed.
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

    // Identity only: a value referring to the view or drawable would keep it alive forever.
    private val tracked = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val prepared = Collections.newSetFromMap(WeakHashMap<Drawable, Boolean>())

    /** Debug builds only: facts shown by the launcher toast. */
    val facts = mutableListOf<String>()
    val barsTracked: Int get() = tracked.size

    private val getBlur: Method? by lazy { semMethod("semGetBlurInfo", 0) }
    private val setBlur: Method? by lazy { semMethod("semSetBlurInfo", 1) }

    fun track(bar: View) {
        if (tracked.add(bar)) Bar(bar)
    }

    /**
     * Finds HomeSearchBar in a window's view tree. Some launcher methods are never reached by
     * hooks (the runtime can inline them), so this runs from a hook known to fire (the Launcher
     * activity resuming) and does not depend on any HomeSearchBar method being called.
     */
    fun discover(root: View): String? {
        val related = mutableListOf<String>()
        fun walk(view: View) {
            val name = view.javaClass.name
            if (name.endsWith(".WidgetSearchBar")) track(view)
            if (BuildConfig.DEBUG && related.size < 14 && (name.contains("QSB") || name.contains("SearchBar"))) {
                related += name.substringAfterLast('.') + " " + view.width + "x" + view.height +
                    " v=" + view.visibility + " attached=" + view.isAttachedToWindow
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
        return if (BuildConfig.DEBUG) related.joinToString("\n").ifEmpty { "no QSB views in this window" } else null
    }

    private inner class Bar(view: View) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
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
            val bar = view.get() ?: return true
            if (bar.width <= 0 || bar.height <= 0) return true
            try {
                restyle(bar)
                if (BuildConfig.DEBUG && !summaryScheduled) {
                    summaryScheduled = true
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

    private fun restyle(bar: View) {
        // 1. The rounded background: recolour the launcher's own drawable, or replace anything else.
        val current = bar.background
        if (current is GradientDrawable) {
            if (prepared.add(current)) {
                current.mutate()
                if (fullyTransparent) current.setStroke(outlineWidth(bar), outlineColor())
            }
            if (current.color?.defaultColor != fill) current.setColor(fill)
        } else if (current == null || current !in prepared) {
            bar.background = GradientDrawable().apply {
                cornerRadius = dimension(bar, "homescreen_search_bar_bg_radius") ?: (bar.height / 2f)
                setColor(fill)
                if (fullyTransparent) setStroke(outlineWidth(bar), outlineColor())
                prepared.add(this)
            }
        }

        // 2. Blur and dim layers sit above the background and would cover the new fill.
        if (bar is ViewGroup) {
            for (index in 0 until bar.childCount) {
                val child = bar.getChildAt(index)
                val name = child.javaClass.name
                if ((name.endsWith("QSBBlurBackground") || name.endsWith("QSBDimBackground")) &&
                    child.visibility != View.GONE
                ) {
                    child.visibility = View.GONE
                }
            }
        }

        // 3. Real-time blur, drawn behind the whole view by the window compositor.
        val get = getBlur
        val set = setBlur
        if (get != null && set != null && get.invoke(bar) != null) set.invoke(bar, null as Any?)
    }

    private fun semMethod(name: String, parameters: Int): Method? =
        View::class.java.methods.firstOrNull { it.name == name && it.parameterCount == parameters }

    private fun outlineWidth(view: View) =
        max(1, dimension(view, "homescreen_search_bar_outline_width")?.roundToInt()
            ?: view.resources.displayMetrics.density.roundToInt())

    /** A transparent bar keeps a thin outline, light on dark fills and dark on light ones. */
    private fun outlineColor() =
        if (gray < 128) Color.argb(120, 255, 255, 255) else Color.argb(120, 0, 0, 0)

    private fun dimension(view: View, name: String): Float? {
        val id = view.resources.getIdentifier(name, "dimen", view.context.packageName)
        return if (id == 0) null else runCatching { view.resources.getDimension(id) }.getOrNull()
    }

    /** Debug builds only. */
    private fun describe(bar: View): String {
        val lines = mutableListOf<String>()
        val blur = runCatching { getBlur?.invoke(bar) != null }.getOrDefault(false)
        lines += "bars=$barsTracked blurInfo=$blur sem=${getBlur != null}/${setBlur != null}"
        fun walk(view: View, depth: Int) {
            if (lines.size >= 16) return
            lines += "  ".repeat(depth) + view.javaClass.simpleName + " " + view.width + "x" + view.height +
                " v=" + view.visibility + " bg=" + (view.background?.javaClass?.simpleName ?: "-")
            if (view is ViewGroup && depth < 2) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        (bar.parent as? View)?.let { lines += "parent " + it.javaClass.simpleName + " " + it.width + "x" + it.height }
        walk(bar, 0)
        return lines.joinToString("\n")
    }
}
