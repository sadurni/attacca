package com.sadurni.attacca.analysis

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder

/**
 * The shuffle order [shuffled] (list indices in play order), set just before an item is replaced
 * by adding its copy and removing it: the copy is placed right before the item at list index
 * [replaced], so once that one is removed the play order is the same as before. After the removal
 * it becomes an ordinary random order again.
 */
@OptIn(UnstableApi::class)
class ReplacingShuffleOrder(private val shuffled: IntArray, private val replaced: Int) : ShuffleOrder {

    private val positions = IntArray(shuffled.size).also { p -> shuffled.forEachIndexed { i, index -> p[index] = i } }

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int =
        positions[index].let { if (it + 1 < shuffled.size) shuffled[it + 1] else C.INDEX_UNSET }

    override fun getPreviousIndex(index: Int): Int =
        positions[index].let { if (it > 0) shuffled[it - 1] else C.INDEX_UNSET }

    override fun getLastIndex(): Int = shuffled.lastOrNull() ?: C.INDEX_UNSET

    override fun getFirstIndex(): Int = shuffled.firstOrNull() ?: C.INDEX_UNSET

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val result = ArrayList<Int>(shuffled.size + insertionCount)
        for (index in shuffled) {
            if (index == replaced) for (i in 0 until insertionCount) result.add(insertionIndex + i)
            result.add(if (index >= insertionIndex) index + insertionCount else index)
        }
        if (result.size == shuffled.size) for (i in 0 until insertionCount) result.add(insertionIndex + i)
        val replacedNow = if (replaced >= insertionIndex) replaced + insertionCount else replaced
        return ReplacingShuffleOrder(result.toIntArray(), replacedNow)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        val result = shuffled.filter { it < indexFrom || it >= indexToExclusive }
            .map { if (it >= indexToExclusive) it - count else it }
            .toIntArray()
        return DefaultShuffleOrder(result, System.nanoTime())
    }

    override fun cloneAndClear(): ShuffleOrder = DefaultShuffleOrder(0)
}
