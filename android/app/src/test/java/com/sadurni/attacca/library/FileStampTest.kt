package com.sadurni.attacca.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileStampTest {

    private val t = 1_761_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun aClockChangeIsNotAnEdit() {
        assertTrue(FileStamp.sameTime(t, t))
        assertTrue(FileStamp.sameTime(t, t + hour))
        assertTrue(FileStamp.sameTime(t, t - hour))
        assertTrue(FileStamp.sameTime(t, t + hour / 2))
        assertTrue(FileStamp.sameTime(t, t + 2 * hour + 1_000))
        assertTrue(FileStamp.sameTime(t, t - 14 * hour))
    }

    @Test
    fun otherChangesAre() {
        assertFalse(FileStamp.sameTime(t, t + 5_000))
        assertFalse(FileStamp.sameTime(t, t + 5 * 60_000))
        assertFalse(FileStamp.sameTime(t, t + hour + 10 * 60_000))
        assertFalse(FileStamp.sameTime(t, t + 15 * hour))
    }
}
