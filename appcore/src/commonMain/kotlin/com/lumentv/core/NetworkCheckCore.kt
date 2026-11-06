package com.lumentv.core

import com.lumentv.AppCore
import com.lumentv.data.db.PlaylistType

/** The shared [NetworkCheck] on the active list of the apps built on [AppCore] (TV and phone, iPhone). */
suspend fun AppCore.networkCheck(link: NetCheck? = null, linkDiagnosis: String? = null): NetReport {
    val p = playlists.get(settings.current().activePlaylistId) ?: playlists.all().firstOrNull()
    val provider: (suspend () -> Unit)? = p?.takeIf { it.type == PlaylistType.XTREAM }?.let { list -> { playlists.xtream(list).authenticate() } }
    return NetworkCheck.run(platform.http, provider, link, linkDiagnosis)
}

/** Why a stream failed, in words: the same sentence on every device, under the player's error. */
suspend fun AppCore.explainFailure(): String = NetworkCheck.explainFailure(networkCheck())
