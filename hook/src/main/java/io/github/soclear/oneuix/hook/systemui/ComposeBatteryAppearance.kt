package io.github.soclear.oneuix.hook.systemui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.Preference
import io.github.soclear.oneuix.common.StatusBarAppearance
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.xlog
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One UI 9's battery is drawn by Compose, not BatteryMeterView's legacy ImageView. */
internal object ComposeBatteryAppearance {
    private data class Label(
        val text: String,
        val bodyWidth: Int,
        val extraWidth: Int,
        val gap: Float,
        val rtl: Boolean,
        val paint: Paint,
        val baseline: Float,
    )

    context(module: XposedModule)
    fun install(options: Preference.SystemUI.StatusBar) = afterAttachTry(
        options.hideBatteryIcon || options.addBatteryLevelText || options.hideBatteryPillText ||
            options.setBatteryIconWidthScale || options.setBatteryIconHeightScale
    ) {
        val stateClass = classLoader.loadClass("com.android.systemui.battery.BatteryLayoutState")
        val drawClass = classLoader.loadClass(
            "com.android.systemui.battery.view.SamsungBatteryComposeViewKt\$\$ExternalSyntheticLambda0"
        )
        // Resolve the complete rendering contract before installing either hook. Unknown firmware
        // keeps its stock rendering instead of partially changing the battery layout.
        val stateField = drawClass.getDeclaredField("f\$1").apply { isAccessible = true }
        check(stateField.type == stateClass)
        val draw = drawClass.getDeclaredMethod("invoke", Any::class.java)
        val constructor = stateClass.declaredConstructors.single { it.parameterCount == 26 }
        val fields = listOf(
            "displayLevel", "shouldShowIcon", "shouldShowText", "shouldShowWarningText",
            "shouldShowBackground", "textColor", "iconColor", "frameColor", "totalWidthPx",
            "totalHeightPx", "textFontSizePx", "textBaselineY", "isRtl", "isCharging",
            "iconOffsetX", "iconWidthPx", "iconHeightPx", "iconOffsetY", "textOffsetX"
        ).associateWith { stateClass.getDeclaredField(it).apply { isAccessible = true } }
        val drawScope = classLoader.loadClass("androidx.compose.ui.graphics.drawscope.DrawScope")
        val getContext = drawScope.getMethod("getDrawContext")
        // Samsung's R8 build erases the DrawContext interface methods and narrows this
        // return type to CanvasDrawScope$drawContext$1. Resolve the actual host type.
        val drawContext = getContext.returnType
        val getSize = drawContext.getMethod("getSize-NH-jbRc")
        val setSize = drawContext.getMethod("setSize-uvyYCjk", Long::class.javaPrimitiveType)
        val getCanvas = drawContext.getMethod("getCanvas")
        val canvasField = classLoader.loadClass("androidx.compose.ui.graphics.AndroidCanvas")
            .getDeclaredField("internalCanvas").apply { isAccessible = true }
        val labels = Collections.synchronizedMap(WeakHashMap<Any, Label>())
        var reportedFailure = false
        fun report(t: Throwable) {
            if (!reportedFailure) {
                reportedFailure = true
                xlog("One UI 9 battery appearance could not be applied", t)
            }
        }

        val widthScale = if (options.setBatteryIconWidthScale) options.batteryIconWidthScale else 1f
        val heightScale = if (options.setBatteryIconHeightScale) options.batteryIconHeightScale else 1f
        fun scaleInt(state: Any, name: String, factor: Float) {
            val field = fields.getValue(name)
            field.setInt(state, (field.getInt(state) * factor).roundToInt())
        }
        // Scales the whole pill: its size, the bolt and the text inside it, so proportions hold.
        fun scalePill(state: Any) {
            if (widthScale == 1f && heightScale == 1f) return
            if (widthScale !in 0.1f..5f || heightScale !in 0.1f..5f) return
            scaleInt(state, "totalWidthPx", widthScale)
            scaleInt(state, "totalHeightPx", heightScale)
            scaleInt(state, "iconWidthPx", widthScale)
            scaleInt(state, "iconHeightPx", heightScale)
            scaleInt(state, "iconOffsetX", widthScale)
            scaleInt(state, "iconOffsetY", heightScale)
            scaleInt(state, "textOffsetX", widthScale)
            val baseline = fields.getValue("textBaselineY")
            baseline.setFloat(state, baseline.getFloat(state) * heightScale)
            val size = fields.getValue("textFontSizePx")
            size.setFloat(state, size.getFloat(state) * minOf(widthScale, heightScale))
        }

        module.hook(draw).intercept { chain ->
            val label = runCatching { labels[stateField.get(chain.thisObject)] }.getOrNull()
                ?: return@intercept chain.proceed()
            // Resolve everything before touching Canvas or invoking the stock drawing function.
            val context: Any
            val canvas: Canvas
            val size: Long
            try {
                context = getContext.invoke(chain.args[0])!!
                canvas = canvasField.get(getCanvas.invoke(context)) as Canvas
                size = getSize.invoke(context) as Long
            } catch (t: Throwable) {
                report(t)
                return@intercept chain.proceed()
            }
            val save = canvas.save()
            val bodySize = (label.bodyWidth.toFloat().toRawBits().toLong() shl 32) or
                (size and 0xffffffffL)
            val result: Any?
            try {
                // Keep the native pill's original bounds; widening the whole draw scope would
                // otherwise stretch its frame and gauge to cover our additional label.
                setSize.invoke(context, bodySize)
                if (label.rtl && label.bodyWidth > 0) canvas.translate(label.extraWidth.toFloat(), 0f)
                result = chain.proceed()
            } finally {
                canvas.restoreToCount(save)
                setSize.invoke(context, size)
            }
            try {
                val x = if (label.rtl || label.bodyWidth == 0) 0f else label.bodyWidth + label.gap
                canvas.drawText(label.text, x, label.baseline, label.paint)
            } catch (t: Throwable) {
                report(t)
            }
            result
        }
        module.hook(constructor).intercept { chain ->
            val result = chain.proceed()
            val state = chain.thisObject ?: return@intercept result
            try {
                scalePill(state)
                // Width of the pill itself, before the label option widens the state.
                val pillWidth = fields.getValue("totalWidthPx").getInt(state)
                val foreground = fields.getValue("frameColor").getInt(state)
                if (options.addBatteryLevelText) {
                    val level = fields.getValue("displayLevel").getInt(state)
                    val charging = fields.getValue("isCharging").getBoolean(state)
                    val fontSize = fields.getValue("textFontSizePx").getFloat(state)
                    val height = fields.getValue("totalHeightPx").getInt(state)
                    val originalWidth = fields.getValue("totalWidthPx").getInt(state)
                    // Validate dimensions before modifying any native fields.
                    if (fontSize !in 1f..512f || height !in 1..1024 || originalWidth !in 1..2048) {
                        return@intercept result
                    }
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = fontSize
                        color = foreground
                        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    }
                    val text = StatusBarAppearance.batteryLabel(
                        level, charging, options.hideBatteryLevelTextPercentageSign,
                        options.hideBatteryLevelTextChargingIcon
                    )
                    val bodyWidth = if (options.hideBatteryIcon) 0 else originalWidth
                    val gap = if (bodyWidth == 0) 0f else fontSize * .22f
                    // Reserve charging space even when unplugged to avoid moving adjacent icons.
                    val widest = StatusBarAppearance.batteryLabel(
                        100, true, options.hideBatteryLevelTextPercentageSign,
                        options.hideBatteryLevelTextChargingIcon
                    )
                    val extraWidth = ceil(paint.measureText(widest) + gap + 1f).toInt()
                    val metrics = paint.fontMetrics
                    val label = Label(text, bodyWidth, extraWidth, gap,
                        fields.getValue("isRtl").getBoolean(state), paint,
                        (height - metrics.ascent - metrics.descent) / 2f)
                    if (options.hideBatteryIcon) {
                        fields.getValue("shouldShowBackground").setBoolean(state, false)
                        fields.getValue("shouldShowIcon").setBoolean(state, false)
                        fields.getValue("shouldShowText").setBoolean(state, false)
                    }
                    fields.getValue("totalWidthPx").setInt(state, bodyWidth + extraWidth)
                    // BatteryLayoutState is a data class: insert only after all hash fields change.
                    labels[state] = label
                } else if (options.hideBatteryIcon) {
                    fields.getValue("shouldShowBackground").setBoolean(state, false)
                    fields.getValue("shouldShowIcon").setBoolean(
                        state, fields.getValue("isCharging").getBoolean(state) &&
                            fields.getValue("shouldShowIcon").getBoolean(state)
                    )
                    // The stock inside-pill text colour is unsuitable on the wallpaper.
                    fields.getValue("textColor").setInt(state, foreground)
                    fields.getValue("iconColor").set(state, foreground)
                }
                if (options.hideBatteryPillText && !options.hideBatteryIcon) {
                    // Without its text the pill shows the charging bolt alone: centre it.
                    fields.getValue("shouldShowText").setBoolean(state, false)
                    val iconWidth = fields.getValue("iconWidthPx").getInt(state)
                    if (pillWidth in 1..2048 && iconWidth in 1..pillWidth) {
                        fields.getValue("iconOffsetX").setInt(state, (pillWidth - iconWidth) / 2)
                    }
                }
            } catch (t: Throwable) {
                report(t)
            }
            result
        }
    }
}
