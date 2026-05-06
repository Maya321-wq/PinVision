package com.bowling.bowlingapp_final

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    // OpenCV Initialization
    init {
        if (!OpenCVLoader.initDebug()) {
            Log.e("BowlingCV", "❌ OpenCV init failed")
        } else {
            Log.d("BowlingCV", "✅ OpenCV initialized")
        }
    }

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analyzer: BowlingFrameAnalyzer
    private lateinit var overlayView: OverlayView
    private lateinit var previewView: PreviewView
    private lateinit var testImageView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayView = findViewById(R.id.overlayView)
        previewView = findViewById(R.id.previewView)
        testImageView = findViewById(R.id.testImageView)

        // ✅ Z-ORDER: Initial top layer force
        overlayView.bringToFront()

        cameraExecutor = Executors.newSingleThreadExecutor()
        analyzer = BowlingFrameAnalyzer(this) { state ->
            runOnUiThread { overlayView.update(state) }
        }

        if (checkCameraPermission()) startCamera()
        else requestPermission.launch(Manifest.permission.CAMERA)

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            recreate()
        }

        findViewById<Button>(R.id.btnTestVideo).setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }
    }

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
    }

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            previewView.visibility = View.GONE
            testImageView.visibility = View.VISIBLE
            
            // ✅ Z-ORDER FIX: Force overlay on top
            overlayView.bringToFront()
            
            VideoTestRunner.run(this, it, analyzer, testImageView) { state ->
                runOnUiThread { overlayView.update(state) }
            }
        }
    }

    private fun checkCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val provider = ProcessCameraProvider.getInstance(this)
        provider.addListener({
            try {
                val cameraProvider = provider.get()
                val preview = Preview.Builder().build().also { 
                    it.setSurfaceProvider(previewView.surfaceProvider) 
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { 
                        it.setAnalyzer(cameraExecutor, analyzer) 
                    }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                
                // ✅ Z-ORDER: Ensure overlay is on top after camera starts
                overlayView.bringToFront()
            } catch (e: Exception) {
                Log.e("BowlingCV", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
