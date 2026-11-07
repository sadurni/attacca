package com.sadurni.attacca.settings

import com.sadurni.attacca.library.InclusionMode
import com.sadurni.attacca.library.InclusionSet
import java.io.File
import java.util.UUID

/**
 * Several inclusion lists kept by name, one in use at a time (or none: everything). The one in
 * use is what [ChainSettings.inclusionList] and [ChainSettings.inclusionMode] describe, so the
 * rest of the app keeps reading those; every change goes through [keepInStep].
 */
object InclusionSets {

    /**
     * [c] with the set in use following its list and mode. A list chosen while no set is in use
     * (or kept from before sets existed) becomes a set of its own, named after its file.
     */
    fun keepInStep(c: ChainSettings): ChainSettings {
        var sets = c.inclusionSets
        var active = c.activeSet?.takeIf { id -> sets.any { it.id == id } }
        val list = c.inclusionList
        if (active == null && list != null) {
            val set = InclusionSet(newId(), uniqueName(sets, File(list).nameWithoutExtension.ifBlank { "List" }), list, c.inclusionMode)
            sets = sets + set
            active = set.id
        }
        if (active != null) sets = sets.map { if (it.id == active) it.copy(list = list, mode = c.inclusionMode) else it }
        return if (sets == c.inclusionSets && active == c.activeSet) c else c.copy(inclusionSets = sets, activeSet = active)
    }

    /** Puts the set [id] in use (null: none, everything may be used); lists made then keep to it. */
    fun select(settings: AppSettings, id: String?) = settings.updateChain { c ->
        val set = c.inclusionSets.firstOrNull { it.id == id }
        c.copy(
            activeSet = set?.id,
            inclusionList = set?.list,
            inclusionMode = set?.mode ?: c.inclusionMode,
            listsOnlyIncluded = set != null,
        )
    }

    /** A new, empty set named [name], put in use. Returns its id. */
    fun create(settings: AppSettings, name: String): String = add(settings, name).also { select(settings, it) }

    /** A new, empty set named [name], not put in use. Returns its id. */
    fun add(settings: AppSettings, name: String): String {
        val id = newId()
        settings.updateChain { c ->
            c.copy(inclusionSets = c.inclusionSets + InclusionSet(id, uniqueName(c.inclusionSets, name.trim().ifBlank { "List" }), null, InclusionMode.BLOCK))
        }
        return id
    }

    /** Sets the list file of the set [id] (in use or not). */
    fun setList(settings: AppSettings, id: String, list: String) = settings.updateChain { c ->
        if (c.activeSet == id) c.copy(inclusionList = list)
        else c.copy(inclusionSets = c.inclusionSets.map { if (it.id == id) it.copy(list = list) else it })
    }

    fun rename(settings: AppSettings, id: String, name: String) = settings.updateChain { c ->
        val others = c.inclusionSets.filter { it.id != id }
        c.copy(inclusionSets = c.inclusionSets.map { if (it.id == id) it.copy(name = uniqueName(others, name.trim().ifBlank { it.name })) else it })
    }

    /** Forgets the set [id] (its list file stays where it is); if in use, none is then. */
    fun delete(settings: AppSettings, id: String) {
        if (settings.chain.value.activeSet == id) select(settings, null)
        settings.updateChain { c -> c.copy(inclusionSets = c.inclusionSets.filter { it.id != id }) }
    }

    /** The set's name, or null when there is none of that id. */
    fun nameOf(c: ChainSettings, id: String?): String? = c.inclusionSets.firstOrNull { it.id == id }?.name

    private fun newId() = UUID.randomUUID().toString()

    /** [name], or "name 2", "name 3"… when another set has it already. */
    fun uniqueName(sets: List<InclusionSet>, name: String): String {
        val taken = sets.map { it.name.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        var n = 2
        while ("$name $n".lowercase() in taken) n++
        return "$name $n"
    }
}
