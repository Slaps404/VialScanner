package org.lji.vialscanner

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.lji.vialcore.ScanResult
import org.lji.vialcore.Verdict
import java.util.Locale

/**
 * Haptics and spoken feedback, ported from `App/Feedback.swift`.
 *
 * They stay in one file here for the same reason they are one file there:
 * every call site fires both together (a result lands, the phone buzzes and,
 * in hands-free, says it out loud), so splitting them would mean touching
 * `ScanScreen.kt` and friends twice.
 *
 * The big platform difference: `UINotificationFeedbackGenerator` and
 * `AVSpeechSynthesizer` are ready to use the moment you construct one. Their
 * Android equivalents, `Vibrator`/`VibratorManager` and `TextToSpeech`, both
 * need a `Context` handed to them, and `TextToSpeech` on top of that needs an
 * async engine handshake and an explicit `shutdown()` or it leaks the bound
 * service. So both objects below get an `init(context)` called once from
 * `MainActivity.onCreate`, and `Speaker.shutdown()` is called from
 * `MainActivity.onDestroy` alongside `ScanController.shutdown()` - the same
 * lifecycle, for the same reason: whoever creates the platform resource is
 * who has to let it go.
 */
object Haptics {
    var enabled = true

    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        // VibratorManager is the API 31+ way in; below that, Vibrator comes
        // straight off the system service. Asking VibratorManager for it on an
        // older device throws rather than degrading, so the branch has to come
        // first, not be a fallback after a failed cast.
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * iOS asks for a named system feedback shape (`.success`, `.warning`) and
     * the OS picks the buzz. Android's `VibrationEffect` has no such catalogue,
     * only raw duration and amplitude, so the closest analogue is: one firm
     * pulse for a read that landed, two short pulses for one that needs a
     * second look. Ambiguous and not-found share the double pulse because
     * Swift's `.warning` covers both of those cases too - the phone is saying
     * "look at this one", not naming which kind of uncertain it is.
     */
    fun result(verdict: Verdict) {
        if (!enabled) return
        when (verdict) {
            Verdict.CERTAIN, Verdict.CONFIDENT -> oneShot(durationMs = 40, amplitude = 200)
            Verdict.AMBIGUOUS, Verdict.NOT_FOUND -> doublePulse()
        }
    }

    /** A light tap for a UI touch: focus tap, zoom pick, toggle flip. */
    fun tap() {
        if (!enabled) return
        oneShot(durationMs = 15, amplitude = 110)
    }

    /** A firmer buzz for something that just got written to the log. */
    fun commit() {
        if (!enabled) return
        oneShot(durationMs = 25, amplitude = 180)
    }

    private fun oneShot(durationMs: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    private fun doublePulse() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // timings alternate off/on starting with the initial delay; amplitudes
            // line up 1:1 with timings, so the zeros mark the gaps between pulses.
            v.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 40, 90, 40),
                    intArrayOf(0, 180, 0, 180),
                    -1,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(longArrayOf(0, 40, 90, 40), -1)
        }
    }
}

/**
 * Spoken results for hands-free mode. See `App/Feedback.swift`'s `Speaker` for
 * why this exists at all: the operator has a vial in one hand and a rack in
 * the other, and a mode that still needs eyes on the phone screen is pointless.
 */
object Speaker {
    var enabled = true

    private var tts: TextToSpeech? = null
    private var ready = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    fun init(context: Context) {
        val app = context.applicationContext
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            val engine = tts ?: return@TextToSpeech
            if (!ready) return@TextToSpeech
            engine.language = Locale.US
            // Swift nudges the rate to 1.04x its default. TextToSpeech's scale is
            // the same idea (1.0 = normal), so the same multiplier carries over
            // even though there is no shared constant to multiply against.
            engine.setSpeechRate(1.04f)
            // Duck-not-interrupt only lasts as long as focus is held. Swift's
            // AVAudioSession is switched on once and stays active; the closest
            // Android has is asking for focus per utterance and giving it back
            // when TTS says it is done, via this listener.
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { releaseFocus() }
                @Deprecated("legacy TextToSpeech callback signature, no int-error overload before API 21")
                override fun onError(utteranceId: String?) { releaseFocus() }
            })
        }
    }

    /**
     * Say a line, cutting off whatever was mid-sentence. `force` is for
     * announcements like "hands-free on" that should be heard even if the
     * operator has turned "speak results" off - the mode itself changing is
     * worth a word regardless of that setting.
     */
    fun say(text: String, force: Boolean = false) {
        if ((!enabled && !force) || text.isBlank()) return
        val engine = tts ?: return
        if (!ready) return
        requestFocus()
        // QUEUE_FLUSH matches Swift's explicit `stopSpeaking(at: .immediate)`
        // before every utterance: a queued backlog of "AbNo 1 3 3" ...
        // "AbNo 8 4 2" would be worse than silence, because by the time the
        // fourth line played the operator would be three vials further down
        // the rack.
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vial-scan-result")
    }

    fun stop() {
        tts?.stop()
        releaseFocus()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun releaseFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    /**
     * What the phone says about a result. Deliberately short - anything longer
     * than a breath and the operator has already moved to the next vial.
     */
    fun phrase(result: ScanResult): String {
        // Said before the verdict is consulted: "new vial" would be a claim
        // about a label the camera never read.
        if (result.readNothing) return "Nothing read. Hold it closer and turn it slowly."
        return when (result.verdict) {
            Verdict.CERTAIN, Verdict.CONFIDENT -> {
                val vial = result.vial
                val def = result.definition
                when {
                    vial != null -> {
                        var s = "AbNo ${spellOut(vial.abNo)}"
                        if (vial.box.isNotEmpty()) s += ", box ${vial.box}"
                        if (result.lotNotOnRecord) s += ", but the lot does not match"
                        s
                    }
                    def != null -> "${def.name}. No vial on record."
                    else -> "Identified"
                }
            }
            Verdict.AMBIGUOUS -> "Not sure. Check this one."
            Verdict.NOT_FOUND -> "New vial. Not in the inventory."
        }
    }

    /** "133" as "1 3 3": a spoken three-digit number is easy to mishear as
     *  another, and the operator is comparing it against a written label. */
    private fun spellOut(s: String): String = s.map { it.toString() }.joinToString(" ")
}
