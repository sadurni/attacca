package com.sadurni.attacca.analysis

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplacingShuffleOrderTest {

    private fun ShuffleOrder.playOrder(): List<Int> {
        val result = ArrayList<Int>()
        var index = firstIndex
        while (index != C.INDEX_UNSET) {
            result.add(index)
            index = getNextIndex(index)
        }
        return result
    }

    private val shuffled = intArrayOf(3, 0, 4, 1, 2)

    @Test
    fun replacingAnItemKeepsThePlayOrder() {
        for (replaced in 0..4) {
            // The copy is added before the item, which is then removed.
            val order = ReplacingShuffleOrder(shuffled, replaced)
                .cloneAndInsert(replaced, 1)
                .cloneAndRemove(replaced + 1, replaced + 2)
            assertEquals("replacing $replaced", shuffled.toList(), order.playOrder())
        }
    }

    @Test
    fun replacingWithTheCopyAfterKeepsThePlayOrder() {
        for (replaced in 0..4) {
            // The copy is added after the item (the playing one), which is then removed.
            val order = ReplacingShuffleOrder(shuffled, replaced)
                .cloneAndInsert(replaced + 1, 1)
                .cloneAndRemove(replaced, replaced + 1)
            assertEquals("replacing $replaced", shuffled.toList(), order.playOrder())
        }
    }

    @Test
    fun theCopySitsWhereTheItemIs() {
        val inserted = ReplacingShuffleOrder(shuffled, 4).cloneAndInsert(4, 1)
        // 3 0 [4 new] 5(old 4) 1 2
        assertEquals(listOf(3, 0, 4, 5, 1, 2), inserted.playOrder())
    }
}
