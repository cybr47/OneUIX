package io.github.soclear.oneuix.hook

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
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
internal class HomeSearchBarStyle(colorLevel: Float, transparency: Float) {
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

    private fun dimension(view: View, name: String): Float? {
        val id = view.resources.getIdentifier(name, "dimen", view.context.packageName)
        return if (id == 0) null else runCatching { view.resources.getDimension(id) }.getOrNull()
    }
}
