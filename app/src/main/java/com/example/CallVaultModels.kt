package com.example

import android.net.Uri

enum class VaultSection {
  CALLS,
  VOICE_NOTES,
}

enum class CallDirection(val arabicLabel: String) {
  ALL("الكل"),
  INCOMING("واردة"),
  OUTGOING("صادرة"),
  ;

  companion object {
    fun safeFromName(raw: String?): CallDirection {
      return when (raw?.uppercase()) {
        "INCOMING" -> INCOMING
        "OUTGOING" -> OUTGOING
        else -> OUTGOING
      }
    }
  }
}

data class CallRecordingItem(
  val id: String,
  val uri: Uri,
  val fileName: String,
  val callerName: String,
  val phoneNumber: String,
  val direction: CallDirection,
  val timestampMs: Long,
  val durationMs: Long,
  val sizeBytes: Long,
  val legacyFilePath: String? = null,
)

data class VoiceNoteItem(
  val id: String,
  val uri: Uri,
  val fileName: String,
  val title: String,
  val timestampMs: Long,
  val durationMs: Long,
  val sizeBytes: Long,
  val legacyFilePath: String? = null,
)

data class CallRecord(
  val id: String = "",
  val fileName: String = "",
  val callerName: String = "",
  val phoneNumber: String = "",
  val direction: String = CallDirection.OUTGOING.name,
  val durationMs: Long = 0L,
  val recordedAtMs: Long = 0L,
  val transcript: String? = null,
  val summaryPoints: List<String>? = null,
  val actionItems: List<String>? = null,
)

data class VoiceNoteRecord(
  val id: String = "",
  val fileName: String = "",
  val title: String = "",
  val durationMs: Long = 0L,
  val recordedAtMs: Long = 0L,
  val transcript: String? = null,
  val summaryPoints: List<String>? = null,
  val actionItems: List<String>? = null,
)
