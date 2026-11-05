package com.lumentv.phone

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where each list of the current tab was scrolled to, and what was chosen in it (a category, a group): back from a
 * film page lands on the same poster. Emptied when another tab is opened, which then starts at the top.
 *
 * The position is written down when a list leaves the screen and put back once its content is there again ([ready]):
 * a list whose titles are read again on return is empty for its first frame, and Compose would then keep it at the top.
 */
class ScrollMemory {
    internal val saved = HashMap<String, Pair<Int, Int>>()
    val values = HashMap<String, Any?>()

    fun forget(key: String) { saved.remove(key) }
    fun clear() { saved.clear(); values.clear() }
}

val LocalScroll = staticCompositionLocalOf { ScrollMemory() }

@Composable
internal fun rememberList(key: String, ready: Boolean = true): LazyListState {
    val memory = LocalScroll.current
    val state = remember(key) { LazyListState() }
    Remembered(memory, key, ready, { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }) { (i, o) -> state.scrollToItem(i, o) }
    return state
}

@Composable
internal fun rememberGrid(key: String, ready: Boolean = true): LazyGridState {
    val memory = LocalScroll.current
    val state = remember(key) { LazyGridState() }
    Remembered(memory, key, ready, { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }) { (i, o) -> state.scrollToItem(i, o) }
    return state
}

@Composable
private fun Remembered(memory: ScrollMemory, key: String, ready: Boolean, where: () -> Pair<Int, Int>, restore: suspend (Pair<Int, Int>) -> Unit) {
    var restored by remember(key) { mutableStateOf(memory.saved[key] == null) }
    LaunchedEffect(key, ready) {
        val at = memory.saved[key]
        if (!restored && ready && at != null) { restore(at); restored = true }
    }
    DisposableEffect(key) {
        onDispose {
            // A list left before its content came back keeps the position it had.
            if (restored) memory.saved[key] = where()
        }
    }
}
