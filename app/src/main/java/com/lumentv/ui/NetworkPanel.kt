@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.lumentv.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lumentv.AppGraph
import com.lumentv.player.PlayerController
import com.lumentv.ui.components.TvButton
import com.lumentv.ui.components.ignoreHeldOk
import com.lumentv.ui.components.panelWidth
import com.lumentv.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.lumentv.core.NetCheck
import com.lumentv.core.NetReport
import com.lumentv.core.NetworkCheck

/**
 * "État du réseau": the box's connection (Wi-Fi signal or cable), internet, the provider's server and, while a stream
 * plays ([c]), how it fares (speed measured, what the picture needs, buffer, stalls); then the likely cause in words.
 */
@Composable
fun NetworkPanel(graph: AppGraph, c: PlayerController?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var run by remember { mutableIntStateOf(0) }
    val report by produceState<NetReport?>(null, run) { value = null; value = check(context, graph, c) }
    // The stream's figures change all the time: read again every 2 s.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    Dialog(onDismissRequest = onDismiss) {
        val focus = remember { FocusRequester() }
        Column(Modifier.ignoreHeldOk().panelWidth(560.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Panel).padding(24.dp)) {
            Text("État du réseau", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Spacer(Modifier.height(12.dp))
            val r = report
            if (r == null) Text("Test en cours…", style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim)
            else {
                r.checks.forEach { line(it) }
                if (c != null) { tick.let { streamLines(context, c).forEach { line(it) } } }
                Spacer(Modifier.height(12.dp))
                Text(r.diagnosis ?: NetworkCheck.ALL_GOOD, style = MaterialTheme.typography.titleMedium, color = Palette.Text,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.PanelHigh).padding(14.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("Fermer", onDismiss, Modifier.focusRequester(focus), primary = true)
                TvButton("Relancer le test", { run++ })
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

@Composable
private fun line(ch: NetCheck) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(when (ch.level) { 0 -> "●"; 1 -> "●"; else -> "●" }, color = when (ch.level) { 0 -> Palette.Good; 1 -> Palette.Gold; else -> Palette.Danger },
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(24.dp))
        Text(ch.what, style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim, modifier = Modifier.width(150.dp))
        Text(ch.result, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
    }
}

/** The device's link here (Wi-Fi signal or cable), then the shared check (internet, provider), then the stream. */
private suspend fun check(context: Context, graph: AppGraph, c: PlayerController?): NetReport = withContext(Dispatchers.IO) {
    var link: NetCheck
    var linkDiagnosis: String? = null
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    when {
        caps == null -> { link = NetCheck("Connexion", "aucune", 2); linkDiagnosis = "La box n'est pas connectée : vérifiez le Wi-Fi ou le câble réseau." }
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> link = NetCheck("Connexion", "câble réseau", 0)
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
            val rssi = if (android.os.Build.VERSION.SDK_INT >= 29) caps.signalStrength else Int.MIN_VALUE
            val (label, level) = when {
                rssi == Int.MIN_VALUE || rssi == 0 -> "Wi-Fi" to 0
                rssi >= -60 -> "Wi-Fi, bon signal ($rssi dBm)" to 0
                rssi >= -72 -> "Wi-Fi, signal moyen ($rssi dBm)" to 1
                else -> "Wi-Fi, signal faible ($rssi dBm)" to 2
            }
            link = NetCheck("Connexion", label, level)
            if (level == 2) linkDiagnosis = "Le Wi-Fi est faible : rapprochez la box internet, ou branchez un câble réseau."
        }
        else -> link = NetCheck("Connexion", "autre", 0)
    }
    val shared = NetworkCheck.run(graph.core, link, linkDiagnosis)
    var diagnosis = shared.diagnosis
    // The stream (figures read on the player's thread)
    if (diagnosis == null && c != null) {
        val (bitrate, speed) = withContext(Dispatchers.Main) { (c.player.videoFormat?.bitrate ?: 0) to c.bandwidth() }
        if (bitrate > 0 && speed in 1 until (bitrate * 1.3).toLong()) diagnosis = "Le débit est trop juste pour cette qualité : une version HD ou SD de la chaîne passera mieux."
        else if (c.state.value.stalls >= 3) diagnosis = "Réseau correct, mais le flux se bloque souvent : le serveur du fournisseur a du mal en ce moment."
    }
    NetReport(shared.checks, diagnosis ?: NetworkCheck.ALL_GOOD)
}

/** What the playing stream needs and gets, read live. */
private fun streamLines(context: Context, c: PlayerController): List<NetCheck> {
    val s = c.state.value
    val bitrate = c.player.videoFormat?.bitrate ?: 0
    val speed = c.bandwidth()
    val ahead = c.player.totalBufferedDuration / 1000
    fun mbps(b: Long) = "%.1f Mb/s".format(java.util.Locale.FRANCE, b / 1_000_000.0)
    return listOfNotNull(
        speed.takeIf { it > 0 }?.let { NetCheck("Débit mesuré", mbps(it), if (bitrate > 0 && it < bitrate * 1.3) 1 else 0) },
        bitrate.takeIf { it > 0 }?.let { NetCheck("Image", "demande ${mbps(it.toLong())}" + (s.videoInfo?.let { v -> " · $v" } ?: ""), 0) },
        NetCheck("Mémoire tampon", "$ahead s d'avance", when { ahead < 3 -> 2; ahead < 8 -> 1; else -> 0 }),
        NetCheck("Coupures", if (s.stalls == 0) "aucune" else "${s.stalls} (dernière : ${s.lastStallMs / 100 / 10.0} s)", when { s.stalls == 0 -> 0; s.stalls < 3 -> 1; else -> 2 }),
    )
}
