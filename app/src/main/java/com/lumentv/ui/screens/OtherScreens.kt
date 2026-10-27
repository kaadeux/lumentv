@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.core.normalize
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.EpgChannelEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.repo.Groups
import com.lumentv.data.repo.SEARCH_LIMIT
import com.lumentv.data.repo.SearchResults
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.Screen
import com.lumentv.ui.components.ConfirmDialog
import com.lumentv.ui.components.EmptyState
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.Loading
import com.lumentv.ui.components.Logo
import com.lumentv.ui.components.Poster
import com.lumentv.ui.components.ProgressLine
import com.lumentv.ui.components.ScreenTitle
import com.lumentv.ui.components.TvButton
import com.lumentv.ui.components.TvTextField
import com.lumentv.ui.player.PlayerLauncher
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun SectionHeader(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))

@Composable
private fun ChannelLine(row: ChannelRow, subtitle: String? = null, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    FocusSurface(onClick, Modifier.fillMaxWidth(), onLongClick = onLong, focusedScale = 1.01f) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(row.ch.logo, row.ch.name, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle ?: row.ch.groupName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PosterRow(movies: List<MovieRow>, series: List<SeriesRow>, vm: MainViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(movies, key = { "m" + it.m.id }) { r ->
            Column(Modifier.width(110.dp)) {
                FocusSurface({ vm.open(Screen.MovieDetail(r.m.playlistId, r.m.uid)) }, Modifier.fillMaxWidth().aspectRatio(2f / 3f), focusedScale = 1.06f) {
                    Poster(r.m.poster, r.m.name, Modifier.fillMaxSize())
                }
                Text(r.m.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Palette.Text)
            }
        }
        items(series, key = { "s" + it.s.id }) { r ->
            Column(Modifier.width(110.dp)) {
                FocusSurface({ vm.open(Screen.SeriesDetail(r.s.playlistId, r.s.uid)) }, Modifier.fillMaxWidth().aspectRatio(2f / 3f), focusedScale = 1.06f) {
                    Poster(r.s.cover, r.s.name, Modifier.fillMaxSize())
                }
                Text(r.s.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Palette.Text)
            }
        }
    }
}

@Composable
fun FavoritesScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val changes by vm.graph.library.changes.collectAsStateWithLifecycle()
    val data by produceState<Triple<List<ChannelRow>, List<MovieRow>, List<SeriesRow>>?>(null, changes) {
        value = Triple(vm.graph.library.favoriteChannelsAll(), vm.graph.library.favoriteMovies(), vm.graph.library.favoriteSeries())
    }
    val d = data ?: return Loading()
    if (d.first.isEmpty() && d.second.isEmpty() && d.third.isEmpty()) {
        return EmptyState("Aucun favori", "Maintenez le bouton OK sur une chaîne, un film ou une série pour l'ajouter ici.")
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { ScreenTitle("Favoris") }
        if (d.second.isNotEmpty() || d.third.isNotEmpty()) {
            item { SectionHeader("Films et séries") }
            item { PosterRow(d.second, d.third, vm) }
        }
        if (d.first.isNotEmpty()) {
            item { SectionHeader("Chaînes") }
            items(d.first, key = { "c" + it.ch.id }) { row ->
                ChannelLine(row, onClick = { PlayerLauncher.live(context, row.ch.id, Groups.FAVORITES) })
            }
        }
    }
}

@Composable
fun SearchScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var query by remember { mutableStateOf(vm.lastSearch) }
    var tab by remember { mutableStateOf(vm.searchTab) }
    val results by produceState(SearchResults(), query) {
        vm.lastSearch = query
        if (normalize(query).length < 2) { value = SearchResults(); return@produceState }
        delay(250)
        value = vm.graph.library.search(query, vm.graph.epg)
    }
    fun select(t: SearchTab) { tab = t; vm.searchTab = t }
    val counts = mapOf(
        SearchTab.CHANNELS to results.channels.size, SearchTab.MOVIES to results.movies.size, SearchTab.SERIES to results.series.size,
        SearchTab.EPISODES to results.episodes.size, SearchTab.PROGRAMS to results.programs.size,
    )
    val all = tab == SearchTab.ALL
    fun <T> pick(list: List<T>) = if (all) list.take(PREVIEW) else list

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { ScreenTitle("Recherche") }
        item {
            TvTextField("Chaîne, film, série, épisode ou programme", query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = "Appuyez sur OK pour saisir…", keyboardType = KeyboardType.Text)
        }
        if (!results.isEmpty) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                item { TabChip("Tout", null, all) { select(SearchTab.ALL) } }
                SearchTab.entries.filter { it != SearchTab.ALL && (counts[it] ?: 0) > 0 }.forEach { t ->
                    item { TabChip(t.label, counts[t], tab == t) { select(t) } }
                }
            }
        }
        if (normalize(query).length >= 2 && results.isEmpty) item {
            Text("Aucun résultat pour « $query ».", color = Palette.TextDim, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 20.dp))
        }
        if ((all || tab == SearchTab.CHANNELS) && results.channels.isNotEmpty()) {
            if (all) item { SectionHeader("Chaînes") }
            items(pick(results.channels), key = { "c" + it.ch.id }) { row -> ChannelLine(row, onClick = { PlayerLauncher.live(context, row.ch.id, Groups.ALL) }) }
            if (all && results.channels.size > PREVIEW) item { MoreButton(results.channels.size) { select(SearchTab.CHANNELS) } }
        }
        if ((all || tab == SearchTab.MOVIES) && results.movies.isNotEmpty()) {
            if (all) item { SectionHeader("Films") }
            if (all) item { PosterRow(results.movies.take(12), emptyList(), vm) }
            else items(results.movies.chunked(6), key = { "mr" + it.first().m.id }) { PosterRow(it, emptyList(), vm) }
            if (all && results.movies.size > 12) item { MoreButton(results.movies.size) { select(SearchTab.MOVIES) } }
        }
        if ((all || tab == SearchTab.SERIES) && results.series.isNotEmpty()) {
            if (all) item { SectionHeader("Séries") }
            if (all) item { PosterRow(emptyList(), results.series.take(12), vm) }
            else items(results.series.chunked(6), key = { "sr" + it.first().s.id }) { PosterRow(emptyList(), it, vm) }
            if (all && results.series.size > 12) item { MoreButton(results.series.size) { select(SearchTab.SERIES) } }
        }
        if ((all || tab == SearchTab.EPISODES) && results.episodes.isNotEmpty()) {
            if (all) item { SectionHeader("Épisodes") }
            items(pick(results.episodes), key = { "e" + it.id }) { e ->
                FocusSurface({ PlayerLauncher.episode(context, e.playlistId, e.seriesUid, e.uid, false) }, Modifier.fillMaxWidth(), focusedScale = 1.01f) {
                    Text("${e.title}  ·  S${e.season} E${e.episode}", style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
            if (all && results.episodes.size > PREVIEW) item { MoreButton(results.episodes.size) { select(SearchTab.EPISODES) } }
        }
        if ((all || tab == SearchTab.PROGRAMS) && results.programs.isNotEmpty()) {
            if (all) item { SectionHeader("Programmes TV") }
            items(pick(results.programs), key = { "p" + it.first.id }) { (pr, ch) ->
                FocusSurface({ ch?.let { PlayerLauncher.live(context, it.ch.id, Groups.ALL) } }, Modifier.fillMaxWidth(), focusedScale = 1.01f) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(pr.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("${ch?.ch?.name ?: "?"} · ${dayLabel(pr.start)} ${timeRange(pr.start, pr.stop)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (all && results.programs.size > PREVIEW) item { MoreButton(results.programs.size) { select(SearchTab.PROGRAMS) } }
        }
    }
}

enum class SearchTab(val label: String) { ALL("Tout"), CHANNELS("Chaînes"), MOVIES("Films"), SERIES("Séries"), EPISODES("Épisodes"), PROGRAMS("Programmes") }

private const val PREVIEW = 6

@Composable
private fun TabChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick, selected = selected, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), focusedScale = 1.05f) {
        Text(label + (count?.let { "  ${if (it >= SEARCH_LIMIT) "$it+" else it}" } ?: ""), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun MoreButton(count: Int, onClick: () -> Unit) {
    TvButton("Voir les ${if (count >= SEARCH_LIMIT) "$count+" else count} résultats", onClick, Modifier.padding(vertical = 6.dp))
}

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by vm.graph.library.observeHistory(150).collectAsStateWithLifecycle(null)
    var confirm by remember { mutableStateOf(false) }
    val h = history ?: return Loading()
    if (h.isEmpty()) return EmptyState("Historique vide", "Les chaînes, films et épisodes que vous regardez apparaîtront ici.")
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { ScreenTitle("Historique") { TvButton("Effacer l'historique", { confirm = true }) } }
        items(h, key = { it.key }) { e ->
            FocusSurface(
                onClick = {
                    scope.launch {
                        when (e.kind) {
                            HistoryKind.CHANNEL -> vm.graph.library.channelByUid(e.playlistId, e.uid)?.let { PlayerLauncher.live(context, it.ch.id, Groups.ALL) }
                                ?: vm.show("Cette chaîne n'existe plus dans la playlist.")
                            HistoryKind.MOVIE -> PlayerLauncher.movie(context, e.playlistId, e.uid, true)
                            HistoryKind.EPISODE -> PlayerLauncher.episode(context, e.playlistId, e.parentUid ?: "", e.uid, true)
                            HistoryKind.FILE -> e.uid.removePrefix("file:").toLongOrNull()?.let { PlayerLauncher.file(context, it) }
                        }
                    }
                },
                onLongClick = { scope.launch { vm.graph.library.deleteHistory(e.key) } },
                modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f,
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(e.image, e.title, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(
                            when (e.kind) { HistoryKind.CHANNEL -> "Chaîne"; HistoryKind.MOVIE -> "Film"; HistoryKind.FILE -> "Enregistrement"; else -> "Épisode" },
                            e.subtitle, dayLabel(e.watchedAt) + " " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(e.watchedAt)),
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        if (e.durationMs > 0) ProgressLine(e.positionMs.toFloat() / e.durationMs, Modifier.fillMaxWidth(0.4f).padding(top = 4.dp))
                    }
                }
            }
        }
    }
    if (confirm) ConfirmDialog("Effacer l'historique ?", "Les positions de reprise des films et épisodes seront aussi effacées.", "Effacer",
        onConfirm = { scope.launch { vm.graph.library.clearHistory() } }, onDismiss = { confirm = false })
}

/** Manual EPG correction: pick the XMLTV channel a playlist channel should use. */
@Composable
fun EpgFixScreen(vm: MainViewModel, channelId: Long) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    val channel by produceState<ChannelRow?>(null, channelId) { value = vm.graph.library.channel(channelId) }
    val c = channel ?: return Loading()
    val choices by produceState<List<EpgChannelEntity>?>(null, c.ch.playlistId) { value = vm.graph.epg.epgChannelChoices(c.ch.playlistId) }
    val current by produceState<String?>(null, c, choices) { value = vm.graph.epg.keyFor(c) }
    LaunchedEffect(c.ch.id) { filter = c.ch.name.substringBefore(' ') }

    fun choose(key: String?) = scope.launch {
        vm.graph.library.setEpgOverride(c.ch.playlistId, c.ch.uid, key)
        vm.graph.epg.invalidate(c.ch.playlistId)
        vm.show(if (key == null) "Association automatique rétablie" else "Guide associé : $key. Actualisez le guide si les programmes n'apparaissent pas.")
        vm.back()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Guide TV de « ${c.ch.name} »")
        Text("Association actuelle : ${current ?: "aucune"}  ·  identifiant de la playlist : ${c.ch.epgId ?: "aucun"}",
            style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            TvTextField("Filtrer", filter, { filter = it }, Modifier.weight(1f), keyboardType = KeyboardType.Text)
            TvButton("Automatique", { choose(null) })
        }
        Spacer(Modifier.height(12.dp))
        val list = choices
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState("Aucune chaîne XMLTV", "Téléchargez d'abord le guide TV (Paramètres › Playlists). Seules les chaînes du guide proches de la playlist sont conservées.")
            else -> {
                val f = normalize(filter)
                val shown = if (f.isBlank()) list else list.filter { normalize(it.displayName).contains(f) || it.epgKey.contains(f) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown.take(300), key = { it.epgKey }) { e ->
                        FocusSurface({ choose(e.epgKey) }, Modifier.fillMaxWidth(), selected = e.epgKey == current, focusedScale = 1.01f) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(e.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
                                Text(e.epgKey, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

