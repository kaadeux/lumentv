package com.lumentv.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lumentv.AppCore
import com.lumentv.core.NetReport
import com.lumentv.core.NetworkCheck
import com.lumentv.core.networkCheck
import com.lumentv.ui.kit.NetLine
import com.lumentv.ui.kit.NetworkReportDialog

/** "État du réseau": internet and the provider's server, timed, then the likely cause (shared NetworkCheck). */
@Composable
internal fun NetworkDialog(core: AppCore, onDismiss: () -> Unit) {
    var run by remember { mutableStateOf(0) }
    val report by produceState<NetReport?>(null, run) { value = null; value = core.networkCheck() }
    NetworkReportDialog(report?.checks?.map { NetLine(it.what, it.result, it.level) }, report?.let { it.diagnosis ?: NetworkCheck.ALL_GOOD }, { run++ }, onDismiss)
}
