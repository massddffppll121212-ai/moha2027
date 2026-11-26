package com.example

import android.net.Uri
import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import kotlin.math.absoluteValue

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

@Keep
@IgnoreExtraProperties
data class CallRecord(
  @DocumentId val id: String = "",
  val userId: String = "",
  val fileName: String = "",
  val callerName: String = "",
  val phoneNumber: String = "",
  val direction: String = CallDirection.OUTGOING.name,
  val durationMs: Long = 0L,
  val recordedAtMs: Long = 0L,
  val transcript: String? = null,
  val summaryPoints: List<String>? = null,
  val actionItems: List<String>? = null,
  val storagePath: String? = null,
  val storageDownloadUrl: String? = null,
  val createdAt: Timestamp? = null,
  val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class VoiceNoteRecord(
  @DocumentId val id: String = "",
  val userId: String = "",
  val fileName: String = "",
  val title: String = "",
  val durationMs: Long = 0L,
  val recordedAtMs: Long = 0L,
  val transcript: String? = null,
  val summaryPoints: List<String>? = null,
  val actionItems: List<String>? = null,
  val storagePath: String? = null,
  val storageDownloadUrl: String? = null,
  val createdAt: Timestamp? = null,
  val updatedAt: Timestamp? = null,
)

fun buildUserScopedDocId(userId: String, fileName: String): String {
  val safeUid = userId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(36)
  val safeFile = fileName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").trim('_').take(80)
  val combined = "${safeUid}_$safeFile".trim('_')
  return combined.ifBlank { "doc_${(userId + fileName).hashCode().absoluteValue}" }.take(120)
}
