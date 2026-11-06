package com.sadurni.attacca.settings

import com.sadurni.attacca.library.ListSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {

    private val anonymised = "BLUETOOTH|Buds Pro|XX:XX:XX:XX:A1:B2"
    private val full = "BLUETOOTH|Buds Pro|4C:87:5D:11:A1:B2"

    @Test
    fun bluetoothMatchesByTheEndOfTheAddress() {
        assertTrue(DeviceIdentity.sameDevice(anonymised, full))
        assertTrue(DeviceIdentity.sameDevice("BLUETOOTH|Buds|4C:87:5D:11:A1:B2", full)) // renamed
        assertFalse(DeviceIdentity.sameDevice(full, "BLUETOOTH|Buds Pro|4C:87:5D:11:A1:B3"))
        assertTrue(DeviceIdentity.sameDevice("BLUETOOTH|Car|", "BLUETOOTH|car|")) // no address: by name
        assertFalse(DeviceIdentity.sameDevice(full, "USB|Buds Pro|4C:87:5D:11:A1:B2"))
        assertTrue(DeviceIdentity.matchesAddress(anonymised, "4c:87:5d:11:a1:b2"))
    }

    @Test
    fun usbMatchesByName() {
        assertTrue(DeviceIdentity.sameDevice("USB|DAC X|card=1;device=0", "USB|DAC X|card=2;device=0"))
        assertFalse(DeviceIdentity.sameDevice("USB|DAC X|card=1", "USB|DAC Y|card=1"))
    }

    @Test
    fun rememberingADuplicateKeepsTheConfiguredRuleUnderTheNewKey() {
        val configured = DeviceRule(anonymised, "Buds Pro", OutputType.BLUETOOTH, profileId = "phones", volumePercent = 40)
        val other = DeviceRule("BLUETOOTH|Car|00:11:22:33:44:55", "Car", OutputType.BLUETOOTH)
        val result = DeviceIdentity.remember(listOf(configured, other), OutputDevice(full, "Buds Pro", OutputType.BLUETOOTH))
        assertEquals(2, result.size)
        assertEquals(configured.copy(key = full), result[0])
        assertEquals(other, result[1])
    }

    @Test
    fun existingDuplicatesAreMerged() {
        val empty = DeviceRule(full, "Buds Pro", OutputType.BLUETOOTH)
        val configured = DeviceRule(anonymised, "Buds Pro", OutputType.BLUETOOTH, resumeList = ListSource.Folder("/m", true), autoPlay = true)
        assertEquals(listOf(configured), DeviceIdentity.dedupe(listOf(empty, configured)))
        // Both duplicates already there: remembering merges them too.
        val merged = DeviceIdentity.remember(listOf(empty, configured), OutputDevice(full, "Buds Pro", OutputType.BLUETOOTH))
        assertEquals(listOf(configured.copy(key = full)), merged)
    }

    @Test
    fun ruleLookupFallsBackToTheSameDevice() {
        val rule = DeviceRule(anonymised, "Buds Pro", OutputType.BLUETOOTH, profileId = "phones")
        val state = ProfilesState(emptyList(), listOf(rule), emptyMap())
        assertEquals(rule, state.rule(full))
    }
}
