package com.example

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PermissionStatus(
  val recordAudioGranted: Boolean = false,
  val phoneStateGranted: Boolean = false,
  val callLogGranted: Boolean = false,
  val notificationsGranted: Boolean = true,
) {
  val allCoreGranted: Boolean
    get() = recordAudioGranted && phoneStateGranted && callLogGranted
}

data class CallVaultUiState(
  val activeSection: VaultSection = VaultSection.CALLS,
  val calls: List<CallRecordingItem> = emptyList(),
  val voiceNotes: List<VoiceNoteItem> = emptyList(),
  val savedCalls: Map<String, CallRecord> = emptyMap(),
  val savedVoiceNotes: Map<String, VoiceNoteRecord> = emptyMap(),
  val selectedFilter: CallDirection = CallDirection.ALL,
  val searchQuery: String = "",
  val permissions: PermissionStatus = PermissionStatus(),
  val isCallRecording: Boolean = false,
  val activeCallSource: String? = null,
  val callRecordingElapsedSeconds: Long = 0L,
  val playingItemId: String? = null,
  val playingLabel: String? = null,
  val expandedItemId: String? = null,
  val isPlaying: Boolean = false,
  val playbackPositionMs: Long = 0L,
  val playbackDurationMs: Long = 0L,
  val waveformAmplitudes: List<Float> = emptyList(),
  val statusMessage: String? = null,
)

class CallVaultViewModel(
  private val appContext: Context,
  val repository: LocalCallVaultRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(CallVaultUiState())
  val uiState: StateFlow<CallVaultUiState> = _uiState.asStateFlow()

  private var mediaPlayer: MediaPlayer? = null
  private var playbackTickerJob: Job? = null
  private var waveformExtractionJob: Job? = null
  private val waveformCache = mutableMapOf<String, List<Float>>()
  private var callRecordingTickerJob: Job? = null

  init {
    refreshPermissions()
    observeLocalTranscriptions()
    loadAllSections()
    observeServices()
  }

  private fun observeLocalTranscriptions() {
    viewModelScope.launch {
      repository.callsFlow.collect { map ->
        _uiState.update { it.copy(savedCalls = map) }
      }
    }
    viewModelScope.launch {
      repository.voiceNotesFlow.collect { map ->
        _uiState.update { it.copy(savedVoiceNotes = map) }
        loadVoiceNotesOnly()
      }
    }
  }

  private fun observeServices() {
    viewModelScope.launch {
      CallRecordingService.isRecordingFlow.collect { isRecording ->
        _uiState.update { it.copy(isCallRecording = isRecording) }
        callRecordingTickerJob?.cancel()
        if (isRecording) {
          callRecordingTickerJob =
            viewModelScope.launch {
              while (true) {
                val startTime = CallRecordingService.recordingStartTimeFlow.value
                val elapsedSec =
                  if (startTime > 0L) {
                    ((System.currentTimeMillis() - startTime) / 1000L).coerceAtLeast(0L)
                  } else {
                    0L
                  }
                _uiState.update { it.copy(callRecordingElapsedSeconds = elapsedSec) }
                delay(500L)
              }
            }
        } else {
          _uiState.update { it.copy(callRecordingElapsedSeconds = 0L) }
        }
      }
    }

    viewModelScope.launch {
      CallRecordingService.activeSourceFlow.collect { source ->
        _uiState.update { it.copy(activeCallSource = source) }
      }
    }

    viewModelScope.launch {
      CallRecordingService.recordingsVersionFlow.collect {
        loadCallsOnly()
      }
    }

    viewModelScope.launch {
      CallRecordingService.lastErrorFlow.collect { errorMsg ->
        if (!errorMsg.isNullOrBlank()) {
          _uiState.update { it.copy(statusMessage = errorMsg) }
        }
      }
    }

    viewModelScope.launch {
      VoiceRecorderService.voiceNotesVersionFlow.collect {
        loadVoiceNotesOnly()
      }
    }

    viewModelScope.launch {
      VoiceRecorderService.lastErrorFlow.collect { errorMsg ->
        if (!errorMsg.isNullOrBlank()) {
          _uiState.update { it.copy(statusMessage = errorMsg) }
        }
      }
    }
  }

  fun refreshPermissions() {
    val recordAudio =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val phoneState =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    val callLog =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED
    val notifications =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      } else {
        true
      }

    _uiState.update {
      it.copy(
        permissions =
          PermissionStatus(
            recordAudioGranted = recordAudio,
            phoneStateGranted = phoneState,
            callLogGranted = callLog,
            notificationsGranted = notifications,
          )
      )
    }
  }

  fun setSection(section: VaultSection) {
    _uiState.update { it.copy(activeSection = section, expandedItemId = null) }
  }

  fun setFilter(filter: CallDirection) {
    _uiState.update { it.copy(selectedFilter = filter) }
  }

  fun setSearchQuery(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
  }

  fun toggleExpandedItem(itemId: String) {
    _uiState.update {
      val nextId = if (it.expandedItemId == itemId) null else itemId
      it.copy(expandedItemId = nextId)
    }
  }

  fun clearStatusMessage() {
    _uiState.update { it.copy(statusMessage = null) }
  }

  fun loadAllSections() {
    loadCallsOnly()
    loadVoiceNotesOnly()
  }

  fun loadCallsOnly() {
    viewModelScope.launch {
      val callItems = withContext(Dispatchers.IO) { queryCalls(appContext) }
      _uiState.update { it.copy(calls = callItems) }
    }
  }

  fun loadVoiceNotesOnly() {
    viewModelScope.launch {
      val savedMap = _uiState.value.savedVoiceNotes
      val noteItems = withContext(Dispatchers.IO) { queryVoiceNotes(appContext, savedMap) }
      _uiState.update { it.copy(voiceNotes = noteItems) }
    }
  }

  fun toggleCallPlayback(item: CallRecordingItem) {
    toggleAudioPlayback(
      itemId = item.id,
      uri = item.uri,
      legacyFilePath = item.legacyFilePath,
      fallbackDurationMs = item.durationMs,
      label = "${item.callerName} • ${item.phoneNumber}",
    )
  }

  fun toggleVoiceNotePlayback(note: VoiceNoteItem) {
    toggleAudioPlayback(
      itemId = note.id,
      uri = note.uri,
      legacyFilePath = note.legacyFilePath,
      fallbackDurationMs = note.durationMs,
      label = note.title,
    )
  }

  fun toggleActivePlayback() {
    val player = mediaPlayer ?: return
    if (_uiState.value.playingItemId == null) return
    if (player.isPlaying) {
      player.pause()
      _uiState.update { it.copy(isPlaying = false) }
    } else {
      player.start()
      _uiState.update { it.copy(isPlaying = true) }
      startPlaybackTicker()
    }
  }

  fun stopActivePlayback() {
    stopPlayback()
  }

  private fun toggleAudioPlayback(
    itemId: String,
    uri: Uri,
    legacyFilePath: String?,
    fallbackDurationMs: Long,
    label: String,
  ) {
    val state = _uiState.value
    if (state.playingItemId == itemId) {
      toggleActivePlayback()
      return
    }

    stopPlayback()
    try {
      val player =
        MediaPlayer().apply {
          setDataSource(appContext, uri)
          prepare()
          setOnCompletionListener {
            playbackTickerJob?.cancel()
            _uiState.update {
              it.copy(
                isPlaying = false,
                playbackPositionMs = 0L,
              )
            }
          }
          start()
        }
      mediaPlayer = player
      val cachedWaveform = waveformCache[itemId]
      val initialWaveform = cachedWaveform ?: AudioWaveformExtractor.fallbackWaveform(itemId)
      _uiState.update {
        it.copy(
          playingItemId = itemId,
          playingLabel = label,
          isPlaying = true,
          playbackPositionMs = 0L,
          playbackDurationMs = player.duration.toLong().coerceAtLeast(fallbackDurationMs),
          waveformAmplitudes = initialWaveform,
        )
      }
      startPlaybackTicker()

      if (cachedWaveform == null) {
        waveformExtractionJob?.cancel()
        waveformExtractionJob =
          viewModelScope.launch(Dispatchers.IO) {
            val extracted =
              AudioWaveformExtractor.extractWaveform(
                context = appContext,
                uri = uri,
                legacyFilePath = legacyFilePath,
                seedKey = itemId,
              )
            waveformCache[itemId] = extracted
            if (_uiState.value.playingItemId == itemId) {
              _uiState.update { it.copy(waveformAmplitudes = extracted) }
            }
          }
      }
    } catch (e: Exception) {
      _uiState.update { it.copy(statusMessage = "تعذر تشغيل الملف: ${e.message}") }
      stopPlayback()
    }
  }

  fun seekTo(positionMs: Long) {
    val player = mediaPlayer ?: return
    try {
      player.seekTo(positionMs.toInt())
      _uiState.update { it.copy(playbackPositionMs = positionMs) }
    } catch (_: Exception) {
    }
  }

  fun deleteCall(item: CallRecordingItem) {
    if (_uiState.value.playingItemId == item.id) {
      stopPlayback()
    }
    viewModelScope.launch(Dispatchers.IO) {
      val deleted =
        try {
          if (item.legacyFilePath != null) {
            File(item.legacyFilePath).delete()
          } else {
            appContext.contentResolver.delete(item.uri, null, null) > 0
          }
        } catch (_: Exception) {
          false
        }

      if (deleted) {
        repository.deleteCallRecord(item.fileName)
      }

      val updated = queryCalls(appContext)
      _uiState.update {
        it.copy(
          calls = updated,
          statusMessage = if (deleted) "تم حذف المكالمة." else "تعذر حذف المكالمة.",
        )
      }
    }
  }

  fun deleteVoiceNote(note: VoiceNoteItem) {
    if (_uiState.value.playingItemId == note.id) {
      stopPlayback()
    }
    viewModelScope.launch(Dispatchers.IO) {
      val deleted =
        try {
          if (note.legacyFilePath != null) {
            File(note.legacyFilePath).delete()
          } else {
            appContext.contentResolver.delete(note.uri, null, null) > 0
          }
        } catch (_: Exception) {
          false
        }

      if (deleted) {
        repository.deleteVoiceNoteRecord(note.fileName)
      }

      val updated = queryVoiceNotes(appContext, _uiState.value.savedVoiceNotes)
      _uiState.update {
        it.copy(
          voiceNotes = updated,
          statusMessage = if (deleted) "تم حذف التسجيل الصوتي." else "تعذر حذف التسجيل الصوتي.",
        )
      }
    }
  }

  private fun startPlaybackTicker() {
    playbackTickerJob?.cancel()
    playbackTickerJob =
      viewModelScope.launch {
        while (true) {
          val player = mediaPlayer
          if (player != null && player.isPlaying) {
            _uiState.update {
              it.copy(
                playbackPositionMs = player.currentPosition.toLong(),
                playbackDurationMs = player.duration.toLong().coerceAtLeast(1L),
              )
            }
          }
          delay(70L)
        }
      }
  }

  private fun stopPlayback() {
    playbackTickerJob?.cancel()
    playbackTickerJob = null
    waveformExtractionJob?.cancel()
    waveformExtractionJob = null
    try {
      mediaPlayer?.stop()
    } catch (_: Exception) {
    }
    try {
      mediaPlayer?.release()
    } catch (_: Exception) {
    }
    mediaPlayer = null
    _uiState.update {
      it.copy(
        playingItemId = null,
        playingLabel = null,
        isPlaying = false,
        playbackPositionMs = 0L,
        playbackDurationMs = 0L,
        waveformAmplitudes = emptyList(),
      )
    }
  }

  override fun onCleared() {
    stopPlayback()
    callRecordingTickerJob?.cancel()
    super.onCleared()
  }

  // استعلام حصري لمجلد المكالمات CallVault/Calls فقط
  private fun queryCalls(context: Context): List<CallRecordingItem> {
    val results = mutableListOf<CallRecordingItem>()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val projection =
        arrayOf(
          MediaStore.Audio.Media._ID,
          MediaStore.Audio.Media.DISPLAY_NAME,
          MediaStore.Audio.Media.SIZE,
          MediaStore.Audio.Media.DATE_ADDED,
          MediaStore.Audio.Media.DURATION,
        )
      val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
      val selectionArgs = arrayOf("%${CallRecordingService.CALLS_RELATIVE_SUBFOLDER}%")
      val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

      context.contentResolver
        .query(
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
          projection,
          selection,
          selectionArgs,
          sortOrder,
        )
        ?.use { cursor ->
          val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
          val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
          val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
          val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
          val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

          while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val fileName = cursor.getString(nameCol) ?: continue
            val size = cursor.getLong(sizeCol)
            val dateSec = cursor.getLong(dateCol)
            var durationMs = cursor.getLong(durCol)
            val contentUri =
              ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

            if (durationMs <= 0L) {
              durationMs = extractDuration(context, contentUri)
            }

            results +=
              parseCallItem(
                id = "call_ms_$id",
                uri = contentUri,
                fileName = fileName,
                timestampMs = dateSec * 1000L,
                durationMs = durationMs,
                sizeBytes = size,
              )
          }
        }
    }

    val legacyCallsDir = CallRecordingService.getLegacyCallsDirectory(context)
    if (legacyCallsDir.exists() && legacyCallsDir.isDirectory) {
      legacyCallsDir
        .listFiles { file -> file.isFile && file.name.endsWith(".mp3", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        ?.forEach { file ->
          val uri = Uri.fromFile(file)
          results +=
            parseCallItem(
              id = "call_file_${file.absolutePath.hashCode()}",
              uri = uri,
              fileName = file.name,
              timestampMs = file.lastModified(),
              durationMs = extractDuration(context, uri),
              sizeBytes = file.length(),
              legacyFilePath = file.absolutePath,
            )
        }
    }

    return results.sortedByDescending { it.timestampMs }
  }

  // استعلام حصري لمجلد التسجيلات الصوتية CallVault/VoiceNotes فقط
  private fun queryVoiceNotes(
    context: Context,
    savedNotes: Map<String, VoiceNoteRecord>,
  ): List<VoiceNoteItem> {
    val results = mutableListOf<VoiceNoteItem>()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val projection =
        arrayOf(
          MediaStore.Audio.Media._ID,
          MediaStore.Audio.Media.DISPLAY_NAME,
          MediaStore.Audio.Media.SIZE,
          MediaStore.Audio.Media.DATE_ADDED,
          MediaStore.Audio.Media.DURATION,
        )
      val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
      val selectionArgs = arrayOf("%${VoiceRecorderService.VOICE_NOTES_RELATIVE_SUBFOLDER}%")
      val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

      context.contentResolver
        .query(
          MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
          projection,
          selection,
          selectionArgs,
          sortOrder,
        )
        ?.use { cursor ->
          val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
          val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
          val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
          val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
          val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

          while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val fileName = cursor.getString(nameCol) ?: continue
            val size = cursor.getLong(sizeCol)
            val dateSec = cursor.getLong(dateCol)
            var durationMs = cursor.getLong(durCol)
            val contentUri =
              ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

            if (durationMs <= 0L) {
              durationMs = extractDuration(context, contentUri)
            }

            results +=
              buildVoiceNoteItem(
                context = context,
                id = "note_ms_$id",
                uri = contentUri,
                fileName = fileName,
                timestampMs = dateSec * 1000L,
                durationMs = durationMs,
                sizeBytes = size,
                savedNotes = savedNotes,
              )
          }
        }
    }

    val legacyNotesDir = VoiceRecorderService.getLegacyVoiceNotesDirectory(context)
    if (legacyNotesDir.exists() && legacyNotesDir.isDirectory) {
      legacyNotesDir
        .listFiles { file -> file.isFile && file.name.endsWith(".mp3", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        ?.forEach { file ->
          val uri = Uri.fromFile(file)
          results +=
            buildVoiceNoteItem(
              context = context,
              id = "note_file_${file.absolutePath.hashCode()}",
              uri = uri,
              fileName = file.name,
              timestampMs = file.lastModified(),
              durationMs = extractDuration(context, uri),
              sizeBytes = file.length(),
              savedNotes = savedNotes,
              legacyFilePath = file.absolutePath,
            )
        }
    }

    return results.sortedByDescending { it.timestampMs }
  }

  private fun buildVoiceNoteItem(
    context: Context,
    id: String,
    uri: Uri,
    fileName: String,
    timestampMs: Long,
    durationMs: Long,
    sizeBytes: Long,
    savedNotes: Map<String, VoiceNoteRecord>,
    legacyFilePath: String? = null,
  ): VoiceNoteItem {
    val title =
      VoiceNotesMetadataStore.getTitle(context, fileName)
        ?: savedNotes[fileName]?.title?.takeIf { it.isNotBlank() }
        ?: fileName.removeSuffix(".mp3")

    return VoiceNoteItem(
      id = id,
      uri = uri,
      fileName = fileName,
      title = title,
      timestampMs = timestampMs,
      durationMs = durationMs,
      sizeBytes = sizeBytes,
      legacyFilePath = legacyFilePath,
    )
  }

  private fun extractDuration(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, uri)
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

  private fun parseCallItem(
    id: String,
    uri: Uri,
    fileName: String,
    timestampMs: Long,
    durationMs: Long,
    sizeBytes: Long,
    legacyFilePath: String? = null,
  ): CallRecordingItem {
    val cleanName = fileName.removeSuffix(".mp3")
    val parts = cleanName.split("_")
    val directionTag = parts.getOrNull(1).orEmpty()
    val direction =
      if (directionTag.equals("Incoming", ignoreCase = true)) {
        CallDirection.INCOMING
      } else {
        CallDirection.OUTGOING
      }

    val extractedNumber = if (parts.size >= 5) parts[2] else ""
    val cachedName =
      if (extractedNumber.isNotBlank()) lookupCallerName(appContext, extractedNumber) else null
    val callerName =
      cachedName
        ?: if (direction == CallDirection.INCOMING) "متصل وارد" else "اتصال صادر"
    val phoneNumber =
      if (extractedNumber.isNotBlank()) extractedNumber else "رقم غير معروف"

    return CallRecordingItem(
      id = id,
      uri = uri,
      fileName = fileName,
      callerName = callerName,
      phoneNumber = phoneNumber,
      direction = direction,
      timestampMs = timestampMs,
      durationMs = durationMs,
      sizeBytes = sizeBytes,
      legacyFilePath = legacyFilePath,
    )
  }

  private fun lookupCallerName(context: Context, number: String): String? {
    val hasCallLog =
      ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasCallLog) return null

    return try {
      context.contentResolver
        .query(
          CallLog.Calls.CONTENT_URI,
          arrayOf(CallLog.Calls.CACHED_NAME),
          "${CallLog.Calls.NUMBER} LIKE ?",
          arrayOf("%${number.takeLast(8)}%"),
          "${CallLog.Calls.DATE} DESC",
        )
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            cursor.getString(0)?.takeIf { it.isNotBlank() }
          } else {
            null
          }
        }
    } catch (_: Exception) {
      null
    }
  }

  companion object {
    fun provideFactory(): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        val app =
          checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
            "APPLICATION_KEY missing from CreationExtras"
          }
        CallVaultViewModel(
          appContext = app.applicationContext,
          repository = LocalCallVaultRepository(app.applicationContext),
        )
      }
    }
  }
}
