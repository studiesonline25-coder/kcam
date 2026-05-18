/**
 * VirtuCam Detection SDK v2 - Real ML-Powered Analysis
 * Uses TensorFlow.js BlazeFace for face detection
 * Implements real spoofing detection algorithms
 */

class VirtuCamDetectorV2 {
    constructor() {
        this.model = null;
        this.frames = [];
        this.faceHistory = [];
        this.brightnessHistory = [];
        this.motionHistory = [];
        this.previousFrame = null;
        this.startTime = null;
        this.detections = [];
    }

    async initialize() {
        console.log('[Detector] Loading BlazeFace model...');
        this.model = await blazeface.load();
        console.log('[Detector] Model loaded successfully');
    }

    async analyzeFrame(videoElement, canvas, ctx) {
        const width = videoElement.videoWidth;
        const height = videoElement.videoHeight;
        
        canvas.width = width;
        canvas.height = height;
        ctx.drawImage(videoElement, 0, 0, width, height);
        
        const imageData = ctx.getImageData(0, 0, width, height);
        const frameData = {
            timestamp: Date.now(),
            imageData: imageData,
            width: width,
            height: height
        };
        
        // Face detection with TensorFlow.js
        const predictions = await this.model.estimateFaces(videoElement, false);
        frameData.faces = predictions;
        frameData.faceCount = predictions.length;
        
        // Brightness analysis
        frameData.brightness = this.calculateBrightness(imageData);
        this.brightnessHistory.push(frameData.brightness);
        
        // Motion analysis (optical flow approximation)
        if (this.previousFrame) {
            frameData.motion = this.calculateMotion(this.previousFrame, imageData);
            this.motionHistory.push(frameData.motion);
        }
        
        // Face consistency
        if (predictions.length > 0) {
            this.faceHistory.push({
                count: predictions.length,
                boxes: predictions.map(p => ({
                    x: p.topLeft[0],
                    y: p.topLeft[1],
                    width: p.bottomRight[0] - p.topLeft[0],
                    height: p.bottomRight[1] - p.topLeft[1],
                    probability: p.probability
                })),
                timestamp: frameData.timestamp
            });
        }
        
        this.frames.push(frameData);
        this.previousFrame = imageData;
        
        return frameData;
    }

    calculateBrightness(imageData) {
        const data = imageData.data;
        let sum = 0;
        
        // Sample every 10th pixel for performance
        for (let i = 0; i < data.length; i += 40) {
            const r = data[i];
            const g = data[i + 1];
            const b = data[i + 2];
            // Luminance formula
            sum += 0.299 * r + 0.587 * g + 0.114 * b;
        }
        
        return sum / (data.length / 40);
    }

    calculateMotion(prevImageData, currImageData) {
        const prev = prevImageData.data;
        const curr = currImageData.data;
        let diff = 0;
        
        // Sample every 100th pixel for performance
        for (let i = 0; i < prev.length; i += 400) {
            const dr = Math.abs(curr[i] - prev[i]);
            const dg = Math.abs(curr[i + 1] - prev[i + 1]);
            const db = Math.abs(curr[i + 2] - prev[i + 2]);
            diff += (dr + dg + db) / 3;
        }
        
        return diff / (prev.length / 400);
    }

    // FFT for periodicity detection
    fft(values) {
        const n = values.length;
        if (n <= 1) return values;
        
        // Simple DFT for small arrays (real FFT would use Cooley-Tukey)
        const result = new Array(n);
        for (let k = 0; k < n; k++) {
            let real = 0;
            let imag = 0;
            for (let t = 0; t < n; t++) {
                const angle = -2 * Math.PI * t * k / n;
                real += values[t] * Math.cos(angle);
                imag += values[t] * Math.sin(angle);
            }
            result[k] = Math.sqrt(real * real + imag * imag);
        }
        return result;
    }

    detectPeriodicity(values, name) {
        if (values.length < 30) return null;
        
        // Take last 128 samples for FFT
        const samples = values.slice(-128);
        const spectrum = this.fft(samples);
        
        // Find dominant frequency (skip DC component at index 0)
        let maxMagnitude = 0;
        let maxIndex = 0;
        for (let i = 1; i < spectrum.length / 2; i++) {
            if (spectrum[i] > maxMagnitude) {
                maxMagnitude = spectrum[i];
                maxIndex = i;
            }
        }
        
        // Calculate periodicity score (0-100)
        const avgMagnitude = spectrum.slice(1, spectrum.length / 2).reduce((a, b) => a + b, 0) / (spectrum.length / 2 - 1);
        const periodicityScore = (maxMagnitude / avgMagnitude - 1) * 10; // Reduced multiplier
        
        return {
            score: Math.min(100, Math.max(0, periodicityScore)),
            dominantFrequency: maxIndex,
            suspicious: periodicityScore > 50 // Much higher threshold - only flag obvious sine waves
        };
    }

    analyzeFaceConsistency() {
        if (this.faceHistory.length < 10) return null;
        
        const recent = this.faceHistory.slice(-60); // Last 2 seconds at 30fps
        
        // Check for sudden disappearances
        let disappearances = 0;
        for (let i = 1; i < recent.length; i++) {
            if (recent[i - 1].count > 0 && recent[i].count === 0) {
                disappearances++;
            }
        }
        
        // Check for face count changes
        const faceCounts = recent.map(f => f.count);
        const uniqueCounts = [...new Set(faceCounts)];
        const stability = 1 - (uniqueCounts.length / recent.length);
        
        // Check for face position jumps
        let jumps = 0;
        for (let i = 1; i < recent.length; i++) {
            if (recent[i - 1].boxes.length > 0 && recent[i].boxes.length > 0) {
                const prev = recent[i - 1].boxes[0];
                const curr = recent[i].boxes[0];
                const dx = Math.abs(curr.x - prev.x);
                const dy = Math.abs(curr.y - prev.y);
                const distance = Math.sqrt(dx * dx + dy * dy);
                
                // Jump if face moved more than 50 pixels in one frame
                if (distance > 50) {
                    jumps++;
                }
            }
        }
        
        return {
            disappearances: disappearances,
            stability: stability * 100,
            jumps: jumps,
            suspicious: disappearances > 3 || stability < 0.7 || jumps > 5
        };
    }

    analyzeMotionPatterns() {
        if (this.motionHistory.length < 30) return null;
        
        const recent = this.motionHistory.slice(-90); // Last 3 seconds
        
        // Calculate motion variance
        const mean = recent.reduce((a, b) => a + b, 0) / recent.length;
        const variance = recent.reduce((a, b) => a + Math.pow(b - mean, 2), 0) / recent.length;
        const stdDev = Math.sqrt(variance);
        
        // Detect if motion is too uniform (sign of screen recording)
        const coefficientOfVariation = mean > 0 ? stdDev / mean : 1;
        
        // Real camera has CV > 0.2, screen recording often < 0.1
        return {
            mean: mean,
            stdDev: stdDev,
            cv: coefficientOfVariation,
            suspicious: coefficientOfVariation < 0.1 && mean > 1
        };
    }

    detectScreenRecordingArtifacts() {
        if (this.frames.length < 30) return null;
        
        const recent = this.frames.slice(-30);
        
        // Check for compression artifacts (screen recordings often have uniform compression)
        const brightnessValues = recent.map(f => f.brightness);
        const brightnessRange = Math.max(...brightnessValues) - Math.min(...brightnessValues);
        
        // Real camera has wider brightness range due to auto-exposure
        // Screen recording has narrow range
        return {
            brightnessRange: brightnessRange,
            suspicious: brightnessRange < 3 && this.frames.length > 60 // Very strict - only flag if almost no variation
        };
    }

    async generateReport() {
        const report = {
            timestamp: new Date().toISOString(),
            totalFrames: this.frames.length,
            duration: this.startTime ? (Date.now() - this.startTime) / 1000 : 0,
            detections: [],
            riskScore: 0,
            verdict: 'UNKNOWN'
        };

        // 1. Brightness periodicity
        const brightnessPeriodicity = this.detectPeriodicity(this.brightnessHistory, 'Brightness');
        if (brightnessPeriodicity) {
            report.detections.push({
                name: 'Brightness Periodicity',
                score: brightnessPeriodicity.score,
                suspicious: brightnessPeriodicity.suspicious,
                details: `Dominant frequency: ${brightnessPeriodicity.dominantFrequency}, Score: ${brightnessPeriodicity.score.toFixed(1)}`
            });
        }

        // 2. Motion periodicity
        const motionPeriodicity = this.detectPeriodicity(this.motionHistory, 'Motion');
        if (motionPeriodicity) {
            report.detections.push({
                name: 'Motion Periodicity',
                score: motionPeriodicity.score,
                suspicious: motionPeriodicity.suspicious,
                details: `Dominant frequency: ${motionPeriodicity.dominantFrequency}, Score: ${motionPeriodicity.score.toFixed(1)}`
            });
        }

        // 3. Face consistency
        const faceConsistency = this.analyzeFaceConsistency();
        if (faceConsistency) {
            const faceScore = (faceConsistency.disappearances * 5) + (faceConsistency.jumps * 2) + ((100 - faceConsistency.stability) * 0.3);
            report.detections.push({
                name: 'Face Detection Consistency',
                score: Math.min(100, faceScore),
                suspicious: faceConsistency.suspicious,
                details: `Disappearances: ${faceConsistency.disappearances}, Jumps: ${faceConsistency.jumps}, Stability: ${faceConsistency.stability.toFixed(1)}%`
            });
        }

        // 4. Motion patterns
        const motionPattern = this.analyzeMotionPatterns();
        if (motionPattern) {
            const motionScore = motionPattern.suspicious ? 70 : 10;
            report.detections.push({
                name: 'Motion Pattern Analysis',
                score: motionScore,
                suspicious: motionPattern.suspicious,
                details: `CV: ${motionPattern.cv.toFixed(3)}, Mean: ${motionPattern.mean.toFixed(2)}`
            });
        }

        // 5. Screen recording artifacts
        const screenArtifacts = this.detectScreenRecordingArtifacts();
        if (screenArtifacts) {
            const artifactScore = screenArtifacts.suspicious ? 80 : 5;
            report.detections.push({
                name: 'Screen Recording Artifacts',
                score: artifactScore,
                suspicious: screenArtifacts.suspicious,
                details: `Brightness range: ${screenArtifacts.brightnessRange.toFixed(2)}`
            });
        }

        // Calculate overall risk score (weighted average)
        const suspiciousDetections = report.detections.filter(d => d.suspicious);
        const avgScore = report.detections.reduce((sum, d) => sum + d.score, 0) / report.detections.length;
        
        report.riskScore = Math.min(100, avgScore + (suspiciousDetections.length * 5));
        
        // Verdict
        if (report.riskScore < 30) {
            report.verdict = 'LIKELY_REAL';
        } else if (report.riskScore < 60) {
            report.verdict = 'SUSPICIOUS';
        } else {
            report.verdict = 'LIKELY_SPOOFED';
        }

        return report;
    }

    reset() {
        this.frames = [];
        this.faceHistory = [];
        this.brightnessHistory = [];
        this.motionHistory = [];
        this.previousFrame = null;
        this.startTime = null;
        this.detections = [];
    }
}
