package com.liftright.repdetection

import kotlin.math.floor
import kotlin.math.max

/**
 * The "is this really a rep?" confirmation stage, ported from `get_is_exercise_hmm`.
 *
 * It scores a candidate rep from ten discretised features (five for the falling
 * "drop" phase, five for the rising phase): phase population, kurtosis/steepness,
 * template correlation, range of motion, and standard deviation. Each feature indexes
 * a small trained likelihood table; the per-feature likelihoods are multiplied
 * (naive-Bayes style) into P(rep) and P(not-rep), then combined with HMM transition
 * priors that depend on whether we are at the first rep or mid-set.
 *
 * All probability tables are the trained constants from the original engine, verbatim.
 * Each table is laid out as [feature=1: P(rep), P(notRep), feature=2: ..., feature=3: ...].
 */
internal class ExerciseHmm {

    // --- Trained likelihood tables (index = (featureValue-1)*2, +1 for not-rep) ---
    private val dropDistribution = doubleArrayOf(0.0001, 0.19598, 0.9997, 0.73618, 0.0002, 0.067839)
    private val riseDistribution = doubleArrayOf(0.00076278, 0.14573, 0.99923, 0.75126, 1.0E-5, 0.10302)
    private val dropKurtosis = doubleArrayOf(0.20061, 0.27136, 0.52708, 0.47739, 0.27231, 0.25126)
    private val riseKurtosis = doubleArrayOf(0.26011, 0.30653, 0.52784, 0.4598, 0.21205, 0.23367)
    private val dropCorrelation = doubleArrayOf(0.29138, 0.4598, 0.26468, 0.40201, 0.44394, 0.13819)
    private val riseCorrelation = doubleArrayOf(0.19603, 0.50503, 0.29443, 0.36432, 0.50953, 0.13065)
    private val dropRom = doubleArrayOf(0.0076278, 0.78141, 0.083905, 0.16583, 0.90847, 0.052764)
    private val riseRom = doubleArrayOf(0.0083905, 0.68844, 0.062548, 0.18593, 0.92906, 0.12563)
    private val dropStd = doubleArrayOf(0.0030511, 0.56784, 0.070938, 0.36181, 0.92601, 0.070352)
    private val riseStd = doubleArrayOf(1.0E-5, 0.48492, 0.057971, 0.37437, 0.94202, 0.1407)

    // Running HMM state (max probabilities carried between reps).
    private var isRepMax = 0.0
    private var isNotRepMax = 0.0

    fun reset() {
        isRepMax = 0.0
        isNotRepMax = 0.0
    }

    /** All features are inputs already in raw form; discretisation happens here. */
    data class Features(
        val dropPopulation: Double,   // percent of samples in the drop phase (0..100)
        val risePopulation: Double,   // percent of samples in the rise phase (0..100)
        val dropKurtosis: Double,
        val riseKurtosis: Double,
        val dropCorrelation: Double,  // |corr| 0..1
        val riseCorrelation: Double,
        val dropRom: Double,          // degrees
        val riseRom: Double,
        val dropStd: Double,
        val riseStd: Double,
    )

    /**
     * @return true if the candidate is confirmed as a repetition.
     */
    fun isRepetition(f: Features): Boolean {
        val popDrop = populationBin(f.dropPopulation)
        val popRise = populationBin(f.risePopulation)
        val kDrop = kurtosisBin(f.dropKurtosis)
        val kRise = kurtosisBin(f.riseKurtosis)
        val cDrop = correlationBin(f.dropCorrelation)
        val cRise = correlationBin(f.riseCorrelation)
        val rDrop = romBin(f.dropRom)
        val rRise = romBin(f.riseRom)
        val sDrop = stdBin(f.dropStd)
        val sRise = stdBin(f.riseStd)

        val isInitial = isRepMax == 0.0 || isNotRepMax == 0.0

        val isP =
            p(dropDistribution, popDrop) * p(riseDistribution, popRise) *
                p(dropKurtosis, kDrop) * p(riseKurtosis, kRise) *
                p(dropCorrelation, cDrop) * p(riseCorrelation, cRise) *
                p(dropRom, rDrop) * p(riseRom, rRise) *
                p(dropStd, sDrop) * p(riseStd, sRise)

        val isNotP =
            pNot(dropDistribution, popDrop) * pNot(riseDistribution, popRise) *
                pNot(dropKurtosis, kDrop) * pNot(riseKurtosis, kRise) *
                pNot(dropCorrelation, cDrop) * pNot(riseCorrelation, cRise) *
                pNot(dropRom, rDrop) * pNot(riseRom, rRise) *
                pNot(dropStd, sDrop) * pNot(riseStd, sRise)

        if (isInitial) {
            isRepMax = 0.3277 * isP
            isNotRepMax = 0.6723 * isNotP
        } else {
            // Transition model from the original engine.
            isRepMax = max(0.89 * isP, 0.14 * isP)
            isNotRepMax = max(0.86 * isNotP, 0.11 * isNotP)
        }

        return isRepMax > isNotRepMax
    }

    // table entry for P(rep | featureValue). featureValue is 1..3.
    private fun p(table: DoubleArray, value: Int): Double = table[(value - 1) * 2]
    private fun pNot(table: DoubleArray, value: Int): Double = table[(value - 1) * 2 + 1]

    // --- Discretisers, ported verbatim from the *ToString helpers ---

    private fun populationBin(population: Double): Int = when {
        population <= 35.0 -> 1
        population <= 70.0 -> 2
        else -> 3
    }

    private fun kurtosisBin(steepness: Double): Int {
        val s = max(floor(steepness), 0.0)
        return when {
            s <= 1.0 -> 1
            s <= 2.0 -> 2
            else -> 3
        }
    }

    private fun correlationBin(correlation: Double): Int = when {
        correlation <= 0.35 -> 1
        correlation <= 0.7 -> 2
        else -> 3
    }

    private fun romBin(rom: Double): Int = when {
        rom <= 10.0 -> 1
        rom <= 20.0 -> 2
        else -> 3
    }

    private fun stdBin(stdResult: Double): Int = when {
        stdResult <= 2.0 -> 1
        stdResult <= 6.0 -> 2
        else -> 3
    }
}
