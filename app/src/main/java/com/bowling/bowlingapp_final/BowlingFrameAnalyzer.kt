package com.bowling.bowlingapp_final

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class BowlingFrameAnalyzer(context: Context, private val onResult: (AnalysisState) -> Unit) : ImageAnalysis.Analyzer {

    private var interpreter: Interpreter? = null
    private val inputBuffer = ByteBuffer.allocateDirect(1 * 320 * 320 * 3 * 4).order(ByteOrder.nativeOrder())
    
    // State
    private var pins = mutableListOf<PinState>()
    private var registered = false
    private var regFrames = 0
    private var fallCounter = 0
    private var carPath = mutableListOf<Point>()
    private var startTimeMs = 0L
    private var frameCount = 0
    private var previewW = 0
    private var previewH = 0

    // Reusable Mats
    private val rgbMat = Mat()
    private val hsvMat = Mat()
    private val scaledMat = Mat()
    private val carMask = Mat()
    private val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))

    init {
        try {
            val modelBuffer = loadModelFile(context, "model.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.d("BowlingCV", "✅ TFLite loaded manually")

            // Diagnostic logging for Bug 5
            val inputTensor = interpreter!!.getInputTensor(0)
            val outputTensor = interpreter!!.getOutputTensor(0)
            Log.d("BowlingCV", "Model input shape: ${inputTensor.shape().toList()}")
            Log.d("BowlingCV", "Model output shape: ${outputTensor.shape().toList()}")
        } catch (e: Exception) {
            Log.e("BowlingCV", "❌ TFLite load failed: ${e.message}")
        }
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = java.io.FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun processVideoFrame(inputMat: Mat): AnalysisState {
        // ✅ Ensure dimensions are updated
        previewW = inputMat.width()
        previewH = inputMat.height()
        
        Log.d("BowlingCV", "📥 Video frame received: ${previewW}x${previewH}")
        Imgproc.cvtColor(inputMat, hsvMat, Imgproc.COLOR_RGB2HSV)
        val detections = detectWithTFLite(inputMat)
        val carPos = detectCarHSV()
        
        // Always track car, even before pin registration
        carPos?.let { 
            carPath.add(it)
            if (carPath.size > 300) carPath.removeAt(0) 
        }
        
        if (!registered) tryRegister(detections)
        else {
            updatePins(detections)
        }
        
        val elapsed = if (startTimeMs > 0) ((System.currentTimeMillis() - startTimeMs) / 1000).toInt() else 0
        val isComplete = registered && pins.isNotEmpty() && pins.all { !it.isStanding }
        val state = AnalysisState(
            phase = if (registered) "TRACKING" else "SCANNING",
            pins = pins.map { it.copy() },
            carPath = carPath.toList(),
            fallenCount = pins.count { !it.isStanding },
            standingCount = pins.count { it.isStanding },
            elapsedSec = elapsed,
            previewW = previewW,
            previewH = previewH,
            isComplete = isComplete
        )
        Log.d("BowlingCV", "📤 STATE: pins=${state.pins.size} standing=${state.standingCount} fallen=${state.fallenCount} preview=${previewW}x${previewH}")
        return state
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        try {
            val image = imageProxy.image ?: run { imageProxy.close(); return }
            previewW = imageProxy.width
            previewH = imageProxy.height

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            // Build proper NV21: Y plane + interleaved VU
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)

            // NV21 needs VU interleaved — V first, then U, but we must respect strides
            val vPixelStride = vPlane.pixelStride
            val uPixelStride = uPlane.pixelStride

            if (vPixelStride == 2 && uPixelStride == 2) {
                // Fast path: planes are already interleaved on device
                vBuffer.get(nv21, ySize, vSize)
                // U follows naturally in the shared buffer; just copy remaining
            } else {
                // Fallback: manually interleave
                val vArr = ByteArray(vSize); vBuffer.get(vArr)
                val uArr = ByteArray(uSize); uBuffer.get(uArr)
                var idx = ySize
                val chromaHeight = imageProxy.height / 2
                val chromaWidth = imageProxy.width / 2
                for (row in 0 until chromaHeight) {
                    for (col in 0 until chromaWidth) {
                        nv21[idx++] = vArr[row * vPlane.rowStride / vPixelStride * vPixelStride + col * vPixelStride]
                        nv21[idx++] = uArr[row * uPlane.rowStride / uPixelStride * uPixelStride + col * uPixelStride]
                    }
                }
            }
            
            val yuvMat = Mat(imageProxy.height + imageProxy.height / 2, imageProxy.width, CvType.CV_8UC1)
            yuvMat.put(0, 0, nv21)
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21)
            yuvMat.release()

            frameCount++
            processCurrentFrame(rgbMat)

            val elapsed = if (startTimeMs > 0) ((System.currentTimeMillis() - startTimeMs) / 1000).toInt() else 0
            val isComplete = registered && pins.isNotEmpty() && pins.all { !it.isStanding }
            onResult(AnalysisState(
                phase = if (registered) "TRACKING" else "SCANNING",
                pins = pins.map { it.copy() },
                carPath = carPath.toList(),
                fallenCount = pins.count { !it.isStanding },
                standingCount = pins.count { it.isStanding },
                elapsedSec = elapsed,
                previewW = previewW,
                previewH = previewH,
                isComplete = isComplete
            ))
        } catch (e: Exception) {
            Log.e("BowlingCV", "Analysis error: ${e.message}", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun processCurrentFrame(mat: Mat) {
        Imgproc.cvtColor(mat, hsvMat, Imgproc.COLOR_RGB2HSV)
        val detections = detectWithTFLite(mat)
        val carPos = detectCarHSV()

        // Always track car, even before pin registration
        carPos?.let { 
            carPath.add(it)
            if (carPath.size > 300) carPath.removeAt(0) 
        }

        if (!registered) tryRegister(detections)
        else {
            updatePins(detections)
        }
    }

    // ✅ Corrected coordinate scaling for normalized TFLite output (0.0-1.0)
    private fun detectWithTFLite(rgb: Mat): List<Map<String, Any>> {
        if (interpreter == null) return emptyList()
        
        Imgproc.resize(rgb, scaledMat, Size(320.0, 320.0))
        val pxArr = ByteArray(320 * 320 * 3)
        scaledMat.get(0, 0, pxArr)
        inputBuffer.rewind()
        for (b in pxArr) inputBuffer.putFloat((b.toInt() and 0xFF) / 255f)
        
        val nmsOutputBuffer = ByteBuffer.allocateDirect(1 * 300 * 6 * 4).order(ByteOrder.nativeOrder())
        nmsOutputBuffer.rewind()
        interpreter!!.run(inputBuffer, nmsOutputBuffer)
        
        val data = FloatArray(300 * 6)
        nmsOutputBuffer.rewind()
        nmsOutputBuffer.asFloatBuffer().get(data)
        
        val results = mutableListOf<Map<String, Any>>()
        val frameW = rgb.width().toDouble()
        val frameH = rgb.height().toDouble()
        
        for (i in 0 until 300) {
            val cx = data[i * 6]
            val cy = data[i * 6 + 1]
            val w = data[i * 6 + 2]
            val h = data[i * 6 + 3]
            val conf = data[i * 6 + 4]
            val clsId = data[i * 6 + 5].toInt()
            
            if (conf < 0.25f) continue
            if (clsId == 0) continue 
            
            // ✅ Scaling normalized coordinates to frame pixels
            val px = cx * frameW
            val py = cy * frameH
            val pw = w * frameW
            val ph = h * frameH

            // ✅ Diagnostic Logging
            if (results.isEmpty() || frameCount % 30 == 0) {
                Log.d("BowlingCV", "📍 RAW TFLITE: cx=${String.format("%.3f", cx)} cy=${String.format("%.3f", cy)} | MODEL px: ${String.format("%.1f", px)} py: ${String.format("%.1f", py)} | FRAME: ${rgb.width()}x${rgb.height()}")
            }

            results.add(mapOf(
                "centroid" to Point(px, py),
                "cls" to clsId,
                "conf" to conf,
                "bbox" to Rect((px - pw / 2).toInt(), (py - ph / 2).toInt(), pw.toInt(), ph.toInt())
            ))
        }
        return results
    }

    private fun detectCarHSV(): Point? {
        Core.inRange(hsvMat, Scalar(5.0, 120.0, 100.0), Scalar(35.0, 255.0, 255.0), carMask)
        Imgproc.morphologyEx(carMask, carMask, Imgproc.MORPH_OPEN, kernel)
        val cnts = mutableListOf<MatOfPoint>()
        Imgproc.findContours(carMask, cnts, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        if (cnts.isEmpty()) return null
        val big = cnts.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        if (Imgproc.contourArea(big) < 100) return null
        val r = Imgproc.boundingRect(big)
        val p = Point(r.x + r.width / 2.0, r.y + r.height / 2.0)
        big.release()
        cnts.forEach { it.release() }
        return p
    }

    private fun tryRegister(dets: List<Map<String, Any>>) {
        val standing = dets.filter { it["cls"] == 3 }
        if (standing.size >= 5) {
            regFrames++
            if (regFrames >= 8) {
                pins = standing.mapIndexed { i, d -> PinState(i, d["centroid"] as Point, true, -1, 0.0, d["bbox"] as Rect) }.toMutableList()
                registered = true
                startTimeMs = System.currentTimeMillis()
                fallCounter = 0
                Log.d("BowlingCV", "✅ Registered ${pins.size} pins")
            }
        } else regFrames = 0
    }

    private fun updatePins(dets: List<Map<String, Any>>) {
        val standingDets = dets.filter { it["cls"] == 3 }
        val fallenDets = dets.filter { it["cls"] == 2 }
        val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0

        for (pin in pins) {
            if (!pin.isStanding) continue
            val explicitFall = fallenDets.any { dist(pin.centroid, it["centroid"] as Point) < 60.0 }
            if (explicitFall) {
                pin.isStanding = false
                pin.fallOrder = ++fallCounter
                pin.fallTimeSec = elapsedSec
                continue
            }
            val match = closest(pin.centroid, standingDets, 70.0)
            if (match != null) {
                if (dist(pin.centroid, match["centroid"] as Point) > 50.0) pin.isStanding = false
                else {
                    pin.centroid = match["centroid"] as Point
                    pin.bbox = match["bbox"] as Rect
                }
            } else pin.isStanding = false
        }
        pins.removeAll { !it.isStanding && it.fallOrder == -1 }
    }

    private fun closest(p: Point, dets: List<Map<String, Any>>, rad: Double): Map<String, Any>? {
        var best: Map<String, Any>? = null; var bd = Double.MAX_VALUE
        for (d in dets) { val dd = dist(p, d["centroid"] as Point); if (dd < bd) { bd = dd; best = d } }
        return if (bd < rad) best else null
    }
    private fun dist(a: Point, b: Point) = Math.hypot(a.x - b.x, a.y - b.y)
}
