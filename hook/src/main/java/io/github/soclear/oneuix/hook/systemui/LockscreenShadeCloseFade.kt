package io.github.soclear.oneuix.hook.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.ONE_UI_VERSION
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.reflect
import io.github.soclear.oneuix.hook.util.xlog

/**
 * Smoothly fades in lockscreen clock, widgets, Now Bar, complications, and bottom shortcuts
 * when closing the notification or Quick Settings panel on the lockscreen (SHADE_LOCKED -> KEYGUARD),
 * while leaving the native pull-down opening animation completely untouched.
 */
object LockscreenShadeCloseFade {
    private const val STATUS_BAR_KEYGUARD = 1
    private const val STATUS_BAR_SHADE_LOCKED = 2
    private const val FADE_IN_DURATION_MS = 350L

    // Matches KeyguardStatusBarViewController.animateKeyguardStatusBarIn() (LINEAR_OUT_SLOW_IN)
    private val FADE_IN_INTERPOLATOR = PathInterpolator(0f, 0f, 0.2f, 1f)

    @Volatile
    private var activeFadeAlpha: Float = -1f
    private var activeAnimator: ValueAnimator? = null

    context(module: XposedModule)
    fun install() = afterAttachTry(ONE_UI_VERSION >= 90000) {
        val npvcClass = classLoader.loadClass("com.android.systemui.shade.NotificationPanelViewController")
        val stateListenerClass = classLoader.loadClass(
            "com.android.systemui.shade.NotificationPanelViewController\$StatusBarStateListener"
        )
        val fwWrapperClass = classLoader.loadClass(
            "com.android.systemui.facewidget.plugin.FaceWidgetContainerWrapper"
        )

        val this0Field = stateListenerClass.getDeclaredField("this\$0").apply { isAccessible = true }
        val barStateField = npvcClass.getDeclaredField("mBarState").apply { isAccessible = true }
        val dozingField = npvcClass.getDeclaredField("mDozing").apply { isAccessible = true }
        val occlusionRunningField = runCatching {
            npvcClass.getDeclaredField("mIsOcclusionTransitionRunning").apply { isAccessible = true }
        }.getOrNull()
        val keyguardStatusBaseField = npvcClass.getDeclaredField("mKeyguardStatusBase").apply { isAccessible = true }
        val keyguardSecBottomAreaField = runCatching {
            npvcClass.getDeclaredField("mKeyguardSecBottomArea").apply { isAccessible = true }
        }.getOrNull()
        val nowBarContainerField = runCatching {
            npvcClass.getDeclaredField("mNowBarContainer").apply { isAccessible = true }
        }.getOrNull()
        val keyguardStateControllerField = runCatching {
            npvcClass.getDeclaredField("mKeyguardStateController").apply { isAccessible = true }
        }.getOrNull()
        val statusBarStateControllerField = runCatching {
            npvcClass.getDeclaredField("mStatusBarStateController").apply { isAccessible = true }
        }.getOrNull()
        val lstcField = runCatching {
            npvcClass.getDeclaredField("mLockscreenShadeTransitionController").apply { isAccessible = true }
        }.getOrNull()

        val setAlphaMethod = fwWrapperClass.getDeclaredMethod(
            "setAlpha",
            Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val updateClockMethod = npvcClass.declaredMethods.firstOrNull {
            it.parameterCount == 0 && (it.name == "updateClock\$1" || it.name == "updateClock")
        }?.apply { isAccessible = true }

        val updateBottomAreaAlphaMethod = runCatching {
            npvcClass.getDeclaredMethod("updateKeyguardSecBottomAreaAlpha").apply { isAccessible = true }
        }.getOrNull()

        val setBottomAreaVisibilityMethod = runCatching {
            npvcClass.getDeclaredMethod(
                "setKeyguardSecBottomAreaVisibility",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()

        val provideComplicationMethod = runCatching {
            npvcClass.getDeclaredMethod("provideComplication").apply { isAccessible = true }
        }.getOrNull()

        val getFaceWidgetAlphaMethod = npvcClass.getDeclaredMethod("getFaceWidgetAlpha").apply {
            isAccessible = true
        }

        val onStateChangedMethod = stateListenerClass.getDeclaredMethod(
            "onStateChanged",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        fun applyFadeAlpha(npvc: Any, alpha: Float) {
            runCatching {
                val statusBase = keyguardStatusBaseField.get(npvc)
                if (statusBase != null) {
                    setAlphaMethod.invoke(statusBase, alpha)
                }
                updateClockMethod?.invoke(npvc)
                updateBottomAreaAlphaMethod?.invoke(npvc)

                (keyguardSecBottomAreaField?.get(npvc) as? View)?.alpha = alpha
                (nowBarContainerField?.get(npvc) as? View)?.alpha = alpha
                (provideComplicationMethod?.invoke(npvc) as? View)?.let { comp ->
                    if (comp.visibility == View.VISIBLE) {
                        comp.alpha = alpha
                    }
                }
            }.onFailure { xlog(it) }
        }

        fun cancelFadeAnimator() {
            activeFadeAlpha = -1f
            activeAnimator?.let { anim ->
                activeAnimator = null
                if (anim.isRunning) anim.cancel()
            }
        }

        // Scale getFaceWidgetAlpha() while our close fade-in is running so any postOnAnimation
        // calls to updateClock$1() or updateKeyguardSecBottomAreaAlpha() follow the fade curve
        // instead of snapping elements back to 1.0f on the next frame.
        module.hook(getFaceWidgetAlphaMethod).intercept { chain ->
            val orig = chain.proceed() as Float
            val fade = activeFadeAlpha
            if (fade in 0f..1f && orig >= 0f) {
                orig * fade
            } else {
                orig
            }
        }

        module.hook(onStateChangedMethod).intercept { chain ->
            val listener = chain.thisObject ?: return@intercept chain.proceed()
            val npvc = this0Field.get(listener) ?: return@intercept chain.proceed()
            val oldBarState = barStateField.getInt(npvc)
            val newState = chain.args[0] as Int

            val result = chain.proceed()

            if (oldBarState == STATUS_BAR_SHADE_LOCKED && newState == STATUS_BAR_KEYGUARD) {
                val isDozing = dozingField.getBoolean(npvc)
                val isOcclusionRunning = occlusionRunningField?.getBoolean(npvc) ?: false
                val keyguardFadingAway = runCatching {
                    keyguardStateControllerField?.get(npvc)?.reflect?.getAs<Boolean>("mKeyguardFadingAway")
                }.getOrNull() ?: false
                val goingToFullShade = runCatching {
                    statusBarStateControllerField?.get(npvc)?.reflect?.callAs<Boolean>("goingToFullShade")
                }.getOrNull() ?: false

                if (!isDozing && !isOcclusionRunning && !keyguardFadingAway && !goingToFullShade) {
                    cancelFadeAnimator()

                    // In StatusBarStateListener.onStateChanged, setKeyguardSecBottomAreaVisibility(1, false)
                    // runs before mBarState is assigned 1, which leaves Now Bar visibility at GONE until a
                    // later traversal. Re-invoking it now that mBarState == 1 makes Now Bar visible upfront
                    // so it fades in smoothly from 0f alongside the clock and widgets.
                    runCatching { setBottomAreaVisibilityMethod?.invoke(npvc, STATUS_BAR_KEYGUARD, false) }

                    activeFadeAlpha = 0f
                    applyFadeAlpha(npvc, 0f)

                    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = FADE_IN_DURATION_MS
                        interpolator = FADE_IN_INTERPOLATOR
                        addUpdateListener { anim ->
                            val currentBarState = barStateField.getInt(npvc)
                            val currentlyDozing = dozingField.getBoolean(npvc)
                            val fractionToShade = runCatching {
                                lstcField?.get(npvc)?.reflect?.getAs<Float>("fractionToShade")
                            }.getOrNull() ?: 0f

                            if (currentBarState != STATUS_BAR_KEYGUARD || currentlyDozing || fractionToShade > 0f) {
                                activeFadeAlpha = -1f
                                anim.cancel()
                                return@addUpdateListener
                            }

                            val value = anim.animatedValue as Float
                            activeFadeAlpha = value
                            applyFadeAlpha(npvc, value)
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                val wasActive = (activeAnimator === animation)
                                activeFadeAlpha = -1f
                                if (wasActive) {
                                    activeAnimator = null
                                }
                                val currentBarState = barStateField.getInt(npvc)
                                val currentlyDozing = dozingField.getBoolean(npvc)
                                val fractionToShade = runCatching {
                                    lstcField?.get(npvc)?.reflect?.getAs<Float>("fractionToShade")
                                }.getOrNull() ?: 0f

                                if (currentBarState == STATUS_BAR_KEYGUARD && !currentlyDozing && fractionToShade == 0f) {
                                    applyFadeAlpha(npvc, 1f)
                                } else {
                                    (keyguardSecBottomAreaField?.get(npvc) as? View)?.alpha = 1f
                                }
                            }
                        })
                    }
                    activeAnimator = animator
                    animator.start()
                }
            } else if (newState != STATUS_BAR_KEYGUARD) {
                cancelFadeAnimator()
            }

            result
        }
    }
}
