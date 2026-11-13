package com.sadurni.attacca.ui.world

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.sadurni.attacca.diorama.SongLook
import com.sadurni.attacca.diorama.SongWave
import com.sadurni.attacca.world.LandMesher
import com.sadurni.attacca.world.Mat4
import com.sadurni.attacca.world.Mesh
import com.sadurni.attacca.world.WorldCamera
import com.sadurni.attacca.world.WorldLand
import com.sadurni.attacca.world.blob
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the screen hands the renderer: the song, where it is, and what is heard (set from the UI, read each frame). */
class WorldFeed {
    @Volatile var songId: String? = null
    @Volatile var look: SongLook? = null

    /** Whether the song's quiet and loud stretches may change the world (not with a world chosen by hand). */
    @Volatile var sectioned: Boolean = true

    /** Where the traveller is on the screen now (px; -1 when out of sight), for a tap on it. */
    @Volatile var travellerX = -1f
    @Volatile var travellerY = -1f

    /** The traveller was tapped: a move. */
    @Volatile var tapMove = false
    @Volatile var wave: SongWave? = null
    @Volatile var peakMs: Double = -1.0
    @Volatile var positionMs: Long = 0L
    /** The song [positionMs] belongs to (null: not known): a place of another song is not taken. */
    @Volatile var positionFor: String? = null
    @Volatile var positionAtNs: Long = 0L
    @Volatile var playing: Boolean = false
    /** Level, low and high of what is heard now (0 … 1), and whether they are known. */
    val levels = FloatArray(3)
    @Volatile var heard: Boolean = false
    /** Debug builds only: a traveller to test with instead of the song's. */
    @Volatile var forceTraveller: com.sadurni.attacca.diorama.Traveller? = null
    /** Debug builds only: the peak shown all the time. */
    @Volatile var forcePeak: Boolean = false

    /** The next song's look (as O'rama shows it), its title and place in the list ("7/23"), if any. */
    @Volatile var nextLook: SongLook? = null
    @Volatile var nextTitle: String? = null
    @Volatile var nextPlace: String? = null

    /** The song's cover, for the boards by the way. */
    @Volatile var cover: android.graphics.Bitmap? = null

    /** Where the song starts on the land's own timeline (ms), as in O'rama: its places fall at the same moments. */
    @Volatile var originMs: Double = 0.0

    /** The festive days' look (null: none), with the Three Kings; [vikram]: the Hindu new year's show going on. */
    @Volatile var festive: com.sadurni.attacca.festive.Festivity? = null
    @Volatile var kings: Boolean = false
    @Volatile var vikram: Boolean = false

    /** Where the sun (or moon) is on the screen now (px; -1 when out of sight), for touches on it. */
    @Volatile var sunX = -1f
    @Volatile var sunY = -1f
    @Volatile var sunR = 0f

    /** The song whose sun turned into a record (touched five times quickly). */
    @Volatile var recordOf: String? = null
}

/** Draws Attacca World: the song's land, the traveller with the four dogs, the sky, from [camera]. */
class WorldRenderer(private val feed: WorldFeed, val camera: WorldCamera) : GLSurfaceView.Renderer {

    private var land: WorldLand? = null
    private var lastTz = 0f
    private var landFor: Triple<String?, SongLook?, SongWave?>? = null
    private val chunks = HashMap<Int, Gpu>()

    /** The sky's colours and night as shown, easing towards the land's (see [eased]). */
    private val skyEase = FloatArray(16)
    private var skyEaseNs = 0L

    /** How far to ease this frame (about 1.2 s to most of the way). */
    private fun skyEaseStep(): Float {
        val now = System.nanoTime()
        val dt = if (skyEaseNs == 0L) -1.0 else (now - skyEaseNs) / 1e6
        skyEaseNs = now
        return if (dt < 0) 1f else (1 - kotlin.math.exp(-dt.coerceIn(0.0, 250.0) / 450.0)).toFloat()
    }

    private fun eased(slot: Int, v: Float, k: Float): Float {
        skyEase[slot] += (v - skyEase[slot]) * k
        return skyEase[slot]
    }
    /** The last land's pieces, drawn (moved by [staleDz] rows) where the new land's are not made yet. */
    private val stale = HashMap<Int, Gpu>()
    private var staleDz = 0f
    // Who goes through the land (traveller, vehicle, dogs, people at relays) and their meshes on the GPU.
    private var cast: com.sadurni.attacca.world.Cast? = null
    private val rigGpu = java.util.IdentityHashMap<com.sadurni.attacca.world.Rig, List<Gpu>>()
    private val draws = ArrayList<com.sadurni.attacca.world.Draw>()
    private var extras: com.sadurni.attacca.world.Extras? = null
    /** The last song's cast, for the vehicle left at its end, and how far to move it into this land. */
    private var oldCast: com.sadurni.attacca.world.Cast? = null
    private var oldDz = 0f
    // The boards' pictures on the GPU: the cover, the next song's sign.
    private var tex = 0
    private var texBuffer = 0
    private var coverTex = 0
    private var recordShown = 0f
    private var coverOf: android.graphics.Bitmap? = null
    private var signTex = 0
    private var signOf: String? = null
    private val texData = FloatArray(6 * 5)
    private val texFloats = ByteBuffer.allocateDirect(6 * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var sightAt = 0L
    private var sight: FloatArray? = null
    private var dragonTurn = 0f
    private var dragonTan = 0.5f
    private var tunnelDark = 0f

    /** Whether this song's traveller came aboard from the one before. */
    private var arrivedOn = false

    /** What a new land is made from (taken on the screen's thread), and what was made. */
    private class Plan(
        val n: Int, val key: Triple<String?, SongLook?, SongWave?>, val old: WorldLand?, val sameSong: Boolean, val onlyNext: Boolean,
        val before: WorldLand.Before?, val kind: com.sadurni.attacca.diorama.Traveller, val arrives: Boolean,
        val prevCast: com.sadurni.attacca.world.Cast?, val wave: SongWave?, val look: SongLook, val songId: String?,
        val sectioned: Boolean, val next: SongLook?, val origin: Double, val cameIn: SongLook?, val person: Int, val atZ: Float,
        val atMs: Long,
    )
    private class Built(
        val plan: Plan, val land: WorldLand, val cast: com.sadurni.attacca.world.Cast, val extras: com.sadurni.attacca.world.Extras,
        val meshes: Map<Int, Mesh>,
    )
    // (Read by the builder too: a plan overtaken by a newer one is dropped at once.)
    @Volatile private var planCount = 0
    private var placeGuardUntil = 0L
    private var plannedNext: SongLook? = null
    private var plannedOrigin = Double.NaN
    /** The plan being made now, if any. */
    private var waitingPlan: Plan? = null
    private val built = java.util.concurrent.atomic.AtomicReference<Built?>(null)
    /** A new song's land is being made: the old one goes on in its own time meanwhile. */
    private var holdOld = false
    private val builder = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "Attacca World new land").apply { isDaemon = true } }

    /** Makes the land of [plan] (on the builder's thread): land, cast, extras, a first frame and the pieces about the start. */
    private fun build(plan: Plan, nowMs: Long): Built? {
        if (plan.n != planCount) return null
        val land = WorldLand(plan.wave, plan.look, (plan.songId ?: "").hashCode(), plan.sectioned, plan.before, plan.next, plan.origin, plan.arrives, plan.cameIn)
        val cast = com.sadurni.attacca.world.Cast(land, plan.kind, plan.person, (plan.songId ?: "").hashCode(), plan.arrives)
        val extras = com.sadurni.attacca.world.Extras(land, (plan.songId ?: "").hashCode())
        if (plan.n != planCount) return null
        val z = if (plan.sameSong) plan.atZ else land.zAt((plan.wave?.startMs ?: 0L).toDouble())
        // A first frame made here, so the figures' and the things' shapes are ready.
        val warm = ArrayList<com.sadurni.attacca.world.Draw>()
        runCatching {
            cast.frame(z, 0.0, false, nowMs, warm)
            extras.frame(z, land.pathAt(z), nowMs, false, 0f, null, plan.look.palette.accent, plan.next != null, warm)
            extras.sight(z)
        }
        val here = floor(z / LandMesher.CHUNK).toInt()
        val meshes = HashMap<Int, Mesh>()
        for (k in here - 1..here + 2) {
            if (plan.n != planCount) return null
            meshes[k] = LandMesher.chunk(land, k)
        }
        return Built(plan, land, cast, extras, meshes)
    }

    /** Puts the land made in place (on the screen's thread). */
    private fun take(b: Built, nowMs: Long) {
        val plan = b.plan
        val old = land
        if (!plan.sameSong) {
            oldCast = plan.prevCast?.takeIf { plan.before != null && it.leaves }
            oldDz = -(plan.before?.atZ ?: 0f)
        }
        arrivedOn = plan.arrives
        land = b.land
        // A new song starts at its start (the player's position for it follows in a moment).
        // (As far into it as the time it took to make: the traveller goes on, no step back.)
        if (!plan.sameSong) {
            posMs = (plan.wave?.startMs ?: 0L).toDouble() + (nowMs - plan.atMs).coerceIn(0L, 4000L)
            placeGuardUntil = nowMs + 2500
            // What World decided at this change, for the log the user can send.
            val line = "${plan.songId?.substringAfterLast('/')}: ${b.land.describeStart()}"
            builder.execute { WorldLog.note(line) }
        }
        holdOld = false
        // The land in sight is kept until the new one's pieces are made (no gap, no flicker):
        // the old pieces are drawn where they were, moved to the new song's rows.
        for (g in stale.values) delete(g)
        stale.clear()
        val keepTo = if (plan.onlyNext && old != null) minOf(old.endZ, b.land.endZ) - WorldLand.ARRIVE - 8f else Float.NEGATIVE_INFINITY
        for ((k, g) in chunks) {
            if ((k + 1) * LandMesher.CHUNK < keepTo) continue
            stale[k] = g
        }
        chunks.keys.removeAll { it in stale }
        staleDz = when {
            plan.sameSong -> 0f
            plan.before != null -> -plan.before.atZ
            else -> Float.NaN
        }
        if (staleDz.isNaN()) {
            for (g in stale.values) delete(g)
            stale.clear()
        }
        pending.clear()
        for ((k, m) in b.meshes) if (k !in chunks) chunks[k] = gpu(m)
        for (list in rigGpu.values) list.forEach(::delete)
        rigGpu.clear()
        b.cast.carryDogsFrom(plan.prevCast)
        b.cast.shownFrom(nowMs)
        cast = b.cast
        extras = b.extras
    }
    private val inverse = FloatArray(16)
    private val boneMatrices = ArrayList<FloatArray>()
    /** The traveller met last (people change at relays and go on from song to song). */
    private var person = 0

    // Chunks are made on a thread of their own and handed over here to go to the GPU.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "Attacca World land").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val pending = HashSet<Int>()
    private val ready = java.util.concurrent.ConcurrentLinkedQueue<Triple<WorldLand, Int, Mesh>>()

    // Weather: rain or snow falling about the camera, clouds drifting, lightning.
    private var weatherBuffer = 0
    private var cloudGpu: List<Gpu> = emptyList()
    private var cloudFor: SongLook? = null
    private var flashUntil = 0L
    private var nextFlash = 0L

    private var land3d = 0
    private var sky = 0
    private var skyBuffer = 0
    private var width = 1
    private var height = 1

    // The traveller's own clock, eased towards the player's.
    private var posMs = 0.0
    private var lastFrameNs = 0L
    private var walked = 0f
    private var pulse = 0f
    private var slowLevel = 0f
    private var level = 0f
    private var wave = 0f
    private var openness = 1f
    private var openTarget = 1f
    private var openAt = 0L

    // Beats: rings going out over the land from where the traveller was (x, z, start ms, strength).
    private val ripples = FloatArray(4 * 4)
    private var nextRipple = 0
    private var lastBeatMs = 0L
    private var beatGrid = -1L

    /** A mesh on the GPU. */
    private class Gpu(val buffer: Int, val vertices: Int)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        land3d = program(LAND_VS, LAND_FS)
        sky = program(SKY_VS, SKY_FS)
        tex = program(TEX_VS, TEX_FS)
        texBuffer = upload(FloatArray(6 * 5))
        coverTex = 0
        coverOf = null
        signTex = 0
        signOf = null
        weatherBuffer = upload(FloatArray(Mesh.STRIDE))
        cloudFor = null
        pending.clear()
        ready.clear()
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        skyBuffer = upload(quad)
        // A new surface: everything uploaded before is gone.
        chunks.clear()
        stale.clear()
        landFor = null
        rigGpu.clear()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(w, 1)
        height = max(h, 1)
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowNs = System.nanoTime()
        val dtMs = if (lastFrameNs == 0L) 16L else ((nowNs - lastFrameNs) / 1_000_000L).coerceIn(1L, 100L)
        lastFrameNs = nowNs
        val nowMs = nowNs / 1_000_000L
        // The song as the screen gave it, all of it at once (never half of one song and half of another).
        val songId: String?
        val feedLook: SongLook?
        val songWave: SongWave?
        val nextLook: SongLook?
        val origin: Double
        val sectioned: Boolean
        synchronized(feed) {
            songId = feed.songId; feedLook = feed.look; songWave = feed.wave
            nextLook = feed.nextLook; origin = feed.originMs; sectioned = feed.sectioned
        }
        // Nothing until the song's look is known (no default world first).
        val look = feedLook ?: run {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            return
        }
        // A new land when the song, its look or its wave (which may come a moment later) changes.
        val key = Triple(songId, feedLook, songWave)
        // (Against what was last asked for, made or still being made.)
        if (key != landFor || nextLook !== plannedNext || origin != plannedOrigin) {
            plannedNext = nextLook
            plannedOrigin = origin
            // Another song from its start: its land goes on from where the last one was left (the
            // same song again, with its wave or another world, keeps what it went on from).
            // (Still being made for this very song: the new one is made from the same start.)
            val waiting = waitingPlan?.takeIf { it.songId == songId }
            val old = waiting?.old ?: land
            val sameSong = waiting?.sameSong ?: (landFor?.first == songId)
            // Only the next song's look changed: what is in sight now stays as it is.
            val onlyNext = waiting == null && key == landFor && origin == old?.originMs
            // (Opened in the middle of a song, the last one's rows are far behind and never seen.)
            val before = waiting?.before ?: when {
                old == null || landFor?.first == null -> null
                sameSong -> old.before
                else -> WorldLand.Before(old, lastTz)
            }
            landFor = key
            // Still aboard from the song before when the same vehicle goes on (nobody got off at its end).
            val prevCast = waiting?.prevCast ?: cast
            val kind = feed.forceTraveller ?: look.traveller
            // A new song starts in what was seen arriving at the end of the last one.
            val cameIn = waiting?.cameIn ?: if (!sameSong && before != null) old?.lookAt(lastTz) else if (sameSong) old?.cameIn else null
            val arrives = waiting?.arrives ?: if (sameSong) arrivedOn else prevCast != null && before != null && prevCast.kind == kind && !prevCast.leaves
            // The new land, its cast and what stands by the way are made by another thread (with the
            // pieces about the start and a first frame of figures), so the screen never stops: the
            // old land goes on being drawn (on its own time) until the new one is ready.
            val plan = Plan(++planCount, key, old, sameSong, onlyNext, before, kind, arrives, prevCast,
                songWave, look, songId, sectioned, nextLook, origin, cameIn, person, lastTz, nowMs)
            waitingPlan = plan
            holdOld = old != null && !sameSong
            builder.execute { build(plan, nowMs)?.let { built.set(it) } }
        }
        // The new land, once made: in its place now.
        built.getAndSet(null)?.let { b -> if (b.plan.n == planCount) { take(b, nowMs); waitingPlan = null } }
        val land = land ?: run {
            // The first land is still being made.
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            return
        }

        // Where the song is: the player's position, run on between its updates, eased into.
        val target = feed.positionMs + if (feed.playing) (nowNs - feed.positionAtNs) / 1_000_000.0 else 0.0
        // (Just after a new song came in the player may still give the last one's place: not taken.)
        val otherSong = feed.positionFor.let { it != null && it != landFor?.first }
        val stalePlace = otherSong || (nowMs < placeGuardUntil && target - posMs > 2500)
        if (holdOld || stalePlace) posMs += if (feed.playing) dtMs.toDouble() else 0.0
        else if (abs(target - posMs) > 1500) posMs = target
        else posMs += (if (feed.playing) dtMs.toDouble() else 0.0) + (target - posMs) * 0.08
        val tz = land.zAt(posMs)
        lastTz = tz
        val tx = land.centerAt(tz)
        val heading = land.headingAt(tz)
        val ty = land.pathAt(tz)
        cast?.let { person = it.personAt(tz) }

        // The beat: how much the low sound rises over its own slow level; a strong one sends a
        // ring over the land. Without the sound's levels (not known yet), the song's tempo does.
        val low = if (feed.heard) feed.levels[1] else 0f
        level += ((if (feed.heard) feed.levels[0] else 0.5f) - level) * 0.15f
        slowLevel += (low - slowLevel) * 0.04f
        val rise = (low - slowLevel).coerceAtLeast(0f)
        pulse = max(pulse * 0.86f, rise * 2.5f)
        val bpm = look.bpm.takeIf { it in 40f..220f } ?: 100f
        val beatMs = 60_000.0 / bpm
        if (feed.playing) {
            if (feed.heard) {
                if (rise > 0.06f + slowLevel * 0.25f && nowMs - lastBeatMs > beatMs * 0.7) {
                    ripple(tz, nowMs, (rise * 5f).coerceIn(0.35f, 1.3f))
                    lastBeatMs = nowMs
                }
            } else {
                val n = (posMs / beatMs).toLong()
                if (n != beatGrid) {
                    beatGrid = n
                    // Every beat a small ring, the bar's first a bigger one.
                    ripple(tz, nowMs, if (n % 4 == 0L) 0.9f else 0.4f)
                    pulse = max(pulse, if (n % 4 == 0L) 0.8f else 0.35f)
                }
            }
        }

        val intensity = land.intensityAt(tz)
        val peakSong = if (feed.peakMs >= 0) {
            val d = abs(posMs - feed.peakMs)
            when {
                d < 3000 -> 1f
                d < 8000 -> (1 - (d - 3000) / 5000).toFloat()
                else -> 0f
            }
        } else 0f
        val peak = if (feed.forcePeak) 1f else peakSong
        // How open the land is about the traveller (0 a narrow valley, 1 open land): how high it
        // rises around, a few steps away, measured now and then.
        if (nowMs - openAt > 500) {
            openAt = nowMs
            var rise = 0f
            for (k in 0 until 12) {
                val a = k * 0.5236f
                rise += (land.heightAt(tx + kotlin.math.cos(a) * 12f, tz + sin(a) * 12f) - ty).coerceAtLeast(0f)
            }
            openTarget = (1f - rise / 12f / 7f).coerceIn(0f, 1f)
        }
        openness += (openTarget - openness) * 0.02f
        val ex = extras
        val christmas = feed.festive == com.sadurni.attacca.festive.Festivity.CHRISTMAS || feed.festive == com.sadurni.attacca.festive.Festivity.ORTHODOX_CHRISTMAS
        if (nowMs - sightAt > 400) {
            sightAt = nowMs
            sight = ex?.sight(tz)
        }
        val tunnel = ex?.tunnelAt(tz) == true
        // Through the traveller's eyes: not drawn, the dogs ahead.
        cast?.throughEyes = camera.firstPerson
        // The Great Wall's dragon over the sky, for the eyes to follow (where it would be looking straight on).
        val viewTan = kotlin.math.tan(minOf(Math.toRadians(if (width > height) 55.0 else 68.0), 2 * kotlin.math.atan(kotlin.math.tan(Math.toRadians(50.0)) / (width.toFloat() / height))) / 2).toFloat()
        dragonTurn = com.sadurni.attacca.world.SkyShow.turnOf(com.sadurni.attacca.world.SkyShow.dragonX((nowMs % 100_000L) / 1000f, width.toFloat() / height), viewTan)
        dragonTan = viewTan
        camera.update(
            dtMs, nowMs, tx, ty, tz, heading, intensity, peak, { x, z -> land.heightAt(x, z) }, openness,
            { x, z -> maxOf(com.sadurni.attacca.world.Props.topNear(land, x, z), cast?.topNear(x, z) ?: Float.NEGATIVE_INFINITY, ex?.topNear(x, z) ?: Float.NEGATIVE_INFINITY) },
            sight, if (ex?.tunnelNear(tz) == true) 1f else 0f, { x, z -> minOf(ex?.ceilingAt(x, z) ?: Float.POSITIVE_INFINITY, if (cast?.underwater == true) WorldLand.WATER - 0.8f else Float.POSITIVE_INFINITY) },
            { z -> land.centerAt(z) }, cast?.eye, ex?.peakSight(),
            if (look.world == com.sadurni.attacca.diorama.World.WALL && peak > 0.05f) dragonTurn else null, cast?.dogsNear, cast?.dogsNearCount ?: 0,
            ex?.boardSight(tz),
        )

        // Chunks in sight: some behind, many ahead; at most two new ones a frame, nearest first.
        val first = floor((tz - 40) / LandMesher.CHUNK).toInt()
        val last = floor((tz + FAR) / LandMesher.CHUNK).toInt()
        chunks.keys.filter { it < first - 1 || it > last + 1 }.forEach { k -> chunks.remove(k)?.let(::delete) }
        // Hand over what the worker made (for this land only), at most three a frame.
        var taken = 0
        while (taken < 3) {
            val (forLand, k, mesh) = ready.poll() ?: break
            if (forLand !== land) continue
            pending.remove(k)
            if (k in first - 1..last + 1 && k !in chunks) chunks[k] = gpu(mesh)
            taken++
        }
        val here = floor(tz / LandMesher.CHUNK).toInt()
        for (k in here - 1..here + 1) if (k !in chunks) { chunks[k] = gpu(LandMesher.chunk(land, k)); pending.remove(k) }
        // Ask for the missing ones, nearest first.
        for (k in (first..last).sortedBy { abs(it * LandMesher.CHUNK + 8 - tz) }) {
            if (k !in chunks && k !in pending && pending.size < 4) {
                pending += k
                val l = land
                worker.execute { ready += Triple(l, k, LandMesher.chunk(l, k)) }
            }
        }

        // Colours: the sky's, a warm haze towards the horizon, darker at night.
        val (skyA, skyB, skyT) = land.lookBlend(tz)
        fun blend(a: Int, b: Int) = rgb(a).let { x -> if (skyT <= 0f) x else rgb(b).let { y -> FloatArray(3) { x[it] + (y[it] - x[it]) * skyT } } }
        // (The sky's colours are eased over a moment: a song skipped halfway never turns night into day at once.)
        val easeK = skyEaseStep()
        val night = eased(9, skyA.night + (skyB.night - skyA.night) * skyT, easeK)
        // The weather and the sun as the land shows them here (blended, eased): never a jump as a song comes in.
        fun blendF(a: Float, b: Float) = a + (b - a) * skyT
        val skyOf = if (skyT < 0.5f) skyA else skyB
        val skySun = eased(10, blendF(skyA.sun, skyB.sun), easeK)
        val skyStorm = eased(11, blendF(skyA.storm, skyB.storm), easeK)
        val skyClouds = eased(12, blendF(skyA.clouds, skyB.clouds), easeK)
        val skyRain = eased(13, blendF(skyA.rain, skyB.rain), easeK)
        val skySnow = eased(14, blendF(skyA.snow, skyB.snow), easeK)
        // Under the water (the eye below its surface), the sea's own blues instead of the sky's.
        val under = camera.eyeY < WorldLand.WATER - 0.2f
        val top = if (under) floatArrayOf(0.04f, 0.2f, 0.34f) else blend(skyA.palette.skyTop, skyB.palette.skyTop).let { c -> FloatArray(3) { eased(it, c[it], easeK) } }
        val bottom = if (under) floatArrayOf(0.1f, 0.42f, 0.55f) else blend(skyA.palette.skyBottom, skyB.palette.skyBottom).let { c -> FloatArray(3) { eased(3 + it, c[it], easeK) } }
        val sunColour = blend(skyA.palette.light, skyB.palette.light).let { c -> FloatArray(3) { eased(6 + it, c[it], easeK) } }
        val haze = if (under) floatArrayOf(0.1f, 0.4f, 0.52f) else FloatArray(3) { bottom[it] * 0.8f + sunColour[it] * 0.2f }
        // Storms: now and then a flash of lightning, on a strong beat if there is one.
        if (skyStorm > 0.3f && nowMs > nextFlash && feed.playing) {
            flashUntil = nowMs + 120
            nextFlash = nowMs + (2500 + Math.random() * 9000 / skyStorm).toLong()
        }
        val flash = if (nowMs < flashUntil) 1f else 0f
        // The light breathes with how loud it is now; darker under a storm.
        // Darker deep in a tunnel.
        val inside = ex?.tunnelDepth(tz) ?: 0f
        tunnelDark += (inside - tunnelDark) * 0.08f
        val brightness = ((1f - 0.5f * night) * (1f - 0.35f * skyStorm) * (0.9f + 0.18f * level + 0.08f * pulse.coerceAtMost(1f)) + flash * 0.8f) * (1f - 0.72f * tunnelDark)

        val view = Mat4.lookAt(camera.eyeX, camera.eyeY, camera.eyeZ, camera.atX, camera.atY, camera.atZ)
        // (A wide strip keeps to about 100 degrees across, so the land is not stretched at its sides.)
        val aspect = width.toFloat() / height
        val fovY = minOf(Math.toRadians(if (width > height) 55.0 else 68.0), 2 * kotlin.math.atan(kotlin.math.tan(Math.toRadians(50.0)) / aspect))
        val projection = Mat4.perspective(fovY.toFloat(), aspect, 0.3f, FAR + 60f)
        val viewProjection = Mat4.mul(projection, view)
        // Where the traveller is on the screen, for a tap on it; a tap asked for a move.
        transform(viewProjection, tx, ty + 1.2f, tz).let { c ->
            if (c[3] > 0) {
                feed.travellerX = (c[0] / c[3] * 0.5f + 0.5f) * width
                feed.travellerY = (0.5f - c[1] / c[3] * 0.5f) * height
            } else {
                feed.travellerX = -1f
            }
        }
        if (feed.tapMove) {
            feed.tapMove = false
            val moved = cast?.tap(nowMs, tz, Math.random().toFloat())
            if (com.sadurni.attacca.BuildConfig.DEBUG) android.util.Log.d("AttaccaWorld", "tap on the traveller: move $moved")
        }

        GLES30.glClearColor(haze[0], haze[1], haze[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // The sky, with the sun's glow where it is.
        val sunDir = floatArrayOf(-0.45f, 0.12f + 0.45f * skySun, 1f)
        // The sun (or the moon) as drawn: lower and nearer straight ahead than the light comes
        // from, so it is in sight from the camera's usual height.
        val sunSky = floatArrayOf(-0.2f, 0.07f + 0.2f * skySun, 1f)
        val sx = camera.eyeX + sunSky[0] * 500
        val sy = camera.eyeY + sunSky[1] * 500
        val sz = camera.eyeZ + sunSky[2] * 500
        val clip = transform(viewProjection, sx, sy, sz)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(sky)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(sky, "uTop"), top[0], top[1], top[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(sky, "uBottom"), haze[0], haze[1], haze[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(sky, "uSun"), sunColour[0], sunColour[1], sunColour[2])
        val horizon = transform(viewProjection, camera.eyeX, camera.eyeY, camera.eyeZ + 1000f).let { if (it[3] > 0) it[1] / it[3] else -1f }
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uHorizon"), horizon)
        if (clip[3] > 0 && !under) {
            feed.sunX = (clip[0] / clip[3] * 0.5f + 0.5f) * width
            feed.sunY = (0.5f - clip[1] / clip[3] * 0.5f) * height
            feed.sunR = 0.12f * height
        } else {
            feed.sunX = -1f
        }
        // Touched five times quickly, the sun turns into a record for the rest of the song, its cover on the label.
        val record = feed.recordOf != null && feed.recordOf == feed.songId
        recordShown += ((if (record) 1f else 0f) - recordShown) * 0.06f
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uRecord"), recordShown)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uStar"), if (feed.kings && !under) 1f else 0f)
        val labelTex = if (recordShown > 0.01f) coverTexture() else 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, labelTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(sky, "uCover"), 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uHasCover"), if (labelTex != 0) 1f else 0f)
        if (clip[3] > 0 && !under) {
            GLES30.glUniform3f(GLES30.glGetUniformLocation(sky, "uSunAt"), clip[0] / clip[3], clip[1] / clip[3], (0.6f + 0.4f * (1 - night)) * (1f + peak * 0.6f) * (0.85f + 0.3f * level + 0.25f * pulse.coerceAtMost(1f)))
        } else {
            GLES30.glUniform3f(GLES30.glGetUniformLocation(sky, "uSunAt"), 0f, 0f, 0f)
        }
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uAspect"), width.toFloat() / height)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uNight"), night)
        // The song's peak in the sky: northern lights over the ice, a red moon over the castles,
        // shooting stars in space, fireworks elsewhere.
        val peakKind = when (look.world) {
            com.sadurni.attacca.diorama.World.ICE -> 1f
            // The red moon is the moon itself, where it is (see uMoonRed).
            com.sadurni.attacca.diorama.World.CASTLE -> -1f
            com.sadurni.attacca.diorama.World.SPACE -> 3f
            com.sadurni.attacca.diorama.World.DESERT -> 12f
            com.sadurni.attacca.diorama.World.WALL -> 5f
            com.sadurni.attacca.diorama.World.JUNGLE -> 6f
            com.sadurni.attacca.diorama.World.AMAZON -> 7f
            com.sadurni.attacca.diorama.World.CANYON -> 8f
            com.sadurni.attacca.diorama.World.JAPAN -> 9f
            com.sadurni.attacca.diorama.World.REEF -> 10f
            com.sadurni.attacca.diorama.World.VOLCANO -> 11f
            // As in O'rama: fireworks over a city at night, shooting stars on other nights, else a rainbow.
            com.sadurni.attacca.diorama.World.NEON -> 0f
            com.sadurni.attacca.diorama.World.CITY -> if (night > 0.5f) 0f else 4f
            else -> if (night > 0.5f) 3f else 4f
        }
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uPeak"), peak)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uPeakKind"), peakKind)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uTime"), (nowMs % 100_000L) / 1000f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uDragonX"), com.sadurni.attacca.world.SkyShow.screenX(dragonTurn, camera.skyTurn, dragonTan))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uFlash"), flash)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uYaw"), kotlin.math.atan2(camera.atX - camera.eyeX, camera.atZ - camera.eyeZ))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uMoonRed"), if (look.world == com.sadurni.attacca.diorama.World.CASTLE) peak else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sky, "uPump"), pulse.coerceAtMost(1f))
        android.opengl.Matrix.invertM(inverse, 0, viewProjection, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(sky, "uInverse"), 1, false, inverse, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, skyBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        // The land.
        GLES30.glUseProgram(land3d)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(land3d, "uViewProjection"), 1, false, viewProjection, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(land3d, "uEye"), camera.eyeX, camera.eyeY, camera.eyeZ)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(land3d, "uFog"), haze[0], haze[1], haze[2])
        // Under the sea while the eye is under the water's surface.
        val underSea = camera.eyeY < WorldLand.WATER - 0.2f
        GLES30.glUniform2f(GLES30.glGetUniformLocation(land3d, "uFogRange"), if (underSea) 12f else 45f, if (underSea) FAR * 0.62f else FAR)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uLight"), brightness)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(land3d, "uSkyTop"), top[0], top[1], top[2])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uWaterY"), WorldLand.WATER - 0.1f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uTime"), (nowMs % 100_000L) / 1000f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uWaveTime"), (nowMs % 100_000L) / 1000f)
        // Big waves with the beat (the low sound's rise), easing off.
        wave = max(wave * 0.97f, pulse.coerceAtMost(1.3f) * (0.4f + 0.6f * intensity))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uWave"), wave)
        val sl = Math.sqrt((sunDir[0] * sunDir[0] + sunDir[1] * sunDir[1] + sunDir[2] * sunDir[2]).toDouble()).toFloat()
        // Light from the sun's side (from behind the camera's left, so faces turned to the viewer are lit).
        GLES30.glUniform3f(GLES30.glGetUniformLocation(land3d, "uSunDir"), 0.5f, 0.75f, -0.42f)
        val warm = FloatArray(3) { (sunColour[it] * 0.55f + WARM[it] * 0.45f) * (1.05f - 0.4f * night) }
        GLES30.glUniform3f(GLES30.glGetUniformLocation(land3d, "uSunLight"), warm[0], warm[1], warm[2])
        val cool = FloatArray(3) { top[it] * 0.55f + 0.45f * COOL[it] }
        GLES30.glUniform3f(GLES30.glGetUniformLocation(land3d, "uSkyLight"), cool[0], cool[1], cool[2])
        if (sl < 0f) return
        GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uPulse"), pulse.coerceAtMost(1.5f))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uBeat"), ((posMs / beatMs) % 64.0).toFloat())
        // The sun (or the moon) as drawn, mirrored in the water; brighter at night so the water shows.
        val sl2 = kotlin.math.sqrt(sunSky[0] * sunSky[0] + sunSky[1] * sunSky[1] + sunSky[2] * sunSky[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(land3d, "uSunSky"), sunSky[0] / sl2, sunSky[1] / sl2, sunSky[2] / sl2)
        val moonRed = if (look.world == com.sadurni.attacca.diorama.World.CASTLE) peak else 0f
        GLES30.glUniform3f(GLES30.glGetUniformLocation(land3d, "uSunColour"), sunColour[0] + (0.95f - sunColour[0]) * moonRed, sunColour[1] * (1 - 0.75f * moonRed), sunColour[2] * (1 - 0.8f * moonRed))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uNight"), night)
        // The traveller's light: a head torch, headlights, the submarine's lamp, a flaming torch.
        val lampAt = cast?.lamp
        if (lampAt != null && lampAt[6] > 0f) {
            GLES30.glUniform3f(GLES30.glGetUniformLocation(land3d, "uLampPos"), lampAt[0], lampAt[1], lampAt[2])
            GLES30.glUniform3f(GLES30.glGetUniformLocation(land3d, "uLampDir"), lampAt[3], lampAt[4], lampAt[5])
            GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uLamp"), lampAt[6])
        } else {
            GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uLamp"), 0f)
        }
        GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uZ"), tz)
        // Rings: x, z, seconds since, strength.
        val rings = FloatArray(16)
        for (k in 0 until 4) {
            rings[k * 4] = ripples[k * 4]
            rings[k * 4 + 1] = ripples[k * 4 + 1]
            rings[k * 4 + 2] = if (ripples[k * 4 + 3] > 0f) (nowMs - ripples[k * 4 + 2].toLong() - rippleBase) / 1000f else 99f
            rings[k * 4 + 3] = ripples[k * 4 + 3]
        }
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(land3d, "uRipple"), 4, rings, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uTerrain"), 1f)
        val modelLoc = GLES30.glGetUniformLocation(land3d, "uModel")
        val alphaLoc = GLES30.glGetUniformLocation(land3d, "uAlpha")
        GLES30.glUniform1f(alphaLoc, 1f)
        GLES30.glUniformMatrix4fv(modelLoc, 1, false, IDENTITY, 0)
        for (g in chunks.values) draw(g)
        if (stale.isNotEmpty()) {
            // Where the new land has no piece yet, the old one's (none once all are made).
            val shown = HashSet<Int>()
            var missing = false
            for (k in first..last) {
                if (k in chunks) continue
                missing = true
                val a = floor((k * LandMesher.CHUNK - staleDz) / LandMesher.CHUNK).toInt()
                val b = floor(((k + 1) * LandMesher.CHUNK - staleDz) / LandMesher.CHUNK).toInt()
                for (j in a..b) if (j in stale) shown += j
            }
            if (!missing) {
                for (g in stale.values) delete(g)
                stale.clear()
            } else if (shown.isNotEmpty()) {
                GLES30.glUniformMatrix4fv(modelLoc, 1, false, Mat4.model(0f, 0f, staleDz), 0)
                for (j in shown) draw(stale[j]!!)
                GLES30.glUniformMatrix4fv(modelLoc, 1, false, IDENTITY, 0)
            }
        }

        // The traveller, the vehicle, the dogs and the people at relays (no beat in their shape).
        GLES30.glUniform1f(GLES30.glGetUniformLocation(land3d, "uTerrain"), 0f)
        draws.clear()
        val dark = ex?.darkAt(tz, night) == true
        cast?.santa = christmas
        cast?.frame(tz, posMs / beatMs, feed.playing, nowMs, draws, dark, tunnel)
        // Who was left at the end of the song before: the vehicle stays where they got off.
        oldCast?.let { if (land.before != null) it.parked(oldDz, draws) else oldCast = null }
        // On the beat's wave, not sunk in it: the figures (and the traveller's eyes) rise with the land.
        com.sadurni.attacca.world.Swell.liftFigures(land, draws, 0, rings, pulse, tz, cast?.eye)
        ex?.let { it.festivity = feed.festive; it.kings = feed.kings; it.vikram = feed.vikram }
        ex?.frame(tz, ty, nowMs, feed.playing, peak, if (feed.forcePeak) tz else if (feed.peakMs >= 0) land.zAt(feed.peakMs) else null, look.palette.accent, feed.nextTitle != null, draws)
        // Clouds: soft heaps drifting high over the land, as many as the song's sky has.
        if (cloudFor !== skyOf) {
            cloudFor = skyOf
            cloudGpu.forEach(::delete)
            cloudGpu = if (skyOf.clouds > 0.05f && skyOf.world != com.sadurni.attacca.diorama.World.SPACE) List(3) { gpu(cloudMesh(it, skyOf)) } else emptyList()
        }
        val cloudCount = (skyClouds * 14).toInt().coerceAtMost(14)
        for (c in 0 until cloudCount) {
            val drift = (nowMs / 1000f * 1.2f + c * 37f) % 300f
            val cz = (tz - 40f) + ((c * 53) % 260) + 0f
            val cx = -150f + drift + (c * 71 % 60)
            val cy = 46f + (c * 13 % 18) - skyStorm * 12f
            GLES30.glUniformMatrix4fv(modelLoc, 1, false, Mat4.model(cx, cy, cz, c * 1.3f, s = 1f + (c % 3) * 0.4f), 0)
            draw(cloudGpu.getOrNull(c % 3) ?: continue)
        }
        for (d in draws) {
            val gpus = rigGpu.getOrPut(d.rig) { d.rig.bones.map { gpu(it.mesh) } }
            while (boneMatrices.size < d.rig.bones.size) boneMatrices += FloatArray(16)
            val m = boneMatrices.toTypedArray()
            d.rig.matrices(d.base, d.pose, m)
            for (j in d.rig.bones.indices) {
                val a = d.rig.bones[j].alpha * d.alpha
                if (a < 1f) {
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                    GLES30.glDepthMask(false)
                    GLES30.glUniform1f(alphaLoc, a)
                }
                GLES30.glUniformMatrix4fv(modelLoc, 1, false, m[j], 0)
                draw(gpus[j])
                if (a < 1f) {
                    GLES30.glUniform1f(alphaLoc, 1f)
                    GLES30.glDepthMask(true)
                    GLES30.glDisable(GLES30.GL_BLEND)
                }
            }
        }

        // The boards' pictures: the cover by the way, the next song's sign.
        ex?.let { drawBoards(it, viewProjection, haze, underSea, brightness) }
        GLES30.glUseProgram(land3d)

        // Rain or snow about the camera: small quads made here each frame and drawn as the land
        // is (glowing, so unlit), which every GPU draws alike.
        val rain = skyRain.coerceAtLeast(skyStorm)
        // Christmas days: snow falling (but in space).
        val snow = if (christmas) max(skySnow, 0.45f) else skySnow
        val bubbles = underSea
        if (bubbles || ((rain > 0.1f || snow > 0.1f) && skyOf.world != com.sadurni.attacca.diorama.World.SPACE && !underSea && tunnelDark < 0.5f)) {
            val isSnow = snow >= rain || bubbles
            val count = if (bubbles) 260 else (DROPS * max(rain, snow)).toInt().coerceIn(150, DROPS)
            val time = (nowMs % 100_000L) / 1000f
            // The camera's right and up, for quads facing it.
            val rx = view[0]; val ry = view[4]; val rz = view[8]
            val ux = view[1]; val uy = view[5]; val uz = view[9]
            val w = if (bubbles) 0.07f else if (isSnow) 0.09f else 0.025f
            val h = if (isSnow) 0.09f else 0.55f
            val cr = if (bubbles) 2.75f else if (isSnow) 3f else 2.66f
            val cg = if (bubbles) 2.95f else if (isSnow) 3f else 2.74f
            val cb = if (bubbles) 3f else if (isSnow) 3f else 2.92f
            var o = 0
            for (d in 0 until count) {
                val bx = drops[d * 3]; val by = drops[d * 3 + 1]; val bz = drops[d * 3 + 2]
                // Bubbles rise.
                val fall = if (bubbles) -1.6f else if (isSnow) 2.2f else 26f
                var px = bx * 60f + if (isSnow) sin(time * 0.7f + bz * 20f) * 1.2f else 0f
                var py = by * 30f - time * fall * (0.8f + 0.4f * bx)
                var pz = bz * 60f
                px = camera.eyeX + wrap(px - camera.eyeX + 30f, 60f) - 30f
                py = camera.eyeY + wrap(py - camera.eyeY + 15f, 30f) - 15f
                pz = camera.eyeZ + wrap(pz - camera.eyeZ + 30f, 60f) - 30f
                // Rain streaks stand upright; flakes face the camera.
                val vx = if (isSnow) ux * h else 0f
                val vy = if (isSnow) uy * h else h
                val vz = if (isSnow) uz * h else 0f
                val ax = rx * w; val ay = ry * w; val az = rz * w
                fun put(x: Float, y: Float, z: Float) {
                    dropData[o++] = x; dropData[o++] = y; dropData[o++] = z
                    dropData[o++] = cr; dropData[o++] = cg; dropData[o++] = cb
                    dropData[o++] = 0f; dropData[o++] = 1f; dropData[o++] = 0f
                }
                if (isSnow) {
                    // A round flake: six triangles about its middle.
                    for (k in 0 until 6) {
                        val a0 = k * 1.0472f
                        val a1 = a0 + 1.0472f
                        put(px, py, pz)
                        put(px + (rx * kotlin.math.cos(a0) + ux * sin(a0)) * w, py + (ry * kotlin.math.cos(a0) + uy * sin(a0)) * w, pz + (rz * kotlin.math.cos(a0) + uz * sin(a0)) * w)
                        put(px + (rx * kotlin.math.cos(a1) + ux * sin(a1)) * w, py + (ry * kotlin.math.cos(a1) + uy * sin(a1)) * w, pz + (rz * kotlin.math.cos(a1) + uz * sin(a1)) * w)
                    }
                } else {
                    put(px - ax - vx, py - ay - vy, pz - az - vz)
                    put(px + ax - vx, py + ay - vy, pz + az - vz)
                    put(px + ax + vx, py + ay + vy, pz + az + vz)
                    put(px - ax - vx, py - ay - vy, pz - az - vz)
                    put(px + ax + vx, py + ay + vy, pz + az + vz)
                    put(px - ax + vx, py - ay + vy, pz - az + vz)
                }
            }
            dropBuffer.clear(); dropBuffer.put(dropData, 0, o); dropBuffer.position(0)
            GLES30.glUniformMatrix4fv(modelLoc, 1, false, IDENTITY, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, weatherBuffer)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, o * 4, dropBuffer, GLES30.GL_STREAM_DRAW)
            draw(Gpu(weatherBuffer, o / Mesh.STRIDE))
        }
    }

    private fun wrap(v: Float, m: Float): Float = v - m * floor(v / m)

    /** The song's cover as a texture (0: none), made again when it changes. */
    private fun coverTexture(): Int {
        val cover = feed.cover
        if (cover !== coverOf) {
            coverOf = cover
            if (coverTex != 0) GLES30.glDeleteTextures(1, intArrayOf(coverTex), 0)
            coverTex = cover?.takeIf { !it.isRecycled }?.let { texture(it) } ?: 0
        }
        return coverTex
    }

    /** The cover and the next song's sign on their boards: textured quads, hazed as the land is. */
    private fun drawBoards(ex: com.sadurni.attacca.world.Extras, viewProjection: FloatArray, haze: FloatArray, underSea: Boolean, light: Float) {
        if (ex.billboards.isEmpty()) return
        // The pictures, made again when they change.
        coverTexture()
        val signText = feed.nextTitle?.let { (feed.nextPlace?.let { p -> "$p  " } ?: "") + it }
        if (signText != signOf) {
            signOf = signText
            if (signTex != 0) GLES30.glDeleteTextures(1, intArrayOf(signTex), 0)
            signTex = signText?.let { texture(signBitmap(it), recycle = true) } ?: 0
        }
        GLES30.glUseProgram(tex)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(tex, "uViewProjection"), 1, false, viewProjection, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(tex, "uEye"), camera.eyeX, camera.eyeY, camera.eyeZ)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(tex, "uFog"), haze[0], haze[1], haze[2])
        GLES30.glUniform2f(GLES30.glGetUniformLocation(tex, "uFogRange"), if (underSea) 12f else 45f, if (underSea) FAR * 0.62f else FAR)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(tex, "uLight"), (light * 1.1f).coerceIn(0.35f, 1.2f))
        GLES30.glUniform1i(GLES30.glGetUniformLocation(tex, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        for (b in ex.billboards) {
            val t = if (b.kind == com.sadurni.attacca.world.Billboard.NEXT) signTex else coverTex
            if (t == 0) continue
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            val c = b.corners
            var o = 0
            fun put(k: Int, u: Float, v: Float) {
                texData[o++] = c[k * 3]; texData[o++] = c[k * 3 + 1]; texData[o++] = c[k * 3 + 2]; texData[o++] = u; texData[o++] = v
            }
            // Bottom left, bottom right, top right, top left (as seen from the front).
            put(0, 0f, 1f); put(1, 1f, 1f); put(2, 1f, 0f)
            put(0, 0f, 1f); put(2, 1f, 0f); put(3, 0f, 0f)
            texFloats.clear(); texFloats.put(texData, 0, o); texFloats.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, texBuffer)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, o * 4, texFloats, GLES30.GL_STREAM_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glDisableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun texture(b: android.graphics.Bitmap, recycle: Boolean = false): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        // Not too big for any GPU.
        val small = if (b.width > 512 || b.height > 512) android.graphics.Bitmap.createScaledBitmap(b, 512, 512, true) else b
        android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, small, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        if (small !== b) small.recycle()
        if (recycle) b.recycle()
        return ids[0]
    }

    /** The sign's face: "Next" over the next song's place and title, painted on wood. */
    private fun signBitmap(text: String): android.graphics.Bitmap {
        val w = 512
        val h = 206
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(0xFF8A5A34.toInt())
        val grain = android.graphics.Paint().apply { color = 0xFF7A4E2C.toInt(); strokeWidth = 3f }
        for (y in 12 until h step 22) c.drawLine(0f, y.toFloat(), w.toFloat(), y + 6f, grain)
        val edge = android.graphics.Paint().apply { color = 0xFF5A3A22.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 12f }
        c.drawRect(6f, 6f, w - 6f, h - 6f, edge)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFF4DC.toInt()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
            setShadowLayer(4f, 2f, 2f, 0xFF3A2414.toInt())
        }
        p.textSize = 40f
        c.drawText("\u2192", w / 2f, 62f, p)
        // As large as fits the board's width.
        var size = 64f
        p.textSize = size
        while (p.measureText(text) > w - 40 && size > 26f) {
            size -= 2f
            p.textSize = size
        }
        val shown = if (p.measureText(text) > w - 40) android.text.TextUtils.ellipsize(text, android.text.TextPaint(p), (w - 40).toFloat(), android.text.TextUtils.TruncateAt.END).toString() else text
        c.drawText(shown, w / 2f, 140f, p)
        return b
    }

    /** Each drop's place in its box (0 … 1, three a drop), the quads made of them and their buffer. */
    private val drops = java.util.Random(7).let { r -> FloatArray(DROPS * 3) { r.nextFloat() } }
    private val dropData = FloatArray(DROPS * 18 * Mesh.STRIDE)
    private val dropBuffer = ByteBuffer.allocateDirect(DROPS * 18 * Mesh.STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** A cloud: soft white heaps, a little grey underneath when the sky is stormy. */
    private fun cloudMesh(k: Int, look: SongLook): Mesh {
        val out = com.sadurni.attacca.world.FloatList(8192)
        val white = if (look.storm > 0.3f) 0xFFA8A8B4.toInt() else if (look.night > 0.5f) 0xFF8A8CA8.toInt() else 0xFFF8F8FC.toInt()
        val r = java.util.Random(k * 31L + 5)
        for (i in 0 until 6) {
            val x = (r.nextFloat() - 0.5f) * 16f
            val z = (r.nextFloat() - 0.5f) * 8f
            val w = 5f + r.nextFloat() * 5f
            out.blob(x - w, -2f, z - w * 0.6f, x + w, 2.5f + r.nextFloat() * 2f, z + w * 0.6f, white, 12, 7)
        }
        return out.mesh()
    }

    private var rippleBase = 0L

    private fun ripple(z: Float, nowMs: Long, strength: Float) {
        val land = land ?: return
        if (rippleBase == 0L) rippleBase = nowMs
        val k = nextRipple
        nextRipple = (nextRipple + 1) % 4
        ripples[k * 4] = land.centerAt(z)
        ripples[k * 4 + 1] = z
        // Kept as ms since the first ring, so a float holds it exactly enough.
        ripples[k * 4 + 2] = (nowMs - rippleBase).toFloat()
        ripples[k * 4 + 3] = strength
        // A strong beat may bring a move.
        cast?.beat(strength, land.intensityAt(z), nowMs, z, Math.random().toFloat())
    }

    private fun draw(g: Gpu) {
        if (g.vertices == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.buffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, Mesh.STRIDE * 4, 0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, Mesh.STRIDE * 4, 12)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, Mesh.STRIDE * 4, 24)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, g.vertices)
    }

    private fun gpu(mesh: Mesh): Gpu = Gpu(upload(mesh.data), mesh.vertices)

    private fun upload(data: FloatArray): Int {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        val fb = ByteBuffer.allocateDirect(max(data.size, 1) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, fb, GLES30.GL_STATIC_DRAW)
        return ids[0]
    }

    private fun delete(g: Gpu) {
        GLES30.glDeleteBuffers(1, intArrayOf(g.buffer), 0)
    }

    private fun transform(m: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
        m[3] * x + m[7] * y + m[11] * z + m[15],
    )

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("AttaccaWorld", "shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glBindAttribLocation(p, 1, "aColor")
        GLES30.glBindAttribLocation(p, 2, "aNormal")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) android.util.Log.e("AttaccaWorld", "program: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        /** How far ahead the land is drawn (blocks); the haze hides its end. */
        const val FAR = 150f
        private val IDENTITY = Mat4.identity()
        private val WARM = floatArrayOf(1f, 0.86f, 0.62f)
        private val COOL = floatArrayOf(0.62f, 0.72f, 0.95f)


        private const val LAND_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aColor;
layout(location = 2) in vec3 aNormal;
uniform mat4 uViewProjection;
uniform mat4 uModel;
uniform vec3 uEye;
uniform float uPulse;
uniform float uZ;
uniform float uTerrain;
uniform vec4 uRipple[4];
uniform float uWaveTime;
uniform float uWave;
uniform float uBeat;
out vec3 vColor;
out float vWater;
out float vDist;
out vec3 vWorld;
out vec3 vNormal;
out float vSwell;
void main() {
    vec4 world = uModel * vec4(aPos, 1.0);
    // Plants sway with the music, the higher up the more (their normals' length says how high).
    float nl = length(aNormal);
    if (nl > 0.15 && nl < 0.55 && uTerrain > 0.5) {
        float hgt = (0.5 - nl) / 0.3;
        float ph = world.x * 0.31 + world.z * 0.17;
        float amp = hgt * hgt * (0.07 + 0.32 * uPulse);
        world.x += sin(uBeat * 3.1416 + ph) * amp;
        world.z += cos(uBeat * 3.1416 * 0.5 + ph) * amp * 0.6;
    }
    // Flames (warm glowing colours: torches, lava) lick and waver.
    if (aColor.r > 2.95 && aColor.g > 2.5 && aColor.g < 2.78 && aColor.b < 2.4) {
        float fl = uWaveTime;
        float ph = world.x * 7.1 + world.z * 5.3;
        world.x += sin(fl * 11.0 + world.y * 14.0 + ph) * 0.035;
        world.z += cos(fl * 9.0 + world.y * 12.0 + ph) * 0.03;
        world.y += (0.5 + 0.5 * sin(fl * 15.0 + ph)) * 0.05;
    }
    // The beat lifts the land near the traveller, the higher the more.
    // (pow with a negative base is undefined on some GPUs: square by hand.)
    float dz = (world.z - uZ) / 26.0;
    float near = exp(-dz * dz) * uTerrain;
    world.y += world.y * uPulse * 0.07 * near * step(3.0, world.y);
    // The beat's rings: a swell running out over the land, fading as it goes.
    float swell = 0.0;
    for (int k = 0; k < 4; k++) {
        vec4 r = uRipple[k];
        float t = r.z;
        if (t < 3.0) {
            float d = distance(world.xz, r.xy);
            float front = d - t * 22.0;
            swell += r.w * exp(-front * front / 6.0) * exp(-t * 1.4);
        }
    }
    world.y += swell * 0.55 * uTerrain * (aNormal.y > 1.5 ? 0.6 : 1.0);
    vSwell = swell * uTerrain;
    vColor = aColor;
    vWorld = world.xyz;
    vNormal = mat3(uModel) * aNormal;
    vWater = 0.0;
    if (aNormal.y > 1.5) {
        // Water: small waves always, rolling swells as strong as the beat.
        vWater = 1.0;
        float t = uWaveTime;
        float a1 = sin(world.x * 0.35 + t * 1.3) * 0.09 + sin(world.z * 0.5 - t * 1.7) * 0.07;
        float a2 = sin(world.z * 0.12 - t * 2.2 + world.x * 0.05) * (0.12 + uWave * 0.9);
        world.y += a1 + a2;
        float wdx = cos(world.x * 0.35 + t * 1.3) * 0.0315 + cos(world.z * 0.12 - t * 2.2 + world.x * 0.05) * 0.05 * (0.12 + uWave * 0.9);
        float wdz = cos(world.z * 0.5 - t * 1.7) * 0.035 + cos(world.z * 0.12 - t * 2.2 + world.x * 0.05) * 0.12 * (0.12 + uWave * 0.9);
        vNormal = normalize(vec3(-wdx, 1.0, -wdz));
        vWorld = world.xyz;
        // The crests catch more light.
        vSwell = max(a2, 0.0) * 0.6;
    }
    vDist = distance(world.xyz, uEye);
    gl_Position = uViewProjection * world;
}
"""
        private const val LAND_FS = """#version 300 es
precision highp float;
in vec3 vColor;
in float vDist;
in vec3 vWorld;
in vec3 vNormal;
in float vSwell;
in float vWater;
uniform vec3 uFog;
uniform vec2 uFogRange;
uniform float uLight;
uniform float uAlpha;
uniform vec3 uSunDir;
uniform vec3 uSunLight;
uniform vec3 uSkyLight;
uniform vec3 uEye;
uniform vec3 uSkyTop;
uniform float uWaterY;
uniform float uTime;
uniform vec3 uSunSky;
uniform vec3 uSunColour;
uniform float uNight;
uniform float uPulse;
uniform vec3 uLampPos;
uniform vec3 uLampDir;
uniform float uLamp;
out vec4 color;
void main() {
    // Glowing colours (2 above the others) shine as they are.
    if (vColor.r > 1.5 || vColor.g > 1.5 || vColor.b > 1.5) {
        float fg = smoothstep(uFogRange.x, uFogRange.y, vDist);
        vec3 gc = vColor - 2.0;
        // Flames flicker (torches, lava); lights glow up a little on the beat.
        if (gc.r > 0.95 && gc.g > 0.5 && gc.g < 0.78 && gc.b < 0.4) {
            // Brightness and colour waver, now and then a flare: orange to yellow and back.
            float ph = vWorld.x * 3.1 + vWorld.z * 1.7;
            float n = 0.5 + 0.25 * sin(uTime * 13.0 + ph) + 0.15 * sin(uTime * 23.7 + ph * 1.9) + 0.1 * sin(uTime * 41.0 + ph * 0.7);
            float flare = pow(max(sin(uTime * 2.3 + ph * 0.4), 0.0), 12.0) * 0.35;
            gc = mix(gc * 0.7, vec3(1.0, 0.86, 0.45), clamp(n * 0.6 + flare, 0.0, 1.0)) * (0.75 + 0.45 * n + flare);
        }
        gc *= 1.0 + 0.15 * uPulse;
        color = vec4(mix(gc, uFog, fg * 0.7), uAlpha);
        return;
    }
    // Warm sunlight on what faces the sun, the sky's cool light in the shade.
    vec3 n = normalize(vNormal);
    float sun = max(dot(n, uSunDir), 0.0);
    float up = n.y * 0.5 + 0.5;
    vec3 lit = vColor * (uSkyLight * (0.32 + 0.3 * up) + uSunLight * sun * 1.0);
    // A touch more colour, as on a clear warm day.
    float grey = dot(lit, vec3(0.3, 0.59, 0.11));
    lit = mix(vec3(grey), lit, 1.18);
    // Water: the sky mirrored (more of it the flatter it is seen), small waves, the sun's glints.
    if (vWater > 0.5 && uEye.y < uWaterY) {
        // Seen from under the water: its surface bright with the light above, rippling.
        vec3 v = normalize(uEye - vWorld);
        float ripple = 0.5 + 0.5 * sin(vWorld.x * 0.9 + uTime * 1.4) * sin(vWorld.z * 0.7 - uTime * 1.1);
        lit = mix(vec3(0.18, 0.5, 0.62), vec3(0.62, 0.86, 0.92), 0.35 + 0.4 * ripple) * (1.0 - 0.6 * uNight);
    } else if (vWater > 0.5) {
        vec3 v = normalize(uEye - vWorld);
        vec3 wn = normalize(vec3(sin(vWorld.x * 1.7 + uTime * 1.3) * 0.05 + sin(vWorld.z * 0.9 - uTime) * 0.04, 1.0,
                                 cos(vWorld.z * 1.9 + uTime * 1.1) * 0.05 + sin(vWorld.x * 0.7 + uTime * 0.8) * 0.04));
        float fres = pow(1.0 - max(dot(v, wn), 0.0), 3.0);
        vec3 deep = vColor * (uSkyLight * 0.5 + uSunLight * 0.35);
        vec3 h = normalize(v + uSunDir);
        float glint = pow(max(dot(wn, h), 0.0), 90.0);
        lit = mix(deep, uSkyTop * 0.9 + uSunLight * 0.1, 0.25 + 0.6 * fres) + uSunLight * glint * 1.4;
        // The sun or the moon mirrored: a path of light on the water towards it, glittering.
        vec3 r = reflect(-v, normalize(vec3(wn.x * 2.2, 1.0, wn.z * 2.2)));
        float m = max(dot(r, uSunSky), 0.0);
        float path = pow(m, 260.0) * 3.0 + pow(m, 40.0) * 0.35;
        lit += uSunColour * path * (1.0 + 1.6 * uNight);
        // At night the water keeps a little of the sky's light, so it shows.
        lit += uSkyTop * 0.18 * uNight * (0.4 + fres);
    }
    // The traveller's light: a beam (head torch, headlights, the submarine's lamp) or a torch's glow all round.
    if (uLamp > 0.5) {
        vec3 l = uLampPos - vWorld;
        float d = length(l);
        vec3 ln = l / max(d, 0.001);
        float facing = 0.35 + 0.65 * max(dot(n, ln), 0.0);
        float reach = uLamp > 1.5 ? (0.85 + 0.1 * sin(uTime * 13.0) + 0.05 * sin(uTime * 23.0)) / (1.0 + d * d * 0.09) : 1.0 / (1.0 + d * d * 0.012);
        float cone = uLamp > 1.5 ? 1.0 : smoothstep(0.78, 0.93, dot(-ln, uLampDir));
        vec3 tint = uLamp > 1.5 ? vec3(1.0, 0.68, 0.36) : vec3(1.0, 0.95, 0.82);
        lit += vColor * tint * facing * reach * cone * 1.6;
    }
    float f = smoothstep(uFogRange.x, uFogRange.y, vDist);
    f = f * f * (3.0 - 2.0 * f);
    // A little haze already near: the air of a warm day.
    f = max(f, clamp(vDist / 600.0, 0.0, 0.06));
    // The ring's crest catches the light.
    lit += uSunLight * clamp(vSwell, 0.0, 1.0) * 0.22;
    // A glass's shine: brighter where it faces away from the eye.
    if (uAlpha < 1.0) lit += vec3(0.6) * pow(1.0 - abs(dot(n, normalize(uEye - vWorld))), 2.0);
    color = vec4(mix(lit * uLight, uFog, f), uAlpha);
}
"""
        private const val TEX_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uViewProjection;
uniform vec3 uEye;
out vec2 vUv;
out float vDist;
void main() {
    vUv = aUv;
    vDist = distance(aPos, uEye);
    gl_Position = uViewProjection * vec4(aPos, 1.0);
}
"""
        private const val TEX_FS = """#version 300 es
precision highp float;
in vec2 vUv;
in float vDist;
uniform sampler2D uTex;
uniform vec3 uFog;
uniform vec2 uFogRange;
uniform float uLight;
out vec4 color;
void main() {
    vec3 c = texture(uTex, vUv).rgb * uLight;
    float f = smoothstep(uFogRange.x, uFogRange.y, vDist);
    color = vec4(mix(c, uFog, f * f * (3.0 - 2.0 * f)), 1.0);
}
"""
        /** Raindrops or snowflakes at most. */
        private const val DROPS = 900

        private const val SKY_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vAt;
void main() {
    vAt = aPos;
    gl_Position = vec4(aPos, 0.999, 1.0);
}
"""
        private const val SKY_FS = """#version 300 es
precision highp float;
in vec2 vAt;
uniform vec3 uTop;
uniform vec3 uBottom;
uniform vec3 uSun;
uniform vec3 uSunAt;
uniform float uHorizon;
uniform float uAspect;
uniform float uNight;
uniform float uFlash;
uniform float uYaw;
uniform float uPeak;
uniform float uPeakKind;
uniform float uDragonX;
uniform float uTime;
uniform float uMoonRed;
uniform mat4 uInverse;
uniform float uPump;
uniform float uRecord;
uniform float uStar;
uniform sampler2D uCover;
uniform float uHasCover;
out vec4 color;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float hash1(float n) { return fract(sin(n * 91.7) * 43758.5453); }
void main() {
    float t = clamp((vAt.y - uHorizon) / 1.4, 0.0, 1.0);
    vec3 c = mix(uBottom, uTop, pow(t, 0.7));
    // Stars at night, fixed to the sky (they turn with the camera).
    if (uNight > 0.05 && t > 0.05) {
        vec2 sp = vec2(vAt.x * uAspect * 0.5 + uYaw * 1.6, vAt.y - uHorizon) * 60.0;
        vec2 cell = floor(sp);
        float h = hash(cell);
        if (h > 0.985) {
            vec2 f = fract(sp) - vec2(hash(cell + 3.1), hash(cell + 7.7)) * 0.6 - 0.2;
            float star = (1.0 - smoothstep(0.0, 0.12, length(f))) * (0.6 + 0.4 * sin(h * 300.0));
            c += vec3(star) * uNight * t;
        }
    }
    c += vec3(0.75, 0.78, 0.95) * uFlash;
    // Where in the world this point of the sky looks (for what stays put as the camera turns).
    vec4 far4 = uInverse * vec4(vAt, 1.0, 1.0);
    vec4 near4 = uInverse * vec4(vAt, -1.0, 1.0);
    vec3 dir = normalize(far4.xyz / far4.w - near4.xyz / near4.w);
    if (uPeak > 0.01 && uPeakKind > 3.5 && uPeakKind < 4.5) {
        // A rainbow standing over the land ahead, fixed in the sky: seen as the camera comes round to it.
        float a = acos(clamp(dot(dir, normalize(vec3(0.25, -0.2, 1.0))), -1.0, 1.0));
        float band = (a - 0.6) / 0.075;
        if (band > 0.0 && band < 1.0 && dir.y > -0.03) {
            vec3 rb = 0.5 + 0.5 * cos(6.2831 * (band * 0.8 + vec3(0.0, 0.33, 0.67)));
            c = mix(c, rb, 0.55 * uPeak * smoothstep(-0.03, 0.04, dir.y) * sin(band * 3.1416));
        }
        c += vec3(0.08) * uPeak * smoothstep(0.0, 0.2, 0.6 - a) * step(a, 0.6);
    } else if (uPeak > 0.01 && t > 0.02 && uPeakKind > -0.5) {
        // Placed on the sky in sight: v from 0 at the horizon to 1 at the screen's top.
        float v = clamp((vAt.y - uHorizon) / max(1.0 - uHorizon, 0.2), 0.0, 1.0);
        vec2 q = vec2(vAt.x * uAspect, v);
        if (uPeakKind < 0.5) {
            // Fireworks: bursts of sparks, a new one every half second, each opening and fading.
            for (int k = 0; k < 4; k++) {
                float slot = floor(uTime * 2.0) - float(k);
                float age = uTime * 2.0 - slot;
                vec2 at = vec2((hash1(slot) - 0.5) * 1.4 * uAspect, 0.3 + hash1(slot + 3.0) * 0.45);
                vec3 tint = 0.5 + 0.5 * cos(6.2831 * (hash1(slot + 7.0) + vec3(0.0, 0.33, 0.67)));
                vec2 d = q - at;
                float r = length(d);
                float ring = (1.0 - smoothstep(0.0, 0.03, abs(r - age * 0.09))) * step(r, 0.36);
                float a = atan(d.y, d.x);
                float spark = smoothstep(0.75, 1.0, cos(a * 14.0 + slot));
                c += tint * ring * spark * (1.0 - age / 4.0) * 1.6 * uPeak;
            }
        } else if (uPeakKind < 1.5) {
            // Northern lights: green curtains with a violet hem, swaying.
            float wave = sin(q.x * 3.0 + uTime * 0.6) * 0.08 + sin(q.x * 7.3 - uTime * 0.9) * 0.04;
            float band = smoothstep(0.0, 0.2, v - 0.15 - wave) * (1.0 - smoothstep(0.55, 0.95, v - wave));
            float rays = 0.6 + 0.4 * sin(q.x * 40.0 + uTime * 1.5 + sin(q.x * 9.0) * 2.0);
            vec3 tint = mix(vec3(0.2, 1.0, 0.55), vec3(0.65, 0.35, 1.0), smoothstep(0.5, 0.85, v - wave));
            c += tint * band * rays * 0.9 * uPeak;
        } else if (uPeakKind < 2.5) {
            // A great red moon rising, its glow over the sky.
            vec2 d = q - vec2(0.3 * uAspect, 0.5);
            float r = length(d);
            c = mix(c, vec3(0.85, 0.18, 0.1), (1.0 - smoothstep(0.15, 0.17, r)) * uPeak);
            c += vec3(0.6, 0.1, 0.05) * exp(-r * 3.0) * 0.5 * uPeak;
        } else if (uPeakKind > 4.5 && uPeakKind < 5.5) {
            // A dragon flying across, its body waving.
            float headX = uDragonX;
            for (int k = 0; k < 18; k++) {
                float bx = headX + float(k) * 0.07;
                float by = 0.55 + sin(uTime * 2.2 + float(k) * 0.45) * 0.1;
                float r = 0.045 * (1.0 - float(k) / 26.0);
                vec3 tint = mod(float(k), 3.0) < 1.0 ? vec3(1.0, 0.8, 0.25) : vec3(0.85, 0.15, 0.12);
                c = mix(c, tint, (1.0 - smoothstep(r * 0.7, r, length(q - vec2(bx, by)))) * uPeak);
            }
            // Its head in front: red with a golden snout, horns, an eye and a waving whisker.
            float hy = 0.55 + sin(uTime * 2.2 - 0.25) * 0.1;
            vec2 h0 = vec2(headX - 0.035, hy);
            vec2 hornA = vec2(headX - 0.01, hy + 0.028);
            for (int s = 0; s < 2; s++) {
                vec2 hornB = hornA + vec2(0.05 + float(s) * 0.02, 0.05 - float(s) * 0.012);
                vec2 pa = q - hornA, ba = hornB - hornA;
                float d = length(pa - ba * clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0));
                c = mix(c, vec3(1.0, 0.82, 0.3), (1.0 - smoothstep(0.004, 0.007, d)) * uPeak);
            }
            vec2 wA = vec2(headX - 0.1, hy - 0.018);
            vec2 wB = wA + vec2(-0.05, -0.03 + sin(uTime * 3.0) * 0.02);
            vec2 wa = q - wA, wb = wB - wA;
            float wd = length(wa - wb * clamp(dot(wa, wb) / dot(wb, wb), 0.0, 1.0));
            c = mix(c, vec3(1.0, 0.8, 0.25), (1.0 - smoothstep(0.002, 0.004, wd)) * uPeak);
            c = mix(c, vec3(0.9, 0.18, 0.12), (1.0 - smoothstep(0.85, 1.0, length((q - h0) / vec2(0.062, 0.045)))) * uPeak);
            c = mix(c, vec3(1.0, 0.8, 0.25), (1.0 - smoothstep(0.85, 1.0, length((q - vec2(headX - 0.09, hy - 0.01)) / vec2(0.042, 0.026)))) * uPeak);
            c = mix(c, vec3(0.2, 0.05, 0.03), (1.0 - smoothstep(0.003, 0.006, length(q - vec2(headX - 0.115, hy - 0.002)))) * uPeak);
            vec2 eye = vec2(headX - 0.05, hy + 0.017);
            c = mix(c, vec3(1.0, 0.97, 0.8), (1.0 - smoothstep(0.008, 0.011, length(q - eye))) * uPeak);
            c = mix(c, vec3(0.05), (1.0 - smoothstep(0.004, 0.006, length(q - eye - vec2(-0.003, 0.0)))) * uPeak);
        } else if (uPeakKind > 5.5 && uPeakKind < 6.5) {
            // Clouds of coloured powder and little lamps rising.
            for (int k = 0; k < 6; k++) {
                float age = fract(uTime * 0.6 + float(k) / 6.0);
                float slot = floor(uTime * 0.6 + float(k) / 6.0);
                vec2 at = vec2((hash1(slot + float(k)) - 0.5) * 1.6 * uAspect, 0.3 + hash1(slot * 2.0 + float(k)) * 0.4);
                vec3 tint = 0.55 + 0.45 * cos(6.2831 * (hash1(slot + float(k) * 3.0) + vec3(0.0, 0.33, 0.67)));
                c += tint * (1.0 - smoothstep(0.0, 0.08 + 0.2 * age, length(q - at))) * (1.0 - age) * 0.7 * uPeak;
            }
        } else if (uPeakKind > 6.5 && uPeakKind < 7.5) {
            // A rainbow over the river.
            float r = length(q - vec2(0.1 * uAspect, -0.2));
            float band = (r - 0.55) / 0.18;
            if (band > 0.0 && band < 1.0) {
                vec3 rb = 0.5 + 0.5 * cos(6.2831 * (band * 0.8 + vec3(0.0, 0.33, 0.67)));
                c = mix(c, rb, 0.5 * uPeak);
            }
        } else if (uPeakKind > 7.5 && uPeakKind < 8.5) {
            // A red sunset, an eagle wheeling.
            c = mix(c, vec3(1.0, 0.45, 0.2), 0.35 * uPeak * (1.0 - v));
            vec2 e = vec2(cos(uTime * 0.5) * 0.5 * uAspect, 0.6 + sin(uTime * 0.5) * 0.08);
            vec2 d = q - e;
            float flap = sin(uTime * 3.0) * 0.03;
            float wing = (1.0 - smoothstep(0.0, 0.012, abs(d.y - abs(d.x) * 0.35 + flap))) * step(abs(d.x), 0.12);
            c = mix(c, vec3(0.15, 0.1, 0.08), wing * uPeak);
        } else if (uPeakKind > 8.5 && uPeakKind < 9.5) {
            // Pink fireworks, petals falling.
            for (int k = 0; k < 3; k++) {
                float slot = floor(uTime * 1.2) - float(k);
                float age = uTime * 1.2 - slot;
                vec2 at = vec2((hash1(slot) - 0.5) * 1.4 * uAspect, 0.35 + hash1(slot + 3.0) * 0.4);
                float r = length(q - at);
                float ring = (1.0 - smoothstep(0.0, 0.025, abs(r - age * 0.1))) * step(r, 0.33);
                c += vec3(1.0, 0.6, 0.75) * ring * (1.0 - age / 3.0) * 1.4 * uPeak;
            }
        } else if (uPeakKind > 9.5 && uPeakKind < 10.5) {
            // A whale passing above, its song in rings of light.
            vec2 wc = vec2((1.3 - fract(uTime / 14.0) * 2.6) * uAspect, 0.55 + sin(uTime * 0.6) * 0.05);
            vec2 d = (q - wc) / vec2(0.32, 0.09);
            float whale = 1.0 - smoothstep(0.85, 1.0, length(d));
            c = mix(c, vec3(0.16, 0.25, 0.36), whale * 0.85 * uPeak);
            float ring = fract(uTime * 0.7);
            c += vec3(0.75, 0.95, 1.0) * (1.0 - smoothstep(0.0, 0.01, abs(length(q - wc) - ring * 0.6))) * (1.0 - ring) * 0.6 * uPeak;
        } else if (uPeakKind > 10.5 && uPeakKind < 11.5) {
            // The volcano erupting: a red glow, burning stones flying up and falling.
            vec2 top = vec2(0.3 * uAspect, 0.05);
            c += vec3(1.0, 0.35, 0.1) * exp(-length(q - top) * 3.0) * 0.8 * uPeak;
            for (int k = 0; k < 12; k++) {
                float age = fract(uTime * 0.6 + hash1(float(k)));
                vec2 at = top + vec2((hash1(float(k) * 7.0) - 0.5) * 0.9 * age, 0.9 * age - 0.8 * age * age);
                c += vec3(1.0, 0.6, 0.2) * (1.0 - smoothstep(0.0, 0.018, length(q - at))) * (1.0 - age * 0.5) * uPeak;
            }
        } else if (uPeakKind > 2.5 && uPeakKind < 3.5) {
            // Shooting stars crossing the sky.
            for (int k = 0; k < 5; k++) {
                float slot = floor(uTime * 1.5 + float(k) * 0.37);
                float age = fract(uTime * 1.5 + float(k) * 0.37);
                vec2 from = vec2((hash1(slot + float(k)) - 0.3) * 2.0 * uAspect, 0.55 + hash1(slot * 1.3) * 0.45);
                vec2 dir = normalize(vec2(-1.0, -0.45));
                vec2 head = from + dir * age * 1.2;
                vec2 d = q - head;
                float along = dot(d, -dir);
                float across = abs(dot(d, vec2(-dir.y, dir.x)));
                float streak = (1.0 - smoothstep(0.0, 0.006, across)) * (1.0 - smoothstep(0.0, 0.25, along)) * step(0.0, along);
                c += vec3(0.85, 0.95, 1.0) * streak * (1.0 - age) * 1.5 * uPeak;
            }
        }
    }
    if (uStar > 0.01) {
        // The star the Kings follow, fixed in the sky high over the way ahead, its tail trailing down.
        vec3 S = normalize(vec3(0.3, 0.2, 1.0));
        float facing = dot(dir, S);
        if (facing > 0.9) {
            vec3 R = normalize(vec3(-S.z, 0.0, S.x));
            vec3 U = cross(R, S);
            vec2 p = vec2(dot(dir, R), dot(dir, U));
            float l = length(p);
            float tw = 0.85 + 0.15 * sin(uTime * 3.0);
            float star = exp(-l * 90.0) * 0.7 + (1.0 - smoothstep(0.004, 0.007, l));
            star += (1.0 - smoothstep(0.0, 0.0025, abs(p.x))) * (1.0 - smoothstep(0.0, 0.05, abs(p.y))) * tw;
            star += (1.0 - smoothstep(0.0, 0.0025, abs(p.y))) * (1.0 - smoothstep(0.0, 0.05, abs(p.x))) * tw;
            vec2 q2 = vec2(p.x + p.y, p.x - p.y) * 0.7071;
            star += (1.0 - smoothstep(0.0, 0.002, abs(q2.x))) * (1.0 - smoothstep(0.0, 0.022, abs(q2.y))) * 0.6;
            star += (1.0 - smoothstep(0.0, 0.002, abs(q2.y))) * (1.0 - smoothstep(0.0, 0.022, abs(q2.x))) * 0.6;
            vec2 td = normalize(vec2(0.85, -0.5));
            float along = dot(p, td);
            float across = abs(p.x * td.y - p.y * td.x);
            float tail = step(0.0, along) * (1.0 - smoothstep(0.0, 0.28, along)) * (1.0 - smoothstep(0.0, 0.003 + along * 0.05, across));
            c += vec3(1.0, 0.95, 0.75) * (star + tail * 0.5 * (1.0 - along / 0.28)) * uStar;
        }
    }
    vec2 d = vec2((vAt.x - uSunAt.x) * uAspect, vAt.y - uSunAt.y);
    float r = length(d);
    // At the castles' peak the moon grows great and red, where it is.
    float size = (0.06 + 0.08 * uMoonRed) * (1.0 + 0.22 * uPump);
    float disc = (1.0 - smoothstep(size, size + 0.015, r));
    float glow = exp(-r * 4.0) * 0.55 + exp(-r * 1.2) * 0.18;
    vec3 sunC = mix(uSun, vec3(0.9, 0.2, 0.12), uMoonRed);
    c += sunC * glow * uSunAt.z * (1.0 + uMoonRed) * (1.0 - 0.6 * uRecord);
    c = mix(c, sunC * (1.0 + 0.4 * (1.0 - uMoonRed)), clamp(disc * uSunAt.z * (1.0 - uRecord), 0.0, 1.0));
    if (uRecord > 0.01 && uSunAt.z > 0.0) {
        // The sun as a record turning in the sky: black grooves catching the light, the cover on its label, notes falling.
        float big = 0.16 * (1.0 + 0.08 * uPump);
        float spin = uTime * 3.4;
        float ang = atan(d.y, d.x) + spin;
        vec3 vinyl = vec3(0.05, 0.05, 0.06) + vec3(0.06) * (0.5 + 0.5 * sin(r * 520.0));
        vinyl += vec3(0.22) * pow(max(0.0, cos(ang * 2.0)), 18.0) * smoothstep(big * 0.4, big * 0.9, r);
        vec3 label = sunC * 0.9 + vec3(0.1);
        if (uHasCover > 0.5) {
            float la = atan(d.y, d.x) - spin;
            vec2 uv = vec2(cos(la), sin(la)) * r / (big * 0.36) * 0.5 + 0.5;
            label = texture(uCover, vec2(uv.x, 1.0 - uv.y)).rgb;
        }
        vec3 rec = r < big * 0.36 ? label : vinyl;
        rec = mix(rec, vec3(0.02), 1.0 - smoothstep(big * 0.03, big * 0.045, r));
        c = mix(c, rec, (1.0 - smoothstep(big, big + 0.008, r)) * uRecord);
        for (int k = 0; k < 6; k++) {
            float age = fract(uTime * 0.35 + float(k) / 6.0);
            vec2 at = vec2((hash1(floor(uTime * 0.35 + float(k) / 6.0) + float(k)) - 0.5) * big * 3.0, -big - age * 0.5);
            vec2 nd = d - at;
            float head = 1.0 - smoothstep(0.012, 0.016, length(nd / vec2(1.25, 1.0)));
            float stem = (1.0 - smoothstep(0.002, 0.004, abs(nd.x - 0.014))) * step(0.0, nd.y) * step(nd.y, 0.05);
            float flag = (1.0 - smoothstep(0.003, 0.005, abs(nd.y - 0.05 + (nd.x - 0.014) * 0.6))) * step(0.014, nd.x) * step(nd.x, 0.032);
            c = mix(c, sunC * 1.1 + vec3(0.15), max(head, max(stem, flag)) * (1.0 - age) * uRecord);
        }
    }
    color = vec4(c, 1.0);
}
"""
    }
}
