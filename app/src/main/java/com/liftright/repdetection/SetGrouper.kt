package com.liftright.repdetection

/**
 * Splits the counted reps into sets by the rest between them, so the display can show
 * "SET 3" and count reps within that set rather than one number for the whole workout.
 *
 * ## Why it re-derives instead of counting incrementally
 * Reps can be taken back after the fact — [PostureVerifier] withdraws one a few seconds
 * later if it turns out to have been the lifter sitting up. An incremental counter would
 * have to unwind that, including the case where the withdrawn rep was the only one in its
 * set. Re-deriving the grouping from the current rep list makes withdrawal free: the rep
 * disappears from the list and the sets fall out correctly with no special casing. Rep
 * lists are tens of entries long, so the cost is irrelevant.
 *
 * ## Choosing [restSeconds]
 * Within a set, reps follow each other in a few seconds; between sets the lifter rests.
 * The threshold has to sit above the slowest rep-to-rep gap and below the shortest rest.
 * Measured across the 16-session validation set, the widest gap inside a real set is far
 * below the shortest rest between sets, so the boundary is not delicate — see the sweep
 * in the project notes. 25 s sits in the middle of that empty band.
 *
 * Note this groups reps that were already *counted*; it is not a detector and does not
 * decide what is a rep. Suppressing reps because of the set they landed in is a separate
 * question, deliberately not done here (see [RepFilters.LiveIsolationGate], which already
 * drops isolated detections on a 12 s rule).
 */
class SetGrouper(
    private val sampleRateHz: Double = 50.0,
    private val restSeconds: Double = 25.0,
) {
    /**
     * @param setCount how many sets the session has contained so far
     * @param currentSet 1-based number of the set the most recent rep belongs to, 0 if none
     * @param repsInCurrentSet reps counted so far in that set
     * @param setOfRep set number for each rep, parallel to the input list
     */
    data class Grouping(
        val setCount: Int,
        val currentSet: Int,
        val repsInCurrentSet: Int,
        val setOfRep: List<Int>,
    ) {
        companion object {
            val EMPTY = Grouping(0, 0, 0, emptyList())
        }
    }

    fun group(reps: List<Repetition>): Grouping {
        if (reps.isEmpty()) return Grouping.EMPTY
        val restSamples = restSeconds * sampleRateHz
        val setOfRep = ArrayList<Int>(reps.size)
        var set = 0
        var repsInSet = 0
        var prevStart: Int? = null
        for (r in reps) {
            val prev = prevStart
            val startsNewSet = prev == null || r.liftStartIndex - prev > restSamples
            if (startsNewSet) {
                set++
                repsInSet = 0
            }
            repsInSet++
            setOfRep.add(set)
            prevStart = r.liftStartIndex
        }
        return Grouping(set, set, repsInSet, setOfRep)
    }
}
