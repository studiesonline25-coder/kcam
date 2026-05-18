/**
 * VirtuCam Detection SDK - JavaScript Implementation
 * Browser-based camera spoofing detection
 */

class VirtuCamDetector {
    constructor(options = {}) {
        this.enableMetadataAnalysis = options.enableMetadataAnalysis !== false;
        this.enableFaceDetection = options.enableFaceDetection !== false;
        this.targetFrames = options.targetFrames || 300;
        
        this.frames = [];
        this.metadataFrames = [];
        this.faceDetectionResults = [];
        
        this.reset();
    }
    
    reset() {
        this.frames = [];
        this.metadataFrames = [];
        this.faceDetectionResults = [];
        this.frameCount = 0;
    }
    
    feedFrame(imageData, metadata = {}) {
        if (this.frameCount >= this.targetFrames) return;
        
        this.frames.push({
            frameNumber: this.frameCount,
            timestamp: Date.now(),
            imageData: imageData
        });
        
        // Store metadata (simulated - browser doesn't have real camera metadata)
        this.metadataFrames.push({
            frameNumber: this.frameCount,
            timestamp: Date.now(),
            // Simulated metadata from video stream
            brightness: this.calculateBrightness(imageData),
            ...metadata
        });
        
        this.frameCount++;
    }
    
    calculateBrightness(imageData) {
        let sum = 0;
        const data = imageData.data;
        const sampleSize = Math.min(10000, data.length / 4); // Sample 10k pixels
        
        for (let i = 0; i < sampleSize * 4; i += 4) {
            const r = data[i];
            const g = data[i + 1];
            const b = data[i + 2];
            sum += (r + g + b) / 3;
        }
        
        return sum / sampleSize;
    }
    
    async analyze() {
        const results = {
            spoofingDetected: false,
            overallScore: 0,
            confidence: 0,
            vulnerabilities: [],
            moduleResults: {}
        };
        
        if (this.frames.length < 100) {
            results.vulnerabilities.push({
                module: 'General',
                description: `Insufficient frames (need 100+, got ${this.frames.length})`,
                score: 0,
                severity: 'LOW'
            });
            results.confidence = 0;
            return results;
        }
        
        let totalScore = 0;
        let moduleCount = 0;
        
        // Analyze metadata patterns
        if (this.enableMetadataAnalysis) {
            const metadataResult = this.analyzeMetadata();
            results.moduleResults.metadata = metadataResult;
            totalScore += metadataResult.score;
            moduleCount++;
            
            metadataResult.anomalies.forEach(anomaly => {
                results.vulnerabilities.push({
                    module: 'Metadata Analysis',
                    description: anomaly,
                    score: metadataResult.score,
                    severity: this.getSeverity(metadataResult.score)
                });
            });
        }
        
        // Analyze face detection consistency
        if (this.enableFaceDetection && this.faceDetectionResults.length > 0) {
            const faceResult = this.analyzeFaceConsistency();
            results.moduleResults.faceConsistency = faceResult;
            totalScore += faceResult.score;
            moduleCount++;
            
            faceResult.anomalies.forEach(anomaly => {
                results.vulnerabilities.push({
                    module: 'Face Consistency',
                    description: anomaly,
                    score: faceResult.score,
                    severity: this.getSeverity(faceResult.score)
                });
            });
        }
        
        // Calculate overall score
        results.overallScore = moduleCount > 0 ? Math.round(totalScore / moduleCount) : 0;
        results.confidence = Math.min(95, Math.round((this.frames.length / this.targetFrames) * 100));
        results.spoofingDetected = results.overallScore > 50;
        
        // Sort vulnerabilities by score
        results.vulnerabilities.sort((a, b) => b.score - a.score);
        
        return results;
    }
    
    analyzeMetadata() {
        const result = {
            score: 0,
            confidence: 85,
            anomalies: [],
            details: {}
        };
        
        // Extract brightness values
        const brightness = this.metadataFrames.map(f => f.brightness);
        
        // Test 1: Periodicity detection (simplified FFT)
        const periodicity = this.detectPeriodicity(brightness);
        if (periodicity.isPeriodic) {
            result.anomalies.push(`Brightness follows periodic pattern (${periodicity.frequency} frames)`);
            result.score += 30;
            result.details.periodicity = periodicity.frequency;
        }
        
        // Test 2: Variance analysis
        const variance = this.calculateVariance(brightness);
        const mean = brightness.reduce((a, b) => a + b, 0) / brightness.length;
        const cv = Math.sqrt(variance) / mean;
        
        if (cv < 0.05) { // Less than 5% variation
            result.anomalies.push(`Brightness too consistent (CV: ${cv.toFixed(4)})`);
            result.score += 20;
            result.details.brightnessCV = cv;
        }
        
        // Test 3: Entropy analysis
        const entropy = this.calculateEntropy(brightness);
        if (entropy < 2.0) {
            result.anomalies.push(`Low entropy detected (${entropy.toFixed(2)})`);
            result.score += 15;
            result.details.entropy = entropy;
        }
        
        // Test 4: Frame timing regularity
        const timestamps = this.metadataFrames.map(f => f.timestamp);
        const intervals = [];
        for (let i = 1; i < timestamps.length; i++) {
            intervals.push(timestamps[i] - timestamps[i - 1]);
        }
        
        const intervalVariance = this.calculateVariance(intervals);
        const intervalMean = intervals.reduce((a, b) => a + b, 0) / intervals.length;
        const intervalCV = Math.sqrt(intervalVariance) / intervalMean;
        
        if (intervalCV < 0.1) { // Very regular timing
            result.anomalies.push(`Frame timing too regular (CV: ${intervalCV.toFixed(4)})`);
            result.score += 15;
            result.details.timingCV = intervalCV;
        }
        
        return result;
    }
    
    analyzeFaceConsistency() {
        const result = {
            score: 0,
            confidence: 90,
            anomalies: [],
            details: {}
        };
        
        // Count frames with/without faces
        const framesWithFaces = this.faceDetectionResults.filter(r => r.faces > 0).length;
        const framesWithoutFaces = this.faceDetectionResults.length - framesWithFaces;
        
        result.details.framesWithFaces = framesWithFaces;
        result.details.framesWithoutFaces = framesWithoutFaces;
        
        // Check for inconsistent face detection
        if (framesWithoutFaces > this.faceDetectionResults.length * 0.3) {
            result.anomalies.push(`Face detection inconsistent (${framesWithoutFaces} frames without faces)`);
            result.score += 25;
        }
        
        // Check for suspiciously consistent face count
        const faceCounts = this.faceDetectionResults.map(r => r.faces);
        const uniqueCounts = [...new Set(faceCounts)];
        
        if (uniqueCounts.length === 1 && this.faceDetectionResults.length > 50) {
            result.anomalies.push(`Face count never changes (always ${uniqueCounts[0]} faces)`);
            result.score += 20;
        }
        
        return result;
    }
    
    detectPeriodicity(signal) {
        if (signal.length < 64) {
            return { isPeriodic: false, frequency: 0 };
        }
        
        const n = Math.min(signal.length, 256);
        const magnitudes = [];
        
        // Simple DFT
        for (let k = 1; k < n / 2; k++) {
            let real = 0;
            let imag = 0;
            
            for (let t = 0; t < n; t++) {
                const angle = 2 * Math.PI * k * t / n;
                real += signal[t] * Math.cos(angle);
                imag += signal[t] * Math.sin(angle);
            }
            
            const magnitude = Math.sqrt(real * real + imag * imag);
            magnitudes.push(magnitude);
        }
        
        // Find dominant frequency
        const maxMagnitude = Math.max(...magnitudes);
        const maxIndex = magnitudes.indexOf(maxMagnitude);
        const avgMagnitude = magnitudes.reduce((a, b) => a + b, 0) / magnitudes.length;
        
        const peakToAvgRatio = maxMagnitude / avgMagnitude;
        const isPeriodic = peakToAvgRatio > 3.0;
        const period = maxIndex > 0 ? Math.round(n / maxIndex) : 0;
        
        return { isPeriodic, frequency: period };
    }
    
    calculateVariance(values) {
        const mean = values.reduce((a, b) => a + b, 0) / values.length;
        const squaredDiffs = values.map(v => Math.pow(v - mean, 2));
        return squaredDiffs.reduce((a, b) => a + b, 0) / values.length;
    }
    
    calculateEntropy(values) {
        // Bin values into 20 buckets
        const min = Math.min(...values);
        const max = Math.max(...values);
        const range = max - min;
        
        if (range === 0) return 0;
        
        const buckets = new Array(20).fill(0);
        values.forEach(v => {
            const bucket = Math.min(19, Math.floor((v - min) / range * 19));
            buckets[bucket]++;
        });
        
        // Calculate Shannon entropy
        let entropy = 0;
        buckets.forEach(count => {
            if (count > 0) {
                const p = count / values.length;
                entropy -= p * Math.log2(p);
            }
        });
        
        return entropy;
    }
    
    getSeverity(score) {
        if (score >= 70) return 'CRITICAL';
        if (score >= 50) return 'HIGH';
        if (score >= 30) return 'MEDIUM';
        return 'LOW';
    }
    
    generateJSONReport(result) {
        return JSON.stringify({
            timestamp: new Date().toISOString(),
            spoofingDetected: result.spoofingDetected,
            overallScore: result.overallScore,
            confidence: result.confidence,
            frameCount: this.frameCount,
            modules: result.moduleResults,
            vulnerabilities: result.vulnerabilities,
            recommendations: this.generateRecommendations(result)
        }, null, 2);
    }
    
    generateHTMLReport(result) {
        const html = `
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>VirtuCam Detection Report</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }
        .container { max-width: 1200px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; }
        h1 { color: #333; border-bottom: 3px solid #667eea; padding-bottom: 10px; }
        .summary { background: #f9f9f9; padding: 20px; border-radius: 5px; margin: 20px 0; }
        .score { font-size: 48px; font-weight: bold; color: ${result.spoofingDetected ? '#f44336' : '#4CAF50'}; }
        .vulnerability { background: #fff3cd; border-left: 4px solid #ffc107; padding: 15px; margin: 10px 0; }
        .vulnerability.critical { background: #f8d7da; border-left-color: #dc3545; }
        .vulnerability.high { background: #fff3cd; border-left-color: #ffc107; }
        .vulnerability.medium { background: #d1ecf1; border-left-color: #17a2b8; }
        .vulnerability.low { background: #d4edda; border-left-color: #28a745; }
    </style>
</head>
<body>
    <div class="container">
        <h1>🔍 VirtuCam Detection Report</h1>
        <div class="summary">
            <h2>${result.spoofingDetected ? '⚠️ SPOOFING DETECTED' : '✅ NO SPOOFING DETECTED'}</h2>
            <div class="score">${result.overallScore}/100</div>
            <p>Confidence: ${result.confidence}%</p>
            <p>Frames Analyzed: ${this.frameCount}</p>
            <p>Timestamp: ${new Date().toLocaleString()}</p>
        </div>
        
        <h2>🚨 Vulnerabilities</h2>
        ${result.vulnerabilities.map(v => `
            <div class="vulnerability ${v.severity.toLowerCase()}">
                <strong>[${v.severity}]</strong> ${v.module}: ${v.description}
                <br><small>Score: ${v.score}/100</small>
            </div>
        `).join('')}
        
        <h2>💡 Recommendations</h2>
        <ul>
            ${this.generateRecommendations(result).map(r => `<li>${r}</li>`).join('')}
        </ul>
    </div>
</body>
</html>
        `;
        return html;
    }
    
    generateRecommendations(result) {
        const recommendations = [];
        
        result.vulnerabilities.forEach(vuln => {
            if (vuln.description.includes('periodic pattern')) {
                recommendations.push('Replace periodic patterns with realistic noise (Perlin noise + CMOS sensor model)');
            }
            if (vuln.description.includes('too consistent')) {
                recommendations.push('Add realistic variance to metadata and frame timing');
            }
            if (vuln.description.includes('Low entropy')) {
                recommendations.push('Increase randomness in metadata generation');
            }
            if (vuln.description.includes('Face detection inconsistent')) {
                recommendations.push('Ensure consistent face detection across all frames');
            }
        });
        
        return [...new Set(recommendations)]; // Remove duplicates
    }
}
