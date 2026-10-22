package io.github.soclear.oneuix.hook

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fill colour, transparency and outline of One UI Home's Google search bar.
 *
 * With the Google search widget the widget itself is transparent and `WidgetSearchBar` paints the
 * pill: a rounded background plus, inside the widget host container, a blurred wallpaper image
 * (QSBBlurBackground) and a dim layer (QSBDimBackground). The Google logo, microphone and Lens
 * icons are the widget's own views.
 *
 * The bar is found when it attaches to a window and its state is checked before each draw, because
 * the launcher re-applies its own look; values are only written when they differ. The view's alpha
 * is never changed. The launcher's blur is kept: the colour is drawn over it as a foreground of the
 * blur image, so the bar looks like tinted frosted glass. Without a blur image the colour fills the
 * bar's background.
 */
internal class HomeSearchBarStyle(colorLevel: Float, transparency: Float) {
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
    private val overlays = WeakHashMap<View, GradientDrawable>()

    private val darkIcons = PorterDuffColorFilter(0xFF202124.toInt(), PorterDuff.Mode.SRC_IN)

    /** A light, mostly opaque bar needs dark icons; anything else keeps the launcher's own. */
    private val lightBar = gray >= 128 && Color.alpha(fill) >= 128

    // Samsung's real-time blur accessors on View (hidden API); absent on other firmware.
    private val getBlur: Method? by lazy { semMethod("semGetBlurInfo", 0) }
    private val setBlur: Method? by lazy { semMethod("semSetBlurInfo", 1) }

    fun track(bar: View) {
        if (tracked.add(bar)) Bar(bar)
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
            if (bar.width > 0 && bar.height > 0) runCatching { restyle(bar) }
            return true
        }
    }

    private fun restyle(bar: View) {
        val layers = layers(bar)
        val blur = layers.firstOrNull { it.javaClass.name.endsWith("QSBBlurBackground") } as? ImageView
        // The launcher's blurred wallpaper is kept when it has an image; the colour is then drawn
        // over it. Without it the colour fills the bar.
        val blurred = blur != null && blur.visibility == View.VISIBLE && blur.drawable != null

        // 1. The bar's own rounded background carries the colour when there is no blur.
        val current = bar.background
        val backgroundColor = if (blurred) Color.TRANSPARENT else fill
        if (current is GradientDrawable) {
            if (prepared.add(current)) {
                current.mutate()
                if (fullyTransparent && !blurred) current.setStroke(outlineWidth(bar), outlineColor())
            }
            if (current.color?.defaultColor != backgroundColor) current.setColor(backgroundColor)
        } else if (current == null || current !in prepared) {
            bar.background = pill(bar, backgroundColor, withStroke = fullyTransparent && !blurred)
        }

        // 2. With blur, the colour (and outline) is a foreground of the blur image: drawn above
        // the blur and below the Google widget's own icons. The dim layer would only muddy the
        // colour, so it is hidden.
        if (blur != null) {
            if (blurred) {
                val overlay = overlays[blur] ?: pill(blur, fill, withStroke = fullyTransparent).also { overlays[blur] = it }
                if (blur.foreground !== overlay) blur.foreground = overlay
            } else if (overlays[blur] != null && blur.foreground === overlays[blur]) {
                blur.foreground = null
            }
        }
        layers.filter { it.javaClass.name.endsWith("QSBDimBackground") && it.visibility != View.GONE }
            .forEach { it.visibility = View.GONE }

        // 3. Real-time blur, drawn behind the whole view by the window compositor.
        val get = getBlur
        val set = setBlur
        if (get != null && set != null && get.invoke(bar) != null) set.invoke(bar, null as Any?)

        // 4. Microphone and Lens icons (right half of the widget) follow the colour: dark on a
        // light bar. The Google logo on the left keeps its own colours.
        tintIcons(bar)
    }

    private fun pill(view: View, color: Int, withStroke: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dimension(view, "homescreen_search_bar_bg_radius") ?: (view.height / 2f)
            setColor(color)
            if (withStroke) setStroke(outlineWidth(view), outlineColor())
            prepared.add(this)
        }

    private fun tintIcons(bar: View) {
        val host = findByName(bar, "QSBAppWidgetHostView", 0) ?: return
        val barLocation = IntArray(2)
        bar.getLocationInWindow(barLocation)
        val location = IntArray(2)
        fun walk(view: View, depth: Int) {
            if (view is ImageView) {
                view.getLocationInWindow(location)
                val centerX = location[0] - barLocation[0] + view.width / 2
                if (centerX > bar.width / 2) {
                    if (lightBar) {
                        if (view.colorFilter !== darkIcons) view.colorFilter = darkIcons
                    } else if (view.colorFilter === darkIcons) {
                        view.clearColorFilter()
                    }
                }
            } else if (view is ViewGroup && depth < 6) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index), depth + 1)
            }
        }
        walk(host, 0)
    }

    /** The blur and dim layers live a few levels down, next to the widget host view. */
    private fun layers(root: View): List<View> {
        val found = mutableListOf<View>()
        fun walk(view: View, depth: Int) {
            if (view !is ViewGroup || depth > 3) return
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                val name = child.javaClass.name
                if (name.endsWith("QSBBlurBackground") || name.endsWith("QSBDimBackground")) found += child
                else walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return found
    }

    private fun findByName(root: View, suffix: String, depth: Int): View? {
        if (root.javaClass.name.endsWith(suffix)) return root
        if (root !is ViewGroup || depth > 4) return null
        for (index in 0 until root.childCount) {
            findByName(root.getChildAt(index), suffix, depth + 1)?.let { return it }
        }
        return null
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
}
