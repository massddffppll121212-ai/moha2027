package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceRecorderActivity : ComponentActivity() {

  private lateinit var etNoteTitle: EditText
  private lateinit var tvStatus: TextView
  private lateinit var tvTimer: TextView
  private lateinit var btnRecordToggle: ImageButton
  private lateinit var tvActionHint: TextView
  private lateinit var btnCancelRecording: Button
  private lateinit var btnBack: ImageButton

  private var timerJob: Job? = null
  private var awaitingFinishAfterStop = false

  @SuppressLint("InvalidFragmentVersionForActivityResult")
  private val micPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      val micGranted = result[Manifest.permission.RECORD_AUDIO] == true || hasMicPermission()
      if (micGranted) {
        startManualRecording()
      } else {
        Toast.makeText(this, "صلاحية الميكروفون مطلوبة للتسجيل الصوتي.", Toast.LENGTH_SHORT).show()
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureAppCheck(this, intent)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.parseColor("#0E1B36")),
      navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#111111")),
    )
    setContentView(R.layout.activity_voice_recorder)

    val root = findViewById<View>(R.id.voice_recorder_root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }

    etNoteTitle = findViewById(R.id.et_voice_note_title)
    tvStatus = findViewById(R.id.tv_voice_status)
    tvTimer = findViewById(R.id.tv_voice_timer)
    btnRecordToggle = findViewById(R.id.btn_voice_record_toggle)
    tvActionHint = findViewById(R.id.tv_voice_action_hint)
    btnCancelRecording = findViewById(R.id.btn_cancel_voice_recording)
    btnBack = findViewById(R.id.btn_back_voice)

    btnBack.setOnClickListener {
      if (VoiceRecorderService.isRecordingFlow.value) {
        Toast.makeText(this, "أوقف التسجيل أو ألغه قبل الرجوع.", Toast.LENGTH_SHORT).show()
      } else {
        finish()
      }
    }

    btnRecordToggle.setOnClickListener {
      if (VoiceRecorderService.isRecordingFlow.value) {
        stopAndSaveRecording()
      } else {
        requestMicAndStartRecording()
      }
    }

    btnCancelRecording.setOnClickListener {
      val cancelIntent =
        Intent(this, VoiceRecorderService::class.java).apply {
          action = VoiceRecorderService.ACTION_CANCEL_VOICE_RECORDING
        }
      startService(cancelIntent)
      awaitingFinishAfterStop = false
      Toast.makeText(this, "تم إلغاء التسجيل الصوتي.", Toast.LENGTH_SHORT).show()
    }

    observeService()
  }

  private fun hasMicPermission(): Boolean {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
  }

  private fun requestMicAndStartRecording() {
    if (hasMicPermission()) {
      startManualRecording()
      return
    }
    val permissions =
      buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          add(Manifest.permission.POST_NOTIFICATIONS)
        }
      }.toTypedArray()
    micPermissionLauncher.launch(permissions)
  }

  private fun startManualRecording() {
    val enteredTitle = etNoteTitle.text?.toString()?.trim().orEmpty()
    val startIntent =
      Intent(this, VoiceRecorderService::class.java).apply {
        action = VoiceRecorderService.ACTION_START_VOICE_RECORDING
        putExtra(VoiceRecorderService.EXTRA_NOTE_TITLE, enteredTitle)
      }
    ContextCompat.startForegroundService(this, startIntent)
  }

  private fun stopAndSaveRecording() {
    val enteredTitle = etNoteTitle.text?.toString()?.trim().orEmpty()
    awaitingFinishAfterStop = true
    val stopIntent =
      Intent(this, VoiceRecorderService::class.java).apply {
        action = VoiceRecorderService.ACTION_STOP_VOICE_RECORDING
        putExtra(VoiceRecorderService.EXTRA_NOTE_TITLE, enteredTitle)
      }
    startService(stopIntent)
  }

  private fun observeService() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          VoiceRecorderService.isRecordingFlow.collect { isRecording ->
            renderRecordingState(isRecording)
            if (!isRecording && awaitingFinishAfterStop) {
              awaitingFinishAfterStop = false
              Toast.makeText(
                this@VoiceRecorderActivity,
                "تم حفظ التسجيل الصوتي في CallVault/VoiceNotes.",
                Toast.LENGTH_SHORT,
              ).show()
              finish()
            }
          }
        }
        launch {
          VoiceRecorderService.activeTitleFlow.collect { activeTitle ->
            if (activeTitle.isNotBlank() && etNoteTitle.text.isNullOrBlank()) {
              etNoteTitle.setText(activeTitle)
            }
          }
        }
        launch {
          VoiceRecorderService.lastErrorFlow.collect { errorMsg ->
            if (!errorMsg.isNullOrBlank()) {
              Toast.makeText(this@VoiceRecorderActivity, errorMsg, Toast.LENGTH_SHORT).show()
            }
          }
        }
      }
    }
  }

  private fun renderRecordingState(isRecording: Boolean) {
    timerJob?.cancel()
    if (isRecording) {
      tvStatus.text = "جاري التسجيل من الميكروفون الآن..."
      tvStatus.setTextColor(Color.parseColor("#FCA5A5"))
      btnRecordToggle.setImageResource(R.drawable.ic_stop)
      tvActionHint.text = "اضغط لإيقاف وحفظ التسجيل"
      btnCancelRecording.visibility = View.VISIBLE

      timerJob =
        lifecycleScope.launch {
          while (true) {
            val startMs = VoiceRecorderService.recordingStartTimeFlow.value
            val elapsedMs =
              if (startMs > 0L) (System.currentTimeMillis() - startMs).coerceAtLeast(0L) else 0L
            tvTimer.text = formatDuration(elapsedMs)
            delay(250L)
          }
        }
    } else {
      tvStatus.text = "جاهز للتسجيل اليدوي من الميكروفون"
      tvStatus.setTextColor(Color.parseColor("#93C5FD"))
      tvTimer.text = "00:00"
      btnRecordToggle.setImageResource(R.drawable.ic_mic)
      tvActionHint.text = "اضغط لبدء التسجيل"
      btnCancelRecording.visibility = View.GONE
    }
  }

  override fun onDestroy() {
    timerJob?.cancel()
    super.onDestroy()
  }
}
