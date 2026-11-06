package com.sadurni.attacca.settings

/** Gestures recognized on the album cover in the player. */
enum class CoverGesture {
    TAP,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    SWIPE_UP,
    SWIPE_DOWN,
    DOUBLE_TAP_TOP,
    DOUBLE_TAP_BOTTOM,
    DOUBLE_TAP_LEFT,
    DOUBLE_TAP_RIGHT,
}

enum class CoverAction {
    NONE,
    NEXT,
    PREVIOUS,
    NEXT_ALBUM,
    PREVIOUS_ALBUM,
    PLAY_PAUSE,
    OPEN_QUEUE,
    OPEN_LISTS,
    OPEN_NAVIGATOR,
    OPEN_SOUND,
    TOGGLE_FAVORITE,
    ADD_BOOKMARK,
    CYCLE_SHUFFLE,
    CYCLE_REPEAT,
    SLEEP_TIMER,
    SHOW_LYRICS,
    GO_TO_FOLDER,
    GO_TO_ALBUM,
    GO_TO_ARTIST,
    GO_TO_END,
    QUICK_LIST,
    RANDOM_LIST,
    SHOW_COVER,
    OPEN_ORAMA,
}

object CoverGestures {
    val DEFAULTS: Map<CoverGesture, CoverAction> = mapOf(
        CoverGesture.SWIPE_LEFT to CoverAction.NEXT,
        CoverGesture.SWIPE_RIGHT to CoverAction.PREVIOUS,
        CoverGesture.DOUBLE_TAP_BOTTOM to CoverAction.OPEN_QUEUE,
        CoverGesture.TAP to CoverAction.SHOW_COVER,
    )
}

/** Sideways swipes on the rows of the current list, and of folders and other lists. */
enum class RowSwipe(val queue: Boolean) {
    QUEUE_LEFT(true),
    QUEUE_RIGHT(true),
    BROWSE_LEFT(false),
    BROWSE_RIGHT(false),
}

enum class RowAction {
    NONE,
    PLAY_NEXT,
    ADD_TO_QUEUE,
    ADD_TO_FAVORITES,

    /** Only on the rows of the current list. */
    REMOVE,
    ;

    companion object {
        /** The actions a swipe of [swipe] can do. */
        fun choicesFor(swipe: RowSwipe): List<RowAction> =
            if (swipe.queue) listOf(NONE, PLAY_NEXT, REMOVE, ADD_TO_FAVORITES) else listOf(NONE, PLAY_NEXT, ADD_TO_QUEUE, ADD_TO_FAVORITES)
    }
}

object RowGestures {
    val DEFAULTS: Map<RowSwipe, RowAction> = mapOf(
        RowSwipe.QUEUE_LEFT to RowAction.REMOVE,
        RowSwipe.QUEUE_RIGHT to RowAction.PLAY_NEXT,
        RowSwipe.BROWSE_LEFT to RowAction.ADD_TO_QUEUE,
        RowSwipe.BROWSE_RIGHT to RowAction.PLAY_NEXT,
    )
}
