package com.example

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : ComponentActivity() {

  private var hasNavigated = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureAppCheck(this, intent)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.parseColor("#111111")),
      navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#111111")),
    )
    setContentView(R.layout.activity_splash)

    val root = findViewById<View>(R.id.splash_root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }

    root.setOnClickListener { navigateToMain() }

    lifecycleScope.launch {
      delay(1100L)
      navigateToMain()
    }
  }

  private fun navigateToMain() {
    if (hasNavigated || isFinishing) return
    hasNavigated = true

    val mainIntent =
      Intent(this, MainActivity::class.java).apply {
        intent?.extras?.let { putExtras(it) }
      }
    startActivity(mainIntent)
    finish()
  }
}
