package com.example

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

class VoiceNotesAdapter(
  private val onCardClick: (VoiceNoteItem) -> Unit,
  private val onCardLongClick: (VoiceNoteItem) -> Unit,
  private val onToggleSelect: (VoiceNoteItem) -> Unit,
  private val onPlayClick: (VoiceNoteItem) -> Unit,
  private val onShareClick: (VoiceNoteItem) -> Unit,
  private val onDeleteClick: (VoiceNoteItem) -> Unit,
  private val onTranscribeClick: (VoiceNoteItem) -> Unit,
) : BaseAdapter() {

  private var items: List<VoiceNoteItem> = emptyList()
  private var playingId: String? = null
  private var isPlaying: Boolean = false
  private var savedNotes: Map<String, VoiceNoteRecord> = emptyMap()
  private var transcribingIds: Set<String> = emptySet()
  private var selectionMode: Boolean = false
  private var selectedIds: Set<String> = emptySet()

  fun submitList(
    items: List<VoiceNoteItem>,
    playingId: String?,
    isPlaying: Boolean,
    savedNotes: Map<String, VoiceNoteRecord>,
    transcribingIds: Set<String>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
  ) {
    this.items = items
    this.playingId = playingId
    this.isPlaying = isPlaying
    this.savedNotes = savedNotes
    this.transcribingIds = transcribingIds
    this.selectionMode = selectionMode
    this.selectedIds = selectedIds
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
    val cbSelect = view.findViewById<CheckBox>(R.id.cb_note_select)
    val tvTitle = view.findViewById<TextView>(R.id.tv_note_title)
    val tvDuration = view.findViewById<TextView>(R.id.tv_note_duration)
    val tvTimestamp = view.findViewById<TextView>(R.id.tv_note_timestamp)
    val tvCloudBadge = view.findViewById<TextView>(R.id.tv_note_cloud_badge)
    val tvTranscriptPreview = view.findViewById<TextView>(R.id.tv_note_transcript_preview)
    val btnPlay = view.findViewById<ImageButton>(R.id.btn_note_play)
    val btnShare = view.findViewById<ImageButton>(R.id.btn_note_share)
    val btnDelete = view.findViewById<ImageButton>(R.id.btn_note_delete)
    val expandedActions = view.findViewById<LinearLayout>(R.id.note_expanded_actions)
    val btnTranscribe = view.findViewById<Button>(R.id.btn_note_transcribe)

    tvTitle.text = note.title
    tvDuration.text = formatDuration(note.durationMs)
    tvTimestamp.text = formatTimestamp(note.timestampMs)

    val isSelected = selectedIds.contains(note.id)
    cbSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
    cbSelect.setOnCheckedChangeListener(null)
    cbSelect.isChecked = isSelected
    cbSelect.setOnClickListener { onToggleSelect(note) }

    val savedNote = savedNotes[note.fileName]
    val transcriptText = savedNote?.transcript?.trim().orEmpty()
    val hasTranscript = transcriptText.isNotEmpty()
    val isTranscribing = transcribingIds.contains(note.id)

    if (hasTranscript) {
      tvCloudBadge.visibility = View.VISIBLE
      tvCloudBadge.text =
        if (savedNote?.syncedToCloud == true) {
          "✓ مفرّغ ومتزامن سحابياً ☁"
        } else {
          "✓ مفرّغ ومحفوظ محلياً"
        }
      tvTranscriptPreview.visibility = View.VISIBLE
      tvTranscriptPreview.text = transcriptText
    } else {
      tvCloudBadge.visibility = View.GONE
      tvTranscriptPreview.visibility = View.GONE
    }

    expandedActions.visibility = View.VISIBLE
    btnTranscribe.isEnabled = !isTranscribing
    btnTranscribe.text =
      when {
        isTranscribing -> "جاري تحويل التسجيل..."
        hasTranscript -> "عرض النص والملخص المحفوظ"
        else -> "تحويل لنص"
      }

    val activePlaying = note.id == playingId && isPlaying
    btnPlay.setImageResource(if (activePlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    btnPlay.contentDescription = if (activePlaying) "إيقاف مؤقت" else "تشغيل التسجيل الصوتي"

    view.setOnClickListener {
      if (selectionMode) {
        onToggleSelect(note)
      } else {
        onCardClick(note)
      }
    }
    view.setOnLongClickListener {
      onCardLongClick(note)
      true
    }
    btnPlay.setOnClickListener { onPlayClick(note) }
    btnShare.setOnClickListener { onShareClick(note) }
    btnDelete.setOnClickListener { onDeleteClick(note) }
    btnTranscribe.setOnClickListener { onTranscribeClick(note) }

    return view
  }
}
