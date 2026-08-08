package com.liftright.repdetection

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Small numeric helpers, each a Kotlin port of the corresponding MATLAB-generated
 * routine used by the rep-detection pipeline (gradient, moving median, mean, std,
 * norm, correlation coefficient, kurtosis).
 */
internal object SignalUtils {

    /** Arithmetic mean. Returns 0 for an empty array. */
    fun mean(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var s = 0.0
        for (v in x) s += v
        return s / x.size
    }

    /** Sample standard deviation (N-1 denominator), matching MATLAB `std`. */
    fun std(x: DoubleArray): Double {
        if (x.size < 2) return 0.0
        val m = mean(x)
        var s = 0.0
        for (v in x) {
            val d = v - m
            s += d * d
        }
        return sqrt(s / (x.size - 1))
    }

    /** Euclidean (L2) norm, matching MATLAB `norm`. */
    fun norm(x: DoubleArray): Double {
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s)
    }

    /**
     * Numerical gradient using central differences in the interior and single-sided
     * differences at the ends (unit spacing) — a port of MATLAB `gradient`.
     */
    fun gradient(x: DoubleArray): DoubleArray {
        val n = x.size
        val g = DoubleArray(n)
        if (n == 0) return g
        if (n == 1) { g[0] = 0.0; return g }
        g[0] = x[1] - x[0]
        g[n - 1] = x[n - 1] - x[n - 2]
        for (i in 1 until n - 1) {
            g[i] = (x[i + 1] - x[i - 1]) / 2.0
        }
        return g
    }

    /**
     * Excess-free sample kurtosis (MATLAB `kurtosis`: the raw fourth standardized
     * moment, which is 3.0 for a normal distribution). Returns 0 if undefined.
     */
    fun kurtosis(x: DoubleArray): Double {
        val n = x.size
        if (n == 0) return 0.0
        val m = mean(x)
        var m2 = 0.0
        var m4 = 0.0
        for (v in x) {
            val d = v - m
            val d2 = d * d
            m2 += d2
            m4 += d2 * d2
        }
        m2 /= n
        m4 /= n
        if (m2 == 0.0) return 0.0
        return m4 / (m2 * m2)
    }

    /**
     * Pearson correlation coefficient between two equal-length series, matching the
     * off-diagonal entry of MATLAB `corrcoef`. Returns 0 if undefined.
     */
    fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        if (n < 2) return 0.0
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val xa = a[i] - ma
            val xb = b[i] - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        val den = sqrt(da * db)
        return if (den == 0.0) 0.0 else num / den
    }

    /**
     * Sliding-window median filter with window length 100, mirroring `nuclear_filter`
     * in the original engine (which median-filtered the orientation trend). The window
     * is right-aligned and shrinks near the end of the array, matching the C behaviour.
     */
    fun movingMedian(x: DoubleArray, window: Int = 100): DoubleArray {
        val n = x.size
        val y = DoubleArray(n)
        for (i in 0 until n) {
            val end = minOf(i + window, n)
            val seg = x.copyOfRange(i, end)
            y[i] = median(seg)
        }
        return y
    }

    /** Median of an array (does not mutate the input). */
    fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val s = x.copyOf()
        s.sort()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /** Index of the minimum value in the range [from, to). */
    fun argMin(x: DoubleArray, from: Int, to: Int): Int {
        var idx = from
        var best = x[from]
        for (i in from + 1 until to) {
            if (x[i] < best) { best = x[i]; idx = i }
        }
        return idx
    }

    /** Index of the maximum value in the range [from, to). */
    fun argMax(x: DoubleArray, from: Int, to: Int): Int {
        var idx = from
        var best = x[from]
        for (i in from + 1 until to) {
            if (x[i] > best) { best = x[i]; idx = i }
        }
        return idx
    }

    fun absDiff(a: Double, b: Double): Double = abs(a - b)
}
