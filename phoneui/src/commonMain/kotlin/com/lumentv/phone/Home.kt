package com.lumentv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lumentv.AppCore
import com.lumentv.core.Featured
import com.lumentv.core.IoDispatcher
import com.lumentv.core.nowMillis
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.repo.LibraryRepository.Shelf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** A title of the banner: its picture, what TMDB knows of it, and what its buttons do. */
private class Hero(val key: String, val title: String, val series: Boolean, val tmdb: String?, val year: Int?,
                   val image: String?, val meta: String?, val play: (() -> Unit)?, val open: () -> Unit)

// ---- Films and Séries homes: banner, resume, TMDB rows (as on the TV and the phone) -----------------------------

@Composable
internal fun MoviesHome(core: AppCore, p: PlaylistEntity, player: VideoPlayer, open: (Route) -> Unit) {
    var shelves by remember(p.id) { mutableStateOf<List<Shelf<MovieRow>>?>(null) }
    LaunchedEffect(p.id, p.lastRefresh) {
        // History and ratings of the moment: the rows are not computed again at each film watched.
        val history = core.library.observeHistory(600).first()
        val ratings = core.library.observeRatings().first()
        core.discover.movies(p.id, history, ratings).collect { list ->
            shelves = list.map { Shelf(it.title, oneEach(it.items, { m -> m.m.name }, { m -> m.m.category })) }.filter { it.items.isNotEmpty() }
        }
    }
    val scope = rememberCoroutineScope()
    val heroes = shelves?.firstOrNull()?.items.orEmpty().take(6).map { m ->
        Hero("mv:" + m.m.id, Featured.shortTitle(m.m.name), false, null, Featured.yearOf(m.m.year, m.m.name), m.m.poster,
            Featured.posterLine(m.m.year, m.m.rating, m.m.name), play = { playMovie(core, player, scope, m) }) { open(Route.Movie(m)) }
    }
    HomeList(core, "Films", heroes, shelves, onCategories = { open(Route.Categories(false)) },
        resume = { ResumeRow(core, p, HistoryKind.MOVIE) { h -> scope.launch { core.library.movieByUid(p.id, h.uid)?.let { open(Route.Movie(it)) } } } },
        row = { items -> items(items, key = { it.m.id }) { m -> Box(Modifier.width(110.dp)) { Poster(m.m.poster, Featured.shortTitle(m.m.name)) { open(Route.Movie(m)) } } } })
}

@Composable
internal fun SeriesHome(core: AppCore, p: PlaylistEntity, player: VideoPlayer, open: (Route) -> Unit) {
    var shelves by remember(p.id) { mutableStateOf<List<Shelf<SeriesRow>>?>(null) }
    LaunchedEffect(p.id, p.lastRefresh) {
        val history = core.library.observeHistory(600).first()
        val ratings = core.library.observeRatings().first()
        core.discover.series(p.id, history, ratings).collect { list ->
            shelves = list.map { Shelf(it.title, oneEach(it.items, { s -> s.s.name }, { s -> s.s.category })) }.filter { it.items.isNotEmpty() }
        }
    }
    val scope = rememberCoroutineScope()
    val heroes = shelves?.firstOrNull()?.items.orEmpty().take(6).map { s ->
        Hero("se:" + s.s.id, Featured.shortTitle(s.s.name), true, s.s.tmdb, Featured.yearOf(s.s.year, s.s.name), s.s.backdrop ?: s.s.cover,
            Featured.posterLine(s.s.year, s.s.rating, null), play = null) { open(Route.Series(s)) }
    }
    HomeList(core, "Séries", heroes, shelves, onCategories = { open(Route.Categories(true)) },
        resume = { ResumeRow(core, p, HistoryKind.EPISODE) { h -> scope.launch { h.parentUid?.let { core.library.seriesByUid(p.id, it) }?.let { open(Route.Series(it)) } } } },
        row = { items -> items(items, key = { it.s.id }) { s -> Box(Modifier.width(110.dp)) { Poster(s.s.cover, Featured.shortTitle(s.s.name)) { open(Route.Series(s)) } } } })
}

/** Plays a film from the banner: where it was left, else from the start; the position is kept on closing. */
private fun playMovie(core: AppCore, player: VideoPlayer, scope: kotlinx.coroutines.CoroutineScope, row: MovieRow) = scope.launch {
    val m = row.m
    val key = LibraryRepository.key(HistoryKind.MOVIE, m.playlistId, m.uid)
    val h = core.library.history(key)
    val from = h?.takeIf { it.durationMs > 0 && it.positionMs in 60_000 until (it.durationMs * 0.95).toLong() }?.positionMs ?: 0
    play(core, player, core.resolver.movie(m), Featured.shortTitle(m.name), Featured.versionLabel(m.name).takeIf { it != "Version" }, from) { pos, dur ->
        scope.launch {
            val done = dur > 0 && pos > dur * 0.95
            core.library.record(HistoryEntity(key, HistoryKind.MOVIE, m.playlistId, m.uid, m.name, null, m.poster, null, nowMillis(), if (done) dur else pos, dur))
        }
    }
}

@Composable
private fun <T> HomeList(
    core: AppCore, title: String, heroes: List<Hero>, shelves: List<Shelf<T>>?, onCategories: () -> Unit,
    resume: @Composable () -> Unit, row: androidx.compose.foundation.lazy.LazyListScope.(List<T>) -> Unit,
) = LazyColumn {
    item {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Catégories ›", color = Palette.Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onCategories).padding(8.dp))
        }
    }
    if (shelves == null) item { Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
    if (heroes.isNotEmpty()) item { Banner(core, heroes) }
    item { resume() }
    shelves.orEmpty().forEach { s ->
        item(key = "t:" + s.title) {
            Text(s.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
        }
        item(key = "r:" + s.title) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { row(s.items) }
        }
    }
    item { Spacer(Modifier.height(24.dp)) }
}

/** The banner: the first titles of the top row, one after the other every 6 s, with TMDB's logo and the buttons. */
@Composable
private fun Banner(core: AppCore, heroes: List<Hero>) {
    val pager = rememberPagerState { heroes.size }
    LaunchedEffect(heroes.size) {
        while (heroes.size > 1) {
            delay(6_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % heroes.size)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(pager, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 10.dp, key = { heroes[it].key }) { page ->
            val h = heroes[page]
            val logo by produceState(core.logos.cached(h.series, h.tmdb, h.title, h.year), h.key) {
                value = withContext(IoDispatcher) { runCatching { core.logos.info(h.series, h.tmdb, h.title, h.year) }.getOrNull() }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(20.dp)).background(Palette.Panel).clickable(onClick = h.open)) {
                AsyncImage(logo?.poster ?: h.image, h.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Palette.Background.copy(alpha = 0.95f))))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val l = logo?.logo
                    if (l != null) AsyncImage(l, h.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth(0.75f).height(70.dp))
                    else Text(h.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(logo?.rating?.let { "★ " + com.lumentv.core.oneDecimal(it) }, h.meta).joinToString("  ·  ")
                    if (meta.isNotEmpty()) Text(meta, color = Palette.Text.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (h.play != null) Button(onClick = h.play, modifier = Modifier.weight(1f)) { Text("▶  Lecture") }
                        OutlinedButton(onClick = h.open, modifier = Modifier.weight(1f)) { Text(if (h.series) "Épisodes" else "ⓘ  Infos") }
                    }
                }
            }
        }
        if (heroes.size > 1) Row(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(heroes.size) { i ->
                Box(Modifier.size(if (i == pager.currentPage) 18.dp else 6.dp, 6.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) Palette.Accent else Palette.TextDim.copy(alpha = 0.5f)))
            }
        }
    }
}

// ---- Profile ----------------------------------------------------------------------------------------------------

private fun clock(t: Long): String = Instant.fromEpochMilliseconds(t).toLocalDateTime(TimeZone.currentSystemDefault()).let {
    it.hour.toString().padStart(2, '0') + ":" + it.minute.toString().padStart(2, '0')
}

private fun day(t: Long): String = Instant.fromEpochMilliseconds(t).toLocalDateTime(TimeZone.currentSystemDefault()).let {
    it.dayOfMonth.toString().padStart(2, '0') + "/" + it.monthNumber.toString().padStart(2, '0') + " " + clock(t)
}

/** The online profile (favourites, history, resume, ratings shared with the TV, the phone and the PC) and the list. */
@Composable
internal fun ProfileHome(core: AppCore, p: PlaylistEntity) {
    val state by core.profile.state.collectAsState()
    val busy by core.playlists.busy.collectAsState()
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var signing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Profil", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item {
            Section("Profil en ligne") {
                when {
                    !core.profile.enabled -> Text("Le profil en ligne n'est pas encore activé dans cette version de l'app.", color = Palette.TextDim)
                    state.name == null -> {
                        Text("Le même profil que sur la TV : favoris, historique, reprise et notes suivent d'un appareil à l'autre. Chiffré sur l'iPhone avant l'envoi.",
                            color = Palette.TextDim, fontSize = 14.sp)
                        OutlinedTextField(name, { name = it }, label = { Text("Nom du profil") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pass, { pass = it }, label = { Text("Phrase secrète") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth())
                        error?.let { Text(it, color = Palette.Danger) }
                        Button(enabled = !signing && name.isNotBlank() && pass.isNotBlank(), onClick = {
                            signing = true; error = null
                            scope.launch {
                                core.profile.signIn(name.trim(), pass).onFailure { error = it.message ?: "Connexion impossible." }
                                signing = false
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (signing) "Connexion…" else "Se connecter") }
                    }
                    else -> {
                        Text("Profil « ${state.name} »", fontWeight = FontWeight.SemiBold)
                        Text(when {
                            state.syncing -> "Synchronisation…"
                            state.error != null -> state.error!!
                            state.lastSync > 0 -> "Synchronisé à " + clock(state.lastSync)
                            else -> "Pas encore synchronisé"
                        }, color = if (state.error != null) Palette.Danger else Palette.TextDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { scope.launch { core.profile.syncNow() } }) { Text("Synchroniser") }
                            OutlinedButton(onClick = { core.profile.signOut() }) { Text("Se déconnecter") }
                        }
                    }
                }
            }
        }
        item {
            Section("Liste") {
                Text(p.name, fontWeight = FontWeight.SemiBold)
                Text(listOf("${p.channelCount} chaînes", "${p.movieCount} films", "${p.seriesCount} séries").joinToString(" · "), color = Palette.TextDim, fontSize = 14.sp)
                if (p.lastRefresh > 0) Text("Actualisée le " + day(p.lastRefresh), color = Palette.TextDim, fontSize = 14.sp)
                p.accountInfo?.let { Text(it, color = Palette.TextDim, fontSize = 14.sp) }
                Button(enabled = p.id !in busy, onClick = {
                    core.scope.launch { if (core.playlists.refresh(p.id).ok) core.epg.refresh(p.id) }
                }) { Text(if (p.id in busy) "Actualisation…" else "Recharger la liste") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    Text(title, color = Palette.Accent, fontWeight = FontWeight.SemiBold)
    content()
}
