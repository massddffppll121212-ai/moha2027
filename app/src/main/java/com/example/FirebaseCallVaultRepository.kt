package com.example

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class FirebaseCallVaultRepository(
  private val db: FirebaseFirestore,
  private val auth: FirebaseAuth = Firebase.auth,
) {
  constructor(context: Context) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  private fun requireUserId(): String {
    return auth.currentUser?.uid
      ?: throw IllegalStateException("يجب تسجيل الدخول بحساب Google أولاً للوصول إلى Firestore.")
  }

  // ==================== القسم الأول: المكالمات (calls) ====================

  fun observeCalls(userId: String): Flow<List<CallRecord>> = flow {
    require(userId.isNotBlank()) { "User ID must not be blank." }
    emitAll(
      db.collection(COLLECTION_CALLS)
        .whereEqualTo("userId", userId)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(
            CallRecord::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, COLLECTION_CALLS)
          }
          throw error
        }
    )
  }

  suspend fun saveCallRecord(
    recording: CallRecordingItem,
    transcription: TranscriptionResult? = null,
    storagePath: String? = null,
    storageDownloadUrl: String? = null,
  ): Result<CallRecord> {
    val uidPreview = auth.currentUser?.uid ?: "unauthenticated"
    val docId = buildUserScopedDocId(uidPreview, recording.fileName)
    var docPath = "$COLLECTION_CALLS/$docId"
    return try {
      val uid = requireUserId()
      val actualDocId = buildUserScopedDocId(uid, recording.fileName)
      val cleanFileName = recording.fileName.trim().take(200)
      require(cleanFileName.isNotEmpty()) { "اسم ملف المكالمة غير صالح." }

      val cleanCallerName = recording.callerName.trim().take(150)
      val cleanPhone = recording.phoneNumber.trim().take(60)
      val safeDirection =
        if (recording.direction == CallDirection.INCOMING) CallDirection.INCOMING else CallDirection.OUTGOING
      val safeDuration = recording.durationMs.coerceIn(0L, 86_400_000L)
      val safeRecordedAt = recording.timestampMs.coerceAtLeast(0L)

      val cleanTranscript = transcription?.transcript?.trim()?.take(100_000)?.takeIf { it.isNotEmpty() }
      val cleanSummary = transcription?.summaryPoints?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(5)
      val cleanActions = transcription?.actionItems?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(50)
      val cleanStoragePath = storagePath?.trim()?.take(500)?.takeIf { it.isNotEmpty() }
      val cleanStorageUrl = storageDownloadUrl?.trim()?.take(1000)?.takeIf { it.isNotEmpty() }

      val docRef = db.collection(COLLECTION_CALLS).document(actualDocId)
      docPath = docRef.path

      val existingSnap = docRef.get().await()
      if (!existingSnap.exists()) {
        val createPayload = buildMap<String, Any> {
          put("userId", uid)
          put("fileName", cleanFileName)
          put("callerName", cleanCallerName)
          put("phoneNumber", cleanPhone)
          put("direction", safeDirection.name)
          put("durationMs", safeDuration)
          put("recordedAtMs", safeRecordedAt)
          if (cleanTranscript != null) put("transcript", cleanTranscript)
          if (cleanSummary != null) put("summaryPoints", cleanSummary)
          if (cleanActions != null) put("actionItems", cleanActions)
          if (cleanStoragePath != null) put("storagePath", cleanStoragePath)
          if (cleanStorageUrl != null) put("storageDownloadUrl", cleanStorageUrl)
          put("createdAt", FieldValue.serverTimestamp())
          put("updatedAt", FieldValue.serverTimestamp())
        }
        docRef.set(createPayload).await()
      } else {
        val updatePayload = buildMap<String, Any> {
          put("callerName", cleanCallerName)
          put("phoneNumber", cleanPhone)
          put("direction", safeDirection.name)
          put("durationMs", safeDuration)
          put("recordedAtMs", safeRecordedAt)
          if (cleanTranscript != null) put("transcript", cleanTranscript)
          if (cleanSummary != null) put("summaryPoints", cleanSummary)
          if (cleanActions != null) put("actionItems", cleanActions)
          if (cleanStoragePath != null) put("storagePath", cleanStoragePath)
          if (cleanStorageUrl != null) put("storageDownloadUrl", cleanStorageUrl)
          put("updatedAt", FieldValue.serverTimestamp())
        }
        docRef.update(updatePayload).await()
      }

      Result.success(
        CallRecord(
          id = actualDocId,
          userId = uid,
          fileName = cleanFileName,
          callerName = cleanCallerName,
          phoneNumber = cleanPhone,
          direction = safeDirection.name,
          durationMs = safeDuration,
          recordedAtMs = safeRecordedAt,
          transcript = cleanTranscript,
          summaryPoints = cleanSummary,
          actionItems = cleanActions,
          storagePath = cleanStoragePath,
          storageDownloadUrl = cleanStorageUrl,
        )
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, docPath)
      Result.failure(e)
    }
  }

  suspend fun deleteCallRecord(fileName: String): Result<Unit> {
    val uidPreview = auth.currentUser?.uid ?: "unauthenticated"
    var docPath = "$COLLECTION_CALLS/${buildUserScopedDocId(uidPreview, fileName)}"
    return try {
      val uid = requireUserId()
      val docRef = db.collection(COLLECTION_CALLS).document(buildUserScopedDocId(uid, fileName))
      docPath = docRef.path
      docRef.delete().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docPath)
      Result.failure(e)
    }
  }

  // ==================== القسم الثاني: التسجيلات الصوتية اليدوية (voice_notes) ====================

  fun observeVoiceNotes(userId: String): Flow<List<VoiceNoteRecord>> = flow {
    require(userId.isNotBlank()) { "User ID must not be blank." }
    emitAll(
      db.collection(COLLECTION_VOICE_NOTES)
        .whereEqualTo("userId", userId)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(
            VoiceNoteRecord::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, COLLECTION_VOICE_NOTES)
          }
          throw error
        }
    )
  }

  suspend fun saveVoiceNoteRecord(
    note: VoiceNoteItem,
    transcription: TranscriptionResult? = null,
    storagePath: String? = null,
    storageDownloadUrl: String? = null,
  ): Result<VoiceNoteRecord> {
    val uidPreview = auth.currentUser?.uid ?: "unauthenticated"
    var docPath = "$COLLECTION_VOICE_NOTES/${buildUserScopedDocId(uidPreview, note.fileName)}"
    return try {
      val uid = requireUserId()
      val actualDocId = buildUserScopedDocId(uid, note.fileName)
      val cleanFileName = note.fileName.trim().take(200)
      require(cleanFileName.isNotEmpty()) { "اسم ملف التسجيل الصوتي غير صالح." }
      val cleanTitle = note.title.trim().take(150).ifEmpty { "تسجيل صوتي" }
      val safeDuration = note.durationMs.coerceIn(0L, 86_400_000L)
      val safeRecordedAt = note.timestampMs.coerceAtLeast(0L)

      val cleanTranscript = transcription?.transcript?.trim()?.take(100_000)?.takeIf { it.isNotEmpty() }
      val cleanSummary = transcription?.summaryPoints?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(5)
      val cleanActions = transcription?.actionItems?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(50)
      val cleanStoragePath = storagePath?.trim()?.take(500)?.takeIf { it.isNotEmpty() }
      val cleanStorageUrl = storageDownloadUrl?.trim()?.take(1000)?.takeIf { it.isNotEmpty() }

      val docRef = db.collection(COLLECTION_VOICE_NOTES).document(actualDocId)
      docPath = docRef.path

      val existingSnap = docRef.get().await()
      if (!existingSnap.exists()) {
        val createPayload = buildMap<String, Any> {
          put("userId", uid)
          put("fileName", cleanFileName)
          put("title", cleanTitle)
          put("durationMs", safeDuration)
          put("recordedAtMs", safeRecordedAt)
          if (cleanTranscript != null) put("transcript", cleanTranscript)
          if (cleanSummary != null) put("summaryPoints", cleanSummary)
          if (cleanActions != null) put("actionItems", cleanActions)
          if (cleanStoragePath != null) put("storagePath", cleanStoragePath)
          if (cleanStorageUrl != null) put("storageDownloadUrl", cleanStorageUrl)
          put("createdAt", FieldValue.serverTimestamp())
          put("updatedAt", FieldValue.serverTimestamp())
        }
        docRef.set(createPayload).await()
      } else {
        val updatePayload = buildMap<String, Any> {
          put("title", cleanTitle)
          put("durationMs", safeDuration)
          put("recordedAtMs", safeRecordedAt)
          if (cleanTranscript != null) put("transcript", cleanTranscript)
          if (cleanSummary != null) put("summaryPoints", cleanSummary)
          if (cleanActions != null) put("actionItems", cleanActions)
          if (cleanStoragePath != null) put("storagePath", cleanStoragePath)
          if (cleanStorageUrl != null) put("storageDownloadUrl", cleanStorageUrl)
          put("updatedAt", FieldValue.serverTimestamp())
        }
        docRef.update(updatePayload).await()
      }

      Result.success(
        VoiceNoteRecord(
          id = actualDocId,
          userId = uid,
          fileName = cleanFileName,
          title = cleanTitle,
          durationMs = safeDuration,
          recordedAtMs = safeRecordedAt,
          transcript = cleanTranscript,
          summaryPoints = cleanSummary,
          actionItems = cleanActions,
          storagePath = cleanStoragePath,
          storageDownloadUrl = cleanStorageUrl,
        )
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, docPath)
      Result.failure(e)
    }
  }

  suspend fun deleteVoiceNoteRecord(fileName: String): Result<Unit> {
    val uidPreview = auth.currentUser?.uid ?: "unauthenticated"
    var docPath = "$COLLECTION_VOICE_NOTES/${buildUserScopedDocId(uidPreview, fileName)}"
    return try {
      val uid = requireUserId()
      val docRef = db.collection(COLLECTION_VOICE_NOTES).document(buildUserScopedDocId(uid, fileName))
      docPath = docRef.path
      docRef.delete().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docPath)
      Result.failure(e)
    }
  }

  companion object {
    const val COLLECTION_CALLS = "calls"
    const val COLLECTION_VOICE_NOTES = "voice_notes"
  }
}
