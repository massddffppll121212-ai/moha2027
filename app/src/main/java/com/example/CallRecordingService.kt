package com.example

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.CallLog
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CallRecordingService : Service() {

  private var mediaRecorder: MediaRecorder? = null
  private var tempOutputFile: File? = null
  private var targetFileName: String? = null
  private var activeDirection: String = "Outgoing"
  private var activePhoneNumber: String? = null
  private var recordingStartedAtMs: Long = 0L
  private var isStopping = false

  override fun onCreate() {
    super.onCreate()
    configureAppCheck(applicationContext, null)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP_RECORDING -> {
        stopRecordingAndFinish()
      }
      ACTION_START_RECORDING, null -> {
        if (!_isRecordingFlow.value) {
          val direction = intent?.getStringExtra(EXTRA_CALL_DIRECTION) ?: "Outgoing"
          val phoneNumber = intent?.getStringExtra(EXTRA_PHONE_NUMBER)
          startRecording(direction = direction, phoneNumber = phoneNumber)
        }
      }
    }
    return START_NOT_STICKY
  }

  private fun startRecording(direction: String, phoneNumber: String?) {
    val hasMicPermission =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasMicPermission) {
      _lastErrorFlow.value = "صلاحية الميكروفون مطلوبة لتسجيل المكالمات."
      stopSelf()
      return
    }

    ensureNotificationChannel()
    val initialNotification =
      buildNotification(
        title = "DonkeyEar · تسجيل المكالمات",
        text = "جاري تهيئة تسجيل المكالمة بجودة عالية...",
        includeStopAction = true,
      )

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        startForeground(
          NOTIFICATION_ID,
          initialNotification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
      } else {
        startForeground(NOTIFICATION_ID, initialNotification)
      }
    } catch (securityException: SecurityException) {
      Log.e(TAG, "Failed to start foreground microphone service", securityException)
      _lastErrorFlow.value = securityException.message ?: "تعذر بدء خدمة تسجيل المكالمات."
      stopSelf()
      return
    }

    val normalizedDirection =
      if (direction.equals("Incoming", ignoreCase = true)) "Incoming" else "Outgoing"
    activeDirection = normalizedDirection
    activePhoneNumber = phoneNumber

    val fileName = buildFileName(direction = normalizedDirection, phoneNumber = phoneNumber)
    val cacheOutput = File(cacheDir, fileName)
    targetFileName = fileName
    tempOutputFile = cacheOutput

    val audioSources =
      listOf(
        MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
        MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
        MediaRecorder.AudioSource.MIC to "MIC",
      )

    val recordingQuality = CallVaultSettings.getCallRecordingQuality(this)

    var startedRecorder: MediaRecorder? = null
    var selectedSourceName: String? = null

    for ((sourceId, sourceName) in audioSources) {
      val candidate = createMediaRecorder()
      try {
        if (cacheOutput.exists()) {
          cacheOutput.delete()
        }
        candidate.apply {
          setAudioSource(sourceId)
          setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
          setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
          setAudioEncodingBitRate(recordingQuality.bitrate)
          setAudioSamplingRate(recordingQuality.sampleRate)
          setOutputFile(cacheOutput.absolutePath)
          prepare()
          start()
        }
        startedRecorder = candidate
        selectedSourceName = sourceName
        Log.i(TAG, "Call recording started using source=$sourceName (${recordingQuality.storageValue}) -> $fileName")
        break
      } catch (e: Exception) {
        Log.w(TAG, "Audio source $sourceName failed: ${e.message}")
        try {
          candidate.release()
        } catch (_: Exception) {
        }
      }
    }

    if (startedRecorder == null || selectedSourceName == null) {
      if (cacheOutput.exists()) {
        cacheOutput.delete()
      }
      tempOutputFile = null
      targetFileName = null
      _lastErrorFlow.value = "تعذر تشغيل أي مصدر صوت لتسجيل المكالمة."
      stopForegroundCompat()
      stopSelf()
      return
    }

    mediaRecorder = startedRecorder
    recordingStartedAtMs = System.currentTimeMillis()
    _lastErrorFlow.value = null
    _activeSourceFlow.value = selectedSourceName
    _recordingStartTimeFlow.value = recordingStartedAtMs
    _isRecordingFlow.value = true

    val activeNotification =
      buildNotification(
        title = "DonkeyEar · تسجيل مكالمة ${if (normalizedDirection == "Incoming") "واردة" else "صادرة"}",
        text = "المصدر: $selectedSourceName · ${recordingQuality.arabicLabel}",
        includeStopAction = true,
      )
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(NOTIFICATION_ID, activeNotification)
  }

  private fun stopRecordingAndFinish() {
    if (isStopping) return
    isStopping = true

    val recorder = mediaRecorder
    mediaRecorder = null
    var recordedSuccessfully = false

    if (recorder != null) {
      try {
        recorder.stop()
        recordedSuccessfully = true
      } catch (e: RuntimeException) {
        Log.w(TAG, "MediaRecorder stop() failed (recording may be too short): ${e.message}")
      } finally {
        try {
          recorder.release()
        } catch (_: Exception) {
        }
      }
    }

    val tempFile = tempOutputFile
    val fileName = targetFileName
    val directionCopy = activeDirection
    val startedAt = recordingStartedAtMs
    tempOutputFile = null
    targetFileName = null
    activePhoneNumber = null
    recordingStartedAtMs = 0L

    val hasValidAudio =
      recordedSuccessfully && tempFile != null && tempFile.exists() && tempFile.length() > 0L && fileName != null

    if (hasValidAudio) {
      saveToCallsFolder(sourceFile = tempFile, fileName = fileName)
    }

    if (tempFile != null && tempFile.exists()) {
      tempFile.delete()
    }

    _isRecordingFlow.value = false
    _activeSourceFlow.value = null
    _recordingStartTimeFlow.value = 0L
    _recordingsVersionFlow.value = _recordingsVersionFlow.value + 1
    isStopping = false

    stopForegroundCompat()
    stopSelf()
  }

  private fun saveToCallsFolder(sourceFile: File, fileName: String): Uri {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values =
        ContentValues().apply {
          put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
          put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
          put(
            MediaStore.Audio.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_MUSIC}/$CALLS_RELATIVE_SUBFOLDER",
          )
          put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

      val resolver = contentResolver
      val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
      if (uri != null) {
        try {
          resolver.openOutputStream(uri)?.use { outputStream ->
            sourceFile.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
          }
          val publishValues = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
          resolver.update(uri, publishValues, null, null)
          Log.i(TAG, "Saved call to MediaStore: Music/$CALLS_RELATIVE_SUBFOLDER/$fileName")
          return uri
        } catch (e: Exception) {
          Log.e(TAG, "Failed writing call to MediaStore, falling back to app vault", e)
          resolver.delete(uri, null, null)
        }
      }
    }

    val vaultDir = getLegacyCallsDirectory(this)
    if (!vaultDir.exists()) {
      vaultDir.mkdirs()
    }
    val destFile = File(vaultDir, fileName)
    sourceFile.copyTo(destFile, overwrite = true)
    Log.i(TAG, "Saved call to app vault: ${destFile.absolutePath}")
    return Uri.fromFile(destFile)
  }

  private fun extractDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      retriever
        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
      0L
    } finally {
      try {
        retriever.release()
      } catch (_: Exception) {
      }
    }
  }

  private fun lookupCallerName(number: String): String? {
    val hasCallLog =
      ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasCallLog) return null
    return try {
      contentResolver
        .query(
          CallLog.Calls.CONTENT_URI,
          arrayOf(CallLog.Calls.CACHED_NAME),
          "${CallLog.Calls.NUMBER} LIKE ?",
          arrayOf("%${number.takeLast(8)}%"),
          "${CallLog.Calls.DATE} DESC",
        )
        ?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) {
      null
    }
  }

  private fun createMediaRecorder(): MediaRecorder {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      MediaRecorder(this)
    } else {
      @Suppress("DEPRECATION")
      MediaRecorder()
    }
  }

  private fun buildFileName(direction: String, phoneNumber: String?): String {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val sanitizedDirection =
      direction.replace(Regex("[^A-Za-z0-9]"), "").ifBlank { "Outgoing" }
    val sanitizedNumber =
      phoneNumber
        ?.replace(Regex("[^0-9+]"), "")
        ?.take(16)
        ?.takeIf { it.isNotBlank() }

    return if (sanitizedNumber != null) {
      "Call_${sanitizedDirection}_${sanitizedNumber}_${timestamp}.mp3"
    } else {
      "Call_${sanitizedDirection}_${timestamp}.mp3"
    }
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "DonkeyEar Calls Recorder",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Displays active automatic call recording status"
          setShowBadge(false)
        }
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(
    title: String,
    text: String,
    includeStopAction: Boolean,
  ): Notification {
    val openAppIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openPendingIntent =
      PendingIntent.getActivity(
        this,
        0,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val builder =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setContentIntent(openPendingIntent)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)

    if (includeStopAction) {
      val stopIntent =
        Intent(this, CallRecordingService::class.java).apply { action = ACTION_STOP_RECORDING }
      val stopPendingIntent =
        PendingIntent.getService(
          this,
          1,
          stopIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      builder.addAction(android.R.drawable.ic_media_pause, "إيقاف", stopPendingIntent)
    }

    return builder.build()
  }

  private fun stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
  }

  override fun onDestroy() {
    if (_isRecordingFlow.value || mediaRecorder != null) {
      stopRecordingAndFinish()
    }
    super.onDestroy()
  }

  companion object {
    private const val TAG = "CallRecordingService"
    private const val CHANNEL_ID = "callvault_recording_channel"
    private const val NOTIFICATION_ID = 4041

    const val ACTION_START_RECORDING = "com.example.action.START_RECORDING"
    const val ACTION_STOP_RECORDING = "com.example.action.STOP_RECORDING"
    const val EXTRA_CALL_DIRECTION = "extra_call_direction"
    const val EXTRA_PHONE_NUMBER = "extra_phone_number"

    const val AUDIO_BITRATE = 256_000
    const val AUDIO_SAMPLE_RATE = 48_000
    const val CALLS_RELATIVE_SUBFOLDER = "CallVault/Calls"

    private val _isRecordingFlow = MutableStateFlow(false)
    val isRecordingFlow: StateFlow<Boolean> = _isRecordingFlow.asStateFlow()

    private val _activeSourceFlow = MutableStateFlow<String?>(null)
    val activeSourceFlow: StateFlow<String?> = _activeSourceFlow.asStateFlow()

    private val _recordingStartTimeFlow = MutableStateFlow(0L)
    val recordingStartTimeFlow: StateFlow<Long> = _recordingStartTimeFlow.asStateFlow()

    private val _recordingsVersionFlow = MutableStateFlow(0)
    val recordingsVersionFlow: StateFlow<Int> = _recordingsVersionFlow.asStateFlow()

    private val _lastErrorFlow = MutableStateFlow<String?>(null)
    val lastErrorFlow: StateFlow<String?> = _lastErrorFlow.asStateFlow()

    fun getLegacyCallsDirectory(context: Context): File {
      val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
      return File(baseDir, CALLS_RELATIVE_SUBFOLDER)
    }
  }
}
