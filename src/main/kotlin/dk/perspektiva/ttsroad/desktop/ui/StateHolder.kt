package dk.perspektiva.ttsroad.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Minimal lifecycle-aware state holder — the desktop equivalent of a ViewModel, without pulling
 * in an Android lifecycle library.
 *
 * A holder owns a [CoroutineScope] that is cancelled exactly once, when the composable that
 * created it leaves the composition. That is the whole point: previously every screen launched its
 * loads into `rememberCoroutineScope()` and kept the result in `remember`, so the load logic could
 * only be exercised through the Compose runtime. A holder can be constructed and driven from a
 * plain `runTest`.
 *
 * Implementing [RememberObserver] rather than relying on a `DisposableEffect` also covers the
 * abandonment case: a composition that is started and then discarded never runs its effects, but
 * it does call [onAbandoned].
 */
abstract class StateHolder(dispatcher: CoroutineDispatcher = Dispatchers.Main) : RememberObserver {
    private val job = SupervisorJob()
    protected val scope: CoroutineScope = CoroutineScope(job + dispatcher)

    /** Hook for subclasses that own something beyond [scope]. */
    protected open fun onCleared() = Unit

    /** Idempotent; safe to call more than once. */
    fun clear() {
        if (!job.isActive) return
        onCleared()
        scope.cancel()
    }

    final override fun onRemembered() = Unit

    final override fun onForgotten() = clear()

    final override fun onAbandoned() = clear()
}

/**
 * Remembers a [StateHolder] keyed by [keys]. Disposal is handled by [StateHolder]'s
 * [RememberObserver] implementation, including when a key change replaces the holder.
 */
@Composable
fun <T : StateHolder> rememberStateHolder(vararg keys: Any?, factory: () -> T): T =
    remember(*keys) { factory() }
