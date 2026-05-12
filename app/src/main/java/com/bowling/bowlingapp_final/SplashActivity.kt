package com.bowling.bowlingapp_final

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo     = findViewById<ImageView>(R.id.logoImage)
        val name     = findViewById<TextView>(R.id.tvAppName)
        val tagline  = findViewById<TextView>(R.id.tvTagline)
        val divider  = findViewById<View>(R.id.divider)
        val badges   = findViewById<LinearLayout>(R.id.badgeRow)
        val status   = findViewById<LinearLayout>(R.id.statusRow)
        val progress = findViewById<ProgressBar>(R.id.progressBar)
        val scanLine = findViewById<View>(R.id.scanLine)

        val spannable = SpannableString("PinVision")
        spannable.setSpan(ForegroundColorSpan(Color.parseColor("#E8EEF5")), 0, 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(Color.parseColor("#4A9EFF")), 3, 9, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        name.text = spannable

        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        val scanAnim = ObjectAnimator.ofFloat(scanLine, "translationY", 0f, screenHeight)
        scanAnim.duration = 3000
        scanAnim.interpolator = LinearInterpolator()
        scanAnim.repeatCount = ObjectAnimator.INFINITE
        scanAnim.start()

        listOf<View>(logo, name, tagline, divider, badges, status, progress)
            .forEachIndexed { i, view ->
                view.alpha = 0f
                view.animate()
                    .alpha(1f)
                    .setStartDelay((i * 150 + 200).toLong())
                    .setDuration(500)
                    .start()
            }

        ObjectAnimator.ofInt(progress, "progress", 0, 100).apply {
            duration = 2200
            startDelay = 600
            start()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 3200)
    }
}
