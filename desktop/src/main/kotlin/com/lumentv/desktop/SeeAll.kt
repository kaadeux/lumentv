package com.lumentv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumentv.data.discover.SeeAllState
import kotlinx.coroutines.flow.first

/** A row's "Tout voir" open on the PC: its title and its titles, page after page. */
class OpenSeeAll<T>(val title: String, val state: SeeAllState<T>)

/**
 * The page, as on the TV: "← Retour", the row's title, its sorts as pills, then a grid that loads more as its end
 * comes into view.
 */
@Composable
fun <T> SeeAllPage(open: OpenSeeAll<T>, key: (T) -> Any, onBack: () -> Unit, tile: @Composable (T) -> Unit) {
    val state = open.state
    val items by state.items.collectAsState()
    val loading by state.loading.collectAsState()
    val sort by state.sort.collectAsState()
    val grid = rememberLazyGridState()
    LaunchedEffect(state, items.size, loading, sort) {
        if (loading || !state.hasMore) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 14 } }.first { it }
        state.more()
    }
    // Another sort starts from the top; back from a title, the grid stays where it was.
    var shownSort by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(sort) }
    LaunchedEffect(sort) { if (sort != shownSort) { grid.scrollToItem(0); shownSort = sort } }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("←  Retour", onBack)
            Text(open.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Pal.text, maxLines = 1)
        }
        if (state.sorts.size > 1) Row(Modifier.padding(top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.sorts.forEachIndexed { i, label ->
                val on = i == sort
                Text(label, color = if (on) Color.Black else Pal.text, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Pal.panelHigh).clickable { state.select(i) }
                        .padding(horizontal = 18.dp, vertical = 8.dp))
            }
        }
        when {
            items.isEmpty() && (loading || state.hasMore) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pal.accent) }
            items.isEmpty() -> Text("Aucun titre de votre liste dans cette sélection.", color = Pal.dim, modifier = Modifier.padding(16.dp))
            else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), state = grid, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 20.dp), modifier = Modifier.fillMaxSize()) {
                items(items, key = { key(it) }) { tile(it) }
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pal.accent) }
                }
            }
        }
    }
}
