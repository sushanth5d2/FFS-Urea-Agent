package com.ffsagent

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(0, 96, 52)
        window.navigationBarColor = Color.rgb(0, 96, 52)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(18))
            setBackgroundResource(R.drawable.splash_bg)
        }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ffs_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(220), dp(220)))
        root.addView(TextView(this).apply {
            text = "FFS Agent"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Automate Urea Booking\nfor Farmers"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(24))
        })
        root.addView(TextView(this).apply {
            text = "Powered by Sushanth Chithaluri"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        setContentView(root)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1400L)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
