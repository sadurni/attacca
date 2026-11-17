package com.sadurni.attacca.settings

import com.sadurni.attacca.library.InclusionMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListSettingsTest {

    @Test
    fun inclusionMadeWithIsKept() {
        val withChildren = ListSettings(endless = true, inclusion = ListInclusion(false, "kids", "/m/Inclusion/kids.m3u8", InclusionMode.BLOCK))
        assertEquals(withChildren, ListSettings.fromJson(JSONObject(withChildren.toJson().toString())))
        val everything = ListSettings(inclusion = ListInclusion(true))
        assertEquals(everything, ListSettings.fromJson(JSONObject(everything.toJson().toString())))
    }

    @Test
    fun listsSavedBeforeHaveNoInclusion() {
        val old = JSONObject().put("shuffle", "OFF").put("repeat", 0).put("endless", true)
        assertNull(ListSettings.fromJson(old).inclusion)
        assertNull(ListSettings.fromJson(JSONObject(ListSettings().toJson().toString())).inclusion)
    }

    @Test
    fun liveChoiceMadeWithIsKept() {
        for (live in LiveChoice.entries) {
            val made = ListSettings(inclusion = ListInclusion(true, live = live))
            assertEquals(live, ListSettings.fromJson(JSONObject(made.toJson().toString())).inclusion?.live)
        }
        // Saved before the choice was kept: none (the list goes on with the setting).
        val old = JSONObject().put("inclusion", JSONObject().put("all", true).put("set", JSONObject.NULL).put("list", JSONObject.NULL).put("mode", "ALLOW"))
        assertNull(ListSettings.fromJson(old).inclusion?.live)
    }
}
