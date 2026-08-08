package com.liftright.repdetection

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parses CSV exports of raw sensor axes for offline testing of [RepDetector] without a
 * live sensor. Accelerometer and gyroscope are expected as two separate files.
 *
 * Two header conventions are recognised:
 *  - A header row with columns literally named `x`, `y`, `z` (case-insensitive) anywhere
 *    among other columns (timestamps, sample indices, etc.) — those named columns are
 *    used regardless of position. This matches raw sensor exports like `x,y,z,time,sample`.
 *  - No recognisable header: each row's *last* three comma-separated numeric fields are
 *    read as X, Y, Z instead, so a leading timestamp column still works and an
 *    unrecognised header row is simply skipped as unparsable.
 */
object CsvImuLoader {

    data class Axes(val x: Double, val y: Double, val z: Double)

    fun parse(input: InputStream): List<Axes> {
        val rows = mutableListOf<Axes>()
        BufferedReader(InputStreamReader(input)).useLines { lines ->
            val iterator = lines.iterator()
            if (!iterator.hasNext()) return@useLines
            val headerFields = iterator.next().trim().split(",").map { it.trim() }
            val xIdx = headerFields.indexOfFirst { it.equals("x", ignoreCase = true) }
            val yIdx = headerFields.indexOfFirst { it.equals("y", ignoreCase = true) }
            val zIdx = headerFields.indexOfFirst { it.equals("z", ignoreCase = true) }

            if (xIdx >= 0 && yIdx >= 0 && zIdx >= 0) {
                while (iterator.hasNext()) {
                    val fields = iterator.next().trim().split(",")
                    if (fields.size <= maxOf(xIdx, yIdx, zIdx)) continue
                    val x = fields[xIdx].trim().toDoubleOrNull()
                    val y = fields[yIdx].trim().toDoubleOrNull()
                    val z = fields[zIdx].trim().toDoubleOrNull()
                    if (x == null || y == null || z == null) continue
                    rows.add(Axes(x, y, z))
                }
            } else {
                // No named x/y/z header — fall back to "last 3 numeric fields per row",
                // re-checking the header line itself in case there wasn't one at all.
                for (rawLine in sequenceOf(headerFields.joinToString(",")) + iterator.asSequence()) {
                    val fields = rawLine.trim().split(",")
                    if (fields.size < 3) continue
                    val (x, y, z) = fields.takeLast(3).map { it.trim().toDoubleOrNull() }
                    if (x == null || y == null || z == null) continue
                    rows.add(Axes(x, y, z))
                }
            }
        }
        return rows
    }

    /** Zips accelerometer + gyroscope axis rows into [ImuSample]s, truncating to the shorter file. */
    fun zipToImuSamples(accel: List<Axes>, gyro: List<Axes>): List<ImuSample> {
        val n = minOf(accel.size, gyro.size)
        return List(n) { i ->
            ImuSample(
                accX = accel[i].x, accY = accel[i].y, accZ = accel[i].z,
                gyroX = gyro[i].x, gyroY = gyro[i].y, gyroZ = gyro[i].z,
            )
        }
    }
}
