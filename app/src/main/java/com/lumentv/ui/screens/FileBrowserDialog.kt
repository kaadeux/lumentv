@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui.screens

import com.lumentv.ui.components.panelWidth

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.ui.components.FocusSurface
import com.lumentv.ui.components.ignoreHeldOk
import com.lumentv.ui.theme.Palette
import java.io.File

/**
 * A file picker that works on TVs without a system document picker. Lists the app's own folder
 * (Android/data/com.lumentv/files, reachable with `adb push`), USB drives and, on Android 6-12, Download.
 */
@Composable
fun FileBrowserDialog(title: String, extensions: Set<String>, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var dir by remember { mutableStateOf<File?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val roots = remember(refresh) {
        buildList {
            context.getExternalFilesDirs(null).filterNotNull().forEachIndexed { i, f ->
                f.mkdirs(); add((if (i == 0) "Dossier de l'application" else "Stockage USB ${i}") to f)
            }
            File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "self" && it.name != "emulated" }
                ?.forEach { add("Support amovible ${it.name}" to it) }
            @Suppress("DEPRECATION")
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dl.canRead()) add("Téléchargements" to dl)
        }
    }

    Dialog(onDismissRequest = { if (dir == null) onDismiss() else dir = dir!!.parentFile?.takeIf { p -> roots.none { it.second == dir } } }) {
        Column(Modifier.ignoreHeldOk().panelWidth(620.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Text(dir?.absolutePath ?: "Choisissez un emplacement", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
            Column(Modifier.padding(vertical = 10.dp)) {
                val current = dir
                val entries: List<Pair<String, () -> Unit>> = if (current == null) {
                    roots.map { (label, f) -> label to { dir = f } }
                } else {
                    val files = current.listFiles().orEmpty()
                        .filter { it.isDirectory || it.extension.lowercase() in extensions || it.name.lowercase().endsWith(".xml.gz") }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    listOf<Pair<String, () -> Unit>>("⬑  Dossier parent" to {
                        dir = if (roots.any { it.second == current }) null else current.parentFile
                    }) + files.map { f -> (if (f.isDirectory) "📁  ${f.name}" else "📄  ${f.name}  (${f.length() / 1024} Ko)") to { if (f.isDirectory) dir = f else onPick(f) } }
                }
                if (current != null && entries.size == 1) {
                    Text("Aucun fichier compatible ici (${extensions.joinToString(", ") { ".$it" }}).", color = Palette.TextDim,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
                }
                val firstRow = remember(current) { FocusRequester() }
                LaunchedEffect(current) { delay(60); runCatching { firstRow.requestFocus() } }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(380.dp)) {
                    itemsIndexed(entries, key = { _, e -> e.first }) { i, (label, action) ->
                        // The first file, if any, gets the focus; otherwise the first row.
                        val focusHere = i == (if (current != null && entries.size > 1) 1 else 0)
                        FocusSurface(action, Modifier.fillMaxWidth().then(if (focusHere) Modifier.focusRequester(firstRow) else Modifier),
                            container = Palette.PanelHigh, focusedScale = 1.01f) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                }
            }
            Text("Astuce : depuis un PC, « adb push liste.m3u /sdcard/Android/data/com.lumentv/files/ » place un fichier dans le dossier de l'application.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
    }
}
