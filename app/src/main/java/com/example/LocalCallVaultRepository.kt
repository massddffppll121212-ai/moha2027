package com.example

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class LocalCallVaultRepository(context: Context) {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val _callsFlow = MutableStateFlow(loadCallsFromPrefs())
  val callsFlow: StateFlow<Map<String, CallRecord>> = _callsFlow.asStateFlow()

  private val _voiceNotesFlow = MutableStateFlow(loadVoiceNotesFromPrefs())
  val voiceNotesFlow: StateFlow<Map<String, VoiceNoteRecord>> = _voiceNotesFlow.asStateFlow()

  fun reload() {
    _callsFlow.value = loadCallsFromPrefs()
    _voiceNotesFlow.value = loadVoiceNotesFromPrefs()
  }

  fun saveCallRecord(
    recording: CallRecordingItem,
    transcription: TranscriptionResult,
  ): Result<CallRecord> {
    return try {
      val cleanFileName = recording.fileName.trim().take(200)
      require(cleanFileName.isNotEmpty()) { "اسم ملف المكالمة غير صالح." }

      val record =
        CallRecord(
          id = "call_$cleanFileName",
          fileName = cleanFileName,
          callerName = recording.callerName.trim().take(150),
          phoneNumber = recording.phoneNumber.trim().take(60),
          direction = recording.direction.name,
          durationMs = recording.durationMs.coerceAtLeast(0L),
          recordedAtMs = recording.timestampMs.coerceAtLeast(0L),
          transcript = transcription.transcript.trim().take(100_000),
          summaryPoints =
            transcription.summaryPoints.map { it.trim() }.filter { it.isNotEmpty() }.take(5),
          actionItems =
            transcription.actionItems.map { it.trim() }.filter { it.isNotEmpty() }.take(50),
        )

      val updated = _callsFlow.value.toMutableMap()
      updated[cleanFileName] = record
      persistCalls(updated)
      _callsFlow.value = updated
      Result.success(record)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun deleteCallRecord(fileName: String) {
    val cleanFileName = fileName.trim()
    if (cleanFileName.isEmpty()) return
    val updated = _callsFlow.value.toMutableMap()
    if (updated.remove(cleanFileName) != null) {
      persistCalls(updated)
      _callsFlow.value = updated
    }
  }

  fun saveVoiceNoteRecord(
    note: VoiceNoteItem,
    transcription: TranscriptionResult,
  ): Result<VoiceNoteRecord> {
    return try {
      val cleanFileName = note.fileName.trim().take(200)
      require(cleanFileName.isNotEmpty()) { "اسم ملف التسجيل الصوتي غير صالح." }

      val record =
        VoiceNoteRecord(
          id = "note_$cleanFileName",
          fileName = cleanFileName,
          title = note.title.trim().take(150).ifEmpty { "تسجيل صوتي" },
          durationMs = note.durationMs.coerceAtLeast(0L),
          recordedAtMs = note.timestampMs.coerceAtLeast(0L),
          transcript = transcription.transcript.trim().take(100_000),
          summaryPoints =
            transcription.summaryPoints.map { it.trim() }.filter { it.isNotEmpty() }.take(5),
          actionItems =
            transcription.actionItems.map { it.trim() }.filter { it.isNotEmpty() }.take(50),
        )

      val updated = _voiceNotesFlow.value.toMutableMap()
      updated[cleanFileName] = record
      persistVoiceNotes(updated)
      _voiceNotesFlow.value = updated
      Result.success(record)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun deleteVoiceNoteRecord(fileName: String) {
    val cleanFileName = fileName.trim()
    if (cleanFileName.isEmpty()) return
    val updated = _voiceNotesFlow.value.toMutableMap()
    if (updated.remove(cleanFileName) != null) {
      persistVoiceNotes(updated)
      _voiceNotesFlow.value = updated
    }
  }

  private fun persistCalls(map: Map<String, CallRecord>) {
    val root = JSONObject()
    for ((key, record) in map) {
      val obj =
        JSONObject().apply {
          put("id", record.id)
          put("fileName", record.fileName)
          put("callerName", record.callerName)
          put("phoneNumber", record.phoneNumber)
          put("direction", record.direction)
          put("durationMs", record.durationMs)
          put("recordedAtMs", record.recordedAtMs)
          put("transcript", record.transcript.orEmpty())
          put("summaryPoints", JSONArray(record.summaryPoints.orEmpty()))
          put("actionItems", JSONArray(record.actionItems.orEmpty()))
        }
      root.put(key, obj)
    }
    prefs.edit().putString(KEY_CALLS_JSON, root.toString()).apply()
  }

  private fun loadCallsFromPrefs(): Map<String, CallRecord> {
    val raw = prefs.getString(KEY_CALLS_JSON, null) ?: return emptyMap()
    return try {
      val root = JSONObject(raw)
      val map = LinkedHashMap<String, CallRecord>()
      val keys = root.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        val obj = root.optJSONObject(key) ?: continue
        val transcript = obj.optString("transcript").takeIf { it.isNotBlank() }
        map[key] =
          CallRecord(
            id = obj.optString("id", "call_$key"),
            fileName = obj.optString("fileName", key),
            callerName = obj.optString("callerName", ""),
            phoneNumber = obj.optString("phoneNumber", ""),
            direction = obj.optString("direction", CallDirection.OUTGOING.name),
            durationMs = obj.optLong("durationMs", 0L),
            recordedAtMs = obj.optLong("recordedAtMs", 0L),
            transcript = transcript,
            summaryPoints = obj.optJSONArray("summaryPoints").toStringList(),
            actionItems = obj.optJSONArray("actionItems").toStringList(),
          )
      }
      map
    } catch (_: Exception) {
      emptyMap()
    }
  }

  private fun persistVoiceNotes(map: Map<String, VoiceNoteRecord>) {
    val root = JSONObject()
    for ((key, record) in map) {
      val obj =
        JSONObject().apply {
          put("id", record.id)
          put("fileName", record.fileName)
          put("title", record.title)
          put("durationMs", record.durationMs)
          put("recordedAtMs", record.recordedAtMs)
          put("transcript", record.transcript.orEmpty())
          put("summaryPoints", JSONArray(record.summaryPoints.orEmpty()))
          put("actionItems", JSONArray(record.actionItems.orEmpty()))
        }
      root.put(key, obj)
    }
    prefs.edit().putString(KEY_VOICE_NOTES_JSON, root.toString()).apply()
  }

  private fun loadVoiceNotesFromPrefs(): Map<String, VoiceNoteRecord> {
    val raw = prefs.getString(KEY_VOICE_NOTES_JSON, null) ?: return emptyMap()
    return try {
      val root = JSONObject(raw)
      val map = LinkedHashMap<String, VoiceNoteRecord>()
      val keys = root.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        val obj = root.optJSONObject(key) ?: continue
        val transcript = obj.optString("transcript").takeIf { it.isNotBlank() }
        map[key] =
          VoiceNoteRecord(
            id = obj.optString("id", "note_$key"),
            fileName = obj.optString("fileName", key),
            title = obj.optString("title", ""),
            durationMs = obj.optLong("durationMs", 0L),
            recordedAtMs = obj.optLong("recordedAtMs", 0L),
            transcript = transcript,
            summaryPoints = obj.optJSONArray("summaryPoints").toStringList(),
            actionItems = obj.optJSONArray("actionItems").toStringList(),
          )
      }
      map
    } catch (_: Exception) {
      emptyMap()
    }
  }

  private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
      for (i in 0 until length()) {
        val value = optString(i).trim()
        if (value.isNotEmpty()) add(value)
      }
    }
  }

  companion object {
    private const val PREFS_NAME = "donkeyear_local_transcriptions"
    private const val KEY_CALLS_JSON = "calls_transcriptions_json"
    private const val KEY_VOICE_NOTES_JSON = "voice_notes_transcriptions_json"
  }
}
