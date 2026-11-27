package com.example

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PermissionStatus(
  val recordAudioGranted: Boolean = false,
  val phoneStateGranted: Boolean = false,
  val callLogGranted: Boolean = false,
  val contactsGranted: Boolean = false,
  val notificationsGranted: Boolean = true,
) {
  val allCoreGranted: Boolean
    get() = recordAudioGranted && phoneStateGranted && callLogGranted && contactsGranted
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
  val transcribingItemIds: Set<String> = emptySet(),
  val isBatchTranscribing: Boolean = false,
  val isSelectionMode: Boolean = false,
  val selectedCallIds: Set<String> = emptySet(),
  val selectedNoteIds: Set<String> = emptySet(),
  val cloudSyncState: CloudSyncState = CloudSyncState(),
  val statusMessage: String? = null,
)

class CallVaultViewModel(
  private val appContext: Context,
  val repository: LocalCallVaultRepository,
  val cloudSyncManager: CloudSyncManager,
) : ViewModel() {

  private val _uiState = MutableStateFlow(CallVaultUiState())
  val uiState: StateFlow<CallVaultUiState> = _uiState.asStateFlow()

  private var mediaPlayer: MediaPlayer? = null
  private var audioFocusRequest: AudioFocusRequest? = null
  private var playbackTickerJob: Job? = null
  private var waveformExtractionJob: Job? = null
  private val waveformCache = mutableMapOf<String, List<Float>>()
  private var callRecordingTickerJob: Job? = null
  private var cloudListenersJob: Job? = null
  private var observedCloudUid: String? = null

  init {
    refreshPermissions()
    observeLocalTranscriptions()
    observeCloudSync()
    loadAllSections()
    observeServices()
    restoreOptionalCloudSession()
  }

  private fun restoreOptionalCloudSession() {
    viewModelScope.launch {
      if (cloudSyncManager.attemptSilentRestoreIfEnabled()) {
        cloudSyncManager.syncAllWithCloud(repository)
      }
    }
  }

  private fun observeCloudSync() {
    viewModelScope.launch {
      cloudSyncManager.syncState.collect { syncState ->
        _uiState.update { it.copy(cloudSyncState = syncState) }
        val uid = syncState.userId?.takeIf { syncState.isSignedIn && it.isNotBlank() }
        if (uid != observedCloudUid) {
          cloudListenersJob?.cancel()
          cloudListenersJob = null
          observedCloudUid = uid
          if (uid != null) {
            startCloudListeners(uid)
          }
        }
      }
    }
  }

  private fun startCloudListeners(uid: String) {
    cloudListenersJob =
      viewModelScope.launch {
        launch {
          cloudSyncManager
            .observeCloudCalls()
            .catch { error ->
              if (error is CancellationException) throw error
              Log.w(TAG, "Cloud calls listener stopped: ${error.localizedMessage}")
            }
            .collect { cloudCalls ->
              if (cloudSyncManager.syncState.value.userId == uid) {
                repository.mergeCloudCalls(cloudCalls)
              }
            }
        }
        launch {
          cloudSyncManager
            .observeCloudVoiceNotes()
            .catch { error ->
              if (error is CancellationException) throw error
              Log.w(TAG, "Cloud voice notes listener stopped: ${error.localizedMessage}")
            }
            .collect { cloudNotes ->
              if (cloudSyncManager.syncState.value.userId == uid) {
                repository.mergeCloudVoiceNotes(cloudNotes)
              }
            }
        }
      }
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
    val contacts =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
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
            contactsGranted = contacts,
            notificationsGranted = notifications,
          )
      )
    }
  }

  fun setSection(section: VaultSection) {
    _uiState.update {
      it.copy(
        activeSection = section,
        expandedItemId = null,
        isSelectionMode = false,
        selectedCallIds = emptySet(),
        selectedNoteIds = emptySet(),
      )
    }
  }

  fun enterSelectionMode(initialItemId: String? = null) {
    _uiState.update { state ->
      if (state.activeSection == VaultSection.CALLS) {
        val next =
          if (initialItemId != null) state.selectedCallIds + initialItemId else state.selectedCallIds
        state.copy(isSelectionMode = true, selectedCallIds = next)
      } else {
        val next =
          if (initialItemId != null) state.selectedNoteIds + initialItemId else state.selectedNoteIds
        state.copy(isSelectionMode = true, selectedNoteIds = next)
      }
    }
  }

  fun exitSelectionMode() {
    _uiState.update {
      it.copy(
        isSelectionMode = false,
        selectedCallIds = emptySet(),
        selectedNoteIds = emptySet(),
      )
    }
  }

  fun toggleItemSelection(itemId: String) {
    _uiState.update { state ->
      if (state.activeSection == VaultSection.CALLS) {
        val next =
          if (state.selectedCallIds.contains(itemId)) {
            state.selectedCallIds - itemId
          } else {
            state.selectedCallIds + itemId
          }
        state.copy(isSelectionMode = true, selectedCallIds = next)
      } else {
        val next =
          if (state.selectedNoteIds.contains(itemId)) {
            state.selectedNoteIds - itemId
          } else {
            state.selectedNoteIds + itemId
          }
        state.copy(isSelectionMode = true, selectedNoteIds = next)
      }
    }
  }

  fun selectAllInActiveSection(visibleIds: List<String>) {
    _uiState.update { state ->
      if (state.activeSection == VaultSection.CALLS) {
        state.copy(isSelectionMode = true, selectedCallIds = visibleIds.toSet())
      } else {
        state.copy(isSelectionMode = true, selectedNoteIds = visibleIds.toSet())
      }
    }
  }

  fun deselectAllInActiveSection() {
    _uiState.update { state ->
      if (state.activeSection == VaultSection.CALLS) {
        state.copy(selectedCallIds = emptySet())
      } else {
        state.copy(selectedNoteIds = emptySet())
      }
    }
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

  suspend fun transcribeCallSuspend(item: CallRecordingItem): Result<TranscriptionResult> {
    _uiState.update { it.copy(transcribingItemIds = it.transcribingItemIds + item.id) }
    return try {
      TranscriptionHelper(repository = repository).transcribeAndSaveCall(appContext, item, repository)
    } finally {
      _uiState.update { it.copy(transcribingItemIds = it.transcribingItemIds - item.id) }
    }
  }

  suspend fun transcribeVoiceNoteSuspend(note: VoiceNoteItem): Result<TranscriptionResult> {
    _uiState.update { it.copy(transcribingItemIds = it.transcribingItemIds + note.id) }
    return try {
      TranscriptionHelper(repository = repository).transcribeAndSaveVoiceNote(appContext, note, repository)
    } finally {
      _uiState.update { it.copy(transcribingItemIds = it.transcribingItemIds - note.id) }
    }
  }

  fun transcribeAllPendingAndSync() {
    if (_uiState.value.isBatchTranscribing) return
    viewModelScope.launch {
      _uiState.update {
        it.copy(isBatchTranscribing = true, statusMessage = "جاري تحويل ومزامنة التسجيلات...")
      }
      var transcribedCount = 0
      var firstError: String? = null
      val helper = TranscriptionHelper(repository = repository)

      val pendingCalls =
        _uiState.value.calls.filter { call ->
          _uiState.value.savedCalls[call.fileName]?.transcript.isNullOrBlank()
        }
      for (call in pendingCalls) {
        _uiState.update { it.copy(transcribingItemIds = it.transcribingItemIds + call.id) }
        val res = helper.transcribeAndSaveCall(appContext, call, repository)
        _uiState.update { it.copy(transcribingItemIds = it.transcribingItemIds - call.id) }
        if (res.isSuccess) {
          transcribedCount++
        } else if (firstError == null) {
          firstError = res.exceptionOrNull()?.message
        }
      }

      val pendingNotes =
        _uiState.value.voiceNotes.filter { note ->
          _uiState.value.savedVoiceNotes[note.fileName]?.transcript.isNullOrBlank()
        }
      for (note in pendingNotes) {
        _uiState.update { it.copy(transcribingItemIds = it.transcribingItemIds + note.id) }
        val res = helper.transcribeAndSaveVoiceNote(appContext, note, repository)
        _uiState.update { it.copy(transcribingItemIds = it.transcribingItemIds - note.id) }
        if (res.isSuccess) {
          transcribedCount++
        } else if (firstError == null) {
          firstError = res.exceptionOrNull()?.message
        }
      }

      val cloudMessage =
        if (cloudSyncManager.syncState.value.isSignedIn) {
          cloudSyncManager.syncAllWithCloud(repository).fold(
            onSuccess = { uploaded -> " وتمت مزامنة $uploaded سجل مع السحابة." },
            onFailure = { err -> " (تعذرت المزامنة السحابية: ${err.localizedMessage ?: "خطأ"})" },
          )
        } else {
          ""
        }

      _uiState.update {
        it.copy(
          isBatchTranscribing = false,
          statusMessage =
            when {
              transcribedCount > 0 -> "تم تفريغ $transcribedCount تسجيل وحفظه بنجاح.$cloudMessage"
              firstError != null -> firstError
              cloudMessage.isNotBlank() -> "جميع التسجيلات مفرّغة مسبقاً.$cloudMessage"
              else -> "جميع التسجيلات مفرّغة ومحفوظة محلياً بالفعل."
            },
        )
      }
    }
  }

  fun syncCloudOnly() {
    viewModelScope.launch {
      cloudSyncManager
        .syncAllWithCloud(repository)
        .onSuccess { count ->
          _uiState.update {
            it.copy(statusMessage = "تمت المزامنة السحابية بنجاح ($count عنصر محدّث).")
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(statusMessage = err.localizedMessage ?: "تعذرت المزامنة السحابية.")
          }
        }
    }
  }

  fun connectGoogleForCloudSync(activity: Activity) {
    viewModelScope.launch {
      cloudSyncManager
        .signInWithGoogleForSync(activity)
        .onSuccess { email ->
          cloudSyncManager.syncAllWithCloud(repository)
          _uiState.update {
            it.copy(statusMessage = "تم ربط حساب $email وتفعيل المزامنة السحابية.")
          }
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(statusMessage = err.localizedMessage ?: "تعذر ربط الحساب للمزامنة.")
          }
        }
    }
  }

  fun disconnectCloudSync() {
    viewModelScope.launch {
      cloudListenersJob?.cancel()
      cloudListenersJob = null
      observedCloudUid = null
      cloudSyncManager.disconnectCloudSync(repository)
      _uiState.update { it.copy(statusMessage = "تم فصل المزامنة السحابية. التسجيلات محفوظة محلياً.") }
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
      abandonPlaybackAudioFocus()
      _uiState.update { it.copy(isPlaying = false) }
    } else {
      prepareDeviceAudioForPlayback()
      requestPlaybackAudioFocus()
      player.setVolume(1.0f, 1.0f)
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
      val mutedWarning = prepareDeviceAudioForPlayback()
      requestPlaybackAudioFocus()

      val playbackAttributes =
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()

      val player =
        MediaPlayer().apply {
          setAudioAttributes(playbackAttributes)
          configureMediaPlayerDataSource(this, uri, legacyFilePath)
          prepare()
          setVolume(1.0f, 1.0f)
          setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer playback error what=$what extra=$extra for $uri")
            abandonPlaybackAudioFocus()
            _uiState.update {
              it.copy(
                statusMessage = "تعذر تشغيل الصوت (رمز الخطأ: $what/$extra).",
              )
            }
            stopPlayback()
            true
          }
          setOnCompletionListener {
            playbackTickerJob?.cancel()
            abandonPlaybackAudioFocus()
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
          statusMessage = mutedWarning ?: it.statusMessage,
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
      Log.e(TAG, "Failed to start audio playback", e)
      abandonPlaybackAudioFocus()
      _uiState.update { it.copy(statusMessage = "تعذر تشغيل الملف: ${e.localizedMessage ?: e.message}") }
      stopPlayback()
    }
  }

  private fun configureMediaPlayerDataSource(
    player: MediaPlayer,
    uri: Uri,
    legacyFilePath: String?,
  ) {
    val legacyFile = legacyFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
    if (legacyFile != null) {
      FileInputStream(legacyFile).use { fis ->
        player.setDataSource(fis.fd)
      }
      return
    }

    if (uri.scheme == "file") {
      val path = uri.path
      if (!path.isNullOrBlank()) {
        val file = File(path)
        if (file.exists() && file.length() > 0L) {
          FileInputStream(file).use { fis ->
            player.setDataSource(fis.fd)
          }
          return
        }
      }
    }

    val pfd =
      try {
        appContext.contentResolver.openFileDescriptor(uri, "r")
      } catch (_: Exception) {
        null
      }
    if (pfd != null) {
      pfd.use { descriptor ->
        player.setDataSource(descriptor.fileDescriptor)
      }
      return
    }

    player.setDataSource(appContext, uri)
  }

  private fun prepareDeviceAudioForPlayback(): String? {
    return try {
      val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
      if (am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE) {
        am.mode = AudioManager.MODE_NORMAL
      }
      val mediaVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
      if (mediaVol <= 0) {
        "تنبيه: مستوى صوت الوسائط في الهاتف مكتوم (0). ارفع الصوت للاستماع."
      } else {
        null
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not inspect AudioManager before playback: ${e.message}")
      null
    }
  }

  private fun requestPlaybackAudioFocus() {
    try {
      val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val attrs =
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req =
          AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
              if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                mediaPlayer?.let {
                  if (it.isPlaying) {
                    it.pause()
                    _uiState.update { state -> state.copy(isPlaying = false) }
                  }
                }
              }
            }
            .build()
        audioFocusRequest = req
        am.requestAudioFocus(req)
      } else {
        @Suppress("DEPRECATION")
        am.requestAudioFocus(
          null,
          AudioManager.STREAM_MUSIC,
          AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Audio focus request failed: ${e.message}")
    }
  }

  private fun abandonPlaybackAudioFocus() {
    try {
      val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
      } else {
        @Suppress("DEPRECATION")
        am.abandonAudioFocus(null)
      }
    } catch (_: Exception) {
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
      val deleted = deleteCallFileAndMetadata(item)
      val updated = queryCalls(appContext)
      _uiState.update {
        it.copy(
          calls = updated,
          selectedCallIds = it.selectedCallIds - item.id,
          statusMessage = if (deleted) "تم حذف المكالمة." else "تعذر حذف المكالمة.",
        )
      }
    }
  }

  fun deleteCalls(items: List<CallRecordingItem>) {
    if (items.isEmpty()) return
    val currentPlaying = _uiState.value.playingItemId
    if (currentPlaying != null && items.any { it.id == currentPlaying }) {
      stopPlayback()
    }
    viewModelScope.launch(Dispatchers.IO) {
      var deletedCount = 0
      for (item in items) {
        if (deleteCallFileAndMetadata(item)) {
          deletedCount++
        }
      }
      val updated = queryCalls(appContext)
      _uiState.update {
        it.copy(
          calls = updated,
          isSelectionMode = false,
          selectedCallIds = emptySet(),
          statusMessage =
            if (deletedCount > 0) {
              "تم حذف $deletedCount مكالمة بنجاح."
            } else {
              "تعذر حذف المكالمات المحددة."
            },
        )
      }
    }
  }

  fun deleteVoiceNote(note: VoiceNoteItem) {
    if (_uiState.value.playingItemId == note.id) {
      stopPlayback()
    }
    viewModelScope.launch(Dispatchers.IO) {
      val deleted = deleteVoiceNoteFileAndMetadata(note)
      val updated = queryVoiceNotes(appContext, _uiState.value.savedVoiceNotes)
      _uiState.update {
        it.copy(
          voiceNotes = updated,
          selectedNoteIds = it.selectedNoteIds - note.id,
          statusMessage = if (deleted) "تم حذف التسجيل الصوتي." else "تعذر حذف التسجيل الصوتي.",
        )
      }
    }
  }

  fun deleteVoiceNotes(notes: List<VoiceNoteItem>) {
    if (notes.isEmpty()) return
    val currentPlaying = _uiState.value.playingItemId
    if (currentPlaying != null && notes.any { it.id == currentPlaying }) {
      stopPlayback()
    }
    viewModelScope.launch(Dispatchers.IO) {
      var deletedCount = 0
      for (note in notes) {
        if (deleteVoiceNoteFileAndMetadata(note)) {
          deletedCount++
        }
      }
      val updated = queryVoiceNotes(appContext, _uiState.value.savedVoiceNotes)
      _uiState.update {
        it.copy(
          voiceNotes = updated,
          isSelectionMode = false,
          selectedNoteIds = emptySet(),
          statusMessage =
            if (deletedCount > 0) {
              "تم حذف $deletedCount تسجيل صوتي بنجاح."
            } else {
              "تعذر حذف التسجيلات المحددة."
            },
        )
      }
    }
  }

  fun deleteShortRecordings(maxDurationSeconds: Int, section: VaultSection) {
    val cleanSec = maxDurationSeconds.coerceIn(1, 3600)
    val thresholdMs = cleanSec * 1000L

    if (section == VaultSection.CALLS) {
      val targets = _uiState.value.calls.filter { it.durationMs < thresholdMs }
      if (targets.isEmpty()) {
        _uiState.update {
          it.copy(statusMessage = "لا توجد مكالمات أقصر من $cleanSec ثانية.")
        }
        return
      }
      val playingId = _uiState.value.playingItemId
      if (playingId != null && targets.any { it.id == playingId }) {
        stopPlayback()
      }
      viewModelScope.launch(Dispatchers.IO) {
        var deletedCount = 0
        for (item in targets) {
          if (deleteCallFileAndMetadata(item)) {
            deletedCount++
          }
        }
        val updated = queryCalls(appContext)
        _uiState.update {
          it.copy(
            calls = updated,
            selectedCallIds = it.selectedCallIds - targets.map { t -> t.id }.toSet(),
            statusMessage = "تم حذف $deletedCount مكالمة أقصر من $cleanSec ثانية.",
          )
        }
      }
    } else {
      val targets = _uiState.value.voiceNotes.filter { it.durationMs < thresholdMs }
      if (targets.isEmpty()) {
        _uiState.update {
          it.copy(statusMessage = "لا توجد تسجيلات صوتية أقصر من $cleanSec ثانية.")
        }
        return
      }
      val playingId = _uiState.value.playingItemId
      if (playingId != null && targets.any { it.id == playingId }) {
        stopPlayback()
      }
      viewModelScope.launch(Dispatchers.IO) {
        var deletedCount = 0
        for (note in targets) {
          if (deleteVoiceNoteFileAndMetadata(note)) {
            deletedCount++
          }
        }
        val updated = queryVoiceNotes(appContext, _uiState.value.savedVoiceNotes)
        _uiState.update {
          it.copy(
            voiceNotes = updated,
            selectedNoteIds = it.selectedNoteIds - targets.map { t -> t.id }.toSet(),
            statusMessage = "تم حذف $deletedCount تسجيل صوتي أقصر من $cleanSec ثانية.",
          )
        }
      }
    }
  }

  private suspend fun deleteCallFileAndMetadata(item: CallRecordingItem): Boolean {
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
      CallMetadataStore.deleteMetadata(appContext, item.fileName)
    }
    return deleted
  }

  private suspend fun deleteVoiceNoteFileAndMetadata(note: VoiceNoteItem): Boolean {
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
    return deleted
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
    abandonPlaybackAudioFocus()
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
    cloudListenersJob?.cancel()
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
        .listFiles { file -> file.isFile && isSupportedAudioFile(file.name) }
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

    val sorted = results.sortedByDescending { it.timestampMs }
    for (item in sorted) {
      repository.ensureCallRecordExists(item)
    }
    return sorted
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
        .listFiles { file -> file.isFile && isSupportedAudioFile(file.name) }
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

    val sorted = results.sortedByDescending { it.timestampMs }
    for (note in sorted) {
      repository.ensureVoiceNoteRecordExists(note)
    }
    return sorted
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
        ?: stripAudioExtension(fileName)

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
    val cleanName = stripAudioExtension(fileName)
    val parts = cleanName.split("_")
    val directionTag = parts.getOrNull(1).orEmpty()
    val savedMeta = CallMetadataStore.getMetadata(appContext, fileName)
    val savedRecord = repository.callsFlow.value[fileName]

    val effectiveDirTag = savedMeta?.direction?.takeIf { it.isNotBlank() } ?: directionTag
    val direction =
      if (effectiveDirTag.equals("Incoming", ignoreCase = true)) {
        CallDirection.INCOMING
      } else {
        CallDirection.OUTGOING
      }

    val extractedNumber =
      savedMeta?.phoneNumber?.takeIf { it.isNotBlank() && it != "رقم غير معروف" }
        ?: (if (parts.size >= 5) parts[2] else "")
          .ifBlank {
            savedRecord?.phoneNumber?.takeIf { it.isNotBlank() && it != "رقم غير معروف" }.orEmpty()
          }

    val genericLabels = setOf("متصل وارد", "اتصال صادر")
    val savedMetaName = savedMeta?.callerName?.takeIf { it.isNotBlank() && it !in genericLabels }
    val savedRecordName = savedRecord?.callerName?.takeIf { it.isNotBlank() && it !in genericLabels }
    val liveName =
      if (savedMetaName == null && extractedNumber.isNotBlank()) {
        CallMetadataStore.resolveCallerName(appContext, extractedNumber)
      } else {
        null
      }

    val fallbackLabel = if (direction == CallDirection.INCOMING) "متصل وارد" else "اتصال صادر"
    val callerName = savedMetaName ?: liveName ?: savedRecordName ?: fallbackLabel
    val phoneNumber = if (extractedNumber.isNotBlank()) extractedNumber else "رقم غير معروف"

    // Persist resolved caller name permanently so future call-log clears never lose it
    if (savedMetaName == null && (liveName != null || savedRecordName != null)) {
      CallMetadataStore.saveMetadata(
        context = appContext,
        fileName = fileName,
        callerName = callerName,
        phoneNumber = phoneNumber,
        direction = if (direction == CallDirection.INCOMING) "Incoming" else "Outgoing",
      )
    }

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

  private fun isSupportedAudioFile(fileName: String): Boolean {
    val lower = fileName.lowercase()
    return lower.endsWith(".m4a") ||
      lower.endsWith(".mp3") ||
      lower.endsWith(".aac") ||
      lower.endsWith(".3gp") ||
      lower.endsWith(".wav")
  }

  private fun stripAudioExtension(fileName: String): String {
    return fileName.replace(Regex("\\.(m4a|mp3|aac|3gp|wav)$", RegexOption.IGNORE_CASE), "")
  }

  companion object {
    private const val TAG = "CallVaultViewModel"

    fun provideFactory(): ViewModelProvider.Factory = viewModelFactory {
      initializer {
        val app =
          checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
            "APPLICATION_KEY missing from CreationExtras"
          }
        CallVaultViewModel(
          appContext = app.applicationContext,
          repository = LocalCallVaultRepository.getInstance(app.applicationContext),
          cloudSyncManager = CloudSyncManager.getInstance(app.applicationContext),
        )
      }
    }
  }
}
