package com.lumentv.phone

import com.lumentv.data.ratingText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lumentv.AppCore
import com.lumentv.core.Featured
import com.lumentv.core.IoDispatcher
import com.lumentv.core.Resume
import com.lumentv.core.nowMillis
import com.lumentv.data.TitleLogos
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.data.db.MovieRow
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.db.PlaylistType
import com.lumentv.data.db.PrefKind
import com.lumentv.data.db.SeriesRow
import com.lumentv.data.db.TransferEntity
import com.lumentv.data.db.TransferStatus
import com.lumentv.data.repo.LibraryRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The film and series pages of the Android phone (PhoneVod.kt in :app), for the iPhone.

/** What TMDB says of a title (logo, rating, summary, people), asked in the background and kept for the session. */
@Composable
private fun tmdbInfo(core: AppCore, title: String, series: Boolean, tmdb: String?, year: Int?): TitleLogos.Info? {
    val clean = Featured.shortTitle(title)
    val info by produceState(core.logos.cached(series, tmdb, clean, year), series, tmdb, clean, year) {
        value = withContext(IoDispatcher) { runCatching { core.logos.info(series, tmdb, clean, year) }.getOrNull() }
    }
    return info
}

private fun duration(secs: Int): String = if (secs >= 3600) "${secs / 3600} h ${((secs % 3600) / 60).toString().padStart(2, '0')}" else "${secs / 60} min"

private fun clockOf(ms: Long): String {
    val s = ms / 1000
    val m = ((s % 3600) / 60).toString().padStart(2, '0')
    val sec = (s % 60).toString().padStart(2, '0')
    return if (s >= 3600) "${s / 3600}:$m:$sec" else "${s / 60}:$sec"
}

// ---- A film ------------------------------------------------------------------------------------------------------

@Composable
internal fun MoviePage(core: AppCore, row: MovieRow, player: VideoPlayer) {
    val history by core.library.observeHistory(300).collectAsState(emptyList())
    val changes by core.library.changes.collectAsState()
    val ratings by core.library.observeRatings().collectAsState(emptyList())
    // The copies of the film (4K MULTI, FHD VF, VOSTFR…), without the Arabic ones; the one watched last chosen first.
    val versions by produceState(listOf(row), row.m.id) {
        value = core.library.versions(row.m).filter { !Featured.foreign(it.m.name, it.m.category) }.ifEmpty { listOf(row) }
    }
    var picked by remember(row.m.id) { mutableStateOf<String?>(null) }
    val watchedCopy = versions.mapNotNull { v -> history.firstOrNull { it.key == LibraryRepository.key(HistoryKind.MOVIE, v.m.playlistId, v.m.uid) } }
        .maxByOrNull { it.watchedAt }
    val uid = picked ?: watchedCopy?.uid ?: row.m.uid
    val current by produceState(versions.firstOrNull { it.m.uid == uid } ?: row, uid, changes, versions) {
        value = core.library.movieByUid(row.m.playlistId, uid) ?: versions.firstOrNull { it.m.uid == uid } ?: row
    }
    val m = current.m
    val key = LibraryRepository.key(HistoryKind.MOVIE, m.playlistId, m.uid)
    val h = history.firstOrNull { it.key == key }
    val resumable = h != null && h.positionMs > 30_000 && (h.durationMs == 0L || h.positionMs < h.durationMs * 0.95)
    val myStars = ratings.firstOrNull { it.key == key }?.stars ?: 0
    val info by produceState(core.artwork.cached(m), m.id) { value = core.artwork.movie(m) }
    val year = Featured.yearOf(m.year ?: info?.releaseDate, m.name)
    val t = tmdbInfo(core, m.name, false, info?.tmdb, year)
    var versionMenu by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val downloads = LocalDownloads.current
    val transfers by remember(downloads) { downloads?.observe() ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(emptyList())
    val dl = transfers.firstOrNull { it.playlistId == m.playlistId && it.refUid == m.uid }
    // A downloaded film plays from its file: no network needed.
    fun start(fromMs: Long) = scope.launch {
        val spec = dl?.takeIf { it.status == TransferStatus.DONE && it.filePath != null }
            ?.let { com.lumentv.data.repo.StreamSpec(DownloadManager.SCHEME + it.filePath, null, null, false) } ?: core.resolver.movie(m)
        val fromFile = spec.url.startsWith(DownloadManager.SCHEME)
        play(core, player, spec, Featured.shortTitle(m.name), Featured.versionLabel(m.name).takeIf { it != "Version" }, fromMs, m.playlistId.takeIf { !fromFile }) { pos, dur ->
            core.scope.launch {
                val done = dur > 0 && pos > dur * 0.95
                core.library.record(HistoryEntity(key, HistoryKind.MOVIE, m.playlistId, m.uid, m.name, null, m.poster, null, nowMillis(), if (done) dur else pos, dur))
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        item(key = "top") {
            DetailTop(info?.backdrop ?: m.poster, com.lumentv.data.frenchTitle(m.name, t), t?.logo)
            Meta(listOfNotNull(ratingText(t, m.rating), year?.toString(), info?.durationSecs?.takeIf { it > 0 }?.let { duration(it) }))
            info?.genre?.takeIf { it.isNotBlank() }?.let {
                Text(it.split(',').take(3).joinToString(" • ") { g -> g.trim() }, fontSize = 13.sp, color = Palette.TextDim,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center)
            }
        }
        item(key = "buttons") {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (resumable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(h!!.positionMs.toFloat() / h.durationMs.coerceAtLeast(1), Modifier.weight(1f))
                        Text("  " + (Resume.remaining(h.positionMs, h.durationMs) ?: ""), fontSize = 12.sp, color = Palette.TextDim)
                    }
                    PhoneButton("▶  Reprendre à ${clockOf(h.positionMs)}", primary = true, modifier = Modifier.fillMaxWidth()) { start(h.positionMs) }
                    PhoneButton("↺  Depuis le début", modifier = Modifier.fillMaxWidth()) { start(0) }
                } else PhoneButton("▶  Lecture", primary = true, modifier = Modifier.fillMaxWidth()) { start(0) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconAction(if (current.isFavorite) "✓" else "+", "Ma liste") { scope.launch { core.library.toggleFavorite(m.playlistId, PrefKind.MOVIE, m.uid) } }
                    if (downloads != null) DownloadAction(dl) {
                        scope.launch {
                            when (dl?.status) {
                                TransferStatus.DONE -> start(0)
                                TransferStatus.RUNNING, TransferStatus.SCHEDULED -> downloads.cancel(dl.id)
                                TransferStatus.FAILED, TransferStatus.CANCELLED -> downloads.retry(dl.id)
                                else -> downloads.downloadMovie(m)
                            }
                        }
                    }
                    if (versions.size > 1) IconAction("⇄", Featured.versionLabel(m.name)) { versionMenu = true }
                }
            }
        }
        item(key = "about") {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                if (h != null || myStars > 0) StarRating(myStars, if (myStars > 0) "Votre note" else "Notez ce film", Modifier.padding(bottom = 10.dp)) { n ->
                    scope.launch { core.library.rate(HistoryKind.MOVIE, m.playlistId, m.uid, n, Featured.shortTitle(m.name), m.poster) }
                }
                ExpandableText(t?.overview ?: info?.plot ?: "Aucun résumé disponible.", 4)
                People(t, "Réalisation")
                Text(m.category, fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (versionMenu) Options("Version", "La même œuvre en plusieurs qualités ou langues", versions.map { v ->
        Option(Featured.versionLabel(v.m.name) + (Featured.rating(v.m.rating)?.let { "  ·  ★ $it" } ?: ""), checked = v.m.uid == m.uid) { picked = v.m.uid }
    }) { versionMenu = false }
}

// ---- A series ----------------------------------------------------------------------------------------------------

/**
 * The episode "Lecture" starts: the furthest one reached (by season and number): itself when left halfway (true), else
 * the one after it; a series seen to its last episode starts again at the first.
 */
private fun nextEpisode(eps: List<EpisodeEntity>?, progress: Map<String, HistoryEntity>, watched: (EpisodeEntity) -> Boolean): Pair<EpisodeEntity, Boolean>? =
    eps?.let { list ->
        val l = list.filter { it.uid in progress }.maxWithOrNull(compareBy({ it.season }, { it.episode }))
        when {
            l != null && !watched(l) -> l to true
            l != null -> list.getOrNull(list.indexOf(l) + 1)?.let { it to false } ?: list.firstOrNull()?.let { it to false }
            else -> list.firstOrNull()?.let { it to false }
        }
    }

@Composable
internal fun SeriesPage(core: AppCore, p: PlaylistEntity, row: SeriesRow, player: VideoPlayer) {
    val changes by core.library.changes.collectAsState()
    val series by produceState(row, row.s.uid, changes) { value = core.library.seriesByUid(row.s.playlistId, row.s.uid) ?: row }
    val s = series.s
    var error by remember(s.id) { mutableStateOf<String?>(null) }
    val episodes by produceState<List<EpisodeEntity>?>(null, s.id) {
        value = runCatching {
            if (p.type == PlaylistType.XTREAM) core.playlists.loadXtreamEpisodes(p, s.uid, s.seriesId) else core.library.storedEpisodes(s.playlistId, s.uid)
        }.getOrElse { error = it.message; core.library.storedEpisodes(s.playlistId, s.uid) }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }
    val history by core.library.observeHistory(600).collectAsState(emptyList())
    val ratings by core.library.observeRatings().collectAsState(emptyList())
    val progress = remember(history) { history.filter { it.kind == HistoryKind.EPISODE && it.parentUid == s.uid }.associateBy { it.uid } }
    fun watched(e: EpisodeEntity) = progress[e.uid]?.let { Resume.watched(it.positionMs, it.durationMs) } == true
    val eps = episodes
    val seasons = eps?.map { it.season }?.distinct()?.sorted().orEmpty()
    val next = nextEpisode(eps, progress, ::watched)
    var picked by remember(s.id) { mutableStateOf<Int?>(null) }
    val season = picked ?: next?.first?.season ?: seasons.firstOrNull() ?: 1
    val inSeason = eps?.filter { it.season == season }.orEmpty()
    val myStars = ratings.firstOrNull { it.key == LibraryRepository.key("se", s.playlistId, s.uid) }?.stars ?: 0
    val t = tmdbInfo(core, s.name, true, s.tmdb, Featured.year(s.year))
    var menu by remember { mutableStateOf<EpisodeEntity?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val downloads = LocalDownloads.current
    val transfers by remember(downloads) { downloads?.observe() ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsState(emptyList())
    fun dlOf(e: EpisodeEntity) = transfers.firstOrNull { it.playlistId == e.playlistId && it.refUid == e.uid }

    // In the app's scope: the next episode still opens when this page has gone in the meantime.
    fun start(e: EpisodeEntity, fromMs: Long) {
        core.scope.launch {
            val all = episodes.orEmpty()
            val i = all.indexOfFirst { it.uid == e.uid }
            val before = all.getOrNull(i - 1)
            val after = if (i >= 0) all.getOrNull(i + 1) else null
            val label = "S${e.season} E${e.episode}"
            val key = LibraryRepository.key(HistoryKind.EPISODE, e.playlistId, e.uid)
            val downloaded = downloads?.fileOf(e.playlistId, e.uid)?.takeIf { it.filePath != null }
            val file = downloaded?.filePath
            val spec = file?.let { com.lumentv.data.repo.StreamSpec(DownloadManager.SCHEME + it, null, null, false) } ?: core.resolver.episode(e)
            val request = PlayRequest(spec.url, spec.userAgent, spec.referer, Featured.shortTitle(s.name), "$label · ${e.title}", fromMs, false,
                onFinish = { pos, dur ->
                    core.scope.launch {
                        val done = dur > 0 && pos > dur * 0.95
                        core.library.record(HistoryEntity(key, HistoryKind.EPISODE, e.playlistId, e.uid, s.name, "$label · ${e.title}", s.cover,
                            s.uid, nowMillis(), if (done) dur else pos, dur))
                    }
                },
                // The episode before resumes where it was left; the next one starts at the beginning.
                onPrevious = before?.let { b -> {
                    core.scope.launch {
                        val h = core.library.history(LibraryRepository.key(HistoryKind.EPISODE, b.playlistId, b.uid))
                        start(b, h?.takeIf { it.durationMs > 0 && it.positionMs < it.durationMs * 0.95 }?.positionMs ?: 0)
                    }
                } },
                onNext = after?.let { n -> { start(n, 0) } },
                autoNext = core.settings.current().autoNext,
            )
            Playback.loadSkips(core, request, s.tmdb, e.season, e.episode)
            Playback.start(core, player, request, e.playlistId.takeIf { file == null })
        }
    }
    fun resumeOf(e: EpisodeEntity) = progress[e.uid]?.takeIf { it.positionMs > 30_000 && !watched(e) }?.positionMs ?: 0
    // Watched = played to the end (same rule as the TV and the PC); "non vu" removes the line.
    fun markWatched(list: List<EpisodeEntity>, on: Boolean) = scope.launch {
        val now = (progress.values.minOfOrNull { it.watchedAt } ?: nowMillis()) - list.size
        list.forEachIndexed { i, e ->
            val key = LibraryRepository.key(HistoryKind.EPISODE, s.playlistId, e.uid)
            if (!on) core.library.deleteHistory(key)
            else {
                val dur = (e.durationSecs * 1000L).coerceAtLeast(1)
                core.library.record(HistoryEntity(key, HistoryKind.EPISODE, s.playlistId, e.uid, s.name, "S${e.season} E${e.episode} · ${e.title}",
                    e.image ?: s.cover, s.uid, progress[e.uid]?.watchedAt ?: (now - (list.size - i)), dur, dur))
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        item(key = "top") {
            DetailTop(s.backdrop ?: s.cover, com.lumentv.data.frenchTitle(s.name, t), t?.logo)
            Meta(listOfNotNull(ratingText(t, s.rating), s.year?.take(4),
                if (seasons.isNotEmpty()) "${seasons.size} saison" + (if (seasons.size > 1) "s" else "") else null))
        }
        item(key = "buttons") {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                next?.let { (e, resume) ->
                    PhoneButton("▶  " + (if (resume) "Reprendre" else "Lecture") + " S${e.season} E${e.episode}", primary = true, modifier = Modifier.fillMaxWidth()) {
                        start(e, if (resume) resumeOf(e) else 0)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconAction(if (series.isFavorite) "✓" else "+", "Ma liste") { scope.launch { core.library.toggleFavorite(s.playlistId, PrefKind.SERIES, s.uid) } }
                    if (inSeason.isNotEmpty()) {
                        val all = inSeason.all { watched(it) }
                        IconAction(if (all) "✓" else "○", if (all) "Saison vue" else "Saison $season vue") { markWatched(inSeason, !all) }
                    }
                }
            }
        }
        item(key = "about") {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                if (progress.isNotEmpty() || myStars > 0) StarRating(myStars, if (myStars > 0) "Votre note" else "Notez cette série", Modifier.padding(bottom = 10.dp)) { n ->
                    scope.launch { core.library.rate("se", s.playlistId, s.uid, n, Featured.shortTitle(s.name), s.cover) }
                }
                (t?.overview ?: s.plot)?.takeIf { it.isNotBlank() }?.let { ExpandableText(it, 3) }
                People(t, "Créée par")
            }
        }
        if (seasons.size > 1) item(key = "seasons") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                items(seasons) { n -> PhoneChip("Saison $n", selected = n == season) { picked = n } }
            }
        } else item(key = "gap") { Spacer(Modifier.height(14.dp)) }
        when {
            eps == null -> item(key = "loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            eps.isEmpty() -> item(key = "empty") {
                Text(error ?: "Le fournisseur ne liste aucun épisode pour cette série.", color = Palette.TextDim, modifier = Modifier.padding(vertical = 16.dp))
            }
            else -> items(inSeason, key = { it.id }) { e ->
                val h = progress[e.uid]
                val seen = watched(e)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { start(e, resumeOf(e)) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.PanelHigh)) {
                        AsyncImage(e.image ?: s.backdrop ?: s.cover, e.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (seen) Text("✓ Vu", fontSize = 11.sp, color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                            .clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 5.dp, vertical = 1.dp))
                        if (h != null && h.durationMs > 0 && !seen) ProgressLine(h.positionMs.toFloat() / h.durationMs, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("${e.episode}. " + e.title.substringAfterLast(" - ").ifBlank { e.title }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val d = dlOf(e)
                        Text(listOfNotNull(e.durationSecs.takeIf { it > 0 }?.let { duration(it) }, when (d?.status) {
                            TransferStatus.DONE -> "⬇ téléchargé"
                            TransferStatus.RUNNING -> "⬇ " + (if (d.totalBytes > 0) "${d.bytes * 100 / d.totalBytes} %" else "en cours")
                            TransferStatus.SCHEDULED -> "⬇ en attente"
                            else -> null
                        }).joinToString(" · "), fontSize = 12.sp, color = Palette.TextDim)
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { menu = e }, contentAlignment = Alignment.Center) {
                        Text("⋮", fontSize = 22.sp, color = Palette.TextDim)
                    }
                }
                e.plot?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }
    menu?.let { e ->
        Options(e.title, null, listOf(
            Option("Lire") { start(e, resumeOf(e)) },
            Option("Depuis le début") { start(e, 0) },
            if (watched(e)) Option("Marquer comme non vu") { markWatched(listOf(e), false) } else Option("Marquer comme vu") { markWatched(listOf(e), true) },
        ) + listOfNotNull(downloads?.let { d ->
            val t = dlOf(e)
            when (t?.status) {
                TransferStatus.DONE -> Option("Supprimer le téléchargement") { scope.launch { d.delete(t.id) } }
                TransferStatus.RUNNING, TransferStatus.SCHEDULED -> Option("Annuler le téléchargement") { scope.launch { d.cancel(t.id) } }
                TransferStatus.FAILED, TransferStatus.CANCELLED -> Option("Réessayer le téléchargement") { scope.launch { d.retry(t.id) } }
                else -> Option("Télécharger l'épisode") { scope.launch { d.downloadEpisode(e, s.name) } }
            }
        })) { menu = null }
    }
}

// ---- Pieces (as on the Android phone) ----------------------------------------------------------------------------

/** The top of a page: the title's picture fading into the page, its logo (or name) over the bottom of it. */
@Composable
private fun DetailTop(image: String?, title: String, logo: String?) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp))) {
        Backdrop(image, Modifier.fillMaxSize(), fade = 0.25f)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Logo(title, logo, 72.dp, 26)
        }
    }
}

@Composable
private fun Meta(parts: List<String>) {
    if (parts.isEmpty()) return
    Text(parts.joinToString("  ·  "), fontSize = 14.sp, color = Palette.Text.copy(alpha = 0.85f), maxLines = 2,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp), textAlign = TextAlign.Center)
}

/** A wide picture, darkened towards the bottom so the text over it reads. */
@Composable
private fun Backdrop(url: String?, modifier: Modifier, fade: Float) {
    val shade = remember(fade) { Brush.verticalGradient(0f to Color.Transparent, fade to Color.Transparent, 1f to Palette.Background) }
    Box(modifier.background(Palette.PanelHigh).drawWithContent { drawContent(); drawRect(shade) }) {
        if (!url.isNullOrBlank()) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** The title as its poster writes it (TMDB logo), else in big letters. */
@Composable
private fun Logo(title: String, logo: String?, height: Dp, textSize: Int) {
    val clean = Featured.shortTitle(title)
    if (logo != null) AsyncImage(logo, clean, contentScale = ContentScale.Fit, modifier = Modifier.height(height).fillMaxWidth(0.8f))
    else Text(clean, fontSize = textSize.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
        lineHeight = (textSize * 1.15f).sp, textAlign = TextAlign.Center)
}

/** A big button: white for the main action, glassy otherwise. */
@Composable
internal fun PhoneButton(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) =
    com.lumentv.ui.kit.KitButton(text, primary, modifier, onClick)

/** A round icon with its label under it ("+ Ma liste"). */
@Composable
private fun IconAction(icon: String, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 22.sp, color = Palette.Text)
        Text(label, fontSize = 11.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(88.dp), textAlign = TextAlign.Center)
    }
}

/** "⬇ Télécharger", "⬇ 42 %", "▶ Téléchargé", "↻ Réessayer". */
@Composable
private fun DownloadAction(t: TransferEntity?, onClick: () -> Unit) = when (t?.status) {
    TransferStatus.DONE -> IconAction("▶", "Téléchargé", onClick)
    TransferStatus.SCHEDULED -> IconAction("⏳", "En attente · annuler", onClick)
    TransferStatus.RUNNING -> IconAction("⏸", if (t.totalBytes > 0) "${t.bytes * 100 / t.totalBytes} % · annuler" else "Annuler", onClick)
    TransferStatus.FAILED, TransferStatus.CANCELLED -> IconAction("↻", "Réessayer", onClick)
    else -> IconAction("⬇", "Télécharger", onClick)
}

/** A glassy pill: seasons. */
@Composable
internal fun PhoneChip(label: String, selected: Boolean = false, onClick: () -> Unit) = com.lumentv.ui.kit.KitChip(label, selected, onClick)

/** Who made it and the main actors, from TMDB only: nothing when TMDB does not know the title. */
@Composable
private fun People(t: TitleLogos.Info?, makersLabel: String) {
    t?.makers?.takeIf { it.isNotEmpty() }?.let { Text("$makersLabel : " + it.joinToString(", "), fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 10.dp)) }
    t?.cast?.takeIf { it.isNotEmpty() }?.let {
        Text("Avec : " + it.joinToString(", "), fontSize = 12.sp, color = Palette.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A summary cut to [lines]; a tap shows it all. */
@Composable
private fun ExpandableText(text: String, lines: Int) {
    var open by remember { mutableStateOf(false) }
    Text(text, fontSize = 14.sp, color = Palette.Text.copy(alpha = 0.92f), maxLines = if (open) 60 else lines, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { open = !open })
}

/** 1 to 5 stars; the same star again removes the note. */
@Composable
private fun StarRating(stars: Int, label: String, modifier: Modifier = Modifier, onRate: (Int) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextDim, modifier = Modifier.padding(end = 8.dp))
        (1..5).forEach { i ->
            Text(if (i <= stars) "★" else "☆", fontSize = 26.sp, color = if (i <= stars) Palette.Gold else Palette.TextDim,
                modifier = Modifier.clip(CircleShape).clickable { onRate(if (i == stars) 0 else i) }.padding(horizontal = 6.dp, vertical = 2.dp))
        }
        if (stars > 0) Text("  $stars/5", fontSize = 14.sp, color = Palette.Text)
    }
}

@Composable
internal fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Palette.Accent))
    }
}

internal class Option(val label: String, val checked: Boolean = false, val danger: Boolean = false, val onClick: () -> Unit)

/** A short menu (versions of a film, an episode's actions): the shared one, as on the TV and the PC. */
@Composable
internal fun Options(title: String, subtitle: String?, options: List<Option>, onDismiss: () -> Unit) =
    com.lumentv.ui.kit.KitMenu(title, options.map { com.lumentv.ui.kit.KitOption(it.label, it.checked, it.danger, it.onClick) }, onDismiss, subtitle)
