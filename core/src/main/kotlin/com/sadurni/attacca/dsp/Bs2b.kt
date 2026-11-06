package com.sadurni.attacca.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Bauer stereophonic-to-binaural crossfeed.
 *
 * Each channel is split into a low-passed part, fed to the opposite ear, and a high-shelf part
 * kept on its own side. This mimics the acoustic crosstalk of speakers so hard-panned recordings
 * stop sounding "inside the head" on headphones.
 */
class Bs2b : StereoCrossfeed {

    private var a0Lo = 0.0
    private var b1Lo = 0.0
    private var a0Hi = 1.0
    private var a1Hi = 0.0
    private var b1Hi = 0.0
    private var gain = 1.0

    private var loL = 0.0
    private var loR = 0.0
    private var hiL = 0.0
    private var hiR = 0.0
    private var prevL = 0.0
    private var prevR = 0.0

    override var outLeft = 0f
        private set
    override var outRight = 0f
        private set

    /**
     * @param cutoffHz low-pass cutoff of the crossfed signal.
     * @param feedDb how much quieter the crossfed low frequencies are than the direct signal.
     */
    fun setParameters(sampleRate: Int, cutoffHz: Int, feedDb: Float) {
        val feed = feedDb.toDouble()
        val gbLo = feed * -5.0 / 6.0 - 3.0
        val gbHi = feed / 6.0 - 3.0
        val gLo = 10.0.pow(gbLo / 20.0)
        val gHi = 1.0 - 10.0.pow(gbHi / 20.0)
        val fcHi = cutoffHz * 2.0.pow((gbLo - 20.0 * log10(gHi)) / 12.0)

        var x = exp(-2.0 * PI * cutoffHz / sampleRate)
        b1Lo = x
        a0Lo = gLo * (1.0 - x)

        x = exp(-2.0 * PI * fcHi / sampleRate)
        b1Hi = x
        a0Hi = 1.0 - gHi * (1.0 - x)
        a1Hi = -x

        gain = 1.0 / (1.0 - gHi + gLo)
    }

    override fun processFrame(left: Float, right: Float) {
        val l = left + ANTI_DENORMAL
        val r = right + ANTI_DENORMAL
        loL = a0Lo * l + b1Lo * loL
        loR = a0Lo * r + b1Lo * loR
        hiL = a0Hi * l + a1Hi * prevL + b1Hi * hiL
        hiR = a0Hi * r + a1Hi * prevR + b1Hi * hiR
        prevL = l
        prevR = r
        outLeft = ((hiL + loR) * gain).toFloat()
        outRight = ((hiR + loL) * gain).toFloat()
    }

    override fun clear() {
        loL = 0.0; loR = 0.0
        hiL = 0.0; hiR = 0.0
        prevL = 0.0; prevR = 0.0
    }
}
