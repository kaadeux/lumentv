package com.lumentv.data.repo

import com.lumentv.core.normalize
import com.lumentv.data.db.ChannelEntity
import com.lumentv.data.db.EpisodeEntity
import com.lumentv.data.db.MovieEntity
import com.lumentv.data.db.SeriesEntity
import com.lumentv.data.m3u.EntryKind
import com.lumentv.data.m3u.M3uClassifier
import com.lumentv.data.m3u.M3uParser
import com.lumentv.data.xtream.XLive
import com.lumentv.data.xtream.XMovie
import com.lumentv.data.xtream.XSeries
import java.io.BufferedReader

const val NO_GROUP = "Sans catégorie"

class ImportResult(
    val channels: List<ChannelEntity>,
    val movies: List<MovieEntity>,
    val series: List<SeriesEntity>,
    val episodes: List<EpisodeEntity>,
    val epgUrl: String?,
    val duplicatesSkipped: Int,
)

/** Hands out unique uids: the second "TF1" in a list becomes "m:tf1|#2". */
private class UidSet {
    private val seen = HashMap<String, Int>()
    fun unique(base: String): String {
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        return if (n == 1) base else "$base#$n"
    }
}

/** Builds database rows from an M3U playlist. Pure: no database, no network, so it is unit tested. */
object M3uImport {
    fun build(playlistId: Long, reader: BufferedReader, dedupe: Boolean): ImportResult {
        val channels = ArrayList<ChannelEntity>()
        val movies = ArrayList<MovieEntity>()
        val seriesByUid = LinkedHashMap<String, SeriesEntity>()
        val episodes = ArrayList<EpisodeEntity>()
        val chUids = UidSet()
        val mvUids = UidSet()
        val epUids = UidSet()
        val seenUrls = HashSet<String>()
        var epgUrl: String? = null
        var dupes = 0

        M3uParser.parse(reader, onHeader = { h -> if (epgUrl == null) epgUrl = h.epgUrl }) { e ->
            if (dedupe && !seenUrls.add(e.url)) { dupes++; return@parse }
            val group = e.group.ifBlank { NO_GROUP }
            val c = M3uClassifier.classify(e)
            val norm = normalize(e.name)
            when (c.kind) {
                EntryKind.LIVE -> channels += ChannelEntity(
                    playlistId = playlistId,
                    uid = chUids.unique("m:" + norm + "|" + (e.tvgId?.lowercase() ?: "")),
                    name = e.name, nameNorm = norm, groupName = group, logo = e.logo, url = e.url,
                    epgId = e.tvgId, number = channels.size + 1, userAgent = e.userAgent, referer = e.referer,
                    catchupDays = e.catchupDays, catchupSource = e.catchupSource,
                )
                EntryKind.MOVIE -> movies += MovieEntity(
                    playlistId = playlistId, uid = mvUids.unique("m:$norm"),
                    name = e.name, nameNorm = norm, category = group, poster = e.logo, url = e.url,
                    ext = e.url.substringBefore('?').substringAfterLast('.', "").takeIf { it.length in 2..4 },
                    year = Regex("\\((19|20)\\d{2}\\)").find(e.name)?.value?.trim('(', ')'),
                    number = movies.size + 1,
                )
                EntryKind.EPISODE -> {
                    val seriesName = c.seriesName ?: e.name
                    val sUid = "m:" + normalize(seriesName)
                    seriesByUid.getOrPut(sUid) {
                        SeriesEntity(playlistId = playlistId, uid = sUid, name = seriesName, nameNorm = normalize(seriesName),
                            category = group, cover = e.logo, number = seriesByUid.size + 1)
                    }
                    episodes += EpisodeEntity(
                        playlistId = playlistId, seriesUid = sUid,
                        uid = epUids.unique("$sUid:s${c.season}e${c.episode}"),
                        title = e.name, nameNorm = norm, season = c.season, episode = c.episode,
                        url = e.url, image = e.logo,
                        ext = e.url.substringBefore('?').substringAfterLast('.', "").takeIf { it.length in 2..4 },
                    )
                }
            }
        }
        return ImportResult(channels, movies, seriesByUid.values.toList(), episodes, epgUrl, dupes)
    }
}

/** Builds database rows from Xtream Codes lists. */
object XtreamImport {
    fun channels(playlistId: Long, items: List<XLive>, categories: Map<String, String>): List<ChannelEntity> =
        items.mapIndexed { i, x ->
            ChannelEntity(
                playlistId = playlistId, uid = "x:${x.streamId}", name = x.name, nameNorm = normalize(x.name),
                groupName = categories[x.categoryId]?.takeIf { it.isNotBlank() } ?: NO_GROUP,
                logo = x.icon?.takeIf { it.isNotBlank() }, streamId = x.streamId,
                epgId = x.epgId?.takeIf { it.isNotBlank() }, number = i + 1, catchupDays = x.archiveDays,
            )
        }

    fun movies(playlistId: Long, items: List<XMovie>, categories: Map<String, String>): List<MovieEntity> =
        items.mapIndexed { i, x ->
            MovieEntity(
                playlistId = playlistId, uid = "x:${x.streamId}", name = x.name, nameNorm = normalize(x.name),
                category = categories[x.categoryId]?.takeIf { it.isNotBlank() } ?: NO_GROUP,
                poster = x.icon?.takeIf { it.isNotBlank() }, streamId = x.streamId, ext = x.ext,
                rating = x.rating, year = x.year, addedAt = x.added, number = i + 1,
            )
        }

    fun series(playlistId: Long, items: List<XSeries>, categories: Map<String, String>): List<SeriesEntity> =
        items.mapIndexed { i, x ->
            SeriesEntity(
                playlistId = playlistId, uid = "x:${x.seriesId}", seriesId = x.seriesId, name = x.name,
                nameNorm = normalize(x.name), category = categories[x.categoryId]?.takeIf { it.isNotBlank() } ?: NO_GROUP,
                cover = x.cover?.takeIf { it.isNotBlank() }, plot = x.plot, rating = x.rating, year = x.year, number = i + 1, tmdb = x.tmdb, updatedAt = x.updated,
            )
        }
}
