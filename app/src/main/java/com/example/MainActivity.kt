package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private lateinit var viewModel: CallVaultViewModel
  private lateinit var callsAdapter: RecordingsAdapter
  private lateinit var voiceNotesAdapter: VoiceNotesAdapter

  private lateinit var tvAppSubtitle: TextView

  private lateinit var tabCalls: TextView
  private lateinit var tabVoiceNotes: TextView
  private lateinit var searchContainer: LinearLayout
  private lateinit var etSearch: EditText
  private lateinit var btnPickDateFilter: ImageButton
  private lateinit var btnClearSearch: ImageButton
  private lateinit var tvStatusTitle: TextView
  private lateinit var tvStatusSubtitle: TextView
  private lateinit var btnGrantPermissions: Button
  private lateinit var toolsBar: LinearLayout
  private lateinit var btnEnterSelectAll: Button
  private lateinit var btnDeleteShort: Button
  private lateinit var selectionActionBar: LinearLayout
  private lateinit var tvSelectionCount: TextView
  private lateinit var btnSelectAllToggle: Button
  private lateinit var btnExitSelection: ImageButton
  private lateinit var btnShareSelected: Button
  private lateinit var btnDeleteSelected: Button
  private lateinit var filterBar: LinearLayout
  private lateinit var filterAll: TextView
  private lateinit var filterIncoming: TextView
  private lateinit var filterOutgoing: TextView
  private lateinit var listRecordings: ListView
  private lateinit var listVoiceNotes: ListView
  private lateinit var emptyState: LinearLayout
  private lateinit var tvEmptyTitle: TextView
  private lateinit var tvEmptySubtitle: TextView
  private lateinit var fabNewVoiceNote: ImageButton
  private lateinit var playerBar: LinearLayout
  private lateinit var btnPlayerToggle: ImageButton
  private lateinit var btnPlayerStop: ImageButton
  private lateinit var tvNowPlaying: TextView
  private lateinit var tvPlayerTime: TextView
  private lateinit var playbackWaveform: AudioWaveformProgressView

  private var hasAutoRequestedPermissions = false
  private var isUserSeeking = false
  private var latestFilteredCalls: List<CallRecordingItem> = emptyList()
  private var latestFilteredNotes: List<VoiceNoteItem> = emptyList()

  private val requiredPermissions: Array<String> by lazy {
    buildList {
      add(Manifest.permission.RECORD_AUDIO)
      add(Manifest.permission.READ_PHONE_STATE)
      add(Manifest.permission.READ_CALL_LOG)
      add(Manifest.permission.READ_CONTACTS)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
      }
    }.toTypedArray()
  }

  @SuppressLint("InvalidFragmentVersionForActivityResult")
  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      viewModel.refreshPermissions()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureAppCheck(this, intent)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.parseColor("#0E1B36")),
      navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#111111")),
    )
    setContentView(R.layout.activity_main)

    viewModel =
      ViewModelProvider(this, CallVaultViewModel.provideFactory())[CallVaultViewModel::class.java]

    bindViews()
    applyWindowInsets()
    setupInteractions()
    setupBackHandler()
    observeState()
    requestCorePermissionsIfNeeded()
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshPermissions()
    viewModel.cloudSyncManager.refreshAuthState()
    viewModel.loadAllSections()
    renderState(viewModel.uiState.value)
  }

  private fun bindViews() {
    tvAppSubtitle = findViewById(R.id.tv_app_subtitle)
    tvAppSubtitle.text = "DonkeyEar Recorder"

    tabCalls = findViewById(R.id.tab_calls)
    tabVoiceNotes = findViewById(R.id.tab_voice_notes)
    searchContainer = findViewById(R.id.search_container)
    etSearch = findViewById(R.id.et_search)
    btnPickDateFilter = findViewById(R.id.btn_pick_date_filter)
    btnClearSearch = findViewById(R.id.btn_clear_search)
    tvStatusTitle = findViewById(R.id.tv_status_title)
    tvStatusSubtitle = findViewById(R.id.tv_status_subtitle)
    btnGrantPermissions = findViewById(R.id.btn_grant_permissions)
    toolsBar = findViewById(R.id.tools_bar)
    btnEnterSelectAll = findViewById(R.id.btn_enter_select_all)
    btnDeleteShort = findViewById(R.id.btn_delete_short)
    selectionActionBar = findViewById(R.id.selection_action_bar)
    tvSelectionCount = findViewById(R.id.tv_selection_count)
    btnSelectAllToggle = findViewById(R.id.btn_select_all_toggle)
    btnExitSelection = findViewById(R.id.btn_exit_selection)
    btnShareSelected = findViewById(R.id.btn_share_selected)
    btnDeleteSelected = findViewById(R.id.btn_delete_selected)
    filterBar = findViewById(R.id.filter_bar)
    filterAll = findViewById(R.id.filter_all)
    filterIncoming = findViewById(R.id.filter_incoming)
    filterOutgoing = findViewById(R.id.filter_outgoing)
    listRecordings = findViewById(R.id.list_recordings)
    listVoiceNotes = findViewById(R.id.list_voice_notes)
    emptyState = findViewById(R.id.empty_state)
    tvEmptyTitle = findViewById(R.id.tv_empty_title)
    tvEmptySubtitle = findViewById(R.id.tv_empty_subtitle)
    fabNewVoiceNote = findViewById(R.id.fab_new_voice_note)
    playerBar = findViewById(R.id.player_bar)
    btnPlayerToggle = findViewById(R.id.btn_player_toggle)
    btnPlayerStop = findViewById(R.id.btn_player_stop)
    tvNowPlaying = findViewById(R.id.tv_now_playing)
    tvPlayerTime = findViewById(R.id.tv_player_time)
    playbackWaveform = findViewById(R.id.playback_waveform)

    callsAdapter =
      RecordingsAdapter(
        onCardClick = { item -> showCallTranscriptionDialog(item) },
        onCardLongClick = { item -> viewModel.enterSelectionMode(item.id) },
        onToggleSelect = { item -> viewModel.toggleItemSelection(item.id) },
        onPlayClick = { item -> viewModel.toggleCallPlayback(item) },
        onShareClick = { item -> shareAudio(item.uri, item.legacyFilePath, item.fileName) },
        onDeleteClick = { item -> confirmDeleteCall(item) },
        onTranscribeClick = { item -> showCallTranscriptionDialog(item) },
      )
    listRecordings.adapter = callsAdapter

    voiceNotesAdapter =
      VoiceNotesAdapter(
        onCardClick = { note -> showVoiceNoteTranscriptionDialog(note) },
        onCardLongClick = { note -> viewModel.enterSelectionMode(note.id) },
        onToggleSelect = { note -> viewModel.toggleItemSelection(note.id) },
        onPlayClick = { note -> viewModel.toggleVoiceNotePlayback(note) },
        onShareClick = { note -> shareAudio(note.uri, note.legacyFilePath, note.fileName) },
        onDeleteClick = { note -> confirmDeleteVoiceNote(note) },
        onTranscribeClick = { note -> showVoiceNoteTranscriptionDialog(note) },
      )
    listVoiceNotes.adapter = voiceNotesAdapter
  }

  private fun applyWindowInsets() {
    val root = findViewById<View>(R.id.main_root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
  }

  private fun setupInteractions() {
    tabCalls.setOnClickListener { viewModel.setSection(VaultSection.CALLS) }
    tabVoiceNotes.setOnClickListener { viewModel.setSection(VaultSection.VOICE_NOTES) }

    fabNewVoiceNote.setOnClickListener {
      startActivity(Intent(this, VoiceRecorderActivity::class.java))
    }

    val btnSettings = findViewById<ImageButton>(R.id.btn_settings)
    btnSettings.setOnClickListener {
      startActivity(Intent(this, SettingsActivity::class.java))
    }

    val btnSync = findViewById<ImageButton>(R.id.btn_sync)
    btnSync.setOnClickListener { showSyncOptionsDialog() }

    val btnSearch = findViewById<ImageButton>(R.id.btn_search)
    val btnRefresh = findViewById<ImageButton>(R.id.btn_refresh)

    btnSearch.setOnClickListener {
      searchContainer.visibility = View.VISIBLE
      etSearch.requestFocus()
      etSearch.setSelection(etSearch.text?.length ?: 0)
      val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    btnPickDateFilter.setOnClickListener { showDateFilterPicker() }

    btnClearSearch.setOnClickListener {
      etSearch.setText("")
      hideKeyboard()
    }

    etSearch.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
          val text = s?.toString().orEmpty()
          btnClearSearch.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
          viewModel.setSearchQuery(text)
        }

        override fun afterTextChanged(s: Editable?) {}
      }
    )

    btnRefresh.setOnClickListener {
      viewModel.loadAllSections()
      if (viewModel.uiState.value.cloudSyncState.isSignedIn) {
        viewModel.syncCloudOnly()
      }
    }
    btnGrantPermissions.setOnClickListener { permissionLauncher.launch(requiredPermissions) }

    btnEnterSelectAll.setOnClickListener {
      val visibleIds = activeVisibleIds()
      if (visibleIds.isEmpty()) {
        Toast.makeText(this, "لا توجد تسجيلات لتحديدها.", Toast.LENGTH_SHORT).show()
      } else {
        viewModel.selectAllInActiveSection(visibleIds)
      }
    }

    btnDeleteShort.setOnClickListener { showDeleteShortRecordingsDialog() }

    btnSelectAllToggle.setOnClickListener {
      val visibleIds = activeVisibleIds()
      val selectedIds = activeSelectedIds(viewModel.uiState.value)
      val allSelected = visibleIds.isNotEmpty() && visibleIds.all { selectedIds.contains(it) }
      if (allSelected) {
        viewModel.deselectAllInActiveSection()
      } else {
        viewModel.selectAllInActiveSection(visibleIds)
      }
    }

    btnExitSelection.setOnClickListener { viewModel.exitSelectionMode() }
    btnShareSelected.setOnClickListener { shareSelectedItems() }
    btnDeleteSelected.setOnClickListener { confirmDeleteSelectedItems() }

    filterAll.setOnClickListener { viewModel.setFilter(CallDirection.ALL) }
    filterIncoming.setOnClickListener { viewModel.setFilter(CallDirection.INCOMING) }
    filterOutgoing.setOnClickListener { viewModel.setFilter(CallDirection.OUTGOING) }

    btnPlayerToggle.setOnClickListener { viewModel.toggleActivePlayback() }
    btnPlayerStop.setOnClickListener { viewModel.stopActivePlayback() }

    playbackWaveform.setOnWaveformSeekListener(
      object : AudioWaveformProgressView.OnWaveformSeekListener {
        override fun onStartTrackingTouch(view: AudioWaveformProgressView) {
          isUserSeeking = true
        }

        override fun onProgressChanged(
          view: AudioWaveformProgressView,
          progressMs: Long,
          fromUser: Boolean,
        ) {
          if (fromUser) {
            val total = viewModel.uiState.value.playbackDurationMs
            tvPlayerTime.text = "${formatDuration(progressMs)} / ${formatDuration(total)}"
          }
        }

        override fun onStopTrackingTouch(view: AudioWaveformProgressView, progressMs: Long) {
          isUserSeeking = false
          viewModel.seekTo(progressMs)
        }
      }
    )
  }

  private fun setupBackHandler() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (viewModel.uiState.value.isSelectionMode) {
            viewModel.exitSelectionMode()
          } else {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
          }
        }
      },
    )
  }

  private fun showSyncOptionsDialog() {
    val state = viewModel.uiState.value
    val cloudState = state.cloudSyncState
    val options =
      buildList {
        add("تحويل ومزامنة جميع التسجيلات المعلّقة")
        if (cloudState.isSignedIn) {
          add("مزامنة فورية مع حساب ${cloudState.userEmail ?: "Google"}")
          add("فصل المزامنة السحابية (إبقاء الحفظ محلياً)")
        } else {
          add("ربط حساب Google للمزامنة السحابية (اختياري)")
        }
      }.toTypedArray()

    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
      .setTitle("المزامنة والتفريغ النصي")
      .setItems(options) { _, which ->
        when (which) {
          0 -> viewModel.transcribeAllPendingAndSync()
          1 -> {
            if (cloudState.isSignedIn) {
              viewModel.syncCloudOnly()
            } else {
              viewModel.connectGoogleForCloudSync(this)
            }
          }
          2 -> viewModel.disconnectCloudSync()
        }
      }
      .setNegativeButton("إغلاق", null)
      .show()
  }

  private fun requestCorePermissionsIfNeeded() {
    if (!hasAutoRequestedPermissions && !viewModel.uiState.value.permissions.allCoreGranted) {
      hasAutoRequestedPermissions = true
      permissionLauncher.launch(requiredPermissions)
    }
  }

  private fun observeState() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
          renderState(state)
        }
      }
    }
  }

  private fun renderState(state: CallVaultUiState) {
    val isCallsTab = state.activeSection == VaultSection.CALLS
    updatePillStyle(tabCalls, isCallsTab)
    updatePillStyle(tabVoiceNotes, !isCallsTab)

    etSearch.hint =
      if (isCallsTab) {
        "ابحث باسم المتصل أو التاريخ (مثال: 26/11)..."
      } else {
        "ابحث باسم التسجيل أو التاريخ (مثال: 26/11)..."
      }
    btnClearSearch.visibility = if (state.searchQuery.isEmpty()) View.GONE else View.VISIBLE

    if (isCallsTab) {
      filterBar.visibility = View.VISIBLE
      fabNewVoiceNote.visibility = View.GONE
      renderCallsSection(state)
    } else {
      filterBar.visibility = View.GONE
      fabNewVoiceNote.visibility = View.VISIBLE
      renderVoiceNotesSection(state)
    }

    renderToolsAndSelectionBar(state)

    if (state.playingItemId != null) {
      playerBar.visibility = View.VISIBLE
      tvNowPlaying.text = state.playingLabel ?: "جاري التشغيل..."
      btnPlayerToggle.setImageResource(
        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
      )
      btnPlayerToggle.contentDescription =
        if (state.isPlaying) "إيقاف مؤقت" else "تشغيل"

      if (state.waveformAmplitudes.isNotEmpty()) {
        playbackWaveform.setWaveformData(state.waveformAmplitudes)
      }
      if (!isUserSeeking) {
        playbackWaveform.setPlaybackState(
          positionMs = state.playbackPositionMs,
          durationMs = state.playbackDurationMs,
          isPlaying = state.isPlaying,
        )
        tvPlayerTime.text =
          "${formatDuration(state.playbackPositionMs)} / ${formatDuration(state.playbackDurationMs)}"
      }
    } else {
      playerBar.visibility = View.GONE
    }

    state.statusMessage?.let { message ->
      Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
      viewModel.clearStatusMessage()
    }
  }

  private fun renderToolsAndSelectionBar(state: CallVaultUiState) {
    if (state.isSelectionMode) {
      toolsBar.visibility = View.GONE
      selectionActionBar.visibility = View.VISIBLE
      val visibleIds = activeVisibleIds()
      val selectedIds = activeSelectedIds(state)
      val allSelected = visibleIds.isNotEmpty() && visibleIds.all { selectedIds.contains(it) }
      tvSelectionCount.text = "المحدد: ${selectedIds.size} من ${visibleIds.size}"
      btnSelectAllToggle.text = if (allSelected) "إلغاء الكل" else "تحديد الكل"
      btnShareSelected.isEnabled = selectedIds.isNotEmpty()
      btnDeleteSelected.isEnabled = selectedIds.isNotEmpty()
    } else {
      selectionActionBar.visibility = View.GONE
      toolsBar.visibility = View.VISIBLE
      val thresholdSec = CallVaultSettings.getShortRecordingThresholdSeconds(this)
      btnDeleteShort.text = "حذف القصيرة (< $thresholdSec ث)"
    }
  }

  private fun activeVisibleIds(): List<String> {
    return if (viewModel.uiState.value.activeSection == VaultSection.CALLS) {
      latestFilteredCalls.map { it.id }
    } else {
      latestFilteredNotes.map { it.id }
    }
  }

  private fun activeSelectedIds(state: CallVaultUiState): Set<String> {
    return if (state.activeSection == VaultSection.CALLS) {
      state.selectedCallIds
    } else {
      state.selectedNoteIds
    }
  }

  private fun syncLabel(state: CallVaultUiState): String {
    return when {
      state.isBatchTranscribing || state.cloudSyncState.isSyncing -> "جاري المزامنة..."
      state.cloudSyncState.isSignedIn -> "متزامن سحابياً ☁"
      else -> "حفظ محلي"
    }
  }

  private fun renderCallsSection(state: CallVaultUiState) {
    listVoiceNotes.visibility = View.GONE

    if (!state.permissions.allCoreGranted) {
      tvStatusTitle.text = "الصلاحيات مطلوبة لتسجيل المكالمات"
      tvStatusSubtitle.text = "فعّل الميكروفون وحالة الهاتف وسجل المكالمات وجهات الاتصال"
      btnGrantPermissions.visibility = View.VISIBLE
    } else if (state.isCallRecording) {
      tvStatusTitle.text = "جاري تسجيل المكالمة الآن..."
      tvStatusSubtitle.text =
        "${state.activeCallSource ?: "الميكروفون والسماعة"} • ${formatDuration(state.callRecordingElapsedSeconds * 1000L)}"
      btnGrantPermissions.visibility = View.GONE
    } else {
      btnGrantPermissions.visibility = View.GONE
      val autoRecordEnabled = CallVaultSettings.isAutoCallRecordingEnabled(this)
      val quality = CallVaultSettings.getCallRecordingQuality(this)
      tvStatusTitle.text =
        if (autoRecordEnabled) {
          "مسجل المكالمات التلقائي مفعّل (${state.calls.size})"
        } else {
          "التسجيل التلقائي للمكالمات متوقف (${state.calls.size})"
        }
      tvStatusSubtitle.text =
        "${quality.arabicLabel} • المحوّل مثبّت • ${syncLabel(state)}"
    }

    updatePillStyle(filterAll, state.selectedFilter == CallDirection.ALL)
    updatePillStyle(filterIncoming, state.selectedFilter == CallDirection.INCOMING)
    updatePillStyle(filterOutgoing, state.selectedFilter == CallDirection.OUTGOING)

    val query = state.searchQuery.trim()
    val filteredCalls =
      state.calls.filter { item ->
        val matchesFilter =
          state.selectedFilter == CallDirection.ALL || item.direction == state.selectedFilter
        val transcript = state.savedCalls[item.fileName]?.transcript.orEmpty()
        val matchesSearch = RecordingSearchMatcher.matchesCall(item, transcript, query)
        matchesFilter && matchesSearch
      }
    latestFilteredCalls = filteredCalls

    callsAdapter.submitList(
      items = filteredCalls,
      playingId = state.playingItemId,
      isPlaying = state.isPlaying,
      savedCalls = state.savedCalls,
      transcribingIds = state.transcribingItemIds,
      selectionMode = state.isSelectionMode,
      selectedIds = state.selectedCallIds,
    )

    if (filteredCalls.isEmpty()) {
      listRecordings.visibility = View.GONE
      emptyState.visibility = View.VISIBLE
      if (query.isNotEmpty() || state.selectedFilter != CallDirection.ALL) {
        tvEmptyTitle.text = "لا توجد مكالمات مطابقة"
        tvEmptySubtitle.text = "جرّب تغيير الفلتر أو البحث باسم متصل أو تاريخ آخر."
      } else {
        tvEmptyTitle.text = "لا توجد مكالمات مسجلة بعد"
        tvEmptySubtitle.text =
          "سيتم تسجيل المكالمات الواردة والصادرة تلقائياً وحفظها في CallVault/Calls."
      }
    } else {
      listRecordings.visibility = View.VISIBLE
      emptyState.visibility = View.GONE
    }
  }

  private fun renderVoiceNotesSection(state: CallVaultUiState) {
    listRecordings.visibility = View.GONE
    btnGrantPermissions.visibility = View.GONE

    val autoEnabled = CallVaultSettings.isVoiceTranscriptionEnabled(this)
    val modeLabel = if (autoEnabled) "التفريغ التلقائي مفعّل" else "التفريغ اليدوي مثبّت"

    tvStatusTitle.text = "التسجيلات الصوتية اليدوية (${state.voiceNotes.size})"
    tvStatusSubtitle.text = "CallVault/VoiceNotes • $modeLabel • ${syncLabel(state)}"

    val query = state.searchQuery.trim()
    val filteredNotes =
      state.voiceNotes.filter { note ->
        val transcript = state.savedVoiceNotes[note.fileName]?.transcript.orEmpty()
        RecordingSearchMatcher.matchesVoiceNote(note, transcript, query)
      }
    latestFilteredNotes = filteredNotes

    voiceNotesAdapter.submitList(
      items = filteredNotes,
      playingId = state.playingItemId,
      isPlaying = state.isPlaying,
      savedNotes = state.savedVoiceNotes,
      transcribingIds = state.transcribingItemIds,
      selectionMode = state.isSelectionMode,
      selectedIds = state.selectedNoteIds,
    )

    if (filteredNotes.isEmpty()) {
      listVoiceNotes.visibility = View.GONE
      emptyState.visibility = View.VISIBLE
      if (query.isNotEmpty()) {
        tvEmptyTitle.text = "لا توجد تسجيلات صوتية مطابقة"
        tvEmptySubtitle.text = "جرّب البحث باسم أو تاريخ آخر."
      } else {
        tvEmptyTitle.text = "لا توجد تسجيلات صوتية بعد"
        tvEmptySubtitle.text = "اضغط على زر + الأحمر بالأسفل لبدء تسجيل صوتي جديد من الميكروفون."
      }
    } else {
      listVoiceNotes.visibility = View.VISIBLE
      emptyState.visibility = View.GONE
    }
  }

  private fun showDateFilterPicker() {
    val calendar = Calendar.getInstance()
    DatePickerDialog(
      this,
      android.R.style.Theme_DeviceDefault_Dialog_Alert,
      { _, year, month, dayOfMonth ->
        val formattedDate = String.format(Locale.US, "%02d/%02d/%04d", dayOfMonth, month + 1, year)
        etSearch.setText(formattedDate)
        etSearch.setSelection(formattedDate.length)
      },
      calendar.get(Calendar.YEAR),
      calendar.get(Calendar.MONTH),
      calendar.get(Calendar.DAY_OF_MONTH),
    ).show()
  }

  private fun updatePillStyle(pill: TextView, selected: Boolean) {
    if (selected) {
      pill.setBackgroundResource(R.drawable.bg_pill_selected)
      pill.setTextColor(Color.WHITE)
    } else {
      pill.setBackgroundResource(R.drawable.bg_pill_unselected)
      pill.setTextColor(Color.parseColor("#94A3B8"))
    }
  }

  private fun confirmDeleteCall(item: CallRecordingItem) {
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
      .setTitle("حذف المكالمة")
      .setMessage("هل تريد حذف تسجيل المكالمة \"${item.callerName}\" نهائياً؟")
      .setPositiveButton("حذف") { _, _ -> viewModel.deleteCall(item) }
      .setNegativeButton("إلغاء", null)
      .show()
  }

  private fun confirmDeleteVoiceNote(note: VoiceNoteItem) {
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
      .setTitle("حذف التسجيل الصوتي")
      .setMessage("هل تريد حذف \"${note.title}\" نهائياً؟")
      .setPositiveButton("حذف") { _, _ -> viewModel.deleteVoiceNote(note) }
      .setNegativeButton("إلغاء", null)
      .show()
  }

  private fun showCallTranscriptionDialog(item: CallRecordingItem) {
    val existing = viewModel.uiState.value.savedCalls[item.fileName]
    val cachedNotice =
      if (existing?.syncedToCloud == true) {
        "محفوظ محلياً ومتزامن مع السحابة ☁"
      } else {
        "تم التحميل من السجل المحلي للمكالمات"
      }
    openTranscriptionDialog(
      title = "تفريغ المكالمة",
      subtitle = "${item.callerName} • ${item.phoneNumber}",
      cachedTranscript = existing?.transcript,
      cachedSummary = existing?.summaryPoints.orEmpty(),
      cachedTasks = existing?.actionItems.orEmpty(),
      cachedNotice = cachedNotice,
      runTranscription = { viewModel.transcribeCallSuspend(item) },
    )
  }

  private fun showVoiceNoteTranscriptionDialog(note: VoiceNoteItem) {
    val existing = viewModel.uiState.value.savedVoiceNotes[note.fileName]
    val cachedNotice =
      if (existing?.syncedToCloud == true) {
        "محفوظ محلياً ومتزامن مع السحابة ☁"
      } else {
        "تم التحميل من السجل المحلي للتسجيلات الصوتية"
      }
    openTranscriptionDialog(
      title = "تفريغ التسجيل الصوتي",
      subtitle = note.title,
      cachedTranscript = existing?.transcript,
      cachedSummary = existing?.summaryPoints.orEmpty(),
      cachedTasks = existing?.actionItems.orEmpty(),
      cachedNotice = cachedNotice,
      runTranscription = { viewModel.transcribeVoiceNoteSuspend(note) },
    )
  }

  private fun openTranscriptionDialog(
    title: String,
    subtitle: String,
    cachedTranscript: String?,
    cachedSummary: List<String>,
    cachedTasks: List<String>,
    cachedNotice: String,
    runTranscription: suspend () -> Result<TranscriptionResult>,
  ) {
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(R.layout.dialog_transcription)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.window?.setLayout(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    val tvTitle = dialog.findViewById<TextView>(R.id.tv_dialog_title)
    val tvSubtitle = dialog.findViewById<TextView>(R.id.tv_dialog_subtitle)
    val tvAiNotice = dialog.findViewById<TextView>(R.id.tv_ai_notice)
    val loadingContainer = dialog.findViewById<LinearLayout>(R.id.loading_container)
    val resultScroll = dialog.findViewById<ScrollView>(R.id.result_scroll)
    val dialogActions = dialog.findViewById<LinearLayout>(R.id.dialog_actions)
    val tvSummaryPoints = dialog.findViewById<TextView>(R.id.tv_summary_points)
    val tvActionItems = dialog.findViewById<TextView>(R.id.tv_action_items)
    val tvTranscriptBody = dialog.findViewById<TextView>(R.id.tv_transcript_body)
    val btnClose = dialog.findViewById<ImageButton>(R.id.btn_close_dialog)
    val btnDone = dialog.findViewById<Button>(R.id.btn_done_dialog)
    val btnCopy = dialog.findViewById<Button>(R.id.btn_copy_transcript)
    val btnRetranscribe = dialog.findViewById<Button>(R.id.btn_retranscribe)

    tvTitle.text = title
    tvSubtitle.text = subtitle

    btnClose.setOnClickListener { dialog.dismiss() }
    btnDone.setOnClickListener { dialog.dismiss() }
    dialog.show()

    fun bindOutput(
      transcript: String,
      summaryPoints: List<String>,
      actionItems: List<String>,
      notice: String,
    ) {
      loadingContainer.visibility = View.GONE
      resultScroll.visibility = View.VISIBLE
      dialogActions.visibility = View.VISIBLE
      btnCopy.visibility = View.VISIBLE
      btnRetranscribe.visibility = View.VISIBLE

      if (notice.isNotBlank()) {
        tvAiNotice.text = notice
        tvAiNotice.visibility = View.VISIBLE
      } else {
        tvAiNotice.visibility = View.GONE
      }

      val formattedSummary =
        summaryPoints.mapIndexed { idx, point -> "${idx + 1}. $point" }.joinToString("\n")
      val formattedTasks = actionItems.joinToString("\n") { task -> "✓ $task" }

      tvSummaryPoints.text = formattedSummary
      tvActionItems.text = formattedTasks
      tvTranscriptBody.text = transcript

      btnCopy.setOnClickListener {
        val fullText =
          buildString {
            appendLine("الملخص:")
            appendLine(formattedSummary)
            appendLine()
            appendLine("المهام المستخرجة:")
            appendLine(formattedTasks)
            appendLine()
            appendLine("النص الكامل:")
            append(transcript)
          }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DonkeyEar Transcript", fullText))
        Toast.makeText(this@MainActivity, "تم نسخ الملخص والنص.", Toast.LENGTH_SHORT).show()
      }
    }

    fun executeAi() {
      loadingContainer.visibility = View.VISIBLE
      resultScroll.visibility = View.GONE
      dialogActions.visibility = View.GONE
      tvAiNotice.visibility = View.GONE

      lifecycleScope.launch {
        val result = runTranscription()
        if (!dialog.isShowing) return@launch

        result
          .onSuccess { data ->
            bindOutput(
              transcript = data.transcript,
              summaryPoints = data.summaryPoints,
              actionItems = data.actionItems,
              notice = data.notice,
            )
          }
          .onFailure { error ->
            loadingContainer.visibility = View.GONE
            tvAiNotice.text = error.message ?: "تعذر تحويل التسجيل إلى نص."
            tvAiNotice.visibility = View.VISIBLE
            dialogActions.visibility = View.VISIBLE
            btnCopy.visibility = View.GONE
            btnRetranscribe.visibility = View.VISIBLE
          }
      }
    }

    btnRetranscribe.setOnClickListener { executeAi() }

    if (!cachedTranscript.isNullOrBlank()) {
      bindOutput(
        transcript = cachedTranscript,
        summaryPoints = cachedSummary,
        actionItems = cachedTasks,
        notice = cachedNotice,
      )
    } else {
      executeAi()
    }
  }

  private fun showDeleteShortRecordingsDialog() {
    val state = viewModel.uiState.value
    val activeSection = state.activeSection
    val savedThreshold = CallVaultSettings.getShortRecordingThresholdSeconds(this)

    val container =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(48, 32, 48, 16)
      }

    val tvHint =
      TextView(this).apply {
        text = "حدد الحد الأقصى بالثواني لحذف جميع التسجيلات الأقصر منه في القسم الحالي:"
        setTextColor(Color.parseColor("#CBD5E1"))
        textSize = 14f
      }
    container.addView(tvHint)

    val etSeconds =
      EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(savedThreshold.toString())
        setSelection(text.length)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#64748B"))
        hint = "المدة بالثواني (مثال: 10)"
        textSize = 16f
        setPadding(24, 24, 24, 24)
        setBackgroundResource(R.drawable.bg_search_field)
        val lp =
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
          )
        lp.topMargin = 24
        layoutParams = lp
      }
    container.addView(etSeconds)

    val presetsRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val lp =
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
          )
        lp.topMargin = 20
        layoutParams = lp
      }
    listOf(5, 10, 15, 30, 60).forEachIndexed { idx, preset ->
      val chip =
        Button(this).apply {
          text = "$preset ث"
          isAllCaps = false
          textSize = 12f
          setTextColor(Color.WHITE)
          setBackgroundResource(R.drawable.bg_pill_unselected)
          minHeight = 48
          val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
          if (idx > 0) lp.marginStart = 8
          layoutParams = lp
          setOnClickListener {
            etSeconds.setText(preset.toString())
            etSeconds.setSelection(etSeconds.text.length)
          }
        }
      presetsRow.addView(chip)
    }
    container.addView(presetsRow)

    val tvMatchCount =
      TextView(this).apply {
        setTextColor(Color.parseColor("#38BDF8"))
        textSize = 13f
        val lp =
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
          )
        lp.topMargin = 20
        layoutParams = lp
      }
    container.addView(tvMatchCount)

    fun updatePreviewCount(raw: String) {
      val sec = raw.toIntOrNull()?.coerceIn(1, 3600)
      if (sec == null) {
        tvMatchCount.text = "أدخل عدداً صحيحاً من الثواني (1 - 3600)"
        return
      }
      val thresholdMs = sec * 1000L
      val count =
        if (activeSection == VaultSection.CALLS) {
          state.calls.count { it.durationMs < thresholdMs }
        } else {
          state.voiceNotes.count { it.durationMs < thresholdMs }
        }
      val sectionName = if (activeSection == VaultSection.CALLS) "مكالمة" else "تسجيل صوتي"
      tvMatchCount.text = "التسجيلات المطابقة للحذف (< $sec ثانية): $count $sectionName"
    }

    updatePreviewCount(savedThreshold.toString())
    etSeconds.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
          updatePreviewCount(s?.toString().orEmpty())
        }
        override fun afterTextChanged(s: Editable?) {}
      }
    )

    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
      .setTitle("حذف التسجيلات القصيرة")
      .setView(container)
      .setPositiveButton("حذف الآن") { _, _ ->
        val entered = etSeconds.text?.toString()?.trim()?.toIntOrNull()
        if (entered == null || entered <= 0) {
          Toast.makeText(this, "يرجى إدخال مدة صحيحة بالثواني.", Toast.LENGTH_SHORT).show()
        } else {
          val clamped = entered.coerceIn(1, 3600)
          CallVaultSettings.setShortRecordingThresholdSeconds(this, clamped)
          btnDeleteShort.text = "حذف القصيرة (< $clamped ث)"
          viewModel.deleteShortRecordings(clamped, activeSection)
        }
      }
      .setNegativeButton("إلغاء", null)
      .show()
  }

  private fun shareSelectedItems() {
    val state = viewModel.uiState.value
    val shareUris = ArrayList<Uri>()
    if (state.activeSection == VaultSection.CALLS) {
      val selected = state.calls.filter { state.selectedCallIds.contains(it.id) }
      for (item in selected) {
        resolveShareableUri(item.uri, item.legacyFilePath)?.let { shareUris.add(it) }
      }
    } else {
      val selected = state.voiceNotes.filter { state.selectedNoteIds.contains(it.id) }
      for (note in selected) {
        resolveShareableUri(note.uri, note.legacyFilePath)?.let { shareUris.add(it) }
      }
    }

    if (shareUris.isEmpty()) {
      Toast.makeText(this, "حدد تسجيلاً واحداً على الأقل للإرسال.", Toast.LENGTH_SHORT).show()
      return
    }

    val shareIntent =
      if (shareUris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
          type = "audio/*"
          putExtra(Intent.EXTRA_STREAM, shareUris[0])
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
      } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
          type = "audio/*"
          putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
      }
    startActivity(Intent.createChooser(shareIntent, "إرسال التسجيلات (${shareUris.size})"))
  }

  private fun confirmDeleteSelectedItems() {
    val state = viewModel.uiState.value
    if (state.activeSection == VaultSection.CALLS) {
      val selected = state.calls.filter { state.selectedCallIds.contains(it.id) }
      if (selected.isEmpty()) {
        Toast.makeText(this, "حدد مكالمة واحدة على الأقل للحذف.", Toast.LENGTH_SHORT).show()
        return
      }
      AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("حذف المكالمات المحددة")
        .setMessage("هل تريد حذف ${selected.size} مكالمة محددة نهائياً؟")
        .setPositiveButton("حذف الكل") { _, _ -> viewModel.deleteCalls(selected) }
        .setNegativeButton("إلغاء", null)
        .show()
    } else {
      val selected = state.voiceNotes.filter { state.selectedNoteIds.contains(it.id) }
      if (selected.isEmpty()) {
        Toast.makeText(this, "حدد تسجيلاً صوتياً واحداً على الأقل للحذف.", Toast.LENGTH_SHORT).show()
        return
      }
      AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("حذف التسجيلات المحددة")
        .setMessage("هل تريد حذف ${selected.size} تسجيل صوتي محدد نهائياً؟")
        .setPositiveButton("حذف الكل") { _, _ -> viewModel.deleteVoiceNotes(selected) }
        .setNegativeButton("إلغاء", null)
        .show()
    }
  }

  private fun resolveShareableUri(uri: Uri, legacyFilePath: String?): Uri? {
    if (uri.scheme == "content") return uri
    val path = legacyFilePath ?: uri.path ?: return null
    val file = File(path)
    if (!file.exists()) return null
    return try {
      FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    } catch (_: Exception) {
      null
    }
  }

  private fun shareAudio(uri: Uri, legacyFilePath: String?, fileName: String) {
    val shareUri = resolveShareableUri(uri, legacyFilePath)
    if (shareUri != null) {
      val shareIntent =
        Intent(Intent.ACTION_SEND).apply {
          type = "audio/*"
          putExtra(Intent.EXTRA_STREAM, shareUri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
      startActivity(Intent.createChooser(shareIntent, "مشاركة التسجيل"))
    } else {
      Toast.makeText(
        this,
        "تعذر العثور على الملف للمشاركة: $fileName",
        Toast.LENGTH_SHORT,
      ).show()
    }
  }

  private fun hideKeyboard() {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
  }
}
