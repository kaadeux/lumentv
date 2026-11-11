package com.lumentv.data.discover

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Titles of a "Tout voir" page, and where the next ones start ([next] null: there are no more). */
class Batch<T>(val items: List<T>, val next: Int?)

/**
 * What a row's "Tout voir" page shows: every title the row has ([all], in the row's order), then, when the row comes
 * from a TMDB list, more of it page after page ([load]), in one of the [sorts] ("Populaires", "Plus récents"…; empty:
 * the list has one order). Sort 0 is the row's own order: its pages go on from [firstFrom].
 */
class SeeAll<T>(
    val sorts: List<String>,
    val all: List<T>,
    val firstFrom: Int = 1,
    val load: (suspend (sort: Int, from: Int) -> Batch<T>)? = null,
) {
    /** The same, with each title turned into what the screens show (a database row…); titles it cannot turn are left out. */
    fun <R> map(f: suspend (List<T>) -> List<R>, all: List<R>): SeeAll<R> {
        val l = load ?: return SeeAll(sorts, all, firstFrom, null)
        return SeeAll(sorts, all, firstFrom) { s, from -> l(s, from).let { b -> Batch(f(b.items), b.next) } }
    }
}

/**
 * A "Tout voir" page open on screen: the titles so far, the sort picked, whether more are coming. [more] is called when
 * the end of the grid comes into view; the screens of the TV, the phones and the PC share it.
 */
class SeeAllState<T>(private val seeAll: SeeAll<T>, private val scope: CoroutineScope, private val key: (T) -> Any) {
    private val _items = MutableStateFlow(seeAll.all.distinctBy(key))
    val items: StateFlow<List<T>> = _items
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _sort = MutableStateFlow(0)
    val sort: StateFlow<Int> = _sort
    val sorts get() = seeAll.sorts

    private var next: Int? = if (seeAll.load != null) seeAll.firstFrom else null
    private var job: Job? = null

    /** False once every title is shown. */
    val hasMore get() = next != null

    fun more() {
        val from = next ?: return
        val load = seeAll.load ?: return
        if (job?.isActive == true) return
        val sort = _sort.value
        _loading.value = true
        job = scope.launch {
            val b = runCatching { load(sort, from) }.getOrNull()
            if (sort != _sort.value) return@launch
            // No answer (no network): stays where it was, asked again a moment later (not in a loop).
            if (b == null || (b.items.isEmpty() && b.next == from)) kotlinx.coroutines.delay(RETRY_MS)
            if (sort != _sort.value) return@launch
            if (b != null) {
                val seen = _items.value.mapTo(HashSet(), key)
                _items.value = _items.value + b.items.filter { seen.add(key(it)) }
                next = b.next
            }
            _loading.value = false
        }
    }

    companion object { const val RETRY_MS = 3_000L }

    fun select(sort: Int) {
        if (sort == _sort.value || sort !in seeAll.sorts.indices) return
        job?.cancel()
        _sort.value = sort
        _items.value = if (sort == 0) seeAll.all.distinctBy(key) else emptyList()
        next = if (sort == 0) seeAll.firstFrom else 1
        _loading.value = false
        more()
    }
}
