/**
 * VirtuCam Detection SDK v2 - Application Logic
 */

let detector = null;
let stream = null;
let isAnalyzing = false;
let frameCount = 0;
let faceCount = 0;
let animationId = null;
let lastFrameTime = 0;
let fps = 0;

const MAX_FRAMES = 300;

// DOM elements
const video = document.getElementById('video');
const faceCanvas = document.getElementById('faceCanvas');
const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d', { willReadFrequently: true });
const faceCtx = faceCanvas.getContext('2d');

const btnStart = document.getElementById('btnStart');
const btnStop = document.getElementById('btnStop');
const btnReset = document.getElementById('btnReset');

const frameCounter = document.getElementById('frameCounter');
const statusIndicator = document.getElementById('statusIndicator');
const progressFill = document.getElementById('progressFill');
const progressText = document.getElementById('progressText');

const statFrames = document.getElementById('statFrames');
const statFaces = document.getElementById('statFaces');
const statQuality = document.getElementById('statQuality');
const statFPS = document.getElementById('statFPS');

const resultsContainer = document.getElementById('resultsContainer');
const detectionsList = document.getElementById('detectionsList');
const logConsole = document.getElementById('logConsole');

// Initialize
async function init() {
    try {
        addLog('Initializing VirtuCam Detection SDK v2...', 'info');
        detector = new VirtuCamDetectorV2();
        await detector.initialize();
        addLog('✓ TensorFlow.js BlazeFace model loaded', 'success');
        addLog('✓ SDK ready for analysis', 'success');
    } catch (error) {
        addLog(`✗ Initialization failed: ${error.message}`, 'error');
        console.error(error);
    }
}

// Start camera and analysis
async function startAnalysis() {
    try {
        addLog('Requesting camera access...', 'info');
        
        stream = await navigator.mediaDevices.getUserMedia({
            video: {
                width: { ideal: 1280 },
                height: { ideal: 720 },
                facingMode: 'user'
            }
        });
        
        video.srcObject = stream;
        await video.play();
        
        // Setup face canvas overlay
        faceCanvas.width = video.videoWidth;
        faceCanvas.height = video.videoHeight;
        
        addLog('✓ Camera started', 'success');
        addLog('Starting real-time ML analysis...', 'info');
        
        isAnalyzing = true;
        frameCount = 0;
        faceCount = 0;
        detector.reset();
        detector.startTime = Date.now();
        
        statusIndicator.classList.add('active');
        btnStart.disabled = true;
        btnStop.disabled = false;
        btnReset.disabled = true;
        
        processFrame();
        
    } catch (error) {
        addLog(`✗ Camera access failed: ${error.message}`, 'error');
        
        if (error.name === 'NotAllowedError') {
            alert('Camera permission denied. Please allow camera access and try again.');
        } else if (error.name === 'NotFoundError') {
            alert('No camera found. Please connect a camera and try again.');
        } else {
            alert(`Camera error: ${error.message}`);
        }
    }
}

// Process each frame
async function processFrame() {
    if (!isAnalyzing) return;
    
    try {
        // Calculate FPS
        const now = performance.now();
        if (lastFrameTime > 0) {
            const delta = now - lastFrameTime;
            fps = Math.round(1000 / delta);
        }
        lastFrameTime = now;
        
        // Analyze frame
        const frameData = await detector.analyzeFrame(video, canvas, ctx);
        frameCount++;
        
        // Update face count
        if (frameData.faceCount > 0) {
            faceCount++;
        }
        
        // Draw face boxes
        drawFaceBoxes(frameData.faces);
        
        // Update UI
        updateStats();
        updateProgress();
        
        // Check if we've collected enough frames
        if (frameCount >= MAX_FRAMES) {
            await finishAnalysis();
            return;
        }
        
        // Continue processing
        animationId = requestAnimationFrame(processFrame);
        
    } catch (error) {
        addLog(`✗ Frame processing error: ${error.message}`, 'error');
        console.error(error);
    }
}

// Draw face detection boxes
function drawFaceBoxes(faces) {
    faceCtx.clearRect(0, 0, faceCanvas.width, faceCanvas.height);
    
    if (!faces || faces.length === 0) return;
    
    faces.forEach(face => {
        const [x, y] = face.topLeft;
        const [x2, y2] = face.bottomRight;
        const width = x2 - x;
        const height = y2 - y;
        
        // Draw box
        faceCtx.strokeStyle = '#00ff00';
        faceCtx.lineWidth = 3;
        faceCtx.strokeRect(x, y, width, height);
        
        // Draw confidence
        faceCtx.fillStyle = '#00ff00';
        faceCtx.font = '16px monospace';
        faceCtx.fillText(`${(face.probability[0] * 100).toFixed(0)}%`, x, y - 5);
        
        // Draw landmarks if available
        if (face.landmarks) {
            faceCtx.fillStyle = '#ff0000';
            face.landmarks.forEach(landmark => {
                faceCtx.beginPath();
                faceCtx.arc(landmark[0], landmark[1], 3, 0, 2 * Math.PI);
                faceCtx.fill();
            });
        }
    });
}

// Update statistics
function updateStats() {
    statFrames.textContent = frameCount;
    statFaces.textContent = faceCount;
    statQuality.textContent = faceCount > 0 ? Math.round((faceCount / frameCount) * 100) + '%' : '0%';
    statFPS.textContent = fps;
    
    frameCounter.textContent = `Frames: ${frameCount} / ${MAX_FRAMES}`;
}

// Update progress bar
function updateProgress() {
    const progress = (frameCount / MAX_FRAMES) * 100;
    progressFill.style.width = progress + '%';
    progressText.textContent = `Analyzing... ${frameCount} / ${MAX_FRAMES} frames (${progress.toFixed(1)}%)`;
}

// Finish analysis and generate report
async function finishAnalysis() {
    addLog('Analysis complete. Generating report...', 'info');
    
    isAnalyzing = false;
    if (animationId) {
        cancelAnimationFrame(animationId);
    }
    
    statusIndicator.classList.remove('active');
    btnStop.disabled = true;
    btnReset.disabled = false;
    
    progressText.textContent = 'Generating report...';
    
    // Generate report
    const report = await detector.generateReport();
    displayReport(report);
    
    addLog(`✓ Report generated. Risk score: ${report.riskScore.toFixed(1)}`, 'success');
}

// Display analysis report
function displayReport(report) {
    let scoreClass = 'success';
    if (report.riskScore >= 60) scoreClass = 'danger';
    else if (report.riskScore >= 30) scoreClass = 'warning';
    
    let verdictText = '';
    let verdictEmoji = '';
    switch (report.verdict) {
        case 'LIKELY_REAL':
            verdictText = 'Likely Real Camera';
            verdictEmoji = '✅';
            break;
        case 'SUSPICIOUS':
            verdictText = 'Suspicious - Needs Review';
            verdictEmoji = '⚠️';
            break;
        case 'LIKELY_SPOOFED':
            verdictText = 'Likely Spoofed/Virtual';
            verdictEmoji = '❌';
            break;
    }
    
    resultsContainer.innerHTML = `
        <div class="score-circle ${scoreClass}">
            ${report.riskScore.toFixed(0)}
        </div>
        <h3 style="text-align: center; margin: 10px 0;">
            ${verdictEmoji} ${verdictText}
        </h3>
        <p style="text-align: center; color: #6c757d; margin-bottom: 20px;">
            Risk Score: ${report.riskScore.toFixed(1)} / 100
        </p>
        <div style="background: white; padding: 15px; border-radius: 8px; margin-bottom: 15px;">
            <strong>Analysis Summary:</strong><br>
            • Frames analyzed: ${report.totalFrames}<br>
            • Duration: ${report.duration.toFixed(1)}s<br>
            • Detections: ${report.detections.length}<br>
            • Suspicious flags: ${report.detections.filter(d => d.suspicious).length}
        </div>
        <button onclick="downloadReport()" class="btn-primary">
            📥 Download Full Report (JSON)
        </button>
    `;
    
    // Display individual detections
    detectionsList.innerHTML = '<h3 style="margin-bottom: 15px;">Detection Details:</h3>';
    
    report.detections.forEach(detection => {
        let itemClass = 'detection-item';
        if (detection.suspicious) {
            itemClass += detection.score >= 60 ? ' danger' : ' warning';
        } else {
            itemClass += ' success';
        }
        
        const icon = detection.suspicious ? '⚠️' : '✓';
        
        detectionsList.innerHTML += `
            <div class="${itemClass}">
                <strong>${icon} ${detection.name}</strong><br>
                <small>Score: ${detection.score.toFixed(1)} / 100</small><br>
                <small>${detection.details}</small>
            </div>
        `;
    });
    
    // Store report for download
    window.currentReport = report;
}

// Download report
function downloadReport() {
    if (!window.currentReport) return;
    
    const blob = new Blob([JSON.stringify(window.currentReport, null, 2)], {
        type: 'application/json'
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `virtucam-analysis-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
    
    addLog('✓ Report downloaded', 'success');
}

// Stop analysis
function stopAnalysis() {
    isAnalyzing = false;
    
    if (animationId) {
        cancelAnimationFrame(animationId);
    }
    
    if (stream) {
        stream.getTracks().forEach(track => track.stop());
        stream = null;
    }
    
    video.srcObject = null;
    statusIndicator.classList.remove('active');
    
    btnStart.disabled = false;
    btnStop.disabled = true;
    btnReset.disabled = false;
    
    progressText.textContent = 'Stopped';
    addLog('Analysis stopped', 'warn');
}

// Reset everything
function resetAnalysis() {
    stopAnalysis();
    
    frameCount = 0;
    faceCount = 0;
    fps = 0;
    
    detector.reset();
    faceCtx.clearRect(0, 0, faceCanvas.width, faceCanvas.height);
    
    updateStats();
    progressFill.style.width = '0%';
    progressText.textContent = 'Ready to start';
    
    resultsContainer.innerHTML = `
        <div class="loading">
            <p style="color: #6c757d;">Start camera to begin real-time ML analysis</p>
        </div>
    `;
    
    detectionsList.innerHTML = '';
    
    btnReset.disabled = true;
    
    addLog('Reset complete', 'info');
}

// Add log entry
function addLog(message, type = 'info') {
    const entry = document.createElement('div');
    entry.className = `log-entry ${type}`;
    entry.textContent = `[${new Date().toLocaleTimeString()}] [${type.toUpperCase()}] ${message}`;
    logConsole.appendChild(entry);
    logConsole.scrollTop = logConsole.scrollHeight;
}

// Event listeners
btnStart.addEventListener('click', startAnalysis);
btnStop.addEventListener('click', stopAnalysis);
btnReset.addEventListener('click', resetAnalysis);

// Initialize on load
window.addEventListener('load', init);

// Cleanup on unload
window.addEventListener('beforeunload', () => {
    if (stream) {
        stream.getTracks().forEach(track => track.stop());
    }
});
