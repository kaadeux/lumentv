package com.lumentv.phone

import com.lumentv.core.dayLabel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lumentv.AppCore
import com.lumentv.core.Featured
import com.lumentv.data.db.HistoryEntity
import com.lumentv.data.db.HistoryKind
import com.lumentv.ui.kit.KitConfirm
import kotlinx.coroutines.launch

/**
 * The Android phone's Historique: films and series (one line per series, its last episode watched), newest first,
 * with the picture, what it is, when and how far; a film plays on from there, a series opens on its next episode.
 * Long press or ⋮ removes a line (and its resume position); "Effacer l'historique" empties it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryPage(core: AppCore, player: VideoPlayer, open: (Route) -> Unit) {
    val history by core.library.observeHistory(150).collectAsState(null)
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<HistoryEntity?>(null) }
    val list = remember(history) {
        history?.filter { it.kind == HistoryKind.MOVIE || it.kind == HistoryKind.EPISODE }
            ?.distinctBy { if (it.kind == HistoryKind.EPISODE) "s:${it.playlistId}:${it.parentUid ?: it.uid}" else it.key }
    }
    fun go(e: HistoryEntity) = scope.launch {
        when (e.kind) {
            HistoryKind.MOVIE -> core.library.movieByUid(e.playlistId, e.uid)?.let { playMovie(core, player, scope, it) }
                ?: run { Parental.notice = "Ce film n'est plus dans la liste." }
            else -> e.parentUid?.let { core.library.seriesByUid(e.playlistId, it) }?.let { open(Route.Series(it)) }
                ?: run { Parental.notice = "Cette série n'est plus dans la liste." }
        }
    }
    when {
        list == null -> Centered { CircularProgressIndicator() }
        list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Historique vide", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Les films et les séries que tu regardes apparaîtront ici.", color = Palette.TextDim, modifier = Modifier.padding(top = 6.dp))
        }
        else -> LazyColumn(state = rememberList("history"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "clear") { PhoneButton("Effacer l'historique", modifier = Modifier.fillMaxWidth()) { confirm = true } }
            items(list, key = { it.key }) { e ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Panel)
                    .combinedClickable(onClick = { go(e) }, onLongClick = { removing = e }).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 56.dp, height = 80.dp).clip(RoundedCornerShape(8.dp)).background(Palette.PanelHigh)) {
                        AsyncImage(e.image, e.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(Featured.shortTitle(e.title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(if (e.kind == HistoryKind.MOVIE) "Film" else "Série", e.subtitle).joinToString(" · "),
                            color = Palette.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(dayLabel(e.watchedAt) + " à " + hm(e.watchedAt), color = Palette.TextDim, fontSize = 12.sp)
                        if (e.durationMs > 0) ProgressLine((e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f), Modifier.fillMaxWidth(0.6f).padding(top = 4.dp))
                    }
                    Text("⋮", fontSize = 22.sp, color = Palette.TextDim, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { removing = e }.padding(10.dp))
                }
            }
        }
    }
    if (confirm) KitConfirm("Effacer l'historique ?", "Les positions de reprise des films et des épisodes seront aussi effacées.", "Effacer",
        onConfirm = { scope.launch { core.library.clearHistory() } }, onDismiss = { confirm = false }, danger = true)
    removing?.let { e ->
        KitConfirm("Retirer de l'historique ?", listOfNotNull(Featured.shortTitle(e.title), e.subtitle).joinToString(" · ") + "\nSa position de reprise sera aussi effacée.",
            "Retirer", onConfirm = { scope.launch { core.library.deleteHistory(e.key) } }, onDismiss = { removing = null }, danger = true)
    }
}
