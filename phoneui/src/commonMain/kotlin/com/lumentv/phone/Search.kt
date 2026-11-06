package com.lumentv.phone

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lumentv.AppCore
import com.lumentv.core.Featured
import com.lumentv.core.nowMillis
import com.lumentv.data.db.ChannelRow
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.repo.SearchResults
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What was typed and found, kept while the app is open (back from a film page lands on the same results). */
private object LastSearch {
    var query = ""
    var results: SearchResults? = null
}

/**
 * The search of the TV and the phone: tolerant of typing mistakes, by title (French or original, through TMDB), by
 * actor or director, then the channels, the episodes and the programmes of the guide.
 */
@Composable
internal fun SearchHome(core: AppCore, p: PlaylistEntity, player: VideoPlayer, open: (Route) -> Unit) {
    var query by remember { mutableStateOf(LastSearch.query) }
    var results by remember { mutableStateOf(LastSearch.results) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(query) {
        LastSearch.query = query
        if (query.trim().length < 2) { results = null; LastSearch.results = null; return@LaunchedEffect }
        delay(400) // the next letter typed cancels this search
        searching = true
        results = runCatching { core.library.search(query.trim(), core.epg, p.id, core.index, core.tmdb) }.getOrNull()
        LastSearch.results = results
        searching = false
    }
    val r = results
    val movies = remember(r) { r?.movies?.let { oneEach(it, { m -> m.m.name }, { m -> m.m.category }) }.orEmpty() }
    val series = remember(r) { r?.series?.let { oneEach(it, { s -> s.s.name }, { s -> s.s.category }) }.orEmpty() }
    LazyColumn(state = rememberList("search"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Film, série, acteur, chaîne…") }, singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                // The keyboard's magnifier closes it: the results show whole.
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
                trailingIcon = {
                    if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else if (query.isNotEmpty()) Text("✕", modifier = Modifier.clickable { query = "" }.padding(10.dp), color = Palette.TextDim)
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        if (r != null && movies.isEmpty() && series.isEmpty() && r.people.isEmpty() && r.channels.isEmpty() && r.episodes.isEmpty() && r.programs.isEmpty())
            item { Text("Rien trouvé pour « ${query.trim()} ».", color = Palette.TextDim, modifier = Modifier.padding(16.dp)) }
        if (movies.isNotEmpty()) {
            item { SectionTitle("Films") }
            item {
                LazyRow(state = rememberList("s:mv"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(movies, key = { it.m.id }) { m -> Box(Modifier.width(110.dp)) { Poster(m.m.poster, Featured.shortTitle(m.m.name)) { open(Route.Movie(m)) } } }
                }
            }
        }
        if (series.isNotEmpty()) {
            item { SectionTitle("Séries") }
            item {
                LazyRow(state = rememberList("s:se"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(series, key = { it.s.id }) { s -> Box(Modifier.width(110.dp)) { Poster(s.s.cover, Featured.shortTitle(s.s.name)) { open(Route.Series(s)) } } }
                }
            }
        }
        // An actor or a director: their films and series of the playlist, straight away.
        r?.people.orEmpty().take(6).forEach { h ->
            val films = oneEach(h.movies, { it.m.name }, { it.m.category })
            val shows = oneEach(h.series, { it.s.name }, { it.s.category })
            if (films.isEmpty() && shows.isEmpty()) return@forEach
            item(key = "pt:" + h.key) { SectionTitle("Avec ${h.title}") }
            item(key = "pr:" + h.key) {
                LazyRow(state = rememberList("s:p:${h.key}"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(films, key = { "m" + it.m.id }) { m -> Box(Modifier.width(110.dp)) { Poster(m.m.poster, Featured.shortTitle(m.m.name)) { open(Route.Movie(m)) } } }
                    items(shows, key = { "s" + it.s.id }) { s -> Box(Modifier.width(110.dp)) { Poster(s.s.cover, Featured.shortTitle(s.s.name)) { open(Route.Series(s)) } } }
                }
            }
        }
        if (r != null && r.channels.isNotEmpty()) {
            item { SectionTitle("Chaînes") }
            items(r.channels.take(30), key = { "c:" + it.ch.id }) { c ->
                Line(c.ch.name, c.ch.groupName, c.ch.logo) { scope.launch { playChannel(core, player, c, null) } }
            }
        }
        if (r != null && r.episodes.isNotEmpty()) {
            item { SectionTitle("Épisodes") }
            items(r.episodes.take(30), key = { "e:" + it.id }) { e ->
                Line(e.title, "Saison ${e.season} · épisode ${e.episode}", e.image) {
                    scope.launch { core.library.seriesByUid(e.playlistId, e.seriesUid)?.let { open(Route.Series(it)) } }
                }
            }
        }
        val now = nowMillis()
        val programs = r?.programs.orEmpty().filter { it.second != null && it.first.stop > now }.take(30)
        if (programs.isNotEmpty()) {
            item { SectionTitle("À la télé") }
            items(programs, key = { "g:" + it.first.id }) { (prog, ch) ->
                val live = prog.start <= now
                Line(prog.title, (if (live) "En ce moment" else at(prog.start)) + " · " + ch!!.ch.name, ch.ch.logo) {
                    if (live) scope.launch { playChannel(core, player, ch, prog.title) }
                }
            }
        }
    }
}

/** "jeu. 20:45": when a programme starts. */
private fun at(t: Long): String = Instant.fromEpochMilliseconds(t).toLocalDateTime(TimeZone.currentSystemDefault()).let {
    listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")[it.dayOfWeek.ordinal] + " " +
        it.hour.toString().padStart(2, '0') + ":" + it.minute.toString().padStart(2, '0')
}

internal suspend fun playChannel(core: AppCore, player: VideoPlayer, c: ChannelRow, now: String?) {
    if (!Parental.allow(core, c)) return
    core.settings.set(com.lumentv.data.settings.Keys.lastChannel, c.ch.id)
    val spec = core.resolver.channel(c.ch)
    core.library.record(HistoryEntity(LibraryRepository.key(HistoryKind.CHANNEL, c.ch.playlistId, c.ch.uid), HistoryKind.CHANNEL,
        c.ch.playlistId, c.ch.uid, c.ch.name, c.ch.groupName, c.ch.logo, watchedAt = nowMillis()))
    play(core, player, spec, c.ch.name, now, 0, c.ch.playlistId, Playback.lowerVariants(core, c)) { _, _ -> }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp))

@Composable
private fun Line(title: String, subtitle: String?, image: String?, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
        if (image != null) AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(title.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Palette.TextDim)
    }
    Column(Modifier.padding(start = 12.dp)) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
