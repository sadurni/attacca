package com.sadurni.attacca.dsp

/** A crossfeed filter run frame by frame: [Bs2b] or [SpeakerVirtualizer]. */
interface StereoCrossfeed {
    val outLeft: Float
    val outRight: Float
    fun processFrame(left: Float, right: Float)
    fun clear()
}
