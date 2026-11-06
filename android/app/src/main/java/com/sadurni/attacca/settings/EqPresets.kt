package com.sadurni.attacca.settings

import android.content.Context
import com.sadurni.attacca.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Starting points for the graphic equalizer (gains in dB for its 10 bands, 31 Hz … 16 kHz), and
 * the user's own, saved by name. A preset is only copied into the profile's bands: changing them
 * afterwards does not touch the preset.
 */
object EqPresets {

    class BuiltIn(val nameRes: Int, val gains: List<Float>)

    val builtIn: List<BuiltIn> = listOf(
        BuiltIn(R.string.eq_preset_flat, listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        BuiltIn(R.string.eq_preset_bass, listOf(6f, 5f, 4f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
        BuiltIn(R.string.eq_preset_treble, listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 3.5f, 5f, 6f)),
        BuiltIn(R.string.eq_preset_loudness, listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 1.5f, 3.5f, 4.5f)),
        BuiltIn(R.string.eq_preset_vocal, listOf(-2f, -2f, -1f, 0.5f, 2f, 3f, 3f, 2f, 0.5f, 0f)),
        BuiltIn(R.string.eq_preset_rock, listOf(4f, 3f, 1.5f, -0.5f, -1f, 0f, 1.5f, 3f, 3.5f, 4f)),
        BuiltIn(R.string.eq_preset_pop, listOf(-1f, 0f, 1.5f, 3f, 3.5f, 2f, 0.5f, 0f, 0.5f, 1f)),
        BuiltIn(R.string.eq_preset_jazz, listOf(2.5f, 2f, 1f, 1.5f, -1f, -1f, 0f, 1f, 2f, 2.5f)),
        BuiltIn(R.string.eq_preset_classical, listOf(3f, 2f, 1f, 0f, 0f, 0f, -0.5f, 1f, 2f, 3f)),
        BuiltIn(R.string.eq_preset_electronic, listOf(5f, 4f, 1.5f, 0f, -1.5f, 1f, 0f, 1f, 3.5f, 4.5f)),
        BuiltIn(R.string.eq_preset_acoustic, listOf(2.5f, 2f, 1.5f, 0.5f, 1f, 1f, 2f, 2.5f, 2f, 1f)),
        BuiltIn(R.string.eq_preset_small_speakers, listOf(-6f, -3f, 2f, 3f, 2f, 1f, 1.5f, 2f, 1f, -1f)),
    )

    private const val PREFS = "eq_presets"
    private const val KEY = "own"

    /** The user's presets, in the order they were saved. */
    fun own(context: Context): List<Pair<String, List<Float>>> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val g = o.getJSONArray("gains")
            o.getString("name") to List(DspSettings.EQ_BANDS) { b -> g.optDouble(b, 0.0).toFloat() }
        }
    }.getOrDefault(emptyList())

    /** Saves [gains] as [name], replacing a preset of the same name. */
    fun save(context: Context, name: String, gains: List<Float>) =
        write(context, own(context).filter { it.first != name } + (name to gains))

    fun delete(context: Context, name: String) = write(context, own(context).filter { it.first != name })

    private fun write(context: Context, presets: List<Pair<String, List<Float>>>) {
        val array = JSONArray(presets.map { (name, gains) -> JSONObject().put("name", name).put("gains", JSONArray(gains.map { it.toDouble() })) })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
