package com.example

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

class VoiceNotesAdapter(
  private val onCardClick: (VoiceNoteItem) -> Unit,
  private val onPlayClick: (VoiceNoteItem) -> Unit,
  private val onShareClick: (VoiceNoteItem) -> Unit,
  private val onDeleteClick: (VoiceNoteItem) -> Unit,
  private val onTranscribeClick: (VoiceNoteItem) -> Unit,
) : BaseAdapter() {

  private var items: List<VoiceNoteItem> = emptyList()
  private var playingId: String? = null
  private var expandedId: String? = null
  private var isPlaying: Boolean = false
  private var savedNotes: Map<String, VoiceNoteRecord> = emptyMap()

  fun submitList(
    items: List<VoiceNoteItem>,
    playingId: String?,
    expandedId: String?,
    isPlaying: Boolean,
    savedNotes: Map<String, VoiceNoteRecord>,
  ) {
    this.items = items
    this.playingId = playingId
    this.expandedId = expandedId
    this.isPlaying = isPlaying
    this.savedNotes = savedNotes
    notifyDataSetChanged()
  }

  override fun getCount(): Int = items.size

  override fun getItem(position: Int): VoiceNoteItem = items[position]

  override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

  override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
    val view =
      convertView
        ?: LayoutInflater.from(parent.context).inflate(R.layout.item_voice_note, parent, false)

    val note = getItem(position)
    val tvTitle = view.findViewById<TextView>(R.id.tv_note_title)
    val tvDuration = view.findViewById<TextView>(R.id.tv_note_duration)
    val tvTimestamp = view.findViewById<TextView>(R.id.tv_note_timestamp)
    val tvCloudBadge = view.findViewById<TextView>(R.id.tv_note_cloud_badge)
    val btnPlay = view.findViewById<ImageButton>(R.id.btn_note_play)
    val btnShare = view.findViewById<ImageButton>(R.id.btn_note_share)
    val btnDelete = view.findViewById<ImageButton>(R.id.btn_note_delete)
    val expandedActions = view.findViewById<LinearLayout>(R.id.note_expanded_actions)
    val btnTranscribe = view.findViewById<Button>(R.id.btn_note_transcribe)

    tvTitle.text = note.title
    tvDuration.text = formatDuration(note.durationMs)
    tvTimestamp.text = formatTimestamp(note.timestampMs)

    val cloudNote = savedNotes[note.fileName]
    val uploadedLocal =
      FirebaseStorageUploader.getUploadedInfo(
        parent.context,
        VaultStorageFolder.VOICE_NOTES,
        note.fileName,
      )
    val hasTranscript = !cloudNote?.transcript.isNullOrBlank()
    val hasStorage = !cloudNote?.storageDownloadUrl.isNullOrBlank() || uploadedLocal != null

    when {
      hasTranscript && hasStorage -> {
        tvCloudBadge.visibility = View.VISIBLE
        tvCloudBadge.text = "✓ مفرّغ في voice_notes ومرفوع في Storage"
      }
      hasTranscript -> {
        tvCloudBadge.visibility = View.VISIBLE
        tvCloudBadge.text = "✓ مفرّغ في Firestore (voice_notes)"
      }
      hasStorage -> {
        tvCloudBadge.visibility = View.VISIBLE
        tvCloudBadge.text = "☁️ محفوظ في voice_notes و Storage"
      }
      cloudNote != null -> {
        tvCloudBadge.visibility = View.VISIBLE
        tvCloudBadge.text = "✓ محفوظ في Firestore (voice_notes)"
      }
      else -> {
        tvCloudBadge.visibility = View.GONE
      }
    }

    val transcriptionEnabled = CallVaultSettings.isVoiceTranscriptionEnabled(parent.context)
    btnTranscribe.text = if (hasTranscript) "عرض النص والملخص المحفوظ" else "تحويل لنص"

    val activePlaying = note.id == playingId && isPlaying
    btnPlay.setImageResource(if (activePlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    btnPlay.contentDescription = if (activePlaying) "إيقاف مؤقت" else "تشغيل التسجيل الصوتي"

    expandedActions.visibility =
      if (note.id == expandedId && transcriptionEnabled) View.VISIBLE else View.GONE

    view.setOnClickListener {
      if (transcriptionEnabled) {
        onCardClick(note)
      }
    }
    btnPlay.setOnClickListener { onPlayClick(note) }
    btnShare.setOnClickListener { onShareClick(note) }
    btnDelete.setOnClickListener { onDeleteClick(note) }
    btnTranscribe.setOnClickListener { onTranscribeClick(note) }

    return view
  }
}
