package com.dboycht.keyforge.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.provider.Settings
import java.io.File
import java.util.Random
import kotlin.math.exp
import kotlin.math.sin

/**
 * The click a key makes, when the user has not switched sound off.
 *
 * ## Why this is not just `AudioManager.playSoundEffect(CLICK)`
 *
 * The system's own touch sound is the right sound *when the user allows touch sounds*: it is the one
 * their keyboard and dialer use, and its volume is the system's business. But it is a no-op when the
 * system's "touch sounds" setting is off, and that is not rare - measured on the author's phone
 * (ColorOS / Android 16) it is off by default, so a key sound wired only to it would be a switch that
 * does nothing at all.
 *
 * So it is two-tiered: **the system click when the system allows it, otherwise our own** short
 * synthesised tick. Either way the in-app switch means what it says, and the settings hint says which
 * sound is being heard.
 *
 * The synthesised sample is generated in memory (see [ClickSample]) and cached as a small WAV,
 * because `SoundPool` needs a file or a descriptor. Nothing extra ships in the APK for it.
 */
internal class KeyClickPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private var engine: SoundPool? = null
    private var soundId = 0
    private var loadAttempted = false

    /** Whether the system is currently willing to play touch sounds (cached, see [systemClickEnabled]). */
    private var systemClickCached: Boolean = false
    private var systemClickReadAt: Long = 0L

    /** Plays one short click. Does nothing when disabled by the user, or if audio will not start. */
    fun play() {
        if (systemClickEnabled()) {
            // The system click first: it is the sound the rest of the phone makes, and using it keeps
            // this keyboard consistent with what the user hears elsewhere.
            audioManager?.playSoundEffect(AudioManager.FX_KEY_CLICK)
            return
        }
        if (!ensureLoaded()) return
        engine?.play(soundId, VOLUME, VOLUME, PRIORITY, NO_LOOP, NORMAL_RATE)
    }

    /** Frees the audio engine. Safe to call more than once, and safe to call without ever playing. */
    fun release() {
        engine?.release()
        engine = null
        soundId = 0
        loadAttempted = false
    }

    /** Loads the sample once, on first use: a user who keeps sound off never pays for an audio engine. */
    private fun ensureLoaded(): Boolean {
        if (loadAttempted) return soundId != 0
        loadAttempted = true
        return runCatching {
            val cache = File(appContext.cacheDir, ClickSample.FILE_NAME)
            val bytes = ClickSample.wavBytes()
            // Rewrite when missing or from an older build (the click was tuned between revisions).
            if (!cache.exists() || cache.length().toInt() != bytes.size) cache.writeBytes(bytes)
            val pool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // The Android-recommended usage for key clicks: not music, and it must not
                        // duck or interrupt whatever the user is listening to.
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            engine = pool
            soundId = pool.load(cache.absolutePath, 1)
            soundId != 0
        }.getOrDefault(false)
    }

    /**
     * Reads the system touch-sound setting, at most once per [SYSTEM_CLICK_TTL_MS].
     *
     * Not once per key press (a `ContentResolver` read per keystroke is wasteful) and not once per
     * process (then changing the setting would need an app restart, which reads as a bug).
     */
    private fun systemClickEnabled(): Boolean {
        val now = System.currentTimeMillis()
        if (systemClickReadAt != 0L && now - systemClickReadAt < SYSTEM_CLICK_TTL_MS) {
            return systemClickCached
        }
        systemClickCached = runCatching {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED)
        }.getOrDefault(0) == 1
        systemClickReadAt = now
        return systemClickCached
    }

    private companion object {
        const val VOLUME = 0.7f
        const val PRIORITY = 1
        const val NO_LOOP = 0
        const val NORMAL_RATE = 1.0f
        const val SYSTEM_CLICK_TTL_MS = 5_000L
    }
}

/**
 * The synthesised click, as a 16-bit PCM mono WAV.
 *
 * Pure (no Android types) so it can be unit-tested: the test asserts the header is a valid WAV, that
 * the samples are neither silent nor clipped, and that the tail really decays - a "click" that does
 * not decay is a beep, and a beep on every key press is unbearable.
 */
internal object ClickSample {

    const val FILE_NAME = "key-click.wav"

    private const val SAMPLE_RATE = 44100
    private const val DURATION_MS = 12
    private const val AMPLITUDE = 0.55f

    /** Deterministic: the same click every run, and a test can reason about the samples. */
    private const val RANDOM_SEED = 42L

    private val samples: ShortArray by lazy { render() }

    /** The whole file, header included. */
    fun wavBytes(): ByteArray {
        val pcm = samples
        val dataSize = pcm.size * 2
        val out = ByteArray(44 + dataSize)
        var p = 0
        fun ascii(text: String) { text.forEach { out[p++] = it.code.toByte() } }
        fun int32(value: Int) {
            out[p++] = (value and 0xFF).toByte()
            out[p++] = ((value shr 8) and 0xFF).toByte()
            out[p++] = ((value shr 16) and 0xFF).toByte()
            out[p++] = ((value shr 24) and 0xFF).toByte()
        }
        fun int16(value: Int) {
            out[p++] = (value and 0xFF).toByte()
            out[p++] = ((value shr 8) and 0xFF).toByte()
        }
        ascii("RIFF"); int32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); int32(16); int16(1); int16(1)
        int32(SAMPLE_RATE); int32(SAMPLE_RATE * 2); int16(2); int16(16)
        ascii("data"); int32(dataSize)
        pcm.forEach { int16(it.toInt()) }
        return out
    }

    /** The decoded samples, for tests. */
    fun pcm(): ShortArray = samples.copyOf()

    /**
     * A short noise burst, low-passed so it reads as a "tick" rather than a hiss, with an exponential
     * decay and a whisper of a high sine so it has a pitch instead of being pure noise.
     */
    private fun render(): ShortArray {
        val count = SAMPLE_RATE * DURATION_MS / 1000
        val random = Random(RANDOM_SEED)
        val out = ShortArray(count)
        var filtered = 0f
        for (i in 0 until count) {
            val progress = i.toFloat() / count
            val envelope = exp(-6f * progress).toFloat()
            val noise = random.nextFloat() * 2f - 1f
            // One-pole low pass: keeps the transient, drops the fizz.
            filtered += (noise - filtered) * 0.35f
            val tone = sin(2.0 * Math.PI * 1800.0 * i / SAMPLE_RATE).toFloat()
            val value = (filtered * 0.75f + tone * 0.25f) * envelope * AMPLITUDE
            out[i] = (value * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
