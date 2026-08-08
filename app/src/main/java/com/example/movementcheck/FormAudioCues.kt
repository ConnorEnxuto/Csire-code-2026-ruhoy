package com.example.movementcheck

import android.media.AudioManager
import android.media.ToneGenerator
import com.liftright.repdetection.form.FormAnalyzer

/**
 * Says a rep was off with a sound instead of pixels.
 *
 * ## Why audio at all
 * On a see-through display every lit pixel sits between the lifter and the barbell, and the
 * one moment a form cue matters is the moment the lifter is under load with their eyes on
 * the bar. A tone costs no screen area and does not need to be looked at, which makes it the
 * only kind of feedback that can be delivered *during* a rep without occluding anything.
 *
 * ## Why only two sounds
 * There are six faults, and nobody learns six tones. The alphabet here is deliberately tiny:
 *
 *  - silence — the rep was fine. Most reps are, so most of a set is quiet.
 *  - one short beep — something slipped (a NOTE).
 *  - two lower beeps — something is clearly wrong (a WARN).
 *
 * That is learnable in one session and carries the only decision that matters mid-set: keep
 * going, or fix something. *Which* fault it was is on screen and in the summary, where there
 * is room to say it properly.
 *
 * [ToneGenerator] is used rather than bundled audio: no assets, no decoding, and it plays on
 * the notification stream so it follows the device's own volume.
 */
class FormAudioCues {

    private var tone: ToneGenerator? = null

    /** Open the audio device. Safe to call repeatedly. */
    fun start() {
        if (tone != null) return
        tone = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
        } catch (e: RuntimeException) {
            // Some devices refuse a tone generator when audio is otherwise engaged. A silent
            // app is a much better outcome than a crashed one, and the visual cue still runs.
            null
        }
    }

    fun stop() {
        tone?.release()
        tone = null
    }

    /**
     * Sound one rep's verdict. Call once per judged rep; a clean rep makes no sound.
     *
     * The worst finding decides the sound — a rep with one bad fault and two minor ones is a
     * bad rep, and grading it any other way would need the lifter to count beeps.
     */
    fun play(report: FormAnalyzer.RepReport) {
        val t = tone ?: return
        if (report.findings.isEmpty()) return
        val worst = report.findings.any { it.severity == FormAnalyzer.Severity.WARN }
        if (worst) {
            t.startTone(ToneGenerator.TONE_PROP_NACK, BAD_MS)
        } else {
            t.startTone(ToneGenerator.TONE_PROP_BEEP, NOTE_MS)
        }
    }

    private companion object {
        /** Out of 100. Loud enough to hear over a gym, short enough not to be an alarm. */
        const val VOLUME = 80
        const val NOTE_MS = 120
        const val BAD_MS = 300
    }
}
