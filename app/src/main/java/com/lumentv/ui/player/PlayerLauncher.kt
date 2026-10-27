package com.lumentv.ui.player

import android.content.Context
import android.content.Intent
import com.lumentv.data.db.ProgramEntity

/** Builds the intents that open [PlayerActivity]. Only ids travel in the intent, never URLs or credentials. */
object PlayerLauncher {
    const val KIND = "kind"
    const val CHANNEL_ID = "channel_id"
    const val GROUP = "group"
    const val PLAYLIST_ID = "playlist_id"
    const val UID = "uid"
    const val SERIES_UID = "series_uid"
    const val START = "start"
    const val STOP = "stop"
    const val TITLE = "title"
    const val RESUME = "resume"
    const val TRANSFER_ID = "transfer_id"
    const val CHANNEL_IDS = "channel_ids"

    /** A recording or a download, from its transfer row. */
    fun file(context: Context, transferId: Long) = context.startActivity(
        Intent(context, PlayerActivity::class.java).putExtra(KIND, "file").putExtra(TRANSFER_ID, transferId)
    )

    fun multiView(context: Context, channelIds: LongArray) = context.startActivity(
        Intent(context, MultiViewActivity::class.java).putExtra(CHANNEL_IDS, channelIds)
    )

    fun live(context: Context, channelId: Long, group: String?) = context.startActivity(
        Intent(context, PlayerActivity::class.java).putExtra(KIND, "live").putExtra(CHANNEL_ID, channelId).putExtra(GROUP, group)
    )

    fun catchup(context: Context, channelId: Long, program: ProgramEntity) = context.startActivity(
        Intent(context, PlayerActivity::class.java).putExtra(KIND, "catchup").putExtra(CHANNEL_ID, channelId)
            .putExtra(START, program.start).putExtra(STOP, program.stop).putExtra(TITLE, program.title)
    )

    fun movie(context: Context, playlistId: Long, uid: String, resume: Boolean) = context.startActivity(
        Intent(context, PlayerActivity::class.java).putExtra(KIND, "movie").putExtra(PLAYLIST_ID, playlistId)
            .putExtra(UID, uid).putExtra(RESUME, resume)
    )

    fun episode(context: Context, playlistId: Long, seriesUid: String, episodeUid: String, resume: Boolean) = context.startActivity(
        Intent(context, PlayerActivity::class.java).putExtra(KIND, "episode").putExtra(PLAYLIST_ID, playlistId)
            .putExtra(SERIES_UID, seriesUid).putExtra(UID, episodeUid).putExtra(RESUME, resume)
    )
}
