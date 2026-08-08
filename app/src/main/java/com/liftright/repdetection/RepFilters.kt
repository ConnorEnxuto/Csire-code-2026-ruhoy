package com.liftright.repdetection

/**
 * Post-processing filters for a completed list of detected repetitions. These are
 * batch-only (they need to see the whole session at once), so they apply to
 * recorded/replayed sessions, not to the live per-sample callback in [RepDetector].
 */
object RepFilters {

    /**
     * Drops a detected rep if no *other* detected rep starts within [gapSeconds] of it.
     *
     * Real sets always cluster multiple reps together with only a few seconds between
     * them; every false positive found during validation (against 16 real subject
     * sessions with known ground truth, see project memory) was an isolated detection
     * with no other rep nearby - typically rest-period noise or post-session sensor
     * handling. Sweeping the gap threshold against that same 16-session ground truth
     * found 12s as a clear, sharp minimum for total rep-count error (rep-count error
     * dropped from 23 to 15 across the 16 sessions at gap=12s; neighbouring values
     * 9-11s and 13-14s were all meaningfully worse), so that's the default here.
     */
    fun filterIsolated(
        reps: List<Repetition>,
        sampleRateHz: Double,
        gapSeconds: Double = 12.0,
    ): List<Repetition> {
        val gapSamples = gapSeconds * sampleRateHz
        return reps.filterIndexed { i, r ->
            val prevOk = i > 0 && (r.liftStartIndex - reps[i - 1].liftStartIndex) <= gapSamples
            val nextOk = i < reps.size - 1 && (reps[i + 1].liftStartIndex - r.liftStartIndex) <= gapSamples
            prevOk || nextOk
        }
    }

    /**
     * Streaming counterpart to [filterIsolated] for a live session, which can't look
     * ahead at the full rep list. A newly-confirmed rep with no established neighbour
     * yet is held back ("pending") until either a following rep arrives within
     * [gapSeconds] - at which point both are released together - or the gap elapses
     * with nothing following, at which point the pending rep is discarded as isolated.
     * Every rep after the first in a cluster is released immediately, since it always
     * has a just-released predecessor within the gap.
     */
    class LiveIsolationGate(
        private val sampleRateHz: Double,
        private val gapSeconds: Double = 12.0,
    ) {
        private val gapSamples = gapSeconds * sampleRateHz
        private var lastReleased: Repetition? = null
        private var pending: Repetition? = null

        /** Feed a newly-confirmed rep. Returns the rep(s) to display now, in order (often empty). */
        fun offer(rep: Repetition): List<Repetition> {
            val held = pending
            if (held != null) {
                return if (rep.liftStartIndex - held.liftStartIndex <= gapSamples) {
                    pending = null
                    lastReleased = rep
                    listOf(held, rep)
                } else {
                    pending = rep
                    emptyList()
                }
            }
            val last = lastReleased
            return if (last != null && rep.liftStartIndex - last.liftStartIndex <= gapSamples) {
                lastReleased = rep
                listOf(rep)
            } else {
                pending = rep
                emptyList()
            }
        }
    }
}
