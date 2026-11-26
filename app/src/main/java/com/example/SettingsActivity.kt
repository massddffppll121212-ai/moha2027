package com.example

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : ComponentActivity() {

  private lateinit var switchAutoCallRecording: Switch
  private lateinit var switchCallsAutoUpload: Switch
  private lateinit var btnQualityHigh: TextView
  private lateinit var btnQualityMedium: TextView
  private lateinit var tvCallQualityHint: TextView

  private lateinit var switchVoiceTranscription: Switch
  private lateinit var btnStoragePhoneOnly: TextView
  private lateinit var btnStoragePhoneAndFirebase: TextView
  private lateinit var tvVoiceStorageHint: TextView

  private lateinit var btnBack: ImageButton
  private lateinit var btnSaveDone: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureAppCheck(this, intent)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.parseColor("#0E1B36")),
      navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#111111")),
    )
    setContentView(R.layout.activity_settings)

    val root = findViewById<View>(R.id.settings_root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }

    bindViews()
    loadSavedSettings()
    setupListeners()
  }

  private fun bindViews() {
    btnBack = findViewById(R.id.btn_settings_back)
    btnSaveDone = findViewById(R.id.btn_save_settings_done)

    switchAutoCallRecording = findViewById(R.id.switch_auto_call_recording)
    switchCallsAutoUpload = findViewById(R.id.switch_calls_auto_upload)
    btnQualityHigh = findViewById(R.id.btn_quality_high)
    btnQualityMedium = findViewById(R.id.btn_quality_medium)
    tvCallQualityHint = findViewById(R.id.tv_call_quality_hint)

    switchVoiceTranscription = findViewById(R.id.switch_voice_transcription)
    btnStoragePhoneOnly = findViewById(R.id.btn_storage_phone_only)
    btnStoragePhoneAndFirebase = findViewById(R.id.btn_storage_phone_and_firebase)
    tvVoiceStorageHint = findViewById(R.id.tv_voice_storage_hint)

    applySwitchColors(switchAutoCallRecording)
    applySwitchColors(switchCallsAutoUpload)
    applySwitchColors(switchVoiceTranscription)
  }

  private fun applySwitchColors(switchView: Switch) {
    val thumbColors =
      ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#CBD5E1")),
      )
    val trackColors =
      ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(Color.parseColor("#1D4ED8"), Color.parseColor("#1E293B")),
      )
    switchView.thumbTintList = thumbColors
    switchView.trackTintList = trackColors
  }

  private fun loadSavedSettings() {
    switchAutoCallRecording.isChecked = CallVaultSettings.isAutoCallRecordingEnabled(this)
    switchCallsAutoUpload.isChecked = CallVaultSettings.isCallsAutoUploadFirebaseEnabled(this)
    renderCallQuality(CallVaultSettings.getCallRecordingQuality(this))

    switchVoiceTranscription.isChecked = CallVaultSettings.isVoiceTranscriptionEnabled(this)
    renderVoiceStorageTarget(CallVaultSettings.getVoiceStorageTarget(this))
  }

  private fun setupListeners() {
    btnBack.setOnClickListener { finish() }

    btnSaveDone.setOnClickListener {
      Toast.makeText(this, "تم حفظ الإعدادات.", Toast.LENGTH_SHORT).show()
      finish()
    }

    findViewById<View>(R.id.row_auto_call_recording).setOnClickListener {
      switchAutoCallRecording.toggle()
    }
    switchAutoCallRecording.setOnCheckedChangeListener { _, isChecked ->
      CallVaultSettings.setAutoCallRecordingEnabled(this, isChecked)
    }

    findViewById<View>(R.id.row_calls_auto_upload).setOnClickListener {
      switchCallsAutoUpload.toggle()
    }
    switchCallsAutoUpload.setOnCheckedChangeListener { _, isChecked ->
      CallVaultSettings.setCallsAutoUploadFirebaseEnabled(this, isChecked)
    }

    btnQualityHigh.setOnClickListener {
      CallVaultSettings.setCallRecordingQuality(this, CallRecordingQuality.HIGH)
      renderCallQuality(CallRecordingQuality.HIGH)
    }

    btnQualityMedium.setOnClickListener {
      CallVaultSettings.setCallRecordingQuality(this, CallRecordingQuality.MEDIUM)
      renderCallQuality(CallRecordingQuality.MEDIUM)
    }

    findViewById<View>(R.id.row_voice_transcription).setOnClickListener {
      switchVoiceTranscription.toggle()
    }
    switchVoiceTranscription.setOnCheckedChangeListener { _, isChecked ->
      CallVaultSettings.setVoiceTranscriptionEnabled(this, isChecked)
    }

    btnStoragePhoneOnly.setOnClickListener {
      CallVaultSettings.setVoiceStorageTarget(this, VoiceStorageTarget.PHONE_ONLY)
      renderVoiceStorageTarget(VoiceStorageTarget.PHONE_ONLY)
    }

    btnStoragePhoneAndFirebase.setOnClickListener {
      CallVaultSettings.setVoiceStorageTarget(this, VoiceStorageTarget.PHONE_AND_FIREBASE)
      renderVoiceStorageTarget(VoiceStorageTarget.PHONE_AND_FIREBASE)
    }
  }

  private fun renderCallQuality(quality: CallRecordingQuality) {
    val isHigh = quality == CallRecordingQuality.HIGH
    updatePillStyle(btnQualityHigh, isHigh)
    updatePillStyle(btnQualityMedium, !isHigh)
    tvCallQualityHint.text =
      if (isHigh) {
        "جودة عالية: 256 kbps • 48 kHz لنقاء أوضح للمكالمات"
      } else {
        "جودة متوسطة: 128 kbps • 44.1 kHz لتوفير مساحة التخزين"
      }
  }

  private fun renderVoiceStorageTarget(target: VoiceStorageTarget) {
    val isPhoneOnly = target == VoiceStorageTarget.PHONE_ONLY
    updatePillStyle(btnStoragePhoneOnly, isPhoneOnly)
    updatePillStyle(btnStoragePhoneAndFirebase, !isPhoneOnly)
    tvVoiceStorageHint.text =
      if (isPhoneOnly) {
        "يتم حفظ التسجيل الصوتي في الهاتف فقط (CallVault/VoiceNotes) دون رفع لـ Firebase."
      } else {
        "يتم حفظ التسجيل الصوتي في الهاتف ومزامنته مع voice_notes و Firebase Storage."
      }
  }

  private fun updatePillStyle(pill: TextView, selected: Boolean) {
    if (selected) {
      pill.setBackgroundResource(R.drawable.bg_pill_selected)
      pill.setTextColor(Color.WHITE)
    } else {
      pill.setBackgroundResource(R.drawable.bg_pill_unselected)
      pill.setTextColor(Color.parseColor("#94A3B8"))
    }
  }
}
