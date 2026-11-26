package com.example

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class LocalCallVaultRepository private constructor(context: Context) {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val lock = Any()

  private val _callsFlow = MutableStateFlow(loadCallsFromPrefs())
  val callsFlow: StateFlow<Map<String, CallRecord>> = _callsFlow.asStateFlow()

  private val _voiceNotesFlow = MutableStateFlow(loadVoiceNotesFromPrefs())
  val voiceNotesFlow: StateFlow<Map<String, VoiceNoteRecord>> = _voiceNotesFlow.asStateFlow()

  fun reload() {
    synchronized(lock) {
      _callsFlow.value = loadCallsFromPrefs()
      _voiceNotesFlow.value = loadVoiceNotesFromPrefs()
    }
  }

  fun saveCallRecord(
    recording: CallRecordingItem,
    transcription: TranscriptionResult,
    syncedToCloud: Boolean = false,
  ): Result<CallRecord> {
    return try {
      val cleanFileName = recording.fileName.trim().take(200)
      require(cleanFileName.isNotEmpty()) { "اسم ملف المكالمة غير صالح." }

      synchronized(lock) {
        val existing = _callsFlow.value[cleanFileName]
        val record =
          CallRecord(
            id = existing?.id?.ifBlank { "call_$cleanFileName" } ?: "call_$cleanFileName",
            userId = existing?.userId.orEmpty(),
            fileName = cleanFileName,
            callerName = recording.callerName.trim().take(150),
            phoneNumber = recording.phoneNumber.trim().take(60),
            direction = CallDirection.safeFromName(recording.direction.name).name,
            durationMs = recording.durationMs.coerceIn(0L, 86_400_000L),
            recordedAtMs = recording.timestampMs.coerceAtLeast(0L),
            transcript = transcription.transcript.trim().take(100_000),
            summaryPoints =
              transcription.summaryPoints.map { it.trim() }.filter { it.isNotEmpty() }.take(5),
            actionItems =
              transcription.actionItems.map { it.trim() }.filter { it.isNotEmpty() }.take(50),
            syncedToCloud = syncedToCloud,
          )

        val updated = _callsFlow.value.toMutableMap()
        updated[cleanFileName] = record
        persistCalls(updated)
        _callsFlow.value = updated
        Result.success(record)
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun markCallSynced(fileName: String, userId: String) {
    val cleanFileName = fileName.trim()
    if (cleanFileName.isEmpty()) return
    synchronized(lock) {
      val current = _callsFlow.value[cleanFileName] ?: return
      val updated = _callsFlow.value.toMutableMap()
      updated[cleanFileName] = current.copy(userId = userId, syncedToCloud = true)
      persistCalls(updated)
      _callsFlow.value = updated
    }
  }

  fun mergeCloudCalls(cloudRecords: List<CallRecord>) {
    if (cloudRecords.isEmpty()) return
    synchronized(lock) {
      val updated = _callsFlow.value.toMutableMap()
      var changed = false
      for (cloud in cloudRecords) {
        val key = cloud.fileName.trim()
        if (key.isEmpty()) continue
        val local = updated[key]
        val cloudTranscript = cloud.transcript?.trim()?.takeIf { it.isNotEmpty() }
        val localTranscript = local?.transcript?.trim()?.takeIf { it.isNotEmpty() }
        if (local == null) {
          updated[key] = cloud.copy(syncedToCloud = true)
          changed = true
        } else if (!local.syncedToCloud && cloudTranscript != null && localTranscript == null) {
          updated[key] =
            local.copy(
              userId = cloud.userId.ifBlank { local.userId },
              transcript = cloudTranscript,
              summaryPoints = cloud.summaryPoints ?: local.summaryPoints,
              actionItems = cloud.actionItems ?: local.actionItems,
              syncedToCloud = true,
            )
          changed = true
        } else if (!local.syncedToCloud && localTranscript == cloudTranscript) {
          updated[key] = local.copy(userId = cloud.userId.ifBlank { local.userId }, syncedToCloud = true)
          changed = true
        }
      }
      if (changed) {
        persistCalls(updated)
        _callsFlow.value = updated
      }
    }
  }

  fun deleteCallRecord(fileName: String) {
    val cleanFileName = fileName.trim()
    if (cleanFileName.isEmpty()) return
    synchronized(lock) {
      val updated = _callsFlow.value.toMutableMap()
      if (updated.remove(cleanFileName) != null) {
        persistCalls(updated)
        _callsFlow.value = updated
      }
    }
  }

  fun saveVoiceNoteRecord(
    note: VoiceNoteItem,
    transcription: TranscriptionResult,
    syncedToCloud: Boolean = false,
  ): Result<VoiceNoteRecord> {
    return try {
      val cleanFileName = note.fileName.trim().take(200)
      require(cleanFileName.isNotEmpty()) { "اسم ملف التسجيل الصوتي غير صالح." }

      synchronized(lock) {
        val existing = _voiceNotesFlow.value[cleanFileName]
        val record =
          VoiceNoteRecord(
            id = existing?.id?.ifBlank { "note_$cleanFileName" } ?: "note_$cleanFileName",
            userId = existing?.userId.orEmpty(),
            fileName = cleanFileName,
            title = note.title.trim().take(150).ifEmpty { "تسجيل صوتي" },
            durationMs = note.durationMs.coerceIn(0L, 86_400_000L),
            recordedAtMs = note.timestampMs.coerceAtLeast(0L),
            transcript = transcription.transcript.trim().take(100_000),
            summaryPoints =
              transcription.summaryPoints.map { it.trim() }.filter { it.isNotEmpty() }.take(5),
            actionItems =
              transcription.actionItems.map { it.trim() }.filter { it.isNotEmpty() }.take(50),
            syncedToCloud = syncedToCloud,
          )

        val updated = _voiceNotesFlow.value.toMutableMap()
        updated[cleanFileName] = record
        persistVoiceNotes(updated)
        _voiceNotesFlow.value = updated
        Result.success(record)
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun markVoiceNoteSynced(fileName: String, userId: String) {
    val cleanFileName = fileName.trim()
    if (cleanFileName.isEmpty()) return
    synchronized(lock) {
      val current = _voiceNotesFlow.value[cleanFileName] ?: return
      val updated = _voiceNotesFlow.value.toMutableMap()
      updated[cleanFileName] = current.copy(userId = userId, syncedToCloud = true)
      persistVoiceNotes(updated)
      _voiceNotesFlow.value = updated
    }
  }

  fun mergeCloudVoiceNotes(cloudRecords: List<VoiceNoteRecord>) {
    if (cloudRecords.isEmpty()) return
    synchronized(lock) {
      val updated = _voiceNotesFlow.value.toMutableMap()
      var changed = false
      for (cloud in cloudRecords) {
        val key = cloud.fileName.trim()
        if (key.isEmpty()) continue
        val local = updated[key]
        val cloudTranscript = cloud.transcript?.trim()?.takeIf { it.isNotEmpty() }
        val localTranscript = local?.transcript?.trim()?.takeIf { it.isNotEmpty() }
        if (local == null) {
          updated[key] = cloud.copy(syncedToCloud = true)
          changed = true
        } else if (!local.syncedToCloud && cloudTranscript != null && localTranscript == null) {
          updated[key] =
            local.copy(
              userId = cloud.userId.ifBlank { local.userId },
              title = cloud.title.ifBlank { local.title },
              transcript = cloudTranscript,
              summaryPoints = cloud.summaryPoints ?: local.summaryPoints,
              actionItems = cloud.actionItems ?: local.actionItems,
              syncedToCloud = true,
            )
          changed = true
        } else if (!local.syncedToCloud && localTranscript == cloudTranscript) {
          updated[key] = local.copy(userId = cloud.userId.ifBlank { local.userId }, syncedToCloud = true)
          changed = true
        }
      }
      if (changed) {
        persistVoiceNotes(updated)
        _voiceNotesFlow.value = updated
      }
    }
  }

  fun deleteVoiceNoteRecord(fileName: String) {
    val cleanFileName = fileName.trim()
    if (cleanFileName.isEmpty()) return
    synchronized(lock) {
      val updated = _voiceNotesFlow.value.toMutableMap()
      if (updated.remove(cleanFileName) != null) {
        persistVoiceNotes(updated)
        _voiceNotesFlow.value = updated
      }
    }
  }

  fun clearCloudSyncFlags() {
    synchronized(lock) {
      val updatedCalls = _callsFlow.value.mapValues { (_, v) -> v.copy(syncedToCloud = false) }
      val updatedNotes = _voiceNotesFlow.value.mapValues { (_, v) -> v.copy(syncedToCloud = false) }
      persistCalls(updatedCalls)
      persistVoiceNotes(updatedNotes)
      _callsFlow.value = updatedCalls
      _voiceNotesFlow.value = updatedNotes
    }
  }

  private fun persistCalls(map: Map<String, CallRecord>) {
    val root = JSONObject()
    for ((key, record) in map) {
      val obj =
        JSONObject().apply {
          put("id", record.id)
          put("userId", record.userId)
          put("fileName", record.fileName)
          put("callerName", record.callerName)
          put("phoneNumber", record.phoneNumber)
          put("direction", record.direction)
          put("durationMs", record.durationMs)
          put("recordedAtMs", record.recordedAtMs)
          put("transcript", record.transcript.orEmpty())
          put("summaryPoints", JSONArray(record.summaryPoints.orEmpty()))
          put("actionItems", JSONArray(record.actionItems.orEmpty()))
          put("syncedToCloud", record.syncedToCloud)
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
            userId = obj.optString("userId", ""),
            fileName = obj.optString("fileName", key),
            callerName = obj.optString("callerName", ""),
            phoneNumber = obj.optString("phoneNumber", ""),
            direction = obj.optString("direction", CallDirection.OUTGOING.name),
            durationMs = obj.optLong("durationMs", 0L),
            recordedAtMs = obj.optLong("recordedAtMs", 0L),
            transcript = transcript,
            summaryPoints = obj.optJSONArray("summaryPoints").toStringList(),
            actionItems = obj.optJSONArray("actionItems").toStringList(),
            syncedToCloud = obj.optBoolean("syncedToCloud", false),
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
          put("userId", record.userId)
          put("fileName", record.fileName)
          put("title", record.title)
          put("durationMs", record.durationMs)
          put("recordedAtMs", record.recordedAtMs)
          put("transcript", record.transcript.orEmpty())
          put("summaryPoints", JSONArray(record.summaryPoints.orEmpty()))
          put("actionItems", JSONArray(record.actionItems.orEmpty()))
          put("syncedToCloud", record.syncedToCloud)
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
            userId = obj.optString("userId", ""),
            fileName = obj.optString("fileName", key),
            title = obj.optString("title", ""),
            durationMs = obj.optLong("durationMs", 0L),
            recordedAtMs = obj.optLong("recordedAtMs", 0L),
            transcript = transcript,
            summaryPoints = obj.optJSONArray("summaryPoints").toStringList(),
            actionItems = obj.optJSONArray("actionItems").toStringList(),
            syncedToCloud = obj.optBoolean("syncedToCloud", false),
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

    @Volatile private var instance: LocalCallVaultRepository? = null

    fun getInstance(context: Context): LocalCallVaultRepository {
      return instance
        ?: synchronized(this) {
          instance ?: LocalCallVaultRepository(context.applicationContext).also { instance = it }
        }
    }
  }
}
