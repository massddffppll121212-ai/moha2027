package com.example

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class CloudSyncState(
  val isFirebaseAvailable: Boolean = false,
  val isSignedIn: Boolean = false,
  val userId: String? = null,
  val userEmail: String? = null,
  val isSyncing: Boolean = false,
  val lastSyncAtMs: Long = 0L,
  val lastSyncMessage: String? = null,
)

class CloudSyncManager private constructor(context: Context) {
  private val appContext = context.applicationContext

  private val _syncState = MutableStateFlow(CloudSyncState())
  val syncState: StateFlow<CloudSyncState> = _syncState.asStateFlow()

  private var authListenerRegistered = false

  init {
    refreshAuthState()
  }

  private fun firestoreOrNull(): FirebaseFirestore? {
    if (!ensureFirebaseInitialized(appContext, null)) return null
    val dbId = appContext.getString(R.string.firestore_database_id)
    return FirebaseFirestore.getInstance(dbId)
  }

  private fun authOrNull(): FirebaseAuth? {
    if (!ensureFirebaseInitialized(appContext, null)) return null
    return Firebase.auth
  }

  fun refreshAuthState() {
    val auth = authOrNull()
    if (auth == null) {
      _syncState.update {
        it.copy(
          isFirebaseAvailable = false,
          isSignedIn = false,
          userId = null,
          userEmail = null,
        )
      }
      return
    }

    if (!authListenerRegistered) {
      authListenerRegistered = true
      auth.addAuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _syncState.update {
          it.copy(
            isFirebaseAvailable = true,
            isSignedIn = user != null,
            userId = user?.uid,
            userEmail = user?.email,
          )
        }
      }
    }

    val user = auth.currentUser
    _syncState.update {
      it.copy(
        isFirebaseAvailable = true,
        isSignedIn = user != null,
        userId = user?.uid,
        userEmail = user?.email,
      )
    }
  }

  suspend fun attemptSilentRestoreIfEnabled(): Boolean {
    if (!CallVaultSettings.isCloudSyncEnabled(appContext)) return false
    val auth = authOrNull() ?: return false
    if (auth.currentUser != null) {
      refreshAuthState()
      return true
    }

    val clientId =
      try {
        appContext.getString(R.string.default_web_client_id)
      } catch (_: Exception) {
        return false
      }

    return try {
      val credentialManager = CredentialManager.create(appContext)
      val googleIdOption =
        GetGoogleIdOption.Builder()
          .setFilterByAuthorizedAccounts(true)
          .setServerClientId(clientId)
          .setAutoSelectEnabled(true)
          .build()
      val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
      val cred = credentialManager.getCredential(appContext, request).credential
      if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        refreshAuthState()
        true
      } else {
        false
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      false
    }
  }

  suspend fun signInWithGoogleForSync(activity: Activity): Result<String> {
    if (!ensureFirebaseInitialized(appContext, activity.intent)) {
      return Result.failure(
        IllegalStateException(
          "ملف إعدادات Firebase (app/google-services.json) غير موجود في نسخة التطبيق الحالية."
        )
      )
    }

    val auth = authOrNull()
      ?: return Result.failure(IllegalStateException("تعذر تهيئة خدمة المصادقة السحابية."))

    val clientId =
      try {
        appContext.getString(R.string.default_web_client_id)
      } catch (_: Exception) {
        return Result.failure(
          IllegalStateException("معرّف Google Sign-In (default_web_client_id) غير متوفر في البناء.")
        )
      }

    return try {
      val credentialManager = CredentialManager.create(activity)
      val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
      val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()
      val cred = credentialManager.getCredential(activity, request).credential
      if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        CallVaultSettings.setCloudSyncEnabled(appContext, true)
        refreshAuthState()
        val email = auth.currentUser?.email ?: auth.currentUser?.uid ?: "Google"
        Result.success(email)
      } else {
        Result.failure(IllegalStateException("نوع بيانات اعتماد Google غير متوقع."))
      }
    } catch (e: GetCredentialCancellationException) {
      Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
      Result.failure(IllegalStateException("تم إلغاء اختيار حساب Google."))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Google Sign-In for Cloud Sync failed", e)
      Result.failure(
        IllegalStateException(e.localizedMessage ?: "تعذر ربط حساب Google للمزامنة.")
      )
    }
  }

  suspend fun disconnectCloudSync(localRepository: LocalCallVaultRepository) {
    withContext(NonCancellable) {
      CallVaultSettings.setCloudSyncEnabled(appContext, false)
      try {
        CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest())
      } catch (e: Exception) {
        Log.w(TAG, "Failed to clear credential state: ${e.message}")
      } finally {
        try {
          authOrNull()?.signOut()
        } catch (_: Exception) {
        }
        localRepository.clearCloudSyncFlags()
        refreshAuthState()
      }
    }
  }

  fun observeCloudCalls(): Flow<List<CallRecord>> = flow {
    val db = firestoreOrNull() ?: return@flow
    val uid = authOrNull()?.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return@flow
    emitAll(
      db.collection(COLLECTION_CALLS)
        .whereEqualTo("userId", uid)
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
          if (exceptionToLog != null && authOrNull()?.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, COLLECTION_CALLS)
          }
          throw error
        }
    )
  }

  fun observeCloudVoiceNotes(): Flow<List<VoiceNoteRecord>> = flow {
    val db = firestoreOrNull() ?: return@flow
    val uid = authOrNull()?.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return@flow
    emitAll(
      db.collection(COLLECTION_VOICE_NOTES)
        .whereEqualTo("userId", uid)
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
          if (exceptionToLog != null && authOrNull()?.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, COLLECTION_VOICE_NOTES)
          }
          throw error
        }
    )
  }

  suspend fun pushCallRecord(record: CallRecord): Result<String> {
    val db =
      firestoreOrNull()
        ?: return Result.failure(IllegalStateException("خدمة Firebase غير مهيأة في التطبيق."))
    val uid =
      authOrNull()?.currentUser?.uid?.takeIf { it.isNotBlank() }
        ?: return Result.failure(IllegalStateException("المزامنة السحابية غير مفعلة."))

    val docId = sanitizeDocId("${uid}_${record.fileName}")
    val docPath = "$COLLECTION_CALLS/$docId"
    return try {
      val docRef = db.collection(COLLECTION_CALLS).document(docId)
      val existing = docRef.get().await()

      val cleanFileName = record.fileName.trim().take(200)
      require(cleanFileName.isNotEmpty()) { "Invalid fileName" }
      val cleanCallerName = record.callerName.trim().take(150)
      val cleanPhone = record.phoneNumber.trim().take(60)
      val cleanDirection = CallDirection.safeFromName(record.direction).name
      val cleanDuration = record.durationMs.coerceIn(0L, 86_400_000L)
      val cleanRecordedAt = record.recordedAtMs.coerceAtLeast(0L)
      val cleanTranscript = record.transcript?.trim()?.take(100_000)?.takeIf { it.isNotEmpty() }
      val cleanSummary =
        record.summaryPoints?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(5)
      val cleanActions =
        record.actionItems?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(50)

      if (!existing.exists()) {
        val createPayload =
          buildMap<String, Any?> {
              put("userId", uid)
              put("fileName", cleanFileName)
              put("callerName", cleanCallerName)
              put("phoneNumber", cleanPhone)
              put("direction", cleanDirection)
              put("durationMs", cleanDuration)
              put("recordedAtMs", cleanRecordedAt)
              put("transcript", cleanTranscript)
              put("summaryPoints", cleanSummary)
              put("actionItems", cleanActions)
              put("createdAt", FieldValue.serverTimestamp())
              put("updatedAt", FieldValue.serverTimestamp())
            }
            .filterValues { it != null }
        docRef.set(createPayload).await()
      } else {
        val updatePayload =
          buildMap<String, Any?> {
              put("callerName", cleanCallerName)
              put("phoneNumber", cleanPhone)
              put("direction", cleanDirection)
              put("durationMs", cleanDuration)
              put("recordedAtMs", cleanRecordedAt)
              put("transcript", cleanTranscript)
              put("summaryPoints", cleanSummary)
              put("actionItems", cleanActions)
              put("updatedAt", FieldValue.serverTimestamp())
            }
            .filterValues { it != null }
        docRef.update(updatePayload).await()
      }
      Result.success(uid)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, docPath)
      Result.failure(e)
    }
  }

  suspend fun pushVoiceNoteRecord(record: VoiceNoteRecord): Result<String> {
    val db =
      firestoreOrNull()
        ?: return Result.failure(IllegalStateException("خدمة Firebase غير مهيأة في التطبيق."))
    val uid =
      authOrNull()?.currentUser?.uid?.takeIf { it.isNotBlank() }
        ?: return Result.failure(IllegalStateException("المزامنة السحابية غير مفعلة."))

    val docId = sanitizeDocId("${uid}_note_${record.fileName}")
    val docPath = "$COLLECTION_VOICE_NOTES/$docId"
    return try {
      val docRef = db.collection(COLLECTION_VOICE_NOTES).document(docId)
      val existing = docRef.get().await()

      val cleanFileName = record.fileName.trim().take(200)
      require(cleanFileName.isNotEmpty()) { "Invalid fileName" }
      val cleanTitle = record.title.trim().take(150).ifEmpty { "تسجيل صوتي" }
      val cleanDuration = record.durationMs.coerceIn(0L, 86_400_000L)
      val cleanRecordedAt = record.recordedAtMs.coerceAtLeast(0L)
      val cleanTranscript = record.transcript?.trim()?.take(100_000)?.takeIf { it.isNotEmpty() }
      val cleanSummary =
        record.summaryPoints?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(5)
      val cleanActions =
        record.actionItems?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(50)

      if (!existing.exists()) {
        val createPayload =
          buildMap<String, Any?> {
              put("userId", uid)
              put("fileName", cleanFileName)
              put("title", cleanTitle)
              put("durationMs", cleanDuration)
              put("recordedAtMs", cleanRecordedAt)
              put("transcript", cleanTranscript)
              put("summaryPoints", cleanSummary)
              put("actionItems", cleanActions)
              put("createdAt", FieldValue.serverTimestamp())
              put("updatedAt", FieldValue.serverTimestamp())
            }
            .filterValues { it != null }
        docRef.set(createPayload).await()
      } else {
        val updatePayload =
          buildMap<String, Any?> {
              put("title", cleanTitle)
              put("durationMs", cleanDuration)
              put("recordedAtMs", cleanRecordedAt)
              put("transcript", cleanTranscript)
              put("summaryPoints", cleanSummary)
              put("actionItems", cleanActions)
              put("updatedAt", FieldValue.serverTimestamp())
            }
            .filterValues { it != null }
        docRef.update(updatePayload).await()
      }
      Result.success(uid)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, docPath)
      Result.failure(e)
    }
  }

  suspend fun syncAllWithCloud(localRepo: LocalCallVaultRepository): Result<Int> {
    val db =
      firestoreOrNull()
        ?: return Result.failure(IllegalStateException("ملف إعدادات Firebase غير مدمج في النسخة."))
    val uid =
      authOrNull()?.currentUser?.uid?.takeIf { it.isNotBlank() }
        ?: return Result.failure(IllegalStateException("يرجى ربط حساب Google أولاً للمزامنة السحابية."))

    _syncState.update { it.copy(isSyncing = true, lastSyncMessage = "جاري المزامنة السحابية...") }
    return try {
      var syncedCount = 0

      // 1. Push pending local calls
      for ((_, callRecord) in localRepo.callsFlow.value) {
        if (!callRecord.syncedToCloud) {
          val res = pushCallRecord(callRecord)
          if (res.isSuccess) {
            localRepo.markCallSynced(callRecord.fileName, uid)
            syncedCount++
          }
        }
      }

      // 2. Push pending local voice notes
      for ((_, noteRecord) in localRepo.voiceNotesFlow.value) {
        if (!noteRecord.syncedToCloud) {
          val res = pushVoiceNoteRecord(noteRecord)
          if (res.isSuccess) {
            localRepo.markVoiceNoteSynced(noteRecord.fileName, uid)
            syncedCount++
          }
        }
      }

      // 3. Pull cloud calls -> merge locally
      val cloudCalls =
        db.collection(COLLECTION_CALLS)
          .whereEqualTo("userId", uid)
          .get()
          .await()
          .toObjects(CallRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
      localRepo.mergeCloudCalls(cloudCalls)

      // 4. Pull cloud voice notes -> merge locally
      val cloudNotes =
        db.collection(COLLECTION_VOICE_NOTES)
          .whereEqualTo("userId", uid)
          .get()
          .await()
          .toObjects(VoiceNoteRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
      localRepo.mergeCloudVoiceNotes(cloudNotes)

      _syncState.update {
        it.copy(
          isSyncing = false,
          lastSyncAtMs = System.currentTimeMillis(),
          lastSyncMessage = "تمت المزامنة السحابية بنجاح",
        )
      }
      Result.success(syncedCount)
    } catch (e: CancellationException) {
      _syncState.update { it.copy(isSyncing = false) }
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.LIST, "$COLLECTION_CALLS|$COLLECTION_VOICE_NOTES")
      _syncState.update {
        it.copy(
          isSyncing = false,
          lastSyncMessage = e.localizedMessage ?: "تعذرت المزامنة السحابية",
        )
      }
      Result.failure(e)
    }
  }

  private fun sanitizeDocId(raw: String): String {
    return raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128).ifEmpty { "doc_${System.currentTimeMillis()}" }
  }

  companion object {
    private const val TAG = "CloudSyncManager"
    const val COLLECTION_CALLS = "calls"
    const val COLLECTION_VOICE_NOTES = "voice_notes"

    @Volatile private var instance: CloudSyncManager? = null

    fun getInstance(context: Context): CloudSyncManager {
      return instance
        ?: synchronized(this) {
          instance ?: CloudSyncManager(context.applicationContext).also { instance = it }
        }
    }
  }
}
