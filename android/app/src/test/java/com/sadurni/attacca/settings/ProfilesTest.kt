package com.sadurni.attacca.settings

import com.sadurni.attacca.library.ListSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilesTest {

    private val bass = DspSettings(bassEnabled = true, bassMaxGainDb = 9f, eqGains = List(10) { it.toFloat() })
    private val car = DspProfile("car", "Car", bass)
    private val phones = DspProfile("phones", "Headphones", DspSettings(crossfeedEnabled = true))
    private val default = DspProfile(ProfilesState.DEFAULT_PROFILE_ID, "Default", DspSettings())

    private val bt = OutputDevice("BLUETOOTH|Car kit|00:11", "Car kit", OutputType.BLUETOOTH)
    private val otherBt = OutputDevice("BLUETOOTH|Buds|22:33", "Buds", OutputType.BLUETOOTH)

    private val state = ProfilesState(
        profiles = listOf(default, car, phones),
        devices = listOf(
            DeviceRule(bt.key, bt.name, bt.type, profileId = "car", resumeList = ListSource.Playlist("/m/car.m3u8"), autoPlay = true),
            DeviceRule(otherBt.key, otherBt.name, otherBt.type),
        ),
        typeDefaults = mapOf(OutputType.BLUETOOTH to "phones"),
    )

    @Test
    fun resolvesDeviceThenTypeThenDefault() {
        assertEquals("car", state.resolveProfile(bt).id)
        assertEquals("phones", state.resolveProfile(otherBt).id)
        val wired = OutputDevice("WIRED", "Jack", OutputType.WIRED)
        assertEquals(ProfilesState.DEFAULT_PROFILE_ID, state.resolveProfile(wired).id)
        assertEquals(ProfilesState.DEFAULT_PROFILE_ID, state.resolveProfile(null).id)
    }

    @Test
    fun jsonRoundTrip() {
        val restored = ProfileStore.fromJson(ProfileStore.toJson(state))
        assertEquals(state, restored)
    }

    @Test
    fun settingsFromOldJsonUseDefaults() {
        val partial = org.json.JSONObject().put("preampOn", true).put("preampDb", 3.0)
        val settings = DspSettings.fromJson(partial)
        assertEquals(true, settings.preampEnabled)
        assertEquals(3f, settings.preampDb)
        assertEquals(DspSettings().copy(preampEnabled = true, preampDb = 3f), settings)
    }
}
