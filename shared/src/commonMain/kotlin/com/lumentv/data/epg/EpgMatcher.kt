package com.lumentv.data.epg

import com.lumentv.core.channelKeyName

/**
 * Finds the XMLTV channel of a playlist channel: the manual correction first, then the tvg-id / epg_channel_id
 * (case-insensitive), then the display name with quality tags removed ("TF1 HD" matches "TF1").
 */
class EpgMatcher(epgIds: Collection<String>, displayNames: Map<String, String>) {
    private val ids: Set<String> = epgIds.mapTo(HashSet()) { it.lowercase() }
    /** channelKeyName(display-name) -> epg key */
    private val byName: Map<String, String> = displayNames

    fun keyFor(epgOverride: String?, epgId: String?, channelName: String): String? {
        epgOverride?.lowercase()?.takeIf { it.isNotBlank() }?.let { return it }
        epgId?.lowercase()?.takeIf { it in ids }?.let { return it }
        return byName[channelKeyName(channelName)]
    }

    val isEmpty get() = ids.isEmpty()
}
