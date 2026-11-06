package com.sadurni.attacca.chain

import android.content.Context
import android.os.PowerManager
import android.widget.Toast
import com.sadurni.attacca.AttaccaApp
import com.sadurni.attacca.R
import com.sadurni.attacca.library.TrackSort
import java.io.File

/**
 * A new list from a track taken at random: by a button, a cover gesture or shaking the phone.
 * The mood of that first track is asked when it can be (asking on, screen on, the app in view);
 * otherwise the last mood chosen is used, so a shake with the screen off goes on in the same mood.
 */
object RandomList {

    /**
     * Starts a random list, asking the mood first when it can. [avoid] is the playing track,
     * [play] plays the list (name, ids).
     */
    fun request(app: AttaccaApp, avoid: String?, play: (String, List<String>) -> Unit) {
        if (app.settings.chain.value.randomAskMood && canAsk(app)) {
            app.randomMoodRequest.value = true
        } else {
            start(app, app.settings.chain.value.randomMood, avoid, app.chainLists.onlyIncludedByDefault(), app.chainLists.listCount, play)
        }
    }

    /** Starts a random list of [mood] (null: any), remembered for the next ones; [onlyIncluded] and [count] tracks as chosen. */
    fun start(app: AttaccaApp, mood: Mood?, avoid: String?, onlyIncluded: Boolean, count: Int, play: (String, List<String>) -> Unit) {
        if (mood != app.settings.chain.value.randomMood) app.settings.updateChain { it.copy(randomMood = mood) }
        val moodName = app.getString(if (mood == null) R.string.random_list_any else com.sadurni.attacca.ui.chain.moodRes(mood))
        toast(app, app.getString(R.string.random_list_making, moodName))
        app.chainLists.quickRandom(mood, count, avoid?.takeIf { !it.contains("://") }, onlyIncluded) { result ->
            when {
                result == null -> toast(app, app.getString(R.string.random_list_none))
                result.second.size < 2 -> toast(app, app.getString(R.string.seed_list_empty))
                else -> {
                    val (seed, ids) = result
                    val title = TrackSort.cachedTags(seed)?.title?.ifBlank { null } ?: File(seed).nameWithoutExtension
                    play(app.getString(R.string.seed_list_name, title), ids)
                }
            }
        }
    }

    /** The question can be seen: the screen is on and the app is in view. */
    private fun canAsk(app: AttaccaApp): Boolean =
        app.uiVisible && (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
