package com.virtucam.detection

import kotlin.math.*

/**
 * Metadata Analysis Module
 * Detects synthetic patterns in camera metadata using FFT and statistical analysis.
 */
class MetadataAnalyzer {
    
    data class MetadataFrame(
        val frameNumber: Long,
        val timestamp: Long,
        val exposureTime: Long,
        val iso: Int,
        val aeState: Int,
        val focusDistance: Float?,
        val aperture: Float?,
        val focalLength: Float?
    )
    
    data class AnalysisResult(
        val score: Int,  // 0-100, higher = more suspicious
        val periodicityDetected: Boolean,
        val periodicityFrequency: String?,
        val anomalies: List<String>,
        val confidence: Int,
        val details: Map<String, Any>
    )
    
    private val frames = mutableListOf<MetadataFrame>()
    
    fun feedFrame(frame: MetadataFrame) {
        frames.add(frame)
    }
    
    fun analyze(): AnalysisResult {
        if (frames.size < 100) {
            return AnalysisResult(
                score = 0,
                periodicityDetected = false,
                periodicityFrequency = null,
                anomalies = listOf("Insufficient frames (need 100+, got ${frames.size})"),
                confidence = 0,
                details = emptyMap()
            )
        }
        
        val anomalies = mutableListOf<String>()
        val details = mutableMapOf<String, Any>()
        var totalScore = 0
        var confidenceSum = 0
        var testCount = 0
        
        // Test 1: Exposure Time Periodicity (FFT)
        val exposurePeriodicity = detectPeriodicity(frames.map { it.exposureTime.toDouble() })
        if (exposurePeriodicity.isPeriodic) {
            anomalies.add("Exposure time follows periodic pattern (${exposurePeriodicity.frequency} frames)")
            totalScore += 30
            details["exposurePeriodicity"] = exposurePeriodicity.frequency
        }
        confidenceSum += exposurePeriodicity.confidence
        testCount++
        
        // Test 2: ISO Periodicity (FFT)
        val isoPeriodicity = detectPeriodicity(frames.map { it.iso.toDouble() })
        if (isoPeriodicity.isPeriodic) {
            anomalies.add("ISO sensitivity follows periodic pattern (${isoPeriodicity.frequency} frames)")
            totalScore += 30
            details["isoPeriodicity"] = isoPeriodicity.frequency
        }
        confidenceSum += isoPeriodicity.confidence
        testCount++
        
        // Test 3: Exposure Time Variance (too consistent = synthetic)
        val exposureVariance = calculateVariance(frames.map { it.exposureTime.toDouble() })
        val exposureMean = frames.map { it.exposureTime.toDouble() }.average()
        val exposureCoefficientOfVariation = sqrt(exposureVariance) / exposureMean
        if (exposureCoefficientOfVariation < 0.01) {  // Less than 1% variation
            anomalies.add("Exposure time too consistent (CV: ${String.format("%.4f", exposureCoefficientOfVariation)})")
            totalScore += 20
            details["exposureCV"] = exposureCoefficientOfVariation
        }
        confidenceSum += 90
        testCount++
        
        // Test 4: ISO Variance
        val isoVariance = calculateVariance(frames.map { it.iso.toDouble() })
        val isoMean = frames.map { it.iso.toDouble() }.average()
        val isoCoefficientOfVariation = sqrt(isoVariance) / isoMean
        if (isoCoefficientOfVariation < 0.02) {  // Less than 2% variation
            anomalies.add("ISO sensitivity too consistent (CV: ${String.format("%.4f", isoCoefficientOfVariation)})")
            totalScore += 20
            details["isoCV"] = isoCoefficientOfVariation
        }
        confidenceSum += 90
        testCount++
        
        // Test 5: AE State Distribution (should vary naturally)
        val aeStates = frames.map { it.aeState }
        val aeStateDistribution = aeStates.groupingBy { it }.eachCount()
        if (aeStateDistribution.size == 1) {
            anomalies.add("AE state never changes (always ${aeStates.first()})")
            totalScore += 15
        } else if (aeStateDistribution.size == 2 && aeStateDistribution.values.all { it > frames.size / 3 }) {
            // Exactly 2 states with suspiciously even distribution
            anomalies.add("AE state alternates between exactly 2 values with even distribution")
            totalScore += 10
        }
        details["aeStateDistribution"] = aeStateDistribution
        confidenceSum += 85
        testCount++
        
        // Test 6: Frame-to-Frame Delta Analysis (impossible jumps)
        val exposureDeltas = frames.zipWithNext { a, b -> 
            abs(b.exposureTime - a.exposureTime).toDouble() / a.exposureTime 
        }
        val largeJumps = exposureDeltas.count { it > 0.5 }  // >50% change
        if (largeJumps > frames.size * 0.1) {  // More than 10% of frames
            anomalies.add("Exposure time has ${largeJumps} impossible jumps (>50% change)")
            totalScore += 15
        }
        details["exposureLargeJumps"] = largeJumps
        confidenceSum += 80
        testCount++
        
        // Test 7: Entropy Analysis (low entropy = predictable)
        val exposureEntropy = calculateEntropy(frames.map { it.exposureTime })
        if (exposureEntropy < 2.0) {  // Low entropy threshold
            anomalies.add("Exposure time has low entropy (${String.format("%.2f", exposureEntropy)})")
            totalScore += 10
        }
        details["exposureEntropy"] = exposureEntropy
        confidenceSum += 75
        testCount++
        
        // Test 8: Autocorrelation (high autocorrelation = periodic)
        val exposureAutocorr = calculateAutocorrelation(frames.map { it.exposureTime.toDouble() }, lag = 90)
        if (exposureAutocorr > 0.7) {  // High correlation at lag=90
            anomalies.add("Exposure time has high autocorrelation at lag 90 (${String.format("%.2f", exposureAutocorr)})")
            totalScore += 20
        }
        details["exposureAutocorr90"] = exposureAutocorr
        confidenceSum += 85
        testCount++
        
        val avgConfidence = confidenceSum / testCount
        val finalScore = totalScore.coerceIn(0, 100)
        
        // Determine primary periodicity frequency
        val primaryFrequency = when {
            exposurePeriodicity.isPeriodic -> "${exposurePeriodicity.frequency} frames (${String.format("%.1f", exposurePeriodicity.frequency / 30.0)}s)"
            isoPeriodicity.isPeriodic -> "${isoPeriodicity.frequency} frames (${String.format("%.1f", isoPeriodicity.frequency / 30.0)}s)"
            else -> null
        }
        
        return AnalysisResult(
            score = finalScore,
            periodicityDetected = exposurePeriodicity.isPeriodic || isoPeriodicity.isPeriodic,
            periodicityFrequency = primaryFrequency,
            anomalies = anomalies,
            confidence = avgConfidence,
            details = details
        )
    }
    
    /**
     * FFT-based periodicity detection
     */
    private data class PeriodicityResult(
        val isPeriodic: Boolean,
        val frequency: Int,  // Dominant frequency in frames
        val confidence: Int
    )
    
    private fun detectPeriodicity(signal: List<Double>): PeriodicityResult {
        if (signal.size < 64) {
            return PeriodicityResult(false, 0, 0)
        }
        
        // Simple DFT (Discrete Fourier Transform) - not optimized FFT
        val n = signal.size.coerceAtMost(256)  // Limit to 256 samples for performance
        val magnitudes = mutableListOf<Double>()
        
        for (k in 1 until n / 2) {  // Skip DC component (k=0)
            var real = 0.0
            var imag = 0.0
            
            for (t in 0 until n) {
                val angle = 2.0 * PI * k * t / n
                real += signal[t] * cos(angle)
                imag += signal[t] * sin(angle)
            }
            
            val magnitude = sqrt(real * real + imag * imag)
            magnitudes.add(magnitude)
        }
        
        // Find dominant frequency
        val maxMagnitude = magnitudes.maxOrNull() ?: 0.0
        val maxIndex = magnitudes.indexOf(maxMagnitude)
        val dominantFrequency = maxIndex + 1
        
        // Calculate average magnitude (excluding dominant peak)
        val avgMagnitude = magnitudes.filterIndexed { i, _ -> i != maxIndex }.average()
        
        // Peak-to-average ratio (high ratio = strong periodicity)
        val peakToAvgRatio = if (avgMagnitude > 0) maxMagnitude / avgMagnitude else 0.0
        
        // Periodicity detected if peak is significantly higher than average
        val isPeriodic = peakToAvgRatio > 3.0  // Threshold: 3x average
        
        // Convert frequency index to period (frames)
        val period = if (dominantFrequency > 0) n / dominantFrequency else 0
        
        // Confidence based on peak-to-average ratio
        val confidence = (peakToAvgRatio * 20).toInt().coerceIn(0, 100)
        
        return PeriodicityResult(
            isPeriodic = isPeriodic,
            frequency = period,
            confidence = confidence
        )
    }
    
    /**
     * Calculate variance of a signal
     */
    private fun calculateVariance(signal: List<Double>): Double {
        val mean = signal.average()
        return signal.map { (it - mean).pow(2) }.average()
    }
    
    /**
     * Calculate Shannon entropy
     */
    private fun calculateEntropy(signal: List<Long>): Double {
        // Bin the values into 20 buckets
        val min = signal.minOrNull() ?: return 0.0
        val max = signal.maxOrNull() ?: return 0.0
        val range = max - min
        if (range == 0L) return 0.0
        
        val buckets = IntArray(20)
        signal.forEach { value ->
            val bucket = ((value - min).toDouble() / range * 19).toInt().coerceIn(0, 19)
            buckets[bucket]++
        }
        
        // Calculate entropy
        var entropy = 0.0
        buckets.forEach { count ->
            if (count > 0) {
                val p = count.toDouble() / signal.size
                entropy -= p * log2(p)
            }
        }
        
        return entropy
    }
    
    /**
     * Calculate autocorrelation at a specific lag
     */
    private fun calculateAutocorrelation(signal: List<Double>, lag: Int): Double {
        if (lag >= signal.size) return 0.0
        
        val mean = signal.average()
        val variance = calculateVariance(signal)
        if (variance == 0.0) return 0.0
        
        var sum = 0.0
        for (i in 0 until signal.size - lag) {
            sum += (signal[i] - mean) * (signal[i + lag] - mean)
        }
        
        return sum / ((signal.size - lag) * variance)
    }
    
    fun reset() {
        frames.clear()
    }
}
