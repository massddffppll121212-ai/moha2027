package com.example

import android.content.Context

enum class CallRecordingQuality(
  val storageValue: String,
  val bitrate: Int,
  val sampleRate: Int,
  val arabicLabel: String,
) {
  HIGH(
    storageValue = "HIGH",
    bitrate = 256_000,
    sampleRate = 48_000,
    arabicLabel = "عالية (256kbps)",
  ),
  MEDIUM(
    storageValue = "MEDIUM",
    bitrate = 128_000,
    sampleRate = 44_100,
    arabicLabel = "متوسطة (128kbps)",
  );

  companion object {
    fun fromStorage(raw: String?): CallRecordingQuality {
      return if (raw.equals(MEDIUM.storageValue, ignoreCase = true)) MEDIUM else HIGH
    }
  }
}

object CallVaultSettings {
  private const val PREFS_NAME = "callvault_app_settings"

  private const val KEY_AUTO_CALL_RECORDING = "calls_auto_recording"
  private const val KEY_CALL_RECORDING_QUALITY = "calls_recording_quality"
  private const val KEY_VOICE_TRANSCRIPTION_ENABLED = "voice_transcription_enabled"

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  // --- قسم 1: إعدادات المكالمات ---

  fun isAutoCallRecordingEnabled(context: Context): Boolean {
    return prefs(context).getBoolean(KEY_AUTO_CALL_RECORDING, true)
  }

  fun setAutoCallRecordingEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_AUTO_CALL_RECORDING, enabled).apply()
  }

  fun getCallRecordingQuality(context: Context): CallRecordingQuality {
    val raw = prefs(context).getString(KEY_CALL_RECORDING_QUALITY, CallRecordingQuality.HIGH.storageValue)
    return CallRecordingQuality.fromStorage(raw)
  }

  fun setCallRecordingQuality(context: Context, quality: CallRecordingQuality) {
    prefs(context).edit().putString(KEY_CALL_RECORDING_QUALITY, quality.storageValue).apply()
  }

  // --- قسم 2: إعدادات التسجيل الصوتي ---

  fun isVoiceTranscriptionEnabled(context: Context): Boolean {
    return prefs(context).getBoolean(KEY_VOICE_TRANSCRIPTION_ENABLED, true)
  }

  fun setVoiceTranscriptionEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_VOICE_TRANSCRIPTION_ENABLED, enabled).apply()
  }
}
