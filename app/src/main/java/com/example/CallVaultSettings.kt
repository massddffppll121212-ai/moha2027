package com.example

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

data class ExcludedNumberEntry(
  val number: String,
  val label: String = "",
)

object CallVaultSettings {
  private const val PREFS_NAME = "callvault_app_settings"

  private const val KEY_AUTO_CALL_RECORDING = "calls_auto_recording"
  private const val KEY_CALL_RECORDING_QUALITY = "calls_recording_quality"
  private const val KEY_CALL_AUTO_TRANSCRIPTION = "calls_auto_transcription_enabled"
  private const val KEY_CALL_SPEAKER_RECORDING = "calls_speaker_mic_recording_enabled"
  private const val KEY_EXCLUDED_NUMBERS_JSON = "calls_excluded_numbers_json"
  private const val KEY_SHORT_RECORDING_THRESHOLD_SEC = "short_recording_threshold_seconds"
  private const val KEY_VOICE_TRANSCRIPTION_ENABLED = "voice_transcription_enabled"
  private const val KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled"

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

  fun isCallAutoTranscriptionEnabled(context: Context): Boolean {
    return prefs(context).getBoolean(KEY_CALL_AUTO_TRANSCRIPTION, false)
  }

  fun setCallAutoTranscriptionEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_CALL_AUTO_TRANSCRIPTION, enabled).apply()
  }

  fun isCallSpeakerRecordingEnabled(context: Context): Boolean {
    return prefs(context).getBoolean(KEY_CALL_SPEAKER_RECORDING, true)
  }

  fun setCallSpeakerRecordingEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_CALL_SPEAKER_RECORDING, enabled).apply()
  }

  // --- قائمة الأرقام المستبعدة من التسجيل ---

  fun normalizePhoneDigits(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.filter { it.isDigit() }
  }

  fun getExcludedNumbers(context: Context): List<ExcludedNumberEntry> {
    val raw = prefs(context).getString(KEY_EXCLUDED_NUMBERS_JSON, null) ?: return emptyList()
    return try {
      val array = JSONArray(raw)
      buildList {
        for (i in 0 until array.length()) {
          val obj = array.optJSONObject(i) ?: continue
          val num = obj.optString("number", "").trim()
          if (num.isNotEmpty()) {
            add(ExcludedNumberEntry(number = num, label = obj.optString("label", "").trim()))
          }
        }
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun saveExcludedNumbers(context: Context, list: List<ExcludedNumberEntry>) {
    val array = JSONArray()
    for (entry in list) {
      array.put(
        JSONObject().apply {
          put("number", entry.number)
          put("label", entry.label)
        }
      )
    }
    prefs(context).edit().putString(KEY_EXCLUDED_NUMBERS_JSON, array.toString()).apply()
  }

  fun addExcludedNumber(context: Context, rawNumber: String, label: String = ""): Boolean {
    val cleanNumber = rawNumber.trim()
    val cleanDigits = normalizePhoneDigits(cleanNumber)
    if (cleanDigits.length < 3) return false

    val current = getExcludedNumbers(context).toMutableList()
    val existingIdx =
      current.indexOfFirst { numbersMatch(it.number, cleanNumber) }
    val resolvedLabel =
      label.trim().ifEmpty { CallMetadataStore.resolveCallerName(context, cleanNumber).orEmpty() }
    val newEntry = ExcludedNumberEntry(number = cleanNumber, label = resolvedLabel)
    if (existingIdx >= 0) {
      current[existingIdx] = newEntry
    } else {
      current.add(0, newEntry)
    }
    saveExcludedNumbers(context, current)
    return true
  }

  fun removeExcludedNumber(context: Context, numberToRemove: String) {
    val updated =
      getExcludedNumbers(context).filterNot { numbersMatch(it.number, numberToRemove) }
    saveExcludedNumbers(context, updated)
  }

  fun isNumberExcluded(context: Context, incomingOrOutgoingNumber: String?): Boolean {
    if (incomingOrOutgoingNumber.isNullOrBlank()) return false
    val excluded = getExcludedNumbers(context)
    if (excluded.isEmpty()) return false
    return excluded.any { numbersMatch(it.number, incomingOrOutgoingNumber) }
  }

  private fun numbersMatch(first: String, second: String): Boolean {
    val a = normalizePhoneDigits(first)
    val b = normalizePhoneDigits(second)
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return true
    if (a.length >= 8 && b.length >= 8) {
      return a.takeLast(8) == b.takeLast(8)
    }
    return false
  }

  // --- حد مدة التسجيلات القصيرة بالثواني ---

  fun getShortRecordingThresholdSeconds(context: Context): Int {
    return prefs(context).getInt(KEY_SHORT_RECORDING_THRESHOLD_SEC, 10).coerceIn(1, 3600)
  }

  fun setShortRecordingThresholdSeconds(context: Context, seconds: Int) {
    prefs(context)
      .edit()
      .putInt(KEY_SHORT_RECORDING_THRESHOLD_SEC, seconds.coerceIn(1, 3600))
      .apply()
  }

  // --- قسم 2: إعدادات التسجيل الصوتي ---

  fun isVoiceTranscriptionEnabled(context: Context): Boolean {
    return prefs(context).getBoolean(KEY_VOICE_TRANSCRIPTION_ENABLED, true)
  }

  fun setVoiceTranscriptionEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_VOICE_TRANSCRIPTION_ENABLED, enabled).apply()
  }

  // --- قسم 3: المزامنة السحابية الاختيارية ---

  fun isCloudSyncEnabled(context: Context): Boolean {
    return prefs(context).getBoolean(KEY_CLOUD_SYNC_ENABLED, false)
  }

  fun setCloudSyncEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, enabled).apply()
  }
}
