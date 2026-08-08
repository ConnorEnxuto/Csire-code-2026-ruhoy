package com.liftright.repdetection

import kotlin.math.abs

/**
 * Dynamic Time Warping distance, ported from `dynamic_time_warping` in the original
 * engine. Compares the fixed 302-point [template] against a variable-length window
 * [t] using an absolute-difference local cost and the classic min-of-three
 * (insertion / deletion / match) accumulation. Returns the total warp cost; the
 * detector treats a window as a candidate rep when this is below the exercise's
 * DTW threshold.
 */
internal object Dtw {

    fun distance(template: DoubleArray, t: DoubleArray): Double {
        val n = template.size          // 302
        val m = t.size
        if (m == 0) return Double.POSITIVE_INFINITY

        // D has (n+1) rows and (m+1) cols, initialised to +inf except D[0,0] = 0.
        val cols = m + 1
        val d = DoubleArray((n + 1) * cols) { Double.POSITIVE_INFINITY }
        d[0] = 0.0

        for (i in 0 until n) {
            for (j in 0 until m) {
                val insertion = d[i * cols + (j + 1)]      // D[i, j+1]
                val deletion = d[(i + 1) * cols + j]       // D[i+1, j]
                val match = d[i * cols + j]                // D[i, j]
                val best = minOf(insertion, deletion, match)
                d[(i + 1) * cols + (j + 1)] = abs(template[i] - t[j]) + best
            }
        }
        return d[n * cols + m]
    }
}
