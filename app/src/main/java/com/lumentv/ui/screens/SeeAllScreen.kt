package com.lumentv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.data.discover.SeeAllState
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.Loading
import com.lumentv.ui.components.returnFocus
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * A "Tout voir" page open over the Films / Séries home: kept by the screen (in the view model's memo), so it is still
 * there, at the same place, when the viewer comes back from a title.
 */
internal class OpenSeeAll<T>(val title: String, val state: SeeAllState<T>) {
    val grid = LazyGridState()
    /** The first poster got the focus once (TV): not again on return. */
    var focused = false
}

/** The key of the "Tout voir" tile of a row, for the focus on return. */
internal fun seeAllKey(title: String) = "seeall:$title"

/**
 * The page: the row's title, its sorts as pills ("Populaires", "Plus récents", "Mieux notés"), then every title in a
 * grid that goes on loading as it comes to the end.
 */
@Composable
internal fun <T> SeeAllPage(vm: MainViewModel, open: OpenSeeAll<T>, tile: @Composable (T, Modifier) -> Unit) {
    val phone = com.lumentv.ui.LocalPhone.current
    val state = open.state
    val items by state.items.collectAsStateWithLifecycle()
    val loading by state.loading.collectAsStateWithLifecycle()
    val sort by state.sort.collectAsStateWithLifecycle()
    // The end of the grid in view: the next titles (again when a batch brought nothing new and it is still in view).
    LaunchedEffect(open, items.size, loading, sort) {
        if (loading || !state.hasMore) return@LaunchedEffect
        snapshotFlow { open.grid.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 18 } }.first { it }
        state.more()
    }
    val first = remember { FocusRequester() }
    if (!phone) LaunchedEffect(open, items.isNotEmpty()) {
        if (open.focused || items.isEmpty() || vm.returnKey != null) return@LaunchedEffect
        repeat(20) { delay(60); if (runCatching { first.requestFocus() }.isSuccess) { open.focused = true; return@LaunchedEffect } }
    }
    Column(Modifier.fillMaxSize().padding(start = if (phone) 8.dp else 20.dp, end = if (phone) 8.dp else 20.dp, top = if (phone) 6.dp else 14.dp)) {
        Text(open.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        if (state.sorts.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 10.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
            rowItemsIndexed(state.sorts) { i, label -> SortPill(label, i == sort) { state.select(i) } }
        }
        when {
            items.isEmpty() && (loading || state.hasMore) -> Loading("Recherche des titres de votre liste…")
            items.isEmpty() -> Text("Aucun titre de votre liste dans cette sélection.", color = Palette.TextDim, modifier = Modifier.padding(16.dp))
            else -> LazyVerticalGrid(posterCells(), state = open.grid, horizontalArrangement = Arrangement.spacedBy(gridGap()), verticalArrangement = Arrangement.spacedBy(gridGap()),
                contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp, start = 4.dp, end = 4.dp), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { i, _ -> i }) { i, t -> tile(t, if (i == 0) Modifier.focusRequester(first) else Modifier) }
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Recherche d'autres titres…", color = Palette.TextDim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                }
            }
        }
    }
}

/** A sort: a pill, filled when it is the one picked. */
@Composable
private fun SortPill(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick, shape = RoundedCornerShape(20.dp), container = if (selected) Palette.Accent else Palette.PanelHigh, focusedScale = 1.06f) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Palette.OnFocus else androidx.tv.material3.LocalContentColor.current, maxLines = 1,
            modifier = Modifier.padding(horizontal = if (com.lumentv.ui.LocalPhone.current) 13.dp else 18.dp, vertical = 8.dp))
    }
}

/** The last tile of a row on the TV: poster-sized, "Tout voir" with an arrow. */
@Composable
internal fun SeeAllTile(vm: MainViewModel, title: String, onClick: () -> Unit) {
    FocusSurface(onClick, Modifier.width(104.dp).aspectRatio(2f / 3f).returnFocus(vm, seeAllKey(title)), focusedScale = 1.08f,
        shape = RoundedCornerShape(10.dp), container = Palette.PanelHigh) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(40.dp).height(40.dp).background(Palette.AccentSoft, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Text("→", style = MaterialTheme.typography.titleLarge, color = Palette.Accent)
            }
            Spacer(Modifier.height(10.dp))
            Text("Tout voir", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = androidx.tv.material3.LocalContentColor.current)
        }
    }
}
