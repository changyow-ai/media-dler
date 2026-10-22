package com.changyow.mediadler.core.transcribe

/** Sample-exact, overlapping inference slices inside one durable checkpoint window. */
object InferenceSlices {
    data class Slice(val start: Int, val endExclusive: Int)

    fun plan(sampleCount: Int, maxSamples: Int, overlapSamples: Int): List<Slice> {
        require(sampleCount >= 0)
        require(maxSamples > 0)
        require(overlapSamples in 0 until maxSamples)
        val result = ArrayList<Slice>()
        var start = 0
        while (start < sampleCount) {
            val end = minOf(start.toLong() + maxSamples, sampleCount.toLong()).toInt()
            result.add(Slice(start, end))
            if (end == sampleCount) break // Never infer a duplicate overlap-only tail.
            start = end - overlapSamples
        }
        return result
    }
}
