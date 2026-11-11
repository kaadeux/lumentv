package com.lumentv.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumentv.AppCore
import com.lumentv.core.Featured
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.discover.SeeAllState
import kotlinx.coroutines.flow.first

/** A row's "Tout voir": its films or its series, with the badges of the series followed. */
internal class SeeAllRoute(val title: String, val movies: SeeAllState<MovieRow>?, val series: SeeAllState<SeriesRow>?,
                           val badges: Map<Long, String> = emptyMap()) : Route

@Composable
internal fun SeeAllPage(core: AppCore, r: SeeAllRoute, open: (Route) -> Unit) {
    r.movies?.let { s ->
        SeeAllGrid(r.title, s, { it.m.id }) { m -> Poster(m.m.poster, Featured.shortTitle(m.m.name), french = core.frenchOf(m)) { open(Route.Movie(m)) } }
    }
    r.series?.let { s ->
        SeeAllGrid(r.title, s, { it.s.id }) { x ->
            Box {
                Poster(x.s.cover, Featured.shortTitle(x.s.name), french = core.frenchOf(x)) { open(Route.Series(x)) }
                r.badges[x.s.id]?.let { com.lumentv.ui.kit.KitBadge(it, Modifier.align(Alignment.TopStart).padding(5.dp)) }
            }
        }
    }
}

/** The row's title, its sorts as chips, then a grid that loads more as its end comes into view (as on the TV). */
@Composable
private fun <T> SeeAllGrid(title: String, state: SeeAllState<T>, key: (T) -> Any, tile: @Composable (T) -> Unit) {
    val items by state.items.collectAsState()
    val loading by state.loading.collectAsState()
    val sort by state.sort.collectAsState()
    val grid = rememberGrid("seeall:$title:$sort")
    LaunchedEffect(state, items.size, loading, sort) {
        if (loading || !state.hasMore) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 12 } }.first { it }
        state.more()
    }
    Column(Modifier.fillMaxSize()) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        if (state.sorts.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(state.sorts) { i, label -> PhoneChip(label, selected = i == sort) { state.select(i) } }
        }
        when {
            items.isEmpty() && (loading || state.hasMore) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Text("Aucun titre de votre liste dans cette sélection.", color = Palette.TextDim, modifier = Modifier.padding(16.dp))
            else -> LazyVerticalGrid(GridCells.Adaptive(108.dp), state = grid, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                items(items, key = { key(it) }) { tile(it) }
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}
