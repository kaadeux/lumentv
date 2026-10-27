@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.data.db.TransferEntity
import com.lumentv.data.db.TransferStatus
import com.lumentv.ui.MainViewModel
import com.lumentv.ui.components.EmptyState
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.MenuOption
import com.lumentv.ui.components.OptionsDialog
import com.lumentv.ui.components.ProgressLine
import com.lumentv.ui.components.ScreenTitle
import com.lumentv.ui.player.PlayerLauncher
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.launch
import java.io.File

/** Downloaded movies and episodes: OK plays a finished one, otherwise opens its options. */
@Composable
fun DownloadsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads by vm.graph.transfers.observe().collectAsStateWithLifecycle(emptyList())
    var menu by remember { mutableStateOf<TransferEntity?>(null) }
    val free = remember { vm.graph.transfers.folder().freeSpace }

    if (downloads.isEmpty()) {
        return EmptyState("Aucun téléchargement", "Sur la fiche d'un film, choisissez « ⬇ Télécharger ». Pour une série, utilisez le bouton ⬇ " +
            "à droite de chaque épisode. Les fichiers se regardent ensuite sans connexion.")
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { ScreenTitle("Téléchargements") { Text("Espace libre : ${size(free)}", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim) } }
        items(downloads, key = { it.id }) { t ->
            FocusSurface({ if (t.status == TransferStatus.DONE) PlayerLauncher.file(context, t.id) else menu = t }, Modifier.fillMaxWidth(),
                onLongClick = { menu = t }, focusedScale = 1.01f) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (t.status) {
                        TransferStatus.RUNNING, TransferStatus.SCHEDULED -> "⬇"; TransferStatus.DONE -> "▶"; TransferStatus.FAILED -> "⚠"; else -> "❚❚"
                    }, modifier = Modifier.width(36.dp), style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = when (t.status) {
                            TransferStatus.RUNNING -> if (t.totalBytes > 0) "${t.bytes * 100 / t.totalBytes} % · ${size(t.bytes)} sur ${size(t.totalBytes)}" else "En cours · ${size(t.bytes)}"
                            TransferStatus.SCHEDULED -> "En attente"
                            TransferStatus.DONE -> "Prêt · ${size(t.bytes)}"
                            TransferStatus.FAILED -> "Échec : ${t.error ?: "erreur inconnue"}"
                            else -> "En pause · ${size(t.bytes)} (OK pour reprendre)"
                        }
                        Text(listOfNotNull(t.subtitle, status).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = if (t.status == TransferStatus.FAILED) Palette.Danger else androidx.compose.ui.graphics.Color.Unspecified)
                        if (t.status == TransferStatus.RUNNING && t.totalBytes > 0)
                            ProgressLine(t.bytes.toFloat() / t.totalBytes, Modifier.fillMaxWidth(0.6f).padding(top = 4.dp))
                    }
                }
            }
        }
        item {
            Text("OK : regarder · appui long : options · dossier : ${vm.graph.transfers.folder().absolutePath}",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
        }
    }

    menu?.let { t ->
        OptionsDialog(t.title, buildList {
            if (t.status == TransferStatus.DONE) add(MenuOption("Regarder") { PlayerLauncher.file(context, t.id) })
            if (t.status == TransferStatus.RUNNING || t.status == TransferStatus.SCHEDULED) add(MenuOption("Mettre en pause") { scope.launch { vm.graph.transfers.cancel(t.id) } })
            if (t.status == TransferStatus.FAILED || t.status == TransferStatus.CANCELLED) add(MenuOption("Reprendre le téléchargement") { scope.launch { vm.graph.transfers.retry(t.id) } })
            add(MenuOption("Supprimer" + if (t.filePath?.let { File(it).exists() } == true) " (et le fichier)" else "") { scope.launch { vm.graph.transfers.delete(t.id) } })
        }, onDismiss = { menu = null }, subtitle = t.error ?: t.subtitle)
    }
}

fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f Go".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes shr 20} Mo"
    else -> "${bytes shr 10} Ko"
}
