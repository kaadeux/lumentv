package com.lumentv.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumentv.AppGraph
import com.lumentv.data.db.PlaylistEntity
import com.lumentv.data.settings.Settings
import com.lumentv.work.RefreshWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Live : Screen
    data object Guide : Screen
    data object Favorites : Screen
    data object Movies : Screen
    data object Series : Screen
    data object Search : Screen
    data object History : Screen
    data object Settings : Screen
    data object Sports : Screen
    data object Recordings : Screen
    /** Phone only: the destinations that are not bottom tabs. */
    data object More : Screen
    data class MovieDetail(val playlistId: Long, val uid: String) : Screen
    data class SeriesDetail(val playlistId: Long, val uid: String) : Screen
    data class PlaylistEdit(val id: Long, val type: String) : Screen
    data class EpgFix(val channelId: Long) : Screen
}

class MainViewModel(val graph: AppGraph) : ViewModel() {
    val stack = mutableStateListOf<Screen>(Screen.Live)
    val current get() = stack.last()
    val root get() = stack.first()

    val settings: StateFlow<Settings> = graph.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val playlists: StateFlow<List<PlaylistEntity>?> = graph.playlists.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The playlist the Live / Guide / Movies / Series screens show. */
    val active: StateFlow<PlaylistEntity?> = combine(playlists, settings) { list, s ->
        list?.firstOrNull { it.id == s.activePlaylistId } ?: list?.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * What was watched and the personal ratings, kept while the app is open: the Films and Séries homes have them at
     * once on return from a film page. Starting from an empty list each time made the rows be computed again without
     * the viewer's taste (other rows), and the poster the viewer came back to went away with its focus.
     */
    val vodHistory: StateFlow<List<com.lumentv.data.db.HistoryEntity>> = graph.library.observeHistory(600)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(300_000), emptyList())
    val ratings: StateFlow<List<com.lumentv.data.db.RatingEntity>> = graph.library.observeRatings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(300_000), emptyList())

    val busy = combine(graph.playlists.busy, graph.epg.busy) { a, b -> a + b }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    /** Groups unlocked with the PIN during this session. */
    val unlocked = mutableStateOf(setOf<String>())
    /** Locked groups of the active playlist, to ask for the PIN before playing from "All channels". */
    var lockedCache: Set<String> = emptySet()
    /** Last group opened in Live, kept while switching screens. */
    var liveGroup: String? = null
    /** Channel the guide should scroll to when opened from a channel's menu. */
    var guideChannel: Long = 0
    var lastSearch: String = ""
    var searchTab: com.lumentv.ui.screens.SearchTab = com.lumentv.ui.screens.SearchTab.ALL
    var movieCategory: String? = null
    var seriesCategory: String? = null

    fun show(message: String) { _toast.value = message }
    fun clearToast() { _toast.value = null }

    fun open(s: Screen) { stack.add(s) }
    fun openRoot(s: Screen) { returnKey = null; stack.clear(); stack.add(s) }

    /** Films and Séries chosen in the tabs: opened fresh, at the top of their home (Back from a page keeps the place). */
    val freshScreens = HashSet<Screen>()

    /** A tab chosen. */
    fun openTab(s: Screen) {
        if (s != root && (s == Screen.Movies || s == Screen.Series)) {
            freshScreens += s
            if (s == Screen.Movies) movieCategory = null else seriesCategory = null
            memo.keys.removeAll { it.startsWith(if (s == Screen.Movies) "hero:m:" else "hero:s:") }
        }
        openRoot(s)
    }

    /** The poster / line a sub-screen was opened from: it gets the focus back when Back returns to the list. */
    var returnKey: String? = null
    fun openFrom(key: String, s: Screen) { returnKey = key; open(s) }

    /** Rows computed for a screen (featured, resume), shown at once on return so the list keeps its place. */
    val memo = HashMap<String, Any>()

    /** Last search and its results, shown at once on return instead of an empty list. */
    var lastResults: Pair<String, com.lumentv.data.repo.SearchResults>? = null
    /** Set when Back closes a sub-screen, so the screen underneath gets the focus again (not the menu). */
    var focusContent = false

    /** A screen's own Back first (a category of Films back to its home…): true when it used it. */
    var backHook: (() -> Boolean)? = null

    fun back(): Boolean {
        if (backHook?.invoke() == true) return true
        if (stack.size > 1) { stack.removeAt(stack.lastIndex); focusContent = true; return true }
        if (root != Screen.Live) { openRoot(Screen.Live); return true }
        return false
    }

    fun setActive(id: Long) = viewModelScope.launch { graph.settings.set(com.lumentv.data.settings.Keys.activePlaylist, id) }

    /** Refreshes a playlist, then its guide; reports both on screen. */
    fun refresh(id: Long, withEpg: Boolean = true) = viewModelScope.launch {
        show("Actualisation en cours…")
        val r = graph.playlists.refresh(id)
        show(if (r.ok) "Liste actualisée : ${r.message}" else r.message)
        if (r.ok && withEpg && graph.epg.epgUrl(graph.playlists.get(id) ?: return@launch) != null) {
            val e = graph.epg.refresh(id)
            show(if (e.ok) "Guide TV : ${e.message}" else "Guide TV : ${e.message}")
        }
        syncWorker()
    }

    fun refreshEpg(id: Long) = viewModelScope.launch {
        show("Téléchargement du guide TV…")
        val e = graph.epg.refresh(id)
        show(if (e.ok) "Guide TV : ${e.message}" else "Guide TV : ${e.message}")
    }

    fun syncWorker() = viewModelScope.launch {
        RefreshWorker.sync(graph.appContext, graph.playlists.all().any { it.refreshHours > 0 })
    }

    class Factory(private val graph: AppGraph) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(graph) as T
    }
}
