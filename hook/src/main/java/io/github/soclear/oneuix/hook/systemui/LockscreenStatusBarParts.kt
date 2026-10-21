package io.github.soclear.oneuix.hook.systemui

import android.app.KeyguardManager
import android.content.res.Resources
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.soclear.oneuix.common.BuildConfig
import io.github.soclear.oneuix.common.StatusBarAppearance
import io.github.soclear.oneuix.common.StatusBarAppearance.LockscreenPart
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Hides chosen parts of a status bar view and keeps them hidden.
 *
 * One UI 9 can show the lock screen's carrier, notification icons and battery in the same status
 * bar view that Home uses, not only in KeyguardStatusBarView. With [onlyWhileLocked] the parts are
 * hidden only while the keyguard is locked and put back as soon as it is not, so Home and apps are
 * never changed. Native code can show a part again, so the choice is re-applied before each draw.
 * Views are tracked weakly and every listener is removed on detach.
 */
internal class LockscreenStatusBarParts(
    private val hidden: Set<LockscreenPart>,
    private val onlyWhileLocked: Boolean,
    private val isConnectivitySlot: (String) -> Boolean,
    private val report: (Throwable) -> Unit,
    private val dump: (String) -> Unit,
) {
    // Only identity is stored. A value that referenced the view would keep it alive forever.
    private val tracked = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun track(bar: View) {
        if (hidden.isNotEmpty() && tracked.add(bar)) Bar(bar)
    }

    private inner class Bar(view: View) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val view = WeakReference(view)
        private val keyguard = view.context.getSystemService(KeyguardManager::class.java)
        private var observer: ViewTreeObserver? = null
        private var targets = emptyList<WeakReference<View>>()
        private var foundAt = 0L
        private var checkedAt = 0L
        private var locked = false
        private var dumped = false

        // Original visibility of every view hidden here. Keys are weak and values are plain Ints.
        private val saved = WeakHashMap<View, Int>()

        init {
            view.addOnAttachStateChangeListener(this)
            if (view.isAttachedToWindow) onViewAttachedToWindow(view)
        }

        override fun onViewAttachedToWindow(v: View) {
            if (observer == null) observer = v.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            apply()
        }

        override fun onViewDetachedFromWindow(v: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            targets = emptyList()
            restore()
        }

        override fun onPreDraw(): Boolean {
            apply()
            return true
        }

        private fun isLocked(): Boolean {
            // isKeyguardLocked is a binder call: do not make one per frame.
            val now = SystemClock.uptimeMillis()
            if (now - checkedAt >= 250) {
                checkedAt = now
                locked = keyguard?.isKeyguardLocked == true
            }
            return locked
        }

        private fun apply() {
            val bar = view.get() ?: return
            try {
                if (onlyWhileLocked && !isLocked()) {
                    restore()
                    return
                }
                var views = targets.mapNotNull { it.get() }
                val now = SystemClock.uptimeMillis()
                // Icons are added at any time, so search again regularly, not only when empty.
                if (views.size != targets.size || now - foundAt >= 300 || views.any { it.parent == null }) {
                    views = find(bar)
                    targets = views.map(::WeakReference)
                    foundAt = now
                }
                views.forEach {
                    if (it.visibility != View.GONE) {
                        if (!saved.containsKey(it)) saved[it] = it.visibility
                        it.visibility = View.GONE
                    }
                }
            } catch (t: Throwable) {
                report(t)
            }
        }

        private fun restore() {
            if (saved.isEmpty()) return
            saved.entries.map { it.key to it.value }.forEach { (v, visibility) ->
                if (v.visibility == View.GONE) v.visibility = visibility
            }
            saved.clear()
        }

        private fun find(bar: View): List<View> {
            if (BuildConfig.DEBUG && !dumped) {
                dumped = true
                dump(describe(bar))
            }
            val found = mutableListOf<View>()
            fun walk(group: ViewGroup) {
                for (index in 0 until group.childCount) {
                    val child = group.getChildAt(index)
                    val part = StatusBarAppearance.lockscreenPart(entryName(child), child.javaClass.name)
                    when {
                        part == null -> if (child is ViewGroup) walk(child)
                        part == LockscreenPart.CONNECTIVITY_ICONS -> {
                            if (part in hidden) found += connectivity(child)
                            // This container can also hold other parts, such as the battery.
                            if (child is ViewGroup) walk(child)
                        }
                        // A part that is kept may still contain a part that is hidden.
                        part !in hidden -> if (child is ViewGroup) walk(child)
                        else -> found += child
                    }
                }
            }
            (bar as? ViewGroup)?.let(::walk)
            if (LockscreenPart.NOTIFICATION_ICONS in hidden &&
                found.none { StatusBarAppearance.lockscreenPart(entryName(it), it.javaClass.name) ==
                    LockscreenPart.NOTIFICATION_ICONS }
            ) {
                found += notificationIconsInWindow(bar)
            }
            if (LockscreenPart.BATTERY in hidden &&
                found.none { StatusBarAppearance.lockscreenPart(entryName(it), it.javaClass.name) ==
                    LockscreenPart.BATTERY }
            ) {
                found += batteryHostInWindow(bar)
            }
            return found.distinct()
        }

        /**
         * One UI 9's Compose battery host can be a sibling of the bound Home status-bar view.
         * Its dedicated resource ID is more reliable than guessing from the generic ComposeView
         * class name. Search only this window's view tree, and never match arbitrary Compose views.
         */
        private fun batteryHostInWindow(bar: View): List<View> {
            val id = bar.resources.getIdentifier(
                "battery_meter_composable_view", "id", bar.context.packageName
            )
            if (id == 0) return emptyList()
            val root = bar.rootView
            val found = mutableListOf<View>()
            fun walk(view: View) {
                if (view.id == id) {
                    found += view
                    return
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) walk(view.getChildAt(index))
                }
            }
            walk(root)
            return found
        }

        /**
         * The notification icon container is not always inside the bar's own subtree. Look in the
         * whole window, but only at views that sit in the status bar's own band at the top, so the
         * lower icon rows (shelf, AOD) are not touched.
         */
        private fun notificationIconsInWindow(bar: View): List<View> {
            val root = bar.rootView as? ViewGroup ?: return emptyList()
            val at = IntArray(2)
            bar.getLocationInWindow(at)
            val bandBottom = at[1] + bar.height
            if (bar.height <= 0) return emptyList()
            val found = mutableListOf<View>()
            fun walk(group: ViewGroup) {
                for (index in 0 until group.childCount) {
                    val child = group.getChildAt(index)
                    val part = StatusBarAppearance.lockscreenPart(entryName(child), child.javaClass.name)
                    if (part == LockscreenPart.NOTIFICATION_ICONS) {
                        child.getLocationInWindow(at)
                        if (child.isShown && at[1] < bandBottom) found += child
                    } else if (child is ViewGroup) walk(child)
                }
            }
            walk(root)
            return found
        }

        /** Only connectivity icons, so alarms, Do Not Disturb and similar indicators stay visible. */
        private fun connectivity(container: View): List<View> {
            var hasSlots = false
            val icons = mutableListOf<View>()
            fun walk(view: View) {
                val slot = runCatching { view.javaClass.getMethod("getSlot").invoke(view) as? String }.getOrNull()
                if (slot != null) {
                    hasSlots = true
                    if (isConnectivitySlot(slot)) icons += view
                } else if (view is ViewGroup) {
                    for (index in 0 until view.childCount) walk(view.getChildAt(index))
                }
            }
            walk(container)
            return when {
                hasSlots -> icons
                !containsPart(container, LockscreenPart.BATTERY) -> listOf(container)
                // No per-icon information and the battery is inside: hide only what is not the
                // battery, never the container as a whole.
                else -> (container as? ViewGroup)?.let { group ->
                    (0 until group.childCount).map(group::getChildAt)
                        .filter { !containsPart(it, LockscreenPart.BATTERY) }
                }.orEmpty()
            }
        }

        private fun containsPart(view: View, part: LockscreenPart): Boolean =
            StatusBarAppearance.lockscreenPart(entryName(view), view.javaClass.name) == part ||
                (view is ViewGroup && (0 until view.childCount).any { containsPart(view.getChildAt(it), part) })
    }

    private fun entryName(view: View): String? =
        if (view.id == View.NO_ID) null
        else try { view.resources.getResourceEntryName(view.id) } catch (_: Resources.NotFoundException) { null }

    /** Debug builds only: one line per view, so a mismatch with a firmware's layout is visible. */
    private fun describe(root: View): String = buildString {
        append("Status bar views (").append(root.javaClass.name).append("):")
        var lines = 0
        fun walk(view: View, depth: Int) {
            if (lines++ >= 150) return
            append('\n').append("  ".repeat(depth)).append(entryName(view) ?: "-").append(" : ")
                .append(view.javaClass.name).append(" vis=").append(view.visibility)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        append("\nName matches anywhere in this window (y = top in window):")
        var matches = 0
        val at = IntArray(2)
        fun scan(view: View) {
            if (matches >= 30) return
            if (view !== root && StatusBarAppearance.lockscreenPart(entryName(view), view.javaClass.name) != null) {
                matches++
                view.getLocationInWindow(at)
                append('\n').append("  ").append(entryName(view) ?: "-").append(" : ")
                    .append(view.javaClass.name).append(" y=").append(at[1]).append(" shown=").append(view.isShown)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) scan(view.getChildAt(i))
        }
        scan(root.rootView)
    }
}
