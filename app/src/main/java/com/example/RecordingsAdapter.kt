package com.example

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsAdapter(
  private val onCardClick: (CallRecordingItem) -> Unit,
  private val onCardLongClick: (CallRecordingItem) -> Unit,
  private val onToggleSelect: (CallRecordingItem) -> Unit,
  private val onPlayClick: (CallRecordingItem) -> Unit,
  private val onShareClick: (CallRecordingItem) -> Unit,
  private val onDeleteClick: (CallRecordingItem) -> Unit,
  private val onTranscribeClick: (CallRecordingItem) -> Unit,
) : BaseAdapter() {

  private var items: List<CallRecordingItem> = emptyList()
  private var playingId: String? = null
  private var isPlaying: Boolean = false
  private var savedCalls: Map<String, CallRecord> = emptyMap()
  private var transcribingIds: Set<String> = emptySet()
  private var selectionMode: Boolean = false
  private var selectedIds: Set<String> = emptySet()

  fun submitList(
    items: List<CallRecordingItem>,
    playingId: String?,
    isPlaying: Boolean,
    savedCalls: Map<String, CallRecord>,
    transcribingIds: Set<String>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
  ) {
    this.items = items
    this.playingId = playingId
    this.isPlaying = isPlaying
    this.savedCalls = savedCalls
    this.transcribingIds = transcribingIds
    this.selectionMode = selectionMode
    this.selectedIds = selectedIds
    notifyDataSetChanged()
  }

  override fun getCount(): Int = items.size

  override fun getItem(position: Int): CallRecordingItem = items[position]

  override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

  override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
    val view =
      convertView
        ?: LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)

    val item = getItem(position)
    val cbSelect = view.findViewById<CheckBox>(R.id.cb_call_select)
    val ivDirection = view.findViewById<ImageView>(R.id.iv_direction)
    val tvCallerName = view.findViewById<TextView>(R.id.tv_caller_name)
    val tvPhoneNumber = view.findViewById<TextView>(R.id.tv_phone_number)
    val tvDuration = view.findViewById<TextView>(R.id.tv_duration)
    val tvDirectionLabel = view.findViewById<TextView>(R.id.tv_direction_label)
    val tvTimestamp = view.findViewById<TextView>(R.id.tv_timestamp)
    val tvCloudBadge = view.findViewById<TextView>(R.id.tv_cloud_badge)
    val tvTranscriptPreview = view.findViewById<TextView>(R.id.tv_call_transcript_preview)
    val btnPlay = view.findViewById<ImageButton>(R.id.btn_play)
    val btnShare = view.findViewById<ImageButton>(R.id.btn_share)
    val btnDelete = view.findViewById<ImageButton>(R.id.btn_delete)
    val expandedActions = view.findViewById<LinearLayout>(R.id.expanded_actions)
    val btnTranscribe = view.findViewById<Button>(R.id.btn_transcribe)

    tvCallerName.text = item.callerName
    tvPhoneNumber.text = item.phoneNumber
    tvDuration.text = formatDuration(item.durationMs)
    tvDirectionLabel.text = item.direction.arabicLabel
    tvTimestamp.text = formatTimestamp(item.timestampMs)

    val isSelected = selectedIds.contains(item.id)
    cbSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
    cbSelect.setOnCheckedChangeListener(null)
    cbSelect.isChecked = isSelected
    cbSelect.setOnClickListener { onToggleSelect(item) }

    val savedCall = savedCalls[item.fileName]
    val transcriptText = savedCall?.transcript?.trim().orEmpty()
    val hasTranscript = transcriptText.isNotEmpty()
    val isTranscribing = transcribingIds.contains(item.id)

    if (hasTranscript) {
      tvCloudBadge.visibility = View.VISIBLE
      tvCloudBadge.text =
        if (savedCall?.syncedToCloud == true) {
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
        isTranscribing -> "جاري تحويل المكالمة..."
        hasTranscript -> "عرض النص والملخص المحفوظ"
        else -> "تحويل لنص"
      }

    ivDirection.setImageResource(
      when (item.direction) {
        CallDirection.INCOMING -> R.drawable.ic_call_incoming
        CallDirection.OUTGOING, CallDirection.ALL -> R.drawable.ic_call_outgoing
      }
    )

    val activePlaying = item.id == playingId && isPlaying
    btnPlay.setImageResource(if (activePlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    btnPlay.contentDescription = if (activePlaying) "إيقاف مؤقت" else "تشغيل التسجيل"

    view.setOnClickListener {
      if (selectionMode) {
        onToggleSelect(item)
      } else {
        onCardClick(item)
      }
    }
    view.setOnLongClickListener {
      onCardLongClick(item)
      true
    }
    btnPlay.setOnClickListener { onPlayClick(item) }
    btnShare.setOnClickListener { onShareClick(item) }
    btnDelete.setOnClickListener { onDeleteClick(item) }
    btnTranscribe.setOnClickListener { onTranscribeClick(item) }

    return view
  }
}

fun formatTimestamp(timestampMs: Long): String {
  if (timestampMs <= 0L) return "غير معروف"
  val formatter = SimpleDateFormat("dd MMM • HH:mm", Locale.forLanguageTag("ar"))
  return formatter.format(Date(timestampMs))
}

fun formatDuration(durationMs: Long): String {
  val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
  val minutes = totalSeconds / 60L
  val seconds = totalSeconds % 60L
  return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
