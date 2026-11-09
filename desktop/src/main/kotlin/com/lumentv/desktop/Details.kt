@file:OptIn(ExperimentalFoundationApi::class)

package com.lumentv.desktop

import com.lumentv.data.ratingText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lumentv.core.Featured
import com.lumentv.core.userMessage
import java.io.File

private val green = Color(0xFF3DDC97)

// ---- Small building blocks ------------------------------------------------------------------------------------

@Composable
fun Pill(text: String, onClick: () -> Unit, icon: ImageVector? = null, primary: Boolean = false, enabled: Boolean = true) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(if (primary) Color.White else Pal.panelHigh)
        .then(if (enabled) Modifier.combinedClickable(onClick = onClick) else Modifier.alpha(0.5f)).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { Icon(it, null, tint = if (primary) Pal.bg else Pal.text, modifier = Modifier.size(18.dp)) }
        Text(text, color = if (primary) Pal.bg else Pal.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun Chip(text: String, color: Color = Pal.text, background: Color = Pal.panelHigh, icon: ImageVector? = null, iconTint: Color = Color(0xFFFFC94D)) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icon?.let { Icon(it, null, tint = iconTint, modifier = Modifier.size(14.dp)) }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Download button for one film / episode: ⬇ → percentage → ✓ (plays the file). */
@Composable
fun DownloadButton(state: AppState, uid: String, item: () -> PlayItem, compact: Boolean = true) {
    val t = state.downloads.of(uid)
    when {
        t == null -> if (compact) IconButton({ state.download(item()) }) { Icon(Icons.Filled.Download, "Télécharger", tint = Pal.dim) }
            else Pill("Télécharger", { state.download(item()) }, Icons.Filled.Download)
        t.status == DlStatus.DONE -> if (compact) IconButton({ state.playFile(t) }) { Icon(Icons.Filled.DownloadDone, "Lire le fichier téléchargé", tint = green) }
            else Pill("Fichier téléchargé", { state.playFile(t) }, Icons.Filled.DownloadDone)
        else -> {
            val pct = if (t.total > 0) (t.done * 100 / t.total).toInt() else 0
            Box(Modifier.size(if (compact) 40.dp else 44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator({ pct / 100f }, Modifier.size(30.dp), strokeWidth = 3.dp, color = Pal.accent, trackColor = Pal.panelHigh)
                Text(if (t.status == DlStatus.QUEUED) "…" else "$pct", fontSize = 10.sp, color = Pal.text)
            }
        }
    }
}

fun AppState.playFile(t: DownloadTask) {
    val start = progress(t.kind, t.uid)?.let { if (it.durationMs > 0 && it.positionMs > it.durationMs - 120_000) 0 else it.positionMs } ?: 0
    val tmdb = t.tmdb ?: t.parentUid?.let { u -> catalog?.series?.firstOrNull { it.uid == u }?.tmdb }
    val (season, episode) = if (t.season > 0) t.season to t.episode
        else Regex("S(\\d+) E(\\d+)").find(t.subtitle.orEmpty())?.destructured?.let { (a, b) -> a.toInt() to b.toInt() } ?: (0 to 0)
    PlayerWindow.open(this, PlayItem(t.kind, t.uid, t.title, t.subtitle, t.image, t.parentUid, File(t.file).toURI().toString(), live = false, startMs = start,
        tmdb = tmdb, season = season, episode = episode))
}

@Composable
private fun TopActions(onBack: () -> Unit, favorite: Boolean, onFavorite: () -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Pill("Retour", onBack, Icons.AutoMirrored.Filled.ArrowBack)
    Spacer(Modifier.weight(1f))
    Box(Modifier.size(42.dp).clip(CircleShape).background(Pal.panelHigh).combinedClickable(onClick = onFavorite), contentAlignment = Alignment.Center) {
        Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favori", tint = if (favorite) Color(0xFFFF6B81) else Pal.text)
    }
}

/** "Réalisation : …" / "Avec : …" from TMDB (nothing without it: the provider's lists are often wrong). */
@Composable
private fun Credit(label: String, names: List<String>, max: Int = 6) {
    if (names.isEmpty()) return
    Row(Modifier.padding(top = 4.dp)) {
        Text("$label : ", color = Pal.dim)
        Text(names.take(max).joinToString(", "), color = Pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Film page ------------------------------------------------------------------------------------------------

@Composable
fun MovieDetail(state: AppState, m0: Mv, onBack: () -> Unit) {
    val p = state.active ?: return
    val c = state.catalog ?: return
    // The copy shown: "Version" switches to another one of the same film (4K, VF, VOSTFR…).
    val versions = remember(c, m0.uid) { state.vod.versions(c, m0) }
    var uid by remember(m0.uid) { mutableStateOf(m0.uid) }
    val m = versions.firstOrNull { it.uid == uid } ?: m0
    val details by produceState(state.library.cachedMovieDetails(m), m.uid) { value = runCatching { state.library.movieDetails(p, m) }.getOrNull() }
    val year = Featured.yearOf(m.year ?: details?.release, m.name)
    val t = rememberInfo(state, false, m.name, null, year)
    val h = state.progress(Kind.MOVIE, m.uid)
    val resumable = h != null && h.positionMs > 60_000 && !state.isWatched(Kind.MOVIE, m.uid)
    fun item(fromStart: Boolean = false): PlayItem {
        val start = if (fromStart || !resumable) 0 else h!!.positionMs
        return PlayItem(Kind.MOVIE, m.uid, m.name, m.year, m.poster, null, state.library.movieUrl(p, m), live = false, startMs = start, userAgent = p.ua)
    }
    Box(Modifier.fillMaxSize()) {
        BannerPicture(details?.backdrop ?: m.poster, 480.dp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TopActions(onBack, state.isFavorite(Kind.MOVIE, m.uid)) { state.toggleFavorite(Kind.MOVIE, m.uid) } }
            item {
                Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 70.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TitleArt(com.lumentv.data.frenchTitle(m.name, t), t?.logo, 40.sp, 120.dp)
                    val meta = listOfNotNull(ratingText(t, details?.rating ?: m.rating), year?.toString(), details?.durationSecs?.takeIf { it > 0 }?.let { "${it / 3600} h ${"%02d".format(it / 60 % 60)}" },
                        details?.genre?.split(',')?.take(3)?.joinToString(" • ") { it.trim() }?.takeIf { it.isNotBlank() },
                        Featured.versionLabel(m.name).takeIf { it != "Version" })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(meta.joinToString("  ·  "), color = Pal.text.copy(alpha = 0.85f))
                        if (state.isWatched(Kind.MOVIE, m.uid)) Chip("REGARDÉ", green, green.copy(alpha = 0.15f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (resumable) "Reprendre" else "Lecture", { PlayerWindow.open(state, item()) }, Icons.Filled.PlayArrow, primary = true)
                        if (resumable) Pill("Depuis le début", { PlayerWindow.open(state, item(fromStart = true)) })
                        if (versions.size > 1) MenuPill("Version : ${Featured.versionLabel(m.name)}  ▾", versions.map { v -> Featured.versionLabel(v.name) to { uid = v.uid } })
                        DownloadButton(state, m.uid, { item(fromStart = true) }, compact = false)
                    }
                    if (resumable) Text("Reprendre à ${PlayerWindow.fmt(h!!.positionMs)}" + if (h.durationMs > 0) " · ${h.positionMs * 100 / h.durationMs} % vu" else "", color = Pal.dim)
                    StarRating(state.rating(Kind.MOVIE, m.uid)) { state.rate(Kind.MOVIE, m.uid, it, m.name, m.poster) }
                    Text(t?.overview ?: details?.plot ?: "Aucun résumé disponible.", color = Pal.text.copy(alpha = 0.92f), lineHeight = 22.sp, modifier = Modifier.fillMaxWidth(0.6f))
                    t?.let { i -> Column { Credit("Réalisation", i.makers); Credit("Avec", i.cast) } }
                }
            }
        }
    }
}

// ---- Series page ----------------------------------------------------------------------------------------------

@Composable
fun SeriesDetail(state: AppState, s0: Se, onBack: () -> Unit) {
    val p = state.active ?: return
    val c = state.catalog ?: return
    val s = remember(c, s0.uid) { c.series.firstOrNull { it.uid == s0.uid } ?: s0 }
    val data by produceState<Result<Pair<Details?, List<Ep>>>?>(null, s.uid) { value = runCatching { state.library.seriesDetails(p, c, s) } }
    val details = data?.getOrNull()?.first
    val eps = data?.getOrNull()?.second.orEmpty()
    val bySeason = remember(eps) { eps.groupBy { it.season } }
    fun item(i: Int, fromStart: Boolean = false) = state.episodeItem(s, eps, i, fromStart)
    // Where to continue: the episode in progress, else the one after the last watched, else the first.
    val (target, targetLabel) = remember(eps, state.doc.history) {
        val inProgress = eps.indices.filter { state.progress(Kind.EPISODE, eps[it].uid)?.let { h -> h.positionMs > 60_000 } == true && !state.isWatched(Kind.EPISODE, eps[it].uid) }
            .maxByOrNull { state.progress(Kind.EPISODE, eps[it].uid)!!.watchedAt }
        val lastDone = eps.indices.filter { state.isWatched(Kind.EPISODE, eps[it].uid) }.maxByOrNull { state.progress(Kind.EPISODE, eps[it].uid)!!.watchedAt }
        when {
            inProgress != null -> inProgress to "Continuer"
            lastDone != null && lastDone + 1 < eps.size -> lastDone + 1 to "Continuer"
            eps.isNotEmpty() -> 0 to "Commencer"
            else -> -1 to ""
        }
    }
    val expanded = remember(s.uid) { mutableStateMapOf<Int, Boolean>() }
    val list = rememberLazyListState()

    val t = rememberInfo(state, true, s.name, s.tmdb, Featured.year(s.year))
    val seasons = bySeason.size

    Box(Modifier.fillMaxSize()) {
        BannerPicture(details?.backdrop ?: s.backdrop ?: s.cover, 480.dp)
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { TopActions(onBack, state.isFavorite(Kind.SERIES, s.uid)) { state.toggleFavorite(Kind.SERIES, s.uid) } }
            item {
                Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 70.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TitleArt(com.lumentv.data.frenchTitle(s.name, t), t?.logo, 40.sp, 120.dp)
                    Text(listOfNotNull(ratingText(t, details?.rating ?: s.rating), (details?.release ?: s.year)?.take(4)?.takeIf { it.isNotBlank() },
                        if (seasons > 0) "$seasons saison" + if (seasons > 1) "s" else "" else null,
                        details?.genre?.takeIf { it.isNotBlank() } ?: s.category).joinToString("  ·  "), color = Pal.text.copy(alpha = 0.85f))
                    if (target >= 0) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill(targetLabel, { PlayerWindow.open(state, item(target)) }, Icons.Filled.PlayArrow, primary = true)
                        val e = eps[target]
                        Text("$targetLabel : S${e.season} E${e.episode} • ${e.title}", color = Pal.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    StarRating(state.rating(Kind.SERIES, s.uid)) { state.rate(Kind.SERIES, s.uid, it, s.name, s.cover) }
                    (t?.overview ?: details?.plot ?: s.plot)?.let { Text(it, color = Pal.text.copy(alpha = 0.92f), lineHeight = 22.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.6f)) }
                    t?.let { i -> Column { Credit("Créée par", i.makers); Credit("Avec", i.cast) } }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ÉPISODES", color = Pal.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (eps.isNotEmpty()) {
                        Pill("Télécharger la série", { eps.indices.forEach { state.download(item(it, true)) } }, Icons.Filled.Download)
                        val allWatched = eps.all { state.isWatched(Kind.EPISODE, it.uid) }
                        Pill(if (allWatched) "Tout marquer non vu" else "Tout marquer regardé",
                            { state.markWatched(eps.indices.map { item(it, true) }, eps.map { it.durationSecs * 1000L }, !allWatched) })
                    }
                }
            }
            when (val r = data) {
                null -> item { Text("Chargement des épisodes…", color = Pal.dim, modifier = Modifier.padding(16.dp)) }
                else -> r.exceptionOrNull()?.let { e -> item { Text(userMessage(e), color = Pal.danger, modifier = Modifier.padding(16.dp)) } }
            }
            bySeason.forEach { (season, list) ->
                val open = expanded[season] ?: (target >= 0 && eps[target].season == season)
                val allWatched = list.all { state.isWatched(Kind.EPISODE, it.uid) }
                item(key = "season$season") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.panel).border(1.dp, Pal.panelHigh, RoundedCornerShape(12.dp))
                        .combinedClickable { expanded[season] = !open }.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Saison $season", color = Pal.text, fontWeight = FontWeight.Bold)
                        Text("${list.size} ép.", color = Pal.dim, fontSize = 13.sp)
                        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Pal.dim)
                        Spacer(Modifier.weight(1f))
                        if (allWatched) Chip("REGARDÉ", green, green.copy(alpha = 0.15f))
                        else Pill("Marquer comme vu", { state.markWatched(list.map { e -> item(eps.indexOf(e), true) }, list.map { it.durationSecs * 1000L }, true) })
                        IconButton({ list.forEach { e -> state.download(item(eps.indexOf(e), true)) } }) { Icon(Icons.Filled.Download, "Télécharger la saison", tint = Pal.dim) }
                    }
                }
                if (open) items(list, key = { it.uid }) { e ->
                    val i = eps.indexOf(e)
                    EpisodeRow(state, e, highlighted = i == target, onPlay = { PlayerWindow.open(state, item(i)) }, download = { item(i, true) },
                        onToggleWatched = { w -> state.markWatched(listOf(item(i, true)), listOf(e.durationSecs * 1000L), w) })
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun EpisodeRow(state: AppState, e: Ep, highlighted: Boolean, onPlay: () -> Unit, download: () -> PlayItem, onToggleWatched: (Boolean) -> Unit) {
    val h = state.progress(Kind.EPISODE, e.uid)
    val watched = state.isWatched(Kind.EPISODE, e.uid)
    val pct = h?.takeIf { it.durationMs > 0 && !watched && it.positionMs > 30_000 }?.let { (it.positionMs * 100 / it.durationMs).toInt() }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp).clip(RoundedCornerShape(12.dp))
        .background(if (highlighted) Pal.accent.copy(alpha = 0.14f) else Pal.panel)
        .border(1.dp, if (highlighted) Pal.accent.copy(alpha = 0.6f) else Pal.panelHigh, RoundedCornerShape(12.dp))
        .combinedClickable(onDoubleClick = onPlay, onClick = onPlay).padding(12.dp).heightIn(min = 84.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.width(150.dp)) {
            AsyncImage(e.image, null, Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Pal.panelHigh), contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
            if (pct != null) LinearProgressIndicator({ pct / 100f }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), color = Pal.accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("E${e.episode}  ${e.title}", color = Pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Saison ${e.season}" + if (e.durationSecs > 0) " · ${e.durationSecs / 60} min" else "", color = Pal.dim, fontSize = 12.sp)
            e.plot?.takeIf { it.isNotBlank() }?.let { Text(it, color = Pal.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                watched -> Chip("REGARDÉ", green, green.copy(alpha = 0.15f), Icons.Filled.CheckCircle, green)
                pct != null -> Chip("REPRENDRE $pct % VU", Color(0xFF9CC2FF), Pal.accent.copy(alpha = 0.18f))
            }
            e.rating?.let { Chip(it, icon = Icons.Filled.Star) }
        }
        Pill(if (watched) "Marquer non vu" else "Marquer comme vu", { onToggleWatched(!watched) }, Icons.Filled.Done)
        Pill("Lire", onPlay, Icons.Filled.PlayArrow, primary = true)
        DownloadButton(state, e.uid, download)
    }
}

// ---- Downloads screen -----------------------------------------------------------------------------------------

@Composable
fun DownloadsScreen(state: AppState) {
    val d = state.downloads
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Téléchargés", style = MaterialTheme.typography.headlineSmall, color = Pal.text, modifier = Modifier.weight(1f))
            Pill("Ouvrir le dossier", { runCatching { java.awt.Desktop.getDesktop().open(d.folder) } })
        }
        Text("Un téléchargement à la fois : il occupe l'écran de votre abonnement, comme une lecture. Dossier : ${d.folder.path}",
            color = Pal.dim, modifier = Modifier.padding(vertical = 8.dp))
        if (d.tasks.isEmpty()) { Empty("Aucun téléchargement", "Le bouton ⬇ des films, épisodes et saisons les ajoute ici."); return@Column }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(d.tasks.toList(), key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.panel).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsyncImage(t.image, null, Modifier.size(56.dp, 80.dp).clip(RoundedCornerShape(6.dp)).background(Pal.panelHigh), contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t.title, color = Pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        t.subtitle?.let { Text(it, color = Pal.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        val pct = if (t.total > 0) t.done.toFloat() / t.total else 0f
                        if (t.status != DlStatus.DONE) LinearProgressIndicator({ pct }, Modifier.fillMaxWidth(0.7f).height(4.dp).clip(RoundedCornerShape(2.dp)), color = Pal.accent, trackColor = Pal.panelHigh)
                        Text(when (t.status) {
                            DlStatus.DONE -> "Terminé · ${Downloads.size(t.total)}"
                            DlStatus.RUNNING -> "${(pct * 100).toInt()} % · ${Downloads.size(t.done)} / ${Downloads.size(t.total)} · ${Downloads.size(t.speed)}/s"
                            DlStatus.QUEUED -> "En attente"
                            DlStatus.PAUSED -> "En pause · ${(pct * 100).toInt()} %"
                            else -> "Échec : ${t.error ?: "erreur inconnue"}"
                        }, color = if (t.status == DlStatus.FAILED) Pal.danger else Pal.dim, fontSize = 12.sp)
                    }
                    if (t.status == DlStatus.DONE) Pill("Lire", { state.playFile(t) }, Icons.Filled.PlayArrow, primary = true)
                    if (t.status == DlStatus.RUNNING || t.status == DlStatus.QUEUED) Pill("Pause", { d.pause(t.id) })
                    if (t.status == DlStatus.PAUSED || t.status == DlStatus.FAILED) Pill("Reprendre", { d.resume(t.id) })
                    Pill("Supprimer", { d.remove(t.id) })
                }
            }
        }
    }
}
