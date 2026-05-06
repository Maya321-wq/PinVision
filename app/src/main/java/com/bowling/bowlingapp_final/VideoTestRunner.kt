package com.bowling.bowlingapp_final

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoCapture
import java.io.File
import java.io.FileOutputStream

object VideoTestRunner {
    fun run(context: Context, uri: Uri, analyzer: BowlingFrameAnalyzer, imageView: ImageView, callback: (AnalysisState) -> Unit) {
        Thread {
            Log.d("VideoTest", "▶️ Starting video test...")
            val temp = File(context.cacheDir, "test_video.mp4")
            try {
                context.contentResolver.openInputStream(uri)?.use { it.copyTo(FileOutputStream(temp)) }
                Thread.sleep(500)
            } catch (e: Exception) {
                Log.e("VideoTest", "❌ File copy failed: ${e.message}")
                return@Thread
            }

            // ✅ Z-ORDER & VISIBILITY: Ensure video is visible but managed
            Handler(Looper.getMainLooper()).post {
                imageView.visibility = View.VISIBLE
            }

            val cap = VideoCapture()
            if (!cap.open(temp.absolutePath)) {
                Log.e("VideoTest", "❌ VideoCapture failed to open")
                return@Thread
            }
            Log.d("VideoTest", "✅ VideoCapture opened. FPS: ${cap.get(org.opencv.videoio.Videoio.CAP_PROP_FPS)}")

            val mat = Mat()
            val rgb = Mat()
            val fps = cap.get(org.opencv.videoio.Videoio.CAP_PROP_FPS).coerceAtLeast(15.0)
            val targetDelay = (1000.0 / fps).toLong()
            var frameCount = 0
            
            var sharedBitmap: Bitmap? = null

            while (cap.read(mat)) {
                val start = System.currentTimeMillis()
                Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_BGR2RGB)
                
                if (sharedBitmap == null || sharedBitmap!!.width != rgb.cols() || sharedBitmap!!.height != rgb.rows()) {
                    sharedBitmap?.recycle()
                    sharedBitmap = Bitmap.createBitmap(rgb.cols(), rgb.rows(), Bitmap.Config.ARGB_8888)
                }
                Utils.matToBitmap(rgb, sharedBitmap!!)
                
                val state = analyzer.processVideoFrame(rgb)
                Handler(Looper.getMainLooper()).post {
                    imageView.setImageBitmap(sharedBitmap)
                    callback(state)
                }
                
                frameCount++
                if (frameCount % 30 == 0) Log.d("VideoTest", "⏳ Processed $frameCount frames")
                
                val elapsed = System.currentTimeMillis() - start
                val sleepTime = targetDelay - elapsed
                if (sleepTime > 0) Thread.sleep(sleepTime)
                
                rgb.release()
            }
            
            sharedBitmap?.recycle()
            cap.release(); mat.release(); temp.delete()
            Log.d("VideoTest", "✅ Video test complete. Total frames: $frameCount")
        }.start()
    }
}
