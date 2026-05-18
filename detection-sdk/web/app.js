/**
 * VirtuCam Detection SDK - Web Application
 */

let detector = null;
let video = null;
let canvas = null;
let faceCanvas = null;
let ctx = null;
let faceCtx = null;
let stream = null;
let captureInterval = null;
let faceDetector = null;

// Initialize
document.addEventListener('DOMContentLoaded', async () => {
    video = document.getElementById('video');
    canvas = document.getElementById('canvas');
    faceCanvas = document.getElementById('faceCanvas');
    ctx = canvas.getContext('2d');
    faceCtx = faceCanvas.getContext('2d');
    
    detector = new VirtuCamDetector({
        enableMetadataAnalysis: true,
        enableFaceDetection: true,
        targetFrames: 300
    });
    
    // Initialize face detection API if available
    if ('FaceDetector' in window) {
        try {
            faceDetector = new FaceDetector();
            console.log('Face Detection API available');
        } catch (e) {
            console.log('Face Detection API not supported:', e);
        }
    }
    
    // Setup event listeners
    document.getElementById('btnStart').addEventListener('click', startCamera);
    document.getElementById('btnStop').addEventListener('click', stopCamera);
    document.getElementById('btnAnalyze').addEventListener('click', analyzeFrames);
    document.getElementById('btnReset').addEventListener('click', resetAnalysis);
    document.getElementById('btnDownloadJSON').addEventListener('click', downloadJSON);
    document.getElementById('btnDownloadHTML').addEventListener('click', downloadHTML);
});

async function startCamera() {
    try {
        stream = await navigator.mediaDevices.getUserMedia({
            video: {
                width: { ideal: 1920 },
                height: { ideal: 1080 },
                facingMode: 'user'
            },
            audio: false
        });
        
        video.srcObject = stream;
        
        // Setup canvas sizes
        video.addEventListener('loadedmetadata', () => {
            canvas.width = video.videoWidth;
            canvas.height = video.videoHeight;
            faceCanvas.width = video.videoWidth;
            faceCanvas.height = video.videoHeight;
        });
        
        // Update UI
        document.getElementById('btnStart').disabled = true;
        document.getElementById('btnStop').disabled = false;
        document.getElementById('btnAnalyze').disabled = false;
        document.getElementById('btnReset').disabled = false;
        
        // Start capturing frames
        startCapture();
        
    } catch (error) {
        console.error('Error accessing camera:', error);
        alert('Failed to access camera. Please grant camera permissions.');
    }
}

function stopCamera() {
    if (stream) {
        stream.getTracks().forEach(track => track.stop());
        stream = null;
    }
    
    if (captureInterval) {
        clearInterval(captureInterval);
        captureInterval = null;
    }
    
    // Update UI
    document.getElementById('btnStart').disabled = false;
    document.getElementById('btnStop').disabled = true;
}

function startCapture() {
    let frameCount = 0;
    const targetFrames = 300;
    
    captureInterval = setInterval(async () => {
        if (frameCount >= targetFrames) {
            clearInterval(captureInterval);
            document.getElementById('progressText').textContent = 'Capture complete! Click "Analyze Now" to see results.';
            return;
        }
        
        // Capture frame
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        
        // Detect faces
        let faceCount = 0;
        if (faceDetector) {
            try {
                const faces = await faceDetector.detect(canvas);
                faceCount = faces.length;
                
                // Draw face rectangles
                faceCtx.clearRect(0, 0, faceCanvas.width, faceCanvas.height);
                faceCtx.strokeStyle = '#00ff00';
                faceCtx.lineWidth = 3;
                
                faces.forEach(face => {
                    const { top, left, width, height } = face.boundingBox;
                    faceCtx.strokeRect(left, top, width, height);
                });
                
                detector.faceDetectionResults.push({
                    frameNumber: frameCount,
                    faces: faceCount
                });
            } catch (e) {
                // Face detection failed, continue
            }
        }
        
        // Feed frame to detector
        detector.feedFrame(imageData);
        
        frameCount++;
        
        // Update UI
        const progress = (frameCount / targetFrames) * 100;
        document.getElementById('progressFill').style.width = `${progress}%`;
        document.getElementById('progressText').textContent = `Capturing frames... ${frameCount} / ${targetFrames}`;
        document.getElementById('frameCounter').textContent = `Frames: ${frameCount} / ${targetFrames}`;
        document.getElementById('statFrames').textContent = frameCount;
        document.getElementById('statFaces').textContent = faceCount;
        
    }, 100); // Capture at ~10fps
}

async function analyzeFrames() {
    // Show loading
    document.getElementById('resultsContainer').innerHTML = `
        <div class="loading active">
            <div class="spinner"></div>
            <p>Analyzing ${detector.frameCount} frames...</p>
            <p style="color: #6c757d;">This may take a few seconds</p>
        </div>
    `;
    
    // Disable buttons during analysis
    document.getElementById('btnAnalyze').disabled = true;
    
    // Run analysis (with small delay to show loading)
    setTimeout(async () => {
        const result = await detector.analyze();
        displayResults(result);
        
        // Re-enable analyze button
        document.getElementById('btnAnalyze').disabled = false;
    }, 500);
}

function displayResults(result) {
    // Hide loading, show results
    document.getElementById('resultsContainer').style.display = 'none';
    document.getElementById('resultsDisplay').style.display = 'block';
    
    // Update score circle
    const scoreCircle = document.getElementById('scoreCircle');
    const scoreValue = document.getElementById('scoreValue');
    const scoreLabel = document.getElementById('scoreLabel');
    const scoreConfidence = document.getElementById('scoreConfidence');
    
    scoreValue.textContent = result.overallScore;
    scoreConfidence.textContent = `Confidence: ${result.confidence}%`;
    
    // Color code based on score
    scoreCircle.className = 'score-circle';
    if (result.overallScore >= 70) {
        scoreCircle.classList.add('danger');
        scoreLabel.textContent = '⚠️ SPOOFING DETECTED';
        scoreLabel.style.color = '#dc3545';
    } else if (result.overallScore >= 50) {
        scoreCircle.classList.add('warning');
        scoreLabel.textContent = '⚠️ HIGH RISK';
        scoreLabel.style.color = '#ffc107';
    } else if (result.overallScore >= 30) {
        scoreCircle.classList.add('warning');
        scoreLabel.textContent = '⚠️ MEDIUM RISK';
        scoreLabel.style.color = '#ff9800';
    } else {
        scoreLabel.textContent = '✅ LOW RISK';
        scoreLabel.style.color = '#28a745';
    }
    
    // Display vulnerabilities
    const vulnerabilitiesList = document.getElementById('vulnerabilitiesList');
    vulnerabilitiesList.innerHTML = '';
    
    if (result.vulnerabilities.length === 0) {
        vulnerabilitiesList.innerHTML = '<li style="color: #28a745; padding: 15px;">✅ No vulnerabilities detected</li>';
    } else {
        result.vulnerabilities.forEach(vuln => {
            const li = document.createElement('li');
            li.className = `vulnerability-item ${vuln.severity.toLowerCase()}`;
            li.innerHTML = `
                <div class="vulnerability-header">
                    <strong>${vuln.module}</strong>
                    <span class="severity-badge ${vuln.severity.toLowerCase()}">${vuln.severity}</span>
                </div>
                <p style="margin: 0; color: #333;">${vuln.description}</p>
                <small style="color: #6c757d;">Score: ${vuln.score}/100</small>
            `;
            vulnerabilitiesList.appendChild(li);
        });
    }
    
    // Display recommendations
    const recommendations = detector.generateRecommendations(result);
    if (recommendations.length > 0) {
        document.getElementById('recommendationsSection').style.display = 'block';
        const recommendationsList = document.getElementById('recommendationsList');
        recommendationsList.innerHTML = '';
        
        recommendations.forEach(rec => {
            const li = document.createElement('li');
            li.textContent = rec;
            recommendationsList.appendChild(li);
        });
    } else {
        document.getElementById('recommendationsSection').style.display = 'none';
    }
    
    // Store result for download
    window.detectionResult = result;
}

function resetAnalysis() {
    detector.reset();
    
    // Reset UI
    document.getElementById('progressFill').style.width = '0%';
    document.getElementById('progressText').textContent = 'Ready to start analysis';
    document.getElementById('frameCounter').textContent = 'Frames: 0 / 300';
    document.getElementById('statFrames').textContent = '0';
    document.getElementById('statFaces').textContent = '0';
    
    document.getElementById('resultsContainer').innerHTML = `
        <div class="loading active">
            <p style="color: #6c757d;">Start the camera and capture 300 frames to begin analysis</p>
        </div>
    `;
    document.getElementById('resultsContainer').style.display = 'block';
    document.getElementById('resultsDisplay').style.display = 'none';
    
    // Clear face canvas
    if (faceCtx) {
        faceCtx.clearRect(0, 0, faceCanvas.width, faceCanvas.height);
    }
}

function downloadJSON() {
    if (!window.detectionResult) return;
    
    const json = detector.generateJSONReport(window.detectionResult);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    
    const a = document.createElement('a');
    a.href = url;
    a.download = `virtucam-detection-${Date.now()}.json`;
    a.click();
    
    URL.revokeObjectURL(url);
}

function downloadHTML() {
    if (!window.detectionResult) return;
    
    const html = detector.generateHTMLReport(window.detectionResult);
    const blob = new Blob([html], { type: 'text/html' });
    const url = URL.createObjectURL(blob);
    
    const a = document.createElement('a');
    a.href = url;
    a.download = `virtucam-detection-${Date.now()}.html`;
    a.click();
    
    URL.revokeObjectURL(url);
}
