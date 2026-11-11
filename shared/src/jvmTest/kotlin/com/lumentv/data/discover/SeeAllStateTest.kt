package com.lumentv.data.discover

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** "Tout voir": the row's titles, then pages after it without repeats, another sort from its first page, the end. */
class SeeAllStateTest {
    private fun seeAll(asked: MutableList<Pair<Int, Int>>) = SeeAll(listOf("Populaires", "Plus récents"), listOf(1, 2, 3), firstFrom = 6) { sort, from ->
        asked += sort to from
        when {
            sort == 0 && from == 6 -> Batch(listOf(3, 4, 5), 10)
            sort == 0 -> Batch(listOf(6), null)
            else -> Batch(listOf(9, 8), null)
        }
    }

    @Test fun pagesSortsAndEnd() {
        val asked = ArrayList<Pair<Int, Int>>()
        // Unconfined: each batch is there as soon as it is asked.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val s = SeeAllState(seeAll(asked), scope) { it }
        assertEquals(listOf(1, 2, 3), s.items.value)
        s.more()
        assertEquals(listOf(1, 2, 3, 4, 5), s.items.value)
        s.more()
        assertEquals(listOf(1, 2, 3, 4, 5, 6), s.items.value)
        assertFalse(s.hasMore)
        s.more()
        assertEquals(listOf(0 to 6, 0 to 10), asked)

        s.select(1)
        assertEquals(listOf(9, 8), s.items.value)
        assertEquals(1 to 1, asked.last())
        // Back to the row's order: its titles, and its next page asked at once.
        s.select(0)
        assertEquals(listOf(1, 2, 3, 4, 5), s.items.value)
    }
}
