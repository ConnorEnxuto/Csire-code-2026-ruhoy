package com.example.movementcheck

import android.content.Context

/**
 * Tiny persisted-settings wrapper (SharedPreferences).
 *
 * [handsFree] chooses the rep-detection pipeline:
 *  - true  (default): fully hands-free. The sliding [RepDetector] runs continuously and
 *    an isolation filter cleans up between-set false positives. No user input per set.
 *  - false: manual set-marking. The user taps the glasses touchpad to start a set and
 *    taps again to end it; the multi-valley detector runs and only reps inside a marked
 *    set are counted. More accurate, at the cost of two taps per set.
 *
 * [showCapture] and [showDevPanel] gate the non-workout controls, and default OFF. On a
 * see-through display every lit pixel sits between the lifter and the barbell, so anything
 * not needed mid-set has to be switched on deliberately before it earns screen space.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("rep_settings", Context.MODE_PRIVATE)

    var handsFree: Boolean
        get() = prefs.getBoolean(KEY_HANDS_FREE, true)
        set(value) { prefs.edit().putBoolean(KEY_HANDS_FREE, value).apply() }

    /** Show the raw-IMU capture control (and its calibration marker) on the main screen. */
    var showCapture: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CAPTURE, false)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_CAPTURE, value).apply() }

    /** Show the CSV replay / onboard-IMU dev panel on the main screen. */
    var showDevPanel: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DEV, false)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_DEV, value).apply() }

    /**
     * Show the per-rep form cue. Defaults ON — it is the reason this app exists — but it is
     * the one overlay that appears *during* a set, so it needs a way off for anyone who
     * wants a bare counter, or while the thresholds are still being calibrated.
     */
    var showFormFeedback: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FORM, true)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_FORM, value).apply() }

    /**
     * Sound a tone when a rep has a fault. Defaults ON: it is the only feedback that reaches
     * the lifter mid-rep without covering any part of the room, so it is the cue this display
     * is best suited to. Off for anyone training somewhere a beep is unwelcome.
     */
    var audioCues: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_CUES, true)
        set(value) { prefs.edit().putBoolean(KEY_AUDIO_CUES, value).apply() }

    /**
     * Show time since the last rep while resting between sets. Defaults OFF — it is another
     * lit element, and it earns its place only for people who actually time their rest.
     */
    var restTimer: Boolean
        get() = prefs.getBoolean(KEY_REST_TIMER, false)
        set(value) { prefs.edit().putBoolean(KEY_REST_TIMER, value).apply() }

    /**
     * Show the per-rep detail in the end-of-workout summary: the rep-path graph and the
     * set/rep location of every fault.
     *
     * Defaults OFF. The end-of-set recap already shows a lifter the set they just did, while
     * they can still remember it; by the end of a workout the same detail is a wall of small
     * text about reps from twenty minutes ago. It stays available for anyone reviewing a
     * session properly, which is what the history screen is for.
     */
    var showDetail: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DETAIL, false)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_DETAIL, value).apply() }

    companion object {
        private const val KEY_SHOW_DETAIL = "show_detail"
        private const val KEY_HANDS_FREE = "hands_free"
        private const val KEY_SHOW_CAPTURE = "show_capture"
        private const val KEY_SHOW_DEV = "show_dev"
        private const val KEY_SHOW_FORM = "show_form"
        private const val KEY_AUDIO_CUES = "audio_cues"
        private const val KEY_REST_TIMER = "rest_timer"
    }
}
