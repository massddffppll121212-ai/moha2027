package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
  private lateinit var tvStatusTitle: TextView
  private lateinit var tvStatusSubtitle: TextView
  private lateinit var btnGrantPermissions: Button
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

  private val requiredPermissions: Array<String> by lazy {
    buildList {
      add(Manifest.permission.RECORD_AUDIO)
      add(Manifest.permission.READ_PHONE_STATE)
      add(Manifest.permission.READ_CALL_LOG)
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
    observeState()
    requestCorePermissionsIfNeeded()
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshPermissions()
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
    tvStatusTitle = findViewById(R.id.tv_status_title)
    tvStatusSubtitle = findViewById(R.id.tv_status_subtitle)
    btnGrantPermissions = findViewById(R.id.btn_grant_permissions)
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
        onCardClick = { item -> viewModel.toggleExpandedItem(item.id) },
        onPlayClick = { item -> viewModel.toggleCallPlayback(item) },
        onShareClick = { item -> shareAudio(item.uri, item.legacyFilePath, item.fileName) },
        onDeleteClick = { item -> confirmDeleteCall(item) },
        onTranscribeClick = { item -> showCallTranscriptionDialog(item) },
      )
    listRecordings.adapter = callsAdapter

    voiceNotesAdapter =
      VoiceNotesAdapter(
        onCardClick = { note -> viewModel.toggleExpandedItem(note.id) },
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

    val btnSearch = findViewById<ImageButton>(R.id.btn_search)
    val btnClearSearch = findViewById<ImageButton>(R.id.btn_clear_search)
    val btnRefresh = findViewById<ImageButton>(R.id.btn_refresh)

    btnSearch.setOnClickListener {
      if (searchContainer.visibility == View.VISIBLE) {
        searchContainer.visibility = View.GONE
        etSearch.setText("")
        hideKeyboard()
      } else {
        searchContainer.visibility = View.VISIBLE
        etSearch.requestFocus()
      }
    }

    btnClearSearch.setOnClickListener {
      etSearch.setText("")
      searchContainer.visibility = View.GONE
      hideKeyboard()
    }

    etSearch.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
          viewModel.setSearchQuery(s?.toString().orEmpty())
        }

        override fun afterTextChanged(s: Editable?) {}
      }
    )

    btnRefresh.setOnClickListener { viewModel.loadAllSections() }
    btnGrantPermissions.setOnClickListener { permissionLauncher.launch(requiredPermissions) }

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
      if (isCallsTab) "ابحث باسم المتصل أو الرقم..." else "ابحث باسم التسجيل الصوتي..."

    if (isCallsTab) {
      filterBar.visibility = View.VISIBLE
      fabNewVoiceNote.visibility = View.GONE
      renderCallsSection(state)
    } else {
      filterBar.visibility = View.GONE
      fabNewVoiceNote.visibility = View.VISIBLE
      renderVoiceNotesSection(state)
    }

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

  private fun renderCallsSection(state: CallVaultUiState) {
    listVoiceNotes.visibility = View.GONE

    if (!state.permissions.allCoreGranted) {
      tvStatusTitle.text = "الصلاحيات مطلوبة لتسجيل المكالمات"
      tvStatusSubtitle.text = "فعّل الميكروفون وحالة الهاتف وسجل المكالمات"
      btnGrantPermissions.visibility = View.VISIBLE
    } else if (state.isCallRecording) {
      tvStatusTitle.text = "جاري تسجيل المكالمة الآن..."
      tvStatusSubtitle.text =
        "${state.activeCallSource ?: "MIC"} • ${formatDuration(state.callRecordingElapsedSeconds * 1000L)}"
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
      tvStatusSubtitle.text = "${quality.arabicLabel} • حفظ محلي في CallVault/Calls"
    }

    updatePillStyle(filterAll, state.selectedFilter == CallDirection.ALL)
    updatePillStyle(filterIncoming, state.selectedFilter == CallDirection.INCOMING)
    updatePillStyle(filterOutgoing, state.selectedFilter == CallDirection.OUTGOING)

    val query = state.searchQuery.trim()
    val filteredCalls =
      state.calls.filter { item ->
        val matchesFilter =
          state.selectedFilter == CallDirection.ALL || item.direction == state.selectedFilter
        val matchesSearch =
          query.isEmpty() ||
            item.callerName.contains(query, ignoreCase = true) ||
            item.phoneNumber.contains(query, ignoreCase = true) ||
            item.fileName.contains(query, ignoreCase = true)
        matchesFilter && matchesSearch
      }

    callsAdapter.submitList(
      items = filteredCalls,
      playingId = state.playingItemId,
      expandedId = state.expandedItemId,
      isPlaying = state.isPlaying,
      savedCalls = state.savedCalls,
    )

    if (filteredCalls.isEmpty()) {
      listRecordings.visibility = View.GONE
      emptyState.visibility = View.VISIBLE
      if (query.isNotEmpty() || state.selectedFilter != CallDirection.ALL) {
        tvEmptyTitle.text = "لا توجد مكالمات مطابقة"
        tvEmptySubtitle.text = "جرّب تغيير الفلتر أو البحث بكلمة أخرى."
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

    val transcriptionEnabled = CallVaultSettings.isVoiceTranscriptionEnabled(this)
    val transcriptionLabel = if (transcriptionEnabled) "التفريغ النصي مفعّل" else "التفريغ النصي متوقف"

    tvStatusTitle.text = "التسجيلات الصوتية اليدوية (${state.voiceNotes.size})"
    tvStatusSubtitle.text = "حفظ محلي في CallVault/VoiceNotes • $transcriptionLabel"

    val query = state.searchQuery.trim()
    val filteredNotes =
      state.voiceNotes.filter { note ->
        query.isEmpty() ||
          note.title.contains(query, ignoreCase = true) ||
          note.fileName.contains(query, ignoreCase = true)
      }

    voiceNotesAdapter.submitList(
      items = filteredNotes,
      playingId = state.playingItemId,
      expandedId = state.expandedItemId,
      isPlaying = state.isPlaying,
      savedNotes = state.savedVoiceNotes,
    )

    if (filteredNotes.isEmpty()) {
      listVoiceNotes.visibility = View.GONE
      emptyState.visibility = View.VISIBLE
      if (query.isNotEmpty()) {
        tvEmptyTitle.text = "لا توجد تسجيلات صوتية مطابقة"
        tvEmptySubtitle.text = "جرّب البحث باسم آخر."
      } else {
        tvEmptyTitle.text = "لا توجد تسجيلات صوتية بعد"
        tvEmptySubtitle.text = "اضغط على زر + الأحمر بالأسفل لبدء تسجيل صوتي جديد من الميكروفون."
      }
    } else {
      listVoiceNotes.visibility = View.VISIBLE
      emptyState.visibility = View.GONE
    }
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
    openTranscriptionDialog(
      title = "تفريغ المكالمة",
      subtitle = "${item.callerName} • ${item.phoneNumber}",
      cachedTranscript = existing?.transcript,
      cachedSummary = existing?.summaryPoints.orEmpty(),
      cachedTasks = existing?.actionItems.orEmpty(),
      cachedNotice = "تم التحميل من السجل المحلي للمكالمات",
      runTranscription = { helper ->
        helper.transcribeAndSaveCall(this@MainActivity, item, viewModel.repository)
      },
    )
  }

  private fun showVoiceNoteTranscriptionDialog(note: VoiceNoteItem) {
    if (!CallVaultSettings.isVoiceTranscriptionEnabled(this)) {
      Toast.makeText(this, "تم إيقاف تحويل التسجيلات الصوتية لنص من الإعدادات.", Toast.LENGTH_SHORT)
        .show()
      return
    }
    val existing = viewModel.uiState.value.savedVoiceNotes[note.fileName]
    openTranscriptionDialog(
      title = "تفريغ التسجيل الصوتي",
      subtitle = note.title,
      cachedTranscript = existing?.transcript,
      cachedSummary = existing?.summaryPoints.orEmpty(),
      cachedTasks = existing?.actionItems.orEmpty(),
      cachedNotice = "تم التحميل من السجل المحلي للتسجيلات الصوتية",
      runTranscription = { helper ->
        helper.transcribeAndSaveVoiceNote(this@MainActivity, note, viewModel.repository)
      },
    )
  }

  private fun openTranscriptionDialog(
    title: String,
    subtitle: String,
    cachedTranscript: String?,
    cachedSummary: List<String>,
    cachedTasks: List<String>,
    cachedNotice: String,
    runTranscription: suspend (TranscriptionHelper) -> Result<TranscriptionResult>,
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

      val helper = TranscriptionHelper(repository = viewModel.repository)
      lifecycleScope.launch {
        val result = runTranscription(helper)
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

  private fun shareAudio(uri: Uri, legacyFilePath: String?, fileName: String) {
    if (uri.scheme == "content") {
      val shareIntent =
        Intent(Intent.ACTION_SEND).apply {
          type = "audio/mpeg"
          putExtra(Intent.EXTRA_STREAM, uri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
      startActivity(Intent.createChooser(shareIntent, "مشاركة التسجيل"))
    } else {
      Toast.makeText(
        this,
        "مسار الملف: ${legacyFilePath ?: fileName}",
        Toast.LENGTH_SHORT,
      ).show()
    }
  }

  private fun hideKeyboard() {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
  }
}
