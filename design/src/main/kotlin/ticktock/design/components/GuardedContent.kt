// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import android.content.Context
import android.graphics.Canvas
import android.widget.FrameLayout
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import ticktock.design.LocalGridlinePalette

/** Dialogs report to the same owner as their screen, even though they have a separate window. */
val LocalRenderFailureHandler = staticCompositionLocalOf<((Throwable) -> Unit)?> { null }

/** A separate composition and traversal boundary; the caller draws recovery outside it. */
@Composable
fun GuardedContent(
    identity: String,
    onFailure: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalGridlinePalette.current
    val indication = LocalIndication.current
    val parentContext = rememberCoroutineScope().coroutineContext
    val frameContext = (parentContext[ContinuationInterceptor] ?: EmptyCoroutineContext) +
        (parentContext[MonotonicFrameClock] ?: EmptyCoroutineContext)
    val parentRegistry = LocalSaveableStateRegistry.current
    val savedState = rememberSaveable(identity, saver = PaneSavedState.Saver) { PaneSavedState() }
    AndroidView(
        factory = { GuardedContentView(it, identity, frameContext, savedState, { value -> parentRegistry?.canBeSaved(value) == true }, onFailure) },
        modifier = modifier,
        update = { host ->
            host.onFailure = onFailure
            host.content = {
                // Inherit theme values without adding the screen theme's full-size layout.
                CompositionLocalProvider(
                    LocalGridlinePalette provides palette,
                    LocalIndication provides indication,
                    LocalRenderFailureHandler provides onFailure,
                    content = content,
                )
            }
        },
        onRelease = { it.release() },
    )
}

/** Keeps saveable child values when its view detaches, and participates in the parent's saved state. */
private class PaneSavedState(private var restored: Map<String, List<Any?>> = emptyMap()) {
    private var active: SaveableStateRegistry? = null
    fun open(canBeSaved: (Any) -> Boolean): SaveableStateRegistry =
        SaveableStateRegistry(restored, canBeSaved).also { active = it }
    fun values(): Map<String, List<Any?>> = active?.performSave() ?: restored
    fun detach() {
        restored = values()
        active = null
    }
    companion object {
        val Saver = mapSaver(
            save = { state: PaneSavedState -> state.values().mapValues { ArrayList(it.value) } },
            restore = { values ->
                @Suppress("UNCHECKED_CAST")
                PaneSavedState(values.mapValues { it.value as List<Any?> })
            },
        )
    }
}

private class PaneComposition(context: Context) : AbstractComposeView(context) {
    override val shouldCreateCompositionOnAttachedToWindow: Boolean get() = false
    var body by mutableStateOf<@Composable () -> Unit>({})
    @Composable override fun Content() = body()
}

private class PaneLifecycle : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}

/** View traversal is outside composition, so it needs its own guard. */
private class GuardedContentView(
    context: Context,
    private val identity: String,
    private val frameContext: CoroutineContext,
    private val savedState: PaneSavedState,
    private val canBeSaved: (Any) -> Boolean,
    var onFailure: (Throwable) -> Unit,
) : FrameLayout(context) {
    private var pane: PaneComposition? = null
    private var recomposer: Recomposer? = null
    private var supervisor = SupervisorJob()
    private var owner: PaneLifecycle? = null
    private var parentLifecycle: Lifecycle? = null
    private var observer: LifecycleEventObserver? = null
    private var failed = false
    private var released = false
    private var registry: SaveableStateRegistry? = null
    var content: @Composable () -> Unit = {}
        set(value) {
            field = value
            pane?.body = wrapped(value)
        }

    private fun wrapped(body: @Composable () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(LocalSaveableStateRegistry provides registry, content = body)
    }

    init { clipChildren = false; clipToPadding = false }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A parent's attach callback runs before its descendants are attached. Creating
        // there lets WrappedComposition defer initial content into a later unguarded
        // descendant callback. Start after the whole host has finished attaching.
        post { if (isAttachedToWindow) createPane() }
    }

    private fun createPane() {
        if (pane != null || failed || released) return
        guarded {
            val parent = checkNotNull(findViewTreeLifecycleOwner()).lifecycle
            check(parent.currentState.isAtLeast(Lifecycle.State.CREATED))
            val childOwner = PaneLifecycle()
            owner = childOwner
            parentLifecycle = parent
            val mirror = LifecycleEventObserver { _, _ -> childOwner.registry.currentState = parent.currentState }
            observer = mirror
            parent.addObserver(mirror)
            childOwner.registry.currentState = parent.currentState
            val handler = CoroutineExceptionHandler { _, failure -> fail(failure) }
            supervisor = SupervisorJob()
            // Share the owner's UI time while keeping failures on an independent job.
            val nextRecomposer = createLifecycleAwareWindowRecomposer(frameContext + supervisor + handler, childOwner.lifecycle)
            recomposer = nextRecomposer
            registry = savedState.open(canBeSaved)
            val child = PaneComposition(context).also {
                // A stable, non-resource ID lets Compose save this pane across activity recreation.
                it.id = 0x02000000 or (identity.hashCode() and 0x00ffffff)
                it.setViewTreeLifecycleOwner(childOwner)
                // Descendants detach before this host; retain providers until we save them.
                it.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(childOwner.lifecycle))
                it.setParentCompositionContext(nextRecomposer)
                it.body = wrapped(content)
            }
            pane = child
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            child.createComposition()
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!failed && !released) guarded { super.onMeasure(widthMeasureSpec, heightMeasureSpec) }
        if (failed || released) setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!failed && !released) guarded { super.onLayout(changed, left, top, right, bottom) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (failed || released) return
        guarded {
            val saved = canvas.save()
            try { super.dispatchDraw(canvas) } finally { canvas.restoreToCount(saved) }
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (failure: Throwable) { fail(failure) }
    }

    private fun fail(failure: Throwable) {
        if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
        if (failure is CancellationException && failure.cause == null) return
        if (failed || released) return
        failed = true
        // Do not alter the view hierarchy or a parent's snapshot during a child traversal.
        post {
            onFailure(failure)
            release()
        }
    }

    fun release() {
        if (released) return
        released = true
        disposePane()
    }

    private fun disposePane() {
        fun finish(block: () -> Unit) {
            try { block() } catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                android.util.Log.w("ScreenRecovery", "Pane cleanup failed", failure)
            }
        }
        // Save before lifecycle destruction unregisters rememberSaveable providers.
        finish { savedState.detach() }
        finish { observer?.let { parentLifecycle?.removeObserver(it) } }
        finish { owner?.registry?.currentState = Lifecycle.State.DESTROYED }
        finish { pane?.disposeComposition() }
        finish { recomposer?.cancel() }
        finish { supervisor.cancel() }
        finish {
            while (childCount > 0) {
                val child = getChildAt(childCount - 1)
                if (child.isAttachedToWindow) {
                    removeView(child)
                } else {
                    // ViewGroup detaches descendants before this host's callback,
                    // while the host still has window attachment info. removeView
                    // would dispatch a second detach before clearing the parent link.
                    detachViewFromParent(child)
                    removeDetachedView(child, false)
                }
            }
        }
        pane = null
        recomposer = null
        owner = null
        observer = null
        parentLifecycle = null
        registry = null
    }

    override fun onDetachedFromWindow() {
        disposePane()
        super.onDetachedFromWindow()
    }
}
