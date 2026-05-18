package com.virtucam.hooks

import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Ultra-realistic noise generator combining:
 * 1. Perlin noise for smooth, natural variations
 * 2. CMOS sensor noise model (shot noise, read noise, dark current)
 * 3. Temporal coherence (noise evolves smoothly over time)
 * 
 * Replaces predictable sine waves with statistically realistic camera sensor behavior.
 */
object RealisticNoiseGenerator {
    
    // Perlin noise permutation table (256 values, shuffled)
    private val permutation = IntArray(512)
    
    init {
        // Initialize with random permutation
        val p = (0..255).toList().shuffled(Random(System.currentTimeMillis()))
        for (i in 0..255) {
            permutation[i] = p[i]
            permutation[i + 256] = p[i]
        }
    }
    
    /**
     * 1D Perlin noise - smooth, continuous noise function
     * Returns value in range [-1.0, 1.0]
     */
    private fun perlin1D(x: Double): Double {
        val X = floor(x).toInt() and 255
        val xf = x - floor(x)
        
        val u = fade(xf)
        
        val a = permutation[X]
        val b = permutation[X + 1]
        
        return lerp(u, grad(a, xf), grad(b, xf - 1.0))
    }
    
    /**
     * 2D Perlin noise for more complex patterns
     * Returns value in range [-1.0, 1.0]
     */
    private fun perlin2D(x: Double, y: Double): Double {
        val X = floor(x).toInt() and 255
        val Y = floor(y).toInt() and 255
        
        val xf = x - floor(x)
        val yf = y - floor(y)
        
        val u = fade(xf)
        val v = fade(yf)
        
        val aa = permutation[permutation[X] + Y]
        val ab = permutation[permutation[X] + Y + 1]
        val ba = permutation[permutation[X + 1] + Y]
        val bb = permutation[permutation[X + 1] + Y + 1]
        
        val x1 = lerp(u, grad(aa, xf, yf), grad(ba, xf - 1, yf))
        val x2 = lerp(u, grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1))
        
        return lerp(v, x1, x2)
    }
    
    /**
     * Fade function for smooth interpolation (6t^5 - 15t^4 + 10t^3)
     */
    private fun fade(t: Double): Double {
        return t * t * t * (t * (t * 6 - 15) + 10)
    }
    
    /**
     * Linear interpolation
     */
    private fun lerp(t: Double, a: Double, b: Double): Double {
        return a + t * (b - a)
    }
    
    /**
     * Gradient function for 1D
     */
    private fun grad(hash: Int, x: Double): Double {
        return if ((hash and 1) == 0) x else -x
    }
    
    /**
     * Gradient function for 2D
     */
    private fun grad(hash: Int, x: Double, y: Double): Double {
        val h = hash and 3
        val u = if (h < 2) x else y
        val v = if (h < 2) y else x
        return (if ((h and 1) == 0) u else -u) + (if ((h and 2) == 0) v else -v)
    }
    
    /**
     * Box-Muller transform for Gaussian (normal) distribution
     * Returns random value with mean=0, stddev=1
     */
    private fun gaussianRandom(): Double {
        val u1 = Random.nextDouble()
        val u2 = Random.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
    
    /**
     * CMOS Sensor Noise Model
     * Combines:
     * - Shot noise (Poisson, proportional to √signal)
     * - Read noise (Gaussian, constant)
     * - Dark current noise (temperature-dependent, increases over time)
     * 
     * @param signalLevel Current signal level (e.g., ISO value)
     * @param frameNumber Current frame number (for temporal variation)
     * @return Noise value to add to signal
     */
    fun cmosNoise(signalLevel: Double, frameNumber: Long): Double {
        // Shot noise: proportional to square root of signal (Poisson approximation)
        val shotNoise = gaussianRandom() * sqrt(signalLevel.coerceAtLeast(1.0)) * 0.3
        
        // Read noise: constant Gaussian noise from sensor electronics
        val readNoise = gaussianRandom() * 2.5
        
        // Dark current: increases slightly with time (sensor heating)
        // Use Perlin noise for smooth temporal variation
        val thermalPhase = frameNumber.toDouble() / 300.0  // Slow drift over ~10 seconds
        val darkCurrent = perlin1D(thermalPhase) * 1.5
        
        return shotNoise + readNoise + darkCurrent
    }
    
    /**
     * Realistic exposure time variation
     * Uses multi-octave Perlin noise for natural camera auto-exposure behavior
     * 
     * @param frameNumber Current frame number
     * @param baseExposure Base exposure time in nanoseconds
     * @return Realistic exposure time with natural variation
     */
    fun realisticExposureTime(frameNumber: Long, baseExposure: Long): Long {
        val t = frameNumber.toDouble() / 30.0  // Time in seconds (assuming 30fps)
        
        // Multi-octave Perlin noise (combine multiple frequencies)
        val noise1 = perlin1D(t * 0.5) * 0.04      // Slow drift (±4%)
        val noise2 = perlin1D(t * 2.0) * 0.015     // Medium variation (±1.5%)
        val noise3 = perlin1D(t * 8.0) * 0.005     // Fast jitter (±0.5%)
        
        val totalNoise = noise1 + noise2 + noise3
        
        // Add CMOS sensor noise
        val sensorNoise = cmosNoise(baseExposure.toDouble() / 1_000_000.0, frameNumber) * 0.0001
        
        val variation = totalNoise + sensorNoise
        return (baseExposure * (1.0 + variation)).toLong().coerceAtLeast(1000L)
    }
    
    /**
     * Realistic ISO sensitivity variation
     * Models auto-ISO behavior with scene-dependent adjustments
     * 
     * @param frameNumber Current frame number
     * @param baseIso Base ISO value
     * @return Realistic ISO with natural variation
     */
    fun realisticIsoSensitivity(frameNumber: Long, baseIso: Int): Int {
        val t = frameNumber.toDouble() / 30.0
        
        // Multi-octave Perlin noise
        val noise1 = perlin1D(t * 0.3) * 15.0      // Slow drift (±15 ISO)
        val noise2 = perlin1D(t * 1.5) * 5.0       // Medium variation (±5 ISO)
        val noise3 = perlin1D(t * 6.0) * 2.0       // Fast jitter (±2 ISO)
        
        val totalNoise = noise1 + noise2 + noise3
        
        // Add sensor noise
        val sensorNoise = cmosNoise(baseIso.toDouble(), frameNumber) * 0.5
        
        val variation = totalNoise + sensorNoise
        return (baseIso + variation).toInt().coerceAtLeast(50)
    }
    
    /**
     * Realistic auto-exposure state transitions
     * Uses 2D Perlin noise (time + scene complexity) for natural state machine behavior
     * 
     * @param frameNumber Current frame number
     * @param sceneComplexity Measure of scene complexity (0.0 - 1.0)
     * @return AE state: 0=INACTIVE, 1=SEARCHING, 2=CONVERGED, 3=LOCKED, 4=FLASH_REQUIRED
     */
    fun realisticAeState(frameNumber: Long, sceneComplexity: Double = 0.5): Int {
        val t = frameNumber.toDouble() / 30.0
        
        // 2D Perlin noise: time + scene complexity
        val noise = perlin2D(t * 0.8, sceneComplexity * 10.0)
        
        // State transitions based on noise thresholds
        return when {
            noise < -0.6 -> 1  // SEARCHING (low probability)
            noise < 0.3 -> 2   // CONVERGED (most common)
            noise < 0.7 -> 3   // LOCKED (occasional)
            else -> 2          // CONVERGED (fallback)
        }
    }
    
    /**
     * Realistic focus distance variation
     * Models continuous autofocus micro-adjustments
     * 
     * @param frameNumber Current frame number
     * @param baseFocusDistance Base focus distance in diopters
     * @return Realistic focus distance with micro-adjustments
     */
    fun realisticFocusDistance(frameNumber: Long, baseFocusDistance: Float): Float {
        val t = frameNumber.toDouble() / 30.0
        
        // Very subtle Perlin noise for focus breathing
        val noise1 = perlin1D(t * 0.4) * 0.02      // Slow drift (±2%)
        val noise2 = perlin1D(t * 3.0) * 0.005     // Fast micro-adjustments (±0.5%)
        
        val totalNoise = noise1 + noise2
        
        return (baseFocusDistance * (1.0 + totalNoise)).toFloat().coerceAtLeast(0.0f)
    }
    
    /**
     * Realistic white balance variation
     * Models auto-white-balance adjustments to scene lighting
     * 
     * @param frameNumber Current frame number
     * @param baseColorTemp Base color temperature in Kelvin
     * @return Realistic color temperature with natural drift
     */
    fun realisticColorTemperature(frameNumber: Long, baseColorTemp: Int): Int {
        val t = frameNumber.toDouble() / 30.0
        
        // Multi-octave Perlin noise for AWB drift
        val noise1 = perlin1D(t * 0.2) * 150.0     // Slow drift (±150K)
        val noise2 = perlin1D(t * 1.0) * 50.0      // Medium variation (±50K)
        val noise3 = perlin1D(t * 4.0) * 20.0      // Fast jitter (±20K)
        
        val totalNoise = noise1 + noise2 + noise3
        
        return (baseColorTemp + totalNoise).toInt().coerceIn(2000, 10000)
    }
}
