package com.farhan.cava3

import kotlin.math.*

/**
 * High-performance audio signal processor for cava3.
 *
 * Implements:
 * - 8192-point custom radix-2 Cooley-Tukey FFT with precomputed Hann window.
 * - Circular sample accumulator with 1024-sample hop (approx 43 fps at 44.1 kHz).
 * - Log-spaced mapping from 20 Hz to 20,000 Hz into ~200 visual points.
 * - Max-pooling for high frequency bins, interpolation for sub-bass bins.
 * - Amplitude to dB full scale conversion.
 * - +3 dB/octave tilt referenced to 1 kHz (maintains treble visibility).
 * - User gain offset (-20 dB to +30 dB).
 * - Asymmetrical temporal smoothing (fast attack ~0.6, slower decay ~0.15).
 * - Peak frequency and peak dB tracking.
 */
class AudioProcessor {

    companion object {
        const val SAMPLE_RATE = 44100
        const val FFT_SIZE = 8192
        const val HOP_SIZE = 1024
        const val NUM_POINTS = 200
        const val MIN_FREQ = 20.0f
        const val MAX_FREQ = 20000.0f
        const val MIN_DB = -80.0f
        const val MAX_DB = 0.0f

        // Fast attack (~0.6) and slower decay (~0.15)
        const val ATTACK_ALPHA = 0.60f
        const val DECAY_ALPHA = 0.15f
    }

    // Circular input sample buffer
    private val ringBuffer = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private var samplesAccumulated = 0

    // FFT working buffers
    private val real = FloatArray(FFT_SIZE)
    private val imag = FloatArray(FFT_SIZE)
    private val hannWindow = FloatArray(FFT_SIZE)

    // Precomputed log-frequency frequencies and bin ranges for NUM_POINTS
    private val pointFrequencies = FloatArray(NUM_POINTS)
    private val pointTilts = FloatArray(NUM_POINTS)

    // Output smoothed visual dB values: normalized between 0.0f (bottom/MIN_DB) and 1.0f (top/MAX_DB)
    val smoothedDb = FloatArray(NUM_POINTS) { MIN_DB }
    val smoothedNorm = FloatArray(NUM_POINTS) { 0.0f }

    // Live peak tracking
    var peakFrequency: Float = 0.0f
        private set
    var peakDb: Float = MIN_DB
        private set
    var hasSignal: Boolean = false
        private set

    // User gain offset in dB
    var userGainDb: Float = 12.0f

    // Bit reversal lookup table
    private val bitReverse = IntArray(FFT_SIZE)

    init {
        // Precompute Hann window: w[n] = 0.5 * (1 - cos(2 * pi * n / (N - 1)))
        for (i in 0 until FFT_SIZE) {
            hannWindow[i] = (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
        }

        // Precompute bit reversal permutation
        var j = 0
        for (i in 0 until FFT_SIZE) {
            bitReverse[i] = j
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
        }

        // Precompute log frequencies and +3 dB / octave tilt relative to 1000 Hz
        val logMin = ln(MIN_FREQ)
        val logMax = ln(MAX_FREQ)
        val logRatio = (logMax - logMin) / (NUM_POINTS - 1)
        val ln2 = ln(2.0f)

        for (i in 0 until NUM_POINTS) {
            val freq = exp(logMin + i * logRatio)
            pointFrequencies[i] = freq
            // Tilt: +3 dB/octave referenced to 1 kHz: 3.0 * log2(freq / 1000)
            val octavesFrom1k = ln(freq / 1000.0f) / ln2
            pointTilts[i] = 3.0f * octavesFrom1k
        }
    }

    /**
     * Feed raw 16-bit mono PCM samples. Returns true if a new FFT frame was processed.
     */
    fun processSamples(pcmSamples: ShortArray, length: Int): Boolean {
        var processedFrame = false

        for (i in 0 until length) {
            // Normalize 16-bit integer to float [-1.0f, 1.0f]
            ringBuffer[ringPos] = pcmSamples[i] / 32768.0f
            ringPos = (ringPos + 1) and (FFT_SIZE - 1)
            samplesAccumulated++

            if (samplesAccumulated >= HOP_SIZE) {
                computeFftFrame()
                samplesAccumulated -= HOP_SIZE
                processedFrame = true
            }
        }

        return processedFrame
    }

    private fun computeFftFrame() {
        // Copy samples from circular buffer with Hann window
        var readIdx = ringPos
        for (i in 0 until FFT_SIZE) {
            real[i] = ringBuffer[readIdx] * hannWindow[i]
            imag[i] = 0.0f
            readIdx = (readIdx + 1) and (FFT_SIZE - 1)
        }

        // Execute in-place Radix-2 Cooley-Tukey FFT
        fft(real, imag, FFT_SIZE)

        // Compute magnitude spectrum for bins 0 .. FFT_SIZE / 2
        // Normalization: magnitude normalized by (FFT_SIZE / 2)
        val halfSize = FFT_SIZE / 2
        val binResolution = SAMPLE_RATE.toFloat() / FFT_SIZE.toFloat() // ~5.383 Hz per bin

        // Find spectrum values at ~200 log-spaced points
        var maxPeakDb = -120.0f
        var maxPeakFreq = 0.0f
        var activeEnergy = 0.0f

        for (i in 0 until NUM_POINTS) {
            val centerFreq = pointFrequencies[i]
            val centerBin = centerFreq / binResolution

            // Determine frequency band bounds for this point
            val prevFreq = if (i > 0) pointFrequencies[i - 1] else MIN_FREQ * 0.9f
            val nextFreq = if (i < NUM_POINTS - 1) pointFrequencies[i + 1] else MAX_FREQ * 1.1f
            val lowerBin = max(1, ((centerFreq + prevFreq) * 0.5f / binResolution).toInt())
            val upperBin = min(halfSize - 1, ((centerFreq + nextFreq) * 0.5f / binResolution).toInt())

            var mag = 0.0f

            if (lowerBin == upperBin || upperBin < lowerBin) {
                // Fewer than one bin: linear interpolation between adjacent bins
                val b0 = centerBin.toInt().coerceIn(1, halfSize - 2)
                val frac = centerBin - b0
                val mag0 = hypot(real[b0], imag[b0])
                val mag1 = hypot(real[b0 + 1], imag[b0 + 1])
                mag = (mag0 * (1.0f - frac) + mag1 * frac) / (FFT_SIZE / 2.0f)
            } else {
                // Multiple bins fall into one point: take the maximum bin
                for (b in lowerBin..upperBin) {
                    val bMag = hypot(real[b], imag[b])
                    if (bMag > mag) mag = bMag
                }
                mag /= (FFT_SIZE / 2.0f)
            }

            // Amplitude to dB full scale
            // 0 dB = full scale (1.0). Clip minimum magnitude to avoid log(0)
            val clampedMag = max(1e-6f, mag)
            val rawDb = 20.0f * log10(clampedMag)

            // Apply +3 dB/octave tilt and user gain
            val pointDb = rawDb + pointTilts[i] + userGainDb

            // Temporal smoothing with asymmetrical attack & decay
            val prevVal = smoothedDb[i]
            val smoothed = if (pointDb > prevVal) {
                prevVal * (1.0f - ATTACK_ALPHA) + pointDb * ATTACK_ALPHA
            } else {
                prevVal * (1.0f - DECAY_ALPHA) + pointDb * DECAY_ALPHA
            }
            smoothedDb[i] = smoothed

            // Map dB range [-80 dB, 0 dB] into normalized [0.0, 1.0]
            val norm = ((smoothed - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0.0f, 1.0f)
            smoothedNorm[i] = norm

            // Track signal energy and peak
            activeEnergy += norm
            if (smoothed > maxPeakDb && smoothed > -72.0f) {
                maxPeakDb = smoothed
                maxPeakFreq = centerFreq
            }
        }

        hasSignal = activeEnergy > 0.5f
        if (hasSignal && maxPeakDb > -72.0f) {
            peakFrequency = maxPeakFreq
            peakDb = maxPeakDb
        } else {
            peakFrequency = 0.0f
            peakDb = MIN_DB
        }
    }

    /**
     * In-place Radix-2 Cooley-Tukey FFT.
     */
    private fun fft(r: FloatArray, im: FloatArray, n: Int) {
        // Bit-reversal permutation
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (i < j) {
                val tr = r[i]; r[i] = r[j]; r[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        // Cooley-Tukey butterflies
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * PI / len
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wr = 1.0f
                var wi = 0.0f
                for (m in 0 until halfLen) {
                    val tr = wr * r[i + m + halfLen] - wi * im[i + m + halfLen]
                    val ti = wr * im[i + m + halfLen] + wi * r[i + m + halfLen]

                    r[i + m + halfLen] = r[i + m] - tr
                    im[i + m + halfLen] = im[i + m] - ti
                    r[i + m] += tr
                    im[i + m] += ti

                    val nextWr = wr * wStepR - wi * wStepI
                    val nextWi = wr * wStepI + wi * wStepR
                    wr = nextWr
                    wi = nextWi
                }
                i += len
            }
            len = len shl 1
        }
    }
}
