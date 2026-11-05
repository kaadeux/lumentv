package com.lumentv.phone

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Where each list of the current tab was scrolled to, and what was chosen in it (a category, a group): back from a
 * film page lands on the same poster. Emptied when another tab is opened, which then starts at the top.
 */
class ScrollMemory {
    private val lists = HashMap<String, LazyListState>()
    private val grids = HashMap<String, LazyGridState>()
    val values = HashMap<String, Any?>()

    fun list(key: String): LazyListState = lists.getOrPut(key) { LazyListState() }
    fun grid(key: String): LazyGridState = grids.getOrPut(key) { LazyGridState() }
    fun forget(key: String) { lists.remove(key); grids.remove(key) }

    fun clear() { lists.clear(); grids.clear(); values.clear() }
}

val LocalScroll = staticCompositionLocalOf { ScrollMemory() }

@Composable internal fun rememberList(key: String): LazyListState = LocalScroll.current.list(key)
@Composable internal fun rememberGrid(key: String): LazyGridState = LocalScroll.current.grid(key)
