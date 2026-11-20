package com.sadurni.attacca.ui.art

/**
 * What plays now, as the playback service last told it (the song, where it was and when, whether
 * it plays): for Art-tacca painting where no screen of the app is open (the wallpaper).
 */
object ArtNow {
    @Volatile
    var mediaId: String? = null
        private set

    /** The place in the song (as the player gives it: from the clip's start) at [atNs]. */
    @Volatile
    var positionMs = 0L
        private set

    @Volatile
    var atNs = 0L
        private set

    @Volatile
    var playing = false
        private set

    @Volatile
    var clipStartMs = 0L
        private set

    @Volatile
    var clipEndMs: Long? = null
        private set

    /** Its title (for the painting's signature). */
    @Volatile
    var title: String? = null
        private set

    fun publish(id: String?, position: Long, isPlaying: Boolean, clipStart: Long, clipEnd: Long?, title: String? = null) {
        mediaId = id
        this.title = title
        positionMs = position
        atNs = System.nanoTime()
        playing = isPlaying
        clipStartMs = clipStart
        clipEndMs = clipEnd
    }
}
