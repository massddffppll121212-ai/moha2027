package com.example

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

enum class VaultStorageFolder(val folderSegment: String) {
  CALLS("CallVault/Calls"),
  VOICE_NOTES("CallVault/VoiceNotes"),
}

data class UploadedAudioMetadata(
  val folder: VaultStorageFolder,
  val fileName: String,
  val storagePath: String,
  val downloadUrl: String,
)

sealed interface CloudUploadState {
  data object Idle : CloudUploadState
  data class Uploading(val folder: VaultStorageFolder, val fileName: String, val progressPercent: Int) :
    CloudUploadState

  data class Success(val metadata: UploadedAudioMetadata) : CloudUploadState
  data class Failed(val folder: VaultStorageFolder, val fileName: String, val message: String) :
    CloudUploadState
}

object FirebaseStorageUploader {
  private const val TAG = "FirebaseStorageUploader"
  private const val PREFS_NAME = "callvault_storage_uploads"
  private const val KEY_PREFIX_PATH = "path_"
  private const val KEY_PREFIX_URL = "url_"

  private val _uploadStateFlow = MutableStateFlow<CloudUploadState>(CloudUploadState.Idle)
  val uploadStateFlow: StateFlow<CloudUploadState> = _uploadStateFlow.asStateFlow()

  private fun cacheKey(folder: VaultStorageFolder, fileName: String): String {
    return "${folder.name}_${fileName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")}"
  }

  fun getUploadedInfo(
    context: Context,
    folder: VaultStorageFolder,
    fileName: String,
  ): UploadedAudioMetadata? {
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val key = cacheKey(folder, fileName)
    val path = prefs.getString("$KEY_PREFIX_PATH$key", null)
    val url = prefs.getString("$KEY_PREFIX_URL$key", null)
    return if (!path.isNullOrBlank() && !url.isNullOrBlank()) {
      UploadedAudioMetadata(
        folder = folder,
        fileName = fileName,
        storagePath = path,
        downloadUrl = url,
      )
    } else {
      null
    }
  }

  private fun saveUploadedInfo(context: Context, metadata: UploadedAudioMetadata) {
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val key = cacheKey(metadata.folder, metadata.fileName)
    prefs.edit()
      .putString("$KEY_PREFIX_PATH$key", metadata.storagePath)
      .putString("$KEY_PREFIX_URL$key", metadata.downloadUrl)
      .apply()
  }

  suspend fun uploadRecordingFile(
    context: Context,
    folder: VaultStorageFolder,
    fileName: String,
    sourceFile: File,
  ): Result<UploadedAudioMetadata> =
    withContext(Dispatchers.IO) {
      val appCtx = context.applicationContext
      val uid = Firebase.auth.currentUser?.uid
      if (uid.isNullOrBlank()) {
        val msg = "تم الحفظ محلياً. سجّل الدخول بحساب Google للرفع إلى Firebase Storage."
        _uploadStateFlow.value = CloudUploadState.Failed(folder, fileName, msg)
        return@withContext Result.failure(IllegalStateException(msg))
      }

      if (!sourceFile.exists() || sourceFile.length() <= 0L) {
        val msg = "ملف التسجيل الصوتي غير متوفر للرفع السحابي."
        _uploadStateFlow.value = CloudUploadState.Failed(folder, fileName, msg)
        return@withContext Result.failure(IllegalStateException(msg))
      }

      val storagePath = "users/$uid/${folder.folderSegment}/$fileName"
      _uploadStateFlow.value = CloudUploadState.Uploading(folder, fileName, 0)

      return@withContext try {
        val storageRef = FirebaseStorage.getInstance().reference.child(storagePath)
        val mimeType = detectAudioMimeType(sourceFile)
        val metadata =
          StorageMetadata.Builder()
            .setContentType(mimeType)
            .setCustomMetadata("fileName", fileName)
            .setCustomMetadata("section", folder.name)
            .setCustomMetadata("ownerUid", uid)
            .build()

        val uploadTask = storageRef.putFile(Uri.fromFile(sourceFile), metadata)
        uploadTask.addOnProgressListener { snapshot ->
          val total = snapshot.totalByteCount
          if (total > 0L) {
            val pct = ((snapshot.bytesTransferred * 100L) / total).toInt().coerceIn(0, 100)
            _uploadStateFlow.value = CloudUploadState.Uploading(folder, fileName, pct)
          }
        }
        uploadTask.await()

        val downloadUrl = storageRef.downloadUrl.await().toString()
        val uploaded =
          UploadedAudioMetadata(
            folder = folder,
            fileName = fileName,
            storagePath = storagePath,
            downloadUrl = downloadUrl,
          )
        saveUploadedInfo(appCtx, uploaded)

        _uploadStateFlow.value = CloudUploadState.Success(uploaded)
        Log.i(TAG, "Uploaded $fileName to Firebase Storage: $storagePath")
        Result.success(uploaded)
      } catch (e: CancellationException) {
        throw e
      } catch (e: StorageException) {
        val friendlyMessage =
          when (e.errorCode) {
            StorageException.ERROR_NOT_AUTHORIZED,
            StorageException.ERROR_NOT_AUTHENTICATED ->
              "تعذر الرفع إلى Firebase Storage: تحقق من تفعيل Storage وقواعد الأمان في Firebase Console."
            StorageException.ERROR_BUCKET_NOT_FOUND,
            StorageException.ERROR_PROJECT_NOT_FOUND,
            StorageException.ERROR_OBJECT_NOT_FOUND ->
              "خدمة Firebase Storage غير مفعّلة بعد في المشروع. فعّل Storage من Firebase Console."
            else -> "فشل رفع التسجيل إلى Firebase Storage: ${e.localizedMessage ?: e.message}"
          }
        Log.e(TAG, "Firebase Storage upload failed (code=${e.errorCode}): ${e.message}", e)
        _uploadStateFlow.value = CloudUploadState.Failed(folder, fileName, friendlyMessage)
        Result.failure(IllegalStateException(friendlyMessage, e))
      } catch (e: Exception) {
        val friendlyMessage =
          "فشل رفع التسجيل إلى Firebase Storage: ${e.localizedMessage ?: e.message}"
        Log.e(TAG, "Unexpected Storage upload error", e)
        _uploadStateFlow.value = CloudUploadState.Failed(folder, fileName, friendlyMessage)
        Result.failure(IllegalStateException(friendlyMessage, e))
      }
    }

  private fun detectAudioMimeType(file: File): String {
    return try {
      val header = ByteArray(8)
      val readCount = file.inputStream().use { it.read(header) }
      if (
        readCount >= 8 &&
          header[4] == 'f'.code.toByte() &&
          header[5] == 't'.code.toByte() &&
          header[6] == 'y'.code.toByte() &&
          header[7] == 'p'.code.toByte()
      ) {
        "audio/mp4"
      } else {
        "audio/mpeg"
      }
    } catch (_: Exception) {
      "audio/mpeg"
    }
  }
}
