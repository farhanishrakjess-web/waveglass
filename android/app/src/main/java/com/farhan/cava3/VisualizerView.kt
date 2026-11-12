package com.farhan.cava3

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.*

/**
 * Custom View that renders the cava3 real-time frequency-response spectrum.
 *
 * Visual hierarchy:
 * 1. Background: solid #909090
 * 2. Background Grid: Log frequency lines (20 Hz - 20 kHz) and dB lines (0 to -80 dB)
 * 3. Margins: dB labels on left, Hz labels at bottom (never obscured by spectrum)
 * 4. White reference curve: Mild Harman target shape (bass lift, ~+8 dB ear-gain peak at 3-4 kHz)
 * 5. Spectrum: Quadratic-Bezier smooth filled curve with #2962FF to #00E5FF horizontal gradient (alpha ~0xEE)
 * 6. Live Peak Marker: White dot with #111111 ring + bold frequency & dB text
 * 7. Idle text: "Waiting for audio..."
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Paints
    private val bgPaint = Paint().apply {
        color = 0xFF909090.toInt()
        style = Paint.Style.FILL
    }

    private val majorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x38000000 // Major black grid with low alpha
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.0f)
    }

    private val minorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1A000000 // Minor black grid with lower alpha
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.0f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt()
        textSize = spToPx(10.0f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val boldLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt()
        textSize = spToPx(11.0f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val harmanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt() // Pure white
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val spectrumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val peakDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val peakRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.0f)
    }

    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E1E.toInt()
        textSize = spToPx(14.0f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    // Geometry margins (in pixels)
    private var marginLeft = 0f
    private var marginRight = 0f
    private var marginTop = 0f
    private var marginBottom = 0f

    // Plot area bounds
    private var plotLeft = 0f
    private var plotRight = 0f
    private var plotTop = 0f
    private var plotBottom = 0f
    private var plotWidth = 0f
    private var plotHeight = 0f

    // Paths
    private val spectrumPath = Path()
    private val harmanPath = Path()

    // Spectrum point coordinates
    private var pointX = FloatArray(AudioProcessor.NUM_POINTS)
    private var pointY = FloatArray(AudioProcessor.NUM_POINTS)

    // Current spectrum data buffer
    private var currentNormData: FloatArray? = null
    private var currentPeakFreq: Float = 0f
    private var currentPeakDb: Float = -80f
    private var isAudioActive: Boolean = false
    var statusMessage: String? = null

    // Frequency grid definitions
    private val majorFreqs = floatArrayOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
    private val majorFreqLabels = arrayOf("20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k")

    private val minorFreqs = floatArrayOf(
        30f, 40f, 60f, 70f, 80f, 90f,
        300f, 400f, 600f, 700f, 800f, 900f,
        3000f, 4000f, 6000f, 7000f, 8000f, 9000f, 15000f
    )

    private val dbLevels = intArrayOf(0, -10, -20, -30, -40, -50, -60, -70, -80)

    init {
        marginLeft = dpToPx(38f)
        marginRight = dpToPx(16f)
        marginTop = dpToPx(20f)
        marginBottom = dpToPx(28f)
    }

    /**
     * Update visualizer data from AudioProcessor.
     */
    fun updateData(normData: FloatArray, peakFreq: Float, peakDb: Float, hasSignal: Boolean) {
        currentNormData = normData
        currentPeakFreq = peakFreq
        currentPeakDb = peakDb
        isAudioActive = hasSignal
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        plotLeft = marginLeft
        plotRight = w - marginRight
        plotTop = marginTop
        plotBottom = h - marginBottom
        plotWidth = max(1f, plotRight - plotLeft)
        plotHeight = max(1f, plotBottom - plotTop)

        // Setup spectrum gradient (smooth horizontal gradient from blue #2962FF to cyan #00E5FF, alpha ~0xEE)
        spectrumPaint.shader = LinearGradient(
            plotLeft, 0f, plotRight, 0f,
            0xEE2962FF.toInt(),
            0xEE00E5FF.toInt(),
            Shader.TileMode.CLAMP
        )

        // Precompute X positions for the 200 points
        val logMin = ln(AudioProcessor.MIN_FREQ)
        val logMax = ln(AudioProcessor.MAX_FREQ)
        val logRange = logMax - logMin

        for (i in 0 until AudioProcessor.NUM_POINTS) {
            val frac = i.toFloat() / (AudioProcessor.NUM_POINTS - 1)
            pointX[i] = plotLeft + frac * plotWidth
        }

        // Build Harman target reference curve path
        buildHarmanPath()
    }

    /**
     * Map frequency (Hz) to X pixel coordinate on logarithmic axis [20 Hz, 20000 Hz].
     */
    private fun freqToX(freq: Float): Float {
        val f = freq.coerceIn(AudioProcessor.MIN_FREQ, AudioProcessor.MAX_FREQ)
        val logMin = ln(AudioProcessor.MIN_FREQ)
        val logMax = ln(AudioProcessor.MAX_FREQ)
        val frac = (ln(f) - logMin) / (logMax - logMin)
        return plotLeft + frac * plotWidth
    }

    /**
     * Map dB value [-80 dB, 0 dB] to Y pixel coordinate.
     */
    private fun dbToY(db: Float): Float {
        val clamped = db.coerceIn(AudioProcessor.MIN_DB, AudioProcessor.MAX_DB)
        val frac = (clamped - AudioProcessor.MIN_DB) / (AudioProcessor.MAX_DB - AudioProcessor.MIN_DB)
        return plotBottom - frac * plotHeight
    }

    /**
     * Construct Harman-style target response reference curve:
     * - Mild bass boost (+5.5 dB) below 150 Hz
     * - Flat around 200 Hz - 1 kHz
     * - Ear-gain peak around 3-4 kHz (~+8 dB)
     * - Treble roll-off above 8 kHz
     * Placed nominally at -30 dB baseline on the 0 to -80 dB scale.
     */
    private fun buildHarmanPath() {
        harmanPath.reset()
        val numSteps = 150
        val baseLineDb = -32.0f

        for (step in 0..numSteps) {
            val frac = step.toFloat() / numSteps
            val logMin = ln(AudioProcessor.MIN_FREQ)
            val logMax = ln(AudioProcessor.MAX_FREQ)
            val freq = exp(logMin + frac * (logMax - logMin))

            var targetOffset = 0.0f

            // 1. Bass lift below 200 Hz
            if (freq < 200f) {
                val bassFrac = (1.0f - (freq - 20f) / 180f).coerceIn(0f, 1f)
                targetOffset += 5.5f * (bassFrac * bassFrac)
            }

            // 2. Ear-gain peak centered around 3300 Hz (+8 dB)
            val octavesFromPeak = ln(freq / 3300.0f) / ln(2.0f)
            targetOffset += 8.0f * exp(-0.5f * (octavesFromPeak / 0.55f).pow(2))

            // 3. Roll-off above 8 kHz down to -5 dB at 20 kHz
            if (freq > 8000f) {
                val trebleFrac = (ln(freq / 8000.0f) / ln(20000.0f / 8000.0f)).coerceIn(0f, 1f)
                targetOffset -= 5.0f * trebleFrac
            }

            val totalDb = baseLineDb + targetOffset
            val x = freqToX(freq)
            val y = dbToY(totalDb)

            if (step == 0) {
                harmanPath.moveTo(x, y)
            } else {
                harmanPath.lineTo(x, y)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Solid background #909090
        canvas.drawPaint(bgPaint)

        // 2. Grid lines & labels
        drawGridAndLabels(canvas)

        // 3. Reference curve: Pure white #FFFFFF, 2.5 dp
        canvas.drawPath(harmanPath, harmanPaint)

        // 4. Spectrum filled curve
        val data = currentNormData
        if (data != null && data.isNotEmpty()) {
            drawSpectrum(canvas, data)
        }

        // 5. Peak marker & label
        if (isAudioActive && currentPeakFreq > 0f && currentPeakDb > -72f) {
            drawPeakMarker(canvas)
        }

        // 6. Idle or error message if audio is not flowing
        if (!isAudioActive) {
            val text = statusMessage ?: "Waiting for audio..."
            val textY = plotTop + plotHeight * 0.45f
            canvas.drawText(text, plotLeft + plotWidth * 0.5f, textY, messagePaint)
        }
    }

    private fun drawGridAndLabels(canvas: Canvas) {
        // Minor frequency vertical grid lines
        for (f in minorFreqs) {
            val x = freqToX(f)
            canvas.drawLine(x, plotTop, x, plotBottom, minorGridPaint)
        }

        // Major frequency vertical grid lines + labels
        for (i in majorFreqs.indices) {
            val f = majorFreqs[i]
            val x = freqToX(f)
            canvas.drawLine(x, plotTop, x, plotBottom, majorGridPaint)

            // Numeric frequency label in bottom margin
            val label = majorFreqLabels[i]
            val textWidth = labelPaint.measureText(label)
            val textX = (x - textWidth / 2f).coerceIn(plotLeft, width.toFloat() - textWidth - 2f)
            val textY = plotBottom + dpToPx(16f)
            canvas.drawText(label, textX, textY, labelPaint)
        }

        // "Hz" label at the far right of bottom axis
        val hzLabel = "Hz"
        val hzX = plotRight + dpToPx(4f)
        val hzY = plotBottom + dpToPx(16f)
        canvas.drawText(hzLabel, hzX, hzY, boldLabelPaint)

        // Horizontal dB grid lines & labels
        for (db in dbLevels) {
            val y = dbToY(db.toFloat())
            val isMajor = (db % 20 == 0)
            val paint = if (isMajor) majorGridPaint else minorGridPaint
            canvas.drawLine(plotLeft, y, plotRight, y, paint)

            // Numeric dB labels on left margin for every 20 dB (0, -20, -40, -60, -80)
            if (isMajor) {
                val dbStr = if (db == 0) "0 dB" else "$db"
                val textWidth = labelPaint.measureText(dbStr)
                val textX = plotLeft - textWidth - dpToPx(6f)
                val textY = y + dpToPx(3.5f)
                canvas.drawText(dbStr, textX, textY, labelPaint)
            }
        }
    }

    private fun drawSpectrum(canvas: Canvas, normData: FloatArray) {
        val count = min(AudioProcessor.NUM_POINTS, normData.size)
        if (count < 2) return

        // Compute Y positions: norm is in [0.0, 1.0], 0.0 is plotBottom, 1.0 is plotTop
        for (i in 0 until count) {
            val norm = normData[i].coerceIn(0.0f, 1.0f)
            pointY[i] = plotBottom - norm * plotHeight
        }

        // Construct smooth quadratic Bezier path through midpoints
        spectrumPath.reset()
        spectrumPath.moveTo(pointX[0], pointY[0])

        for (i in 0 until count - 1) {
            val midX = (pointX[i] + pointX[i + 1]) * 0.5f
            val midY = (pointY[i] + pointY[i + 1]) * 0.5f
            spectrumPath.quadTo(pointX[i], pointY[i], midX, midY)
        }
        spectrumPath.lineTo(pointX[count - 1], pointY[count - 1])

        // Close path along bottom baseline so the fill area covers the lower region
        spectrumPath.lineTo(pointX[count - 1], plotBottom)
        spectrumPath.lineTo(pointX[0], plotBottom)
        spectrumPath.close()

        // Draw filled curve (no bars, no outline)
        canvas.drawPath(spectrumPath, spectrumPaint)
    }

    private fun drawPeakMarker(canvas: Canvas) {
        val peakX = freqToX(currentPeakFreq)
        val peakY = dbToY(currentPeakDb)

        // Draw peak dot: white dot (radius 4.5 dp) with dark #111111 ring (2 dp stroke)
        val dotRadius = dpToPx(4.5f)
        canvas.drawCircle(peakX, peakY, dotRadius, peakDotPaint)
        canvas.drawCircle(peakX, peakY, dotRadius, peakRingPaint)

        // Format label: e.g. "1.2k Hz  -23 dB" or "240 Hz  -12 dB"
        val freqStr = if (currentPeakFreq >= 1000f) {
            String.format("%.1fk Hz", currentPeakFreq / 1000f)
        } else {
            String.format("%.0f Hz", currentPeakFreq)
        }
        val dbStr = String.format("%+.0f dB", currentPeakDb)
        val peakText = "$freqStr  $dbStr"

        val textWidth = boldLabelPaint.measureText(peakText)
        val textX = (peakX - textWidth / 2f).coerceIn(plotLeft, plotRight - textWidth)
        val textY = if (peakY - dpToPx(12f) < plotTop + dpToPx(12f)) {
            peakY + dpToPx(18f) // Put below dot if near the top edge
        } else {
            peakY - dpToPx(10f) // Above dot
        }

        canvas.drawText(peakText, textX, textY, boldLabelPaint)
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}
