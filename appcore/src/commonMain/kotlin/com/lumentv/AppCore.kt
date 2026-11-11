package com.lumentv

import com.lumentv.core.Http
import com.lumentv.core.HttpBody
import com.lumentv.core.KeyValues
import com.lumentv.core.TextFile
import com.lumentv.core.XmlReader
import com.lumentv.data.IntroDb
import com.lumentv.data.TitleLogos
import com.lumentv.data.Tmdb
import com.lumentv.data.db.AppDatabase
import com.lumentv.data.repo.Artwork
import com.lumentv.data.repo.Discover
import com.lumentv.data.repo.EpgRepository
import com.lumentv.data.repo.LibraryIndex
import com.lumentv.data.repo.LibraryRepository
import com.lumentv.data.repo.PlaylistRepository
import com.lumentv.data.repo.StreamResolver
import com.lumentv.data.settings.SettingsStore
import com.lumentv.data.sports.FixtureClient
import com.lumentv.data.sports.FrenchTvGuide
import com.lumentv.data.sports.SportsRepository
import com.lumentv.data.xtream.Presence
import com.lumentv.sync.ProfileManager
import kotlinx.coroutines.CoroutineScope

/** What each platform gives the shared app: its network clients, files and identity. */
class Platform(
    /** The main client (playlists, guides, Xtream, sync). */
    val http: Http,
    /** TMDB and its logos: with a disk cache where the platform has one. */
    val tmdbHttp: Http,
    /** The sports calendar: retries a "429" where the platform can. */
    val fixtureHttp: Http,
    /** XMLTV guides, in the encoding the file declares. */
    val xmlReader: (HttpBody) -> XmlReader,
    /** The French sports guide (always UTF-8). */
    val utf8XmlReader: (HttpBody) -> XmlReader,
    /** Folder for caches (may be emptied by the system) and for the app's own files. */
    val cacheDir: String,
    val filesDir: String,
    /** Where the online profile keeps its keys and last sync time. */
    val profilePrefs: KeyValues,
    val deviceId: String,
    val deviceName: String,
    val tmdbKey: String,
    val syncUrl: String,
)

/**
 * The app without its screens, the same on the TV, the phone and the iPhone: the database, the settings and every
 * repository, built once for the whole app. Android's AppGraph and the iPhone's entry point both create one.
 */
class AppCore(val db: AppDatabase, val settings: SettingsStore, val platform: Platform, val scope: CoroutineScope) {
    val playlists = PlaylistRepository(db, platform.http) { settings.current().defaultUserAgent }
    val epg = EpgRepository(db, playlists, { settings.current().epgDays }, platform.xmlReader)
    val library = LibraryRepository(db)
    val resolver = StreamResolver(playlists)
    val artwork = Artwork(playlists)
    val tmdb = Tmdb(platform.tmdbHttp, platform.tmdbKey)
    /** Logos and ratings through the same client, so they share TMDB's cache. */
    val logos = TitleLogos(platform.tmdbHttp, platform.tmdbKey)
    /** The whole library, light, in memory: search and TMDB matching. */
    val index = LibraryIndex(db)
    val discover = Discover(db, tmdb, index, playlists)
    /** The history of a list removed: moved to another one, or kept aside until a list has those titles. */
    val historyTransfer = com.lumentv.data.repo.HistoryTransfer(db, playlists, index)
    /** What the history kept aside became when a new list was loaded, for the screens to tell. */
    val historyNotice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    init {
        playlists.onFirstLoad = { id -> historyTransfer.adopt(id)?.let { historyNotice.value = it } }
        playlists.onRefreshed = { id -> index.of(id) }
    }
    /** Which French channel shows each game, days ahead (xmltvfr.fr, sports channels only, twice a day at most). */
    val frenchGuide = FrenchTvGuide(platform.http, TextFile(platform.cacheDir + "/sports_tv.json"), platform.utf8XmlReader)
    val sports = SportsRepository(db, settings, epg, library, FixtureClient(platform.fixtureHttp), frenchGuide)
    /** Tells the other Lumen TV devices of the same subscription that this one is playing. */
    val presence = Presence(platform.http, platform.syncUrl, platform.deviceId, platform.deviceName)
    val introDb = IntroDb(platform.http)
    val profile = ProfileManager(
        platform.profilePrefs, TextFile(platform.filesDir + "/profile_synced.bin"), platform.syncUrl,
        db, settings, playlists, epg, library, platform.http, scope,
    )
}
