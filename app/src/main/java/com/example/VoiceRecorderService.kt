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
import kotlinx.coroutines.launch

object VoiceNotesMetadataStore {
  private const val PREFS_NAME = "callvault_voice_notes_meta"

  fun saveTitle(context: Context, fileName: String, title: String) {
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString("title_$fileName", title).apply()
  }

  fun getTitle(context: Context, fileName: String): String? {
    val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString("title_$fileName", null)?.takeIf { it.isNotBlank() }
  }
}

class VoiceRecorderService : Service() {

  private var mediaRecorder: MediaRecorder? = null
  private var tempOutputFile: File? = null
  private var targetFileName: String? = null
  private var currentTitle: String = ""
  private var recordingStartedAtMs: Long = 0L
  private var isStopping = false

  override fun onCreate() {
    super.onCreate()
    configureAppCheck(applicationContext, null)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP_VOICE_RECORDING -> {
        val updatedTitle = intent.getStringExtra(EXTRA_NOTE_TITLE)?.trim()
        if (!updatedTitle.isNullOrBlank()) {
          currentTitle = updatedTitle.take(150)
          _activeTitleFlow.value = currentTitle
        }
        stopVoiceRecording(saveRecording = true)
      }
      ACTION_CANCEL_VOICE_RECORDING -> {
        stopVoiceRecording(saveRecording = false)
      }
      ACTION_START_VOICE_RECORDING, null -> {
        if (!_isRecordingFlow.value) {
          val noteTitle = intent?.getStringExtra(EXTRA_NOTE_TITLE)?.trim().orEmpty()
          startVoiceRecording(noteTitle)
        }
      }
    }
    return START_NOT_STICKY
  }

  private fun startVoiceRecording(initialTitle: String) {
    val hasMicPermission =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasMicPermission) {
      _lastErrorFlow.value = "صلاحية الميكروفون مطلوبة للتسجيل الصوتي."
      stopSelf()
      return
    }

    ensureNotificationChannel()
    val defaultTitle =
      initialTitle.ifBlank {
        val timeLabel = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        "تسجيل صوتي $timeLabel"
      }.take(150)
    currentTitle = defaultTitle

    val notification =
      buildNotification(
        title = "DonkeyEar · تسجيل صوتي",
        text = defaultTitle,
        includeStopAction = true,
      )

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (securityException: SecurityException) {
      Log.e(TAG, "Failed to start foreground voice recorder", securityException)
      _lastErrorFlow.value = securityException.message ?: "تعذر بدء خدمة التسجيل الصوتي."
      stopSelf()
      return
    }

    val fileName = buildFileName()
    val cacheOutput = File(cacheDir, fileName)
    targetFileName = fileName
    tempOutputFile = cacheOutput

    val recorder = createMediaRecorder()
    try {
      if (cacheOutput.exists()) {
        cacheOutput.delete()
      }
      recorder.apply {
        // مسجل صوتي يدوي من المايك فقط - لا علاقة له بالمكالمات
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioEncodingBitRate(AUDIO_BITRATE)
        setAudioSamplingRate(AUDIO_SAMPLE_RATE)
        setOutputFile(cacheOutput.absolutePath)
        prepare()
        start()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Microphone recording failed", e)
      try {
        recorder.release()
      } catch (_: Exception) {
      }
      if (cacheOutput.exists()) {
        cacheOutput.delete()
      }
      tempOutputFile = null
      targetFileName = null
      _lastErrorFlow.value = "تعذر بدء التسجيل من الميكروفون: ${e.localizedMessage ?: e.message}"
      stopForegroundCompat()
      stopSelf()
      return
    }

    mediaRecorder = recorder
    recordingStartedAtMs = System.currentTimeMillis()
    _lastErrorFlow.value = null
    _activeTitleFlow.value = currentTitle
    _recordingStartTimeFlow.value = recordingStartedAtMs
    _isRecordingFlow.value = true
  }

  private fun stopVoiceRecording(saveRecording: Boolean) {
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
        Log.w(TAG, "MediaRecorder stop() failed: ${e.message}")
      } finally {
        try {
          recorder.release()
        } catch (_: Exception) {
        }
      }
    }

    val tempFile = tempOutputFile
    val fileName = targetFileName
    val finalTitle =
      currentTitle.ifBlank {
        "تسجيل صوتي ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}"
      }
    val startedAt = recordingStartedAtMs
    tempOutputFile = null
    targetFileName = null
    currentTitle = ""
    recordingStartedAtMs = 0L

    val hasValidAudio =
      saveRecording &&
        recordedSuccessfully &&
        tempFile != null &&
        tempFile.exists() &&
        tempFile.length() > 0L &&
        fileName != null

    if (hasValidAudio) {
      val durationMs = extractDurationMs(tempFile)
      val savedUri = saveToVoiceNotesFolder(sourceFile = tempFile, fileName = fileName)
      VoiceNotesMetadataStore.saveTitle(applicationContext, fileName, finalTitle)
      if (CallVaultSettings.isVoiceTranscriptionEnabled(applicationContext)) {
        val note =
          VoiceNoteItem(
            id = "auto_note_$fileName",
            uri = savedUri,
            fileName = fileName,
            title = finalTitle,
            timestampMs = if (startedAt > 0L) startedAt else System.currentTimeMillis(),
            durationMs = durationMs,
            sizeBytes = tempFile.length(),
            legacyFilePath = if (savedUri.scheme == "file") savedUri.path else null,
          )
        val appCtx = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
          .launch {
            TranscriptionHelper(repository = LocalCallVaultRepository.getInstance(appCtx))
              .transcribeAndSaveVoiceNote(appCtx, note)
          }
      }
    }

    if (tempFile != null && tempFile.exists()) {
      tempFile.delete()
    }

    _isRecordingFlow.value = false
    _activeTitleFlow.value = ""
    _recordingStartTimeFlow.value = 0L
    _voiceNotesVersionFlow.value = _voiceNotesVersionFlow.value + 1
    isStopping = false

    stopForegroundCompat()
    stopSelf()
  }

  private fun saveToVoiceNotesFolder(sourceFile: File, fileName: String): Uri {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values =
        ContentValues().apply {
          put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
          put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
          put(
            MediaStore.Audio.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_MUSIC}/$VOICE_NOTES_RELATIVE_SUBFOLDER",
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
          Log.i(TAG, "Saved voice note to MediaStore: Music/$VOICE_NOTES_RELATIVE_SUBFOLDER/$fileName")
          return uri
        } catch (e: Exception) {
          Log.e(TAG, "Failed writing voice note to MediaStore, falling back to app dir", e)
          resolver.delete(uri, null, null)
        }
      }
    }

    val vaultDir = getLegacyVoiceNotesDirectory(this)
    if (!vaultDir.exists()) {
      vaultDir.mkdirs()
    }
    val destFile = File(vaultDir, fileName)
    sourceFile.copyTo(destFile, overwrite = true)
    Log.i(TAG, "Saved voice note to app vault: ${destFile.absolutePath}")
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

  private fun createMediaRecorder(): MediaRecorder {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      MediaRecorder(this)
    } else {
      @Suppress("DEPRECATION")
      MediaRecorder()
    }
  }

  private fun buildFileName(): String {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "VoiceNote_${timestamp}.mp3"
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "DonkeyEar Voice Recorder",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Displays manual microphone voice note recording status"
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
    val openIntent =
      Intent(this, VoiceRecorderActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openPendingIntent =
      PendingIntent.getActivity(
        this,
        10,
        openIntent,
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
        Intent(this, VoiceRecorderService::class.java).apply { action = ACTION_STOP_VOICE_RECORDING }
      val stopPendingIntent =
        PendingIntent.getService(
          this,
          11,
          stopIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      builder.addAction(android.R.drawable.ic_media_pause, "حفظ وإيقاف", stopPendingIntent)
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
      stopVoiceRecording(saveRecording = true)
    }
    super.onDestroy()
  }

  companion object {
    private const val TAG = "VoiceRecorderService"
    private const val CHANNEL_ID = "callvault_voice_notes_channel"
    private const val NOTIFICATION_ID = 4042

    const val ACTION_START_VOICE_RECORDING = "com.example.action.START_VOICE_RECORDING"
    const val ACTION_STOP_VOICE_RECORDING = "com.example.action.STOP_VOICE_RECORDING"
    const val ACTION_CANCEL_VOICE_RECORDING = "com.example.action.CANCEL_VOICE_RECORDING"
    const val EXTRA_NOTE_TITLE = "extra_note_title"

    const val AUDIO_BITRATE = 256_000
    const val AUDIO_SAMPLE_RATE = 48_000
    const val VOICE_NOTES_RELATIVE_SUBFOLDER = "CallVault/VoiceNotes"

    private val _isRecordingFlow = MutableStateFlow(false)
    val isRecordingFlow: StateFlow<Boolean> = _isRecordingFlow.asStateFlow()

    private val _activeTitleFlow = MutableStateFlow("")
    val activeTitleFlow: StateFlow<String> = _activeTitleFlow.asStateFlow()

    private val _recordingStartTimeFlow = MutableStateFlow(0L)
    val recordingStartTimeFlow: StateFlow<Long> = _recordingStartTimeFlow.asStateFlow()

    private val _voiceNotesVersionFlow = MutableStateFlow(0)
    val voiceNotesVersionFlow: StateFlow<Int> = _voiceNotesVersionFlow.asStateFlow()

    private val _lastErrorFlow = MutableStateFlow<String?>(null)
    val lastErrorFlow: StateFlow<String?> = _lastErrorFlow.asStateFlow()

    fun getLegacyVoiceNotesDirectory(context: Context): File {
      val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
      return File(baseDir, VOICE_NOTES_RELATIVE_SUBFOLDER)
    }
  }
}
