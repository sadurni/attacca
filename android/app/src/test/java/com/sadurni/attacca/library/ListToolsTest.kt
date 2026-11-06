package com.sadurni.attacca.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ListToolsTest {

    private val id: (String) -> String = { it }

    @Test
    fun dedupeKeepsTheFirstPlace() {
        assertEquals(listOf("a", "b", "c"), ListTools.dedupe(listOf("a", "b", "a", "c", "b"), id))
    }

    @Test
    fun combinesWithAnotherList() {
        val list = listOf("a", "b", "c")
        val other = listOf("d", "b", "e", "d")
        assertEquals(listOf("a", "b", "c", "d", "e"), ListTools.add(list, other, id))
        assertEquals(listOf("b"), ListTools.keepShared(list, other, id))
        assertEquals(listOf("a", "c"), ListTools.removeOther(list, other, id))
    }

    @Test
    fun removesPlayedTracks() {
        assertEquals(listOf("a", "c"), ListTools.removePlayed(listOf("a", "b", "c"), setOf("b", "x"), id))
    }
}
