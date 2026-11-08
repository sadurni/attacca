package com.sadurni.attaccaplg

import com.sadurni.attacca.playback.Mp3Damage
import com.sadurni.attaccaplg.audio.Decoder
import java.io.File
import kotlin.test.Test

/** Local check only (a damaged file of the user's): clicks heard before and after a repair. */
class RepairListenTest {
    @Test
    fun clicks() {
        val path = System.getenv("ATTACCA_REPAIR_FILE") ?: return
        val bk = File(path)
        val fixed = Mp3Damage.repair(bk.readBytes())!!
        println("cut ${fixed.cutMs} ms, silenced ${fixed.silenced}")
        val out = File(System.getProperty("java.io.tmpdir"), "repaired-new.mp3").apply { writeBytes(fixed.data) }
        val tmp = System.getProperty("java.io.tmpdir")
        val orig = File(tmp, "orig.mp3").apply { writeBytes(bk.readBytes()) }
        val old = File(tmp, "old.mp3").apply { writeBytes(File(path.removeSuffix(".bk")).readBytes()) }
        for (f in listOf(orig, old, out)) {
            val per = IntArray(400)
            val wins = sortedSetOf<Long>()
            var prev = 0f; var clicks = 0; var n = 0L; var loud = 0
            val fmt = Decoder.decode(f, {}) { s, frames ->
                for (i in 0 until frames * 2 step 2) {
                    val v = s[i]
                    if (kotlin.math.abs(v - prev) > 0.6f) { clicks++; if (f === out) wins.add(n / 4410); per[((n / 44100) / 10).toInt().coerceAtMost(399)]++ }
                    if (kotlin.math.abs(v) > 0.999f) loud++
                    prev = v; n++
                }
            }
            if (fmt == null) try {
                com.sadurni.attaccaplg.audio.Mp3Repeats.audioStream(f).use { src ->
                    val t = javax.sound.sampled.AudioFormat(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 2, 4, 44100f, false)
                    val pcm = javax.sound.sampled.AudioSystem.getAudioInputStream(t, src)
                    val buf = ByteArray(65536); var tot = 0L
                    while (true) { val r = pcm.read(buf); if (r < 0) break; tot += r }
                    println("read $tot")
                }
            } catch (e: Throwable) { e.printStackTrace(System.out) }
            println("${f.name}: $fmt ${n / 44100} s, jumps ${clicks}, full scale $loud; windows ${wins.size}: ${wins.take(60).map { it / 10.0 }}; per 10 s: ${per.take(40).joinToString(",")}")
        }
    }
}
