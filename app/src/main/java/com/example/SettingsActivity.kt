package com.example

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

  private lateinit var switchAutoCallRecording: Switch
  private lateinit var switchAutoCallTranscription: Switch
  private lateinit var switchCallSpeakerRecording: Switch
  private lateinit var btnQualityHigh: TextView
  private lateinit var btnQualityMedium: TextView
  private lateinit var tvCallQualityHint: TextView
  private lateinit var tvExcludedNumbersCount: TextView
  private lateinit var tvShortThresholdValue: TextView

  private lateinit var switchVoiceTranscription: Switch

  private lateinit var tvCloudSyncStatus: TextView
  private lateinit var btnCloudSyncPrimary: Button
  private lateinit var btnCloudSyncDisconnect: Button

  private lateinit var btnBack: ImageButton
  private lateinit var btnSaveDone: Button

  private var onExcludedListChangedCallback: (() -> Unit)? = null

  private val cloudSyncManager by lazy { CloudSyncManager.getInstance(applicationContext) }
  private val localRepository by lazy { LocalCallVaultRepository.getInstance(applicationContext) }

  private val pickContactLauncher =
    registerForActivityResult(ActivityResultContracts.PickContact()) { contactUri ->
      if (contactUri != null) {
        handlePickedContact(contactUri)
      }
    }

  private val contactsPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        pickContactLauncher.launch(null)
      } else {
        Toast.makeText(
          this,
          "يرجى منح صلاحية جهات الاتصال لاختيار رقم من الهاتف، أو أدخل الرقم يدوياً.",
          Toast.LENGTH_SHORT,
        ).show()
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureAppCheck(this, intent)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.parseColor("#0E1B36")),
      navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#111111")),
    )
    setContentView(R.layout.activity_settings)

    val root = findViewById<View>(R.id.settings_root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }

    bindViews()
    loadSavedSettings()
    setupListeners()
    observeCloudSyncState()
  }

  private fun bindViews() {
    btnBack = findViewById(R.id.btn_settings_back)
    btnSaveDone = findViewById(R.id.btn_save_settings_done)

    switchAutoCallRecording = findViewById(R.id.switch_auto_call_recording)
    switchAutoCallTranscription = findViewById(R.id.switch_auto_call_transcription)
    switchCallSpeakerRecording = findViewById(R.id.switch_call_speaker_recording)
    btnQualityHigh = findViewById(R.id.btn_quality_high)
    btnQualityMedium = findViewById(R.id.btn_quality_medium)
    tvCallQualityHint = findViewById(R.id.tv_call_quality_hint)
    tvExcludedNumbersCount = findViewById(R.id.tv_excluded_numbers_count)
    tvShortThresholdValue = findViewById(R.id.tv_short_threshold_value)

    switchVoiceTranscription = findViewById(R.id.switch_voice_transcription)

    tvCloudSyncStatus = findViewById(R.id.tv_cloud_sync_status)
    btnCloudSyncPrimary = findViewById(R.id.btn_cloud_sync_primary)
    btnCloudSyncDisconnect = findViewById(R.id.btn_cloud_sync_disconnect)

    applySwitchColors(switchAutoCallRecording)
    applySwitchColors(switchAutoCallTranscription)
    applySwitchColors(switchCallSpeakerRecording)
    applySwitchColors(switchVoiceTranscription)
  }

  private fun applySwitchColors(switchView: Switch) {
    val thumbColors =
      ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#CBD5E1")),
      )
    val trackColors =
      ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(Color.parseColor("#1D4ED8"), Color.parseColor("#1E293B")),
      )
    switchView.thumbTintList = thumbColors
    switchView.trackTintList = trackColors
  }

  private fun loadSavedSettings() {
    switchAutoCallRecording.isChecked = CallVaultSettings.isAutoCallRecordingEnabled(this)
    switchAutoCallTranscription.isChecked = CallVaultSettings.isCallAutoTranscriptionEnabled(this)
    switchCallSpeakerRecording.isChecked = CallVaultSettings.isCallSpeakerRecordingEnabled(this)
    renderCallQuality(CallVaultSettings.getCallRecordingQuality(this))
    renderExcludedCount()
    renderShortThreshold()
    switchVoiceTranscription.isChecked = CallVaultSettings.isVoiceTranscriptionEnabled(this)
  }

  private fun renderExcludedCount() {
    val count = CallVaultSettings.getExcludedNumbers(this).size
    tvExcludedNumbersCount.text =
      if (count == 0) {
        "لا توجد أرقام مستبعدة حالياً"
      } else {
        "$count رقم مستبعد لن يتم تسجيل مكالماته"
      }
  }

  private fun renderShortThreshold() {
    val sec = CallVaultSettings.getShortRecordingThresholdSeconds(this)
    tvShortThresholdValue.text = "حذف التسجيلات الأقصر من $sec ثانية"
  }

  private fun setupListeners() {
    btnBack.setOnClickListener { finish() }

    btnSaveDone.setOnClickListener {
      Toast.makeText(this, "تم حفظ الإعدادات.", Toast.LENGTH_SHORT).show()
      finish()
    }

    findViewById<View>(R.id.row_auto_call_recording).setOnClickListener {
      switchAutoCallRecording.toggle()
    }
    switchAutoCallRecording.setOnCheckedChangeListener { _, isChecked ->
      CallVaultSettings.setAutoCallRecordingEnabled(this, isChecked)
    }

    findViewById<View>(R.id.row_auto_call_transcription).setOnClickListener {
      switchAutoCallTranscription.toggle()
    }
    switchAutoCallTranscription.setOnCheckedChangeListener { _, isChecked ->
      CallVaultSettings.setCallAutoTranscriptionEnabled(this, isChecked)
    }

    findViewById<View>(R.id.row_call_speaker_recording).setOnClickListener {
      switchCallSpeakerRecording.toggle()
    }
    switchCallSpeakerRecording.setOnCheckedChangeListener { _, isChecked ->
      CallVaultSettings.setCallSpeakerRecordingEnabled(this, isChecked)
    }

    findViewById<View>(R.id.row_excluded_numbers).setOnClickListener {
      showExcludedNumbersDialog()
    }

    findViewById<View>(R.id.row_short_threshold).setOnClickListener {
      showShortThresholdDialog()
    }

    btnQualityHigh.setOnClickListener {
      CallVaultSettings.setCallRecordingQuality(this, CallRecordingQuality.HIGH)
      renderCallQuality(CallRecordingQuality.HIGH)
    }

    btnQualityMedium.setOnClickListener {
      CallVaultSettings.setCallRecordingQuality(this, CallRecordingQuality.MEDIUM)
      renderCallQuality(CallRecordingQuality.MEDIUM)
    }

    findViewById<View>(R.id.row_voice_transcription).setOnClickListener {
      switchVoiceTranscription.toggle()
    }
    switchVoiceTranscription.setOnCheckedChangeListener { _, isChecked ->
      CallVaultSettings.setVoiceTranscriptionEnabled(this, isChecked)
    }

    btnCloudSyncPrimary.setOnClickListener {
      val state = cloudSyncManager.syncState.value
      lifecycleScope.launch {
        if (!state.isSignedIn) {
          cloudSyncManager
            .signInWithGoogleForSync(this@SettingsActivity)
            .onSuccess { email ->
              Toast.makeText(
                this@SettingsActivity,
                "تم ربط $email. جاري المزامنة...",
                Toast.LENGTH_SHORT,
              ).show()
              cloudSyncManager.syncAllWithCloud(localRepository)
            }
            .onFailure { err ->
              Toast.makeText(
                this@SettingsActivity,
                err.localizedMessage ?: "تعذر ربط الحساب.",
                Toast.LENGTH_LONG,
              ).show()
            }
        } else {
          cloudSyncManager
            .syncAllWithCloud(localRepository)
            .onSuccess { count ->
              Toast.makeText(
                this@SettingsActivity,
                "تمت المزامنة السحابية ($count سجل).",
                Toast.LENGTH_SHORT,
              ).show()
            }
            .onFailure { err ->
              Toast.makeText(
                this@SettingsActivity,
                err.localizedMessage ?: "تعذرت المزامنة.",
                Toast.LENGTH_LONG,
              ).show()
            }
        }
      }
    }

    btnCloudSyncDisconnect.setOnClickListener {
      lifecycleScope.launch {
        cloudSyncManager.disconnectCloudSync(localRepository)
        Toast.makeText(
          this@SettingsActivity,
          "تم فصل المزامنة السحابية. التسجيلات محفوظة محلياً.",
          Toast.LENGTH_SHORT,
        ).show()
      }
    }
  }

  private fun observeCloudSyncState() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        cloudSyncManager.syncState.collect { state ->
          if (!state.isFirebaseAvailable) {
            tvCloudSyncStatus.text = "غير متاح: ملف google-services.json غير مدمج في النسخة"
            btnCloudSyncPrimary.isEnabled = false
            btnCloudSyncDisconnect.visibility = View.GONE
          } else if (state.isSignedIn) {
            tvCloudSyncStatus.text =
              if (state.isSyncing) {
                "جاري المزامنة مع ${state.userEmail ?: "Google"}..."
              } else {
                "متزامن مع: ${state.userEmail ?: state.userId ?: "حساب Google"}"
              }
            btnCloudSyncPrimary.isEnabled = !state.isSyncing
            btnCloudSyncPrimary.text = if (state.isSyncing) "جاري المزامنة..." else "مزامنة الآن"
            btnCloudSyncDisconnect.visibility = View.VISIBLE
          } else {
            tvCloudSyncStatus.text = "الوضع الحالي: حفظ محلي فقط بدون حساب"
            btnCloudSyncPrimary.isEnabled = true
            btnCloudSyncPrimary.text = "ربط حساب Google للمزامنة"
            btnCloudSyncDisconnect.visibility = View.GONE
          }
        }
      }
    }
  }

  private fun renderCallQuality(quality: CallRecordingQuality) {
    val isHigh = quality == CallRecordingQuality.HIGH
    updatePillStyle(btnQualityHigh, isHigh)
    updatePillStyle(btnQualityMedium, !isHigh)
    tvCallQualityHint.text =
      if (isHigh) {
        "جودة عالية: 256 kbps • 48 kHz لنقاء أوضح للمكالمات"
      } else {
        "جودة متوسطة: 128 kbps • 44.1 kHz لتوفير مساحة التخزين"
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

  private fun showExcludedNumbersDialog() {
    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(44, 28, 44, 16)
      }

    val etNumber =
      EditText(this).apply {
        inputType = InputType.TYPE_CLASS_PHONE
        hint = "أدخل رقم الهاتف (مثال: 0501234567)"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#64748B"))
        setPadding(24, 22, 24, 22)
        setBackgroundResource(R.drawable.bg_search_field)
      }
    root.addView(etNumber)

    val etLabel =
      EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        hint = "الاسم أو الوصف (اختياري)"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#64748B"))
        setPadding(24, 22, 24, 22)
        setBackgroundResource(R.drawable.bg_search_field)
        val lp =
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
          )
        lp.topMargin = 16
        layoutParams = lp
      }
    root.addView(etLabel)

    val actionsRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val lp =
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
          )
        lp.topMargin = 18
        layoutParams = lp
      }

    val btnAddManual =
      Button(this).apply {
        text = "إضافة الرقم"
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 13f
        minHeight = 48
        setBackgroundResource(R.drawable.bg_pill_selected)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginEnd = 8
        layoutParams = lp
      }

    val btnPickContact =
      Button(this).apply {
        text = "من جهات الاتصال"
        isAllCaps = false
        setTextColor(Color.parseColor("#38BDF8"))
        textSize = 13f
        minHeight = 48
        setBackgroundResource(R.drawable.bg_pill_unselected)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }

    actionsRow.addView(btnAddManual)
    actionsRow.addView(btnPickContact)
    root.addView(actionsRow)

    val listScroll =
      ScrollView(this).apply {
        val lp =
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            520,
          )
        lp.topMargin = 20
        layoutParams = lp
      }
    val listContainer =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
      }
    listScroll.addView(listContainer)
    root.addView(listScroll)

    fun rebuildExcludedList() {
      listContainer.removeAllViews()
      val entries = CallVaultSettings.getExcludedNumbers(this)
      renderExcludedCount()
      if (entries.isEmpty()) {
        val tvEmpty =
          TextView(this).apply {
            text = "القائمة فارغة حالياً. أضف رقماً لاستبعاده من التسجيل التلقائي."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(8, 24, 8, 8)
          }
        listContainer.addView(tvEmpty)
        return
      }

      for (entry in entries) {
        val row =
          LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_pill_unselected)
            setPadding(24, 16, 16, 16)
            val lp =
              LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
              )
            lp.bottomMargin = 12
            layoutParams = lp
          }

        val infoCol =
          LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
          }
        val tvName =
          TextView(this).apply {
            text = entry.label.ifBlank { "رقم مستبعد" }
            setTextColor(Color.WHITE)
            textSize = 14f
          }
        val tvNum =
          TextView(this).apply {
            text = entry.number
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            textDirection = View.TEXT_DIRECTION_LTR
          }
        infoCol.addView(tvName)
        infoCol.addView(tvNum)

        val btnRemove =
          Button(this).apply {
            text = "إزالة"
            isAllCaps = false
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 12f
            minHeight = 48
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
              CallVaultSettings.removeExcludedNumber(this@SettingsActivity, entry.number)
              rebuildExcludedList()
            }
          }

        row.addView(infoCol)
        row.addView(btnRemove)
        listContainer.addView(row)
      }
    }

    onExcludedListChangedCallback = { rebuildExcludedList() }
    rebuildExcludedList()

    btnAddManual.setOnClickListener {
      val num = etNumber.text?.toString().orEmpty().trim()
      val label = etLabel.text?.toString().orEmpty().trim()
      if (CallVaultSettings.addExcludedNumber(this, num, label)) {
        etNumber.setText("")
        etLabel.setText("")
        rebuildExcludedList()
        Toast.makeText(this, "تمت إضافة الرقم إلى قائمة الاستبعاد.", Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(this, "أدخل رقم هاتف صحيح (3 أرقام على الأقل).", Toast.LENGTH_SHORT).show()
      }
    }

    btnPickContact.setOnClickListener {
      val hasContacts =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
          PackageManager.PERMISSION_GRANTED
      if (hasContacts) {
        pickContactLauncher.launch(null)
      } else {
        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
      }
    }

    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
      .setTitle("الأرقام المستبعدة من التسجيل")
      .setView(root)
      .setPositiveButton("إغلاق") { _, _ ->
        onExcludedListChangedCallback = null
      }
      .setOnDismissListener {
        onExcludedListChangedCallback = null
      }
      .show()
  }

  private fun handlePickedContact(contactUri: Uri) {
    if (
      ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    var contactId: String? = null
    var displayName = ""
    try {
      contentResolver
        .query(
          contactUri,
          arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
          null,
          null,
          null,
        )
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            contactId = cursor.getString(0)
            displayName = cursor.getString(1)?.trim().orEmpty()
          }
        }
    } catch (_: Exception) {
    }

    val id = contactId ?: return
    val numbers = mutableListOf<String>()
    try {
      contentResolver
        .query(
          ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
          arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
          "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
          arrayOf(id),
          null,
        )
        ?.use { phoneCursor ->
          while (phoneCursor.moveToNext()) {
            val raw = phoneCursor.getString(0)?.trim()
            if (!raw.isNullOrEmpty() && numbers.none { it == raw }) {
              numbers.add(raw)
            }
          }
        }
    } catch (_: Exception) {
    }

    if (numbers.isEmpty()) {
      Toast.makeText(this, "لا يوجد رقم هاتف محفوظ لهذه الجهة.", Toast.LENGTH_SHORT).show()
      return
    }

    if (numbers.size == 1) {
      if (CallVaultSettings.addExcludedNumber(this, numbers.first(), displayName)) {
        renderExcludedCount()
        onExcludedListChangedCallback?.invoke()
        Toast.makeText(this, "تم استبعاد ${displayName.ifBlank { numbers.first() }}.", Toast.LENGTH_SHORT).show()
      }
    } else {
      AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("اختر رقماً للاستبعاد (${displayName.ifBlank { "جهة اتصال" }})")
        .setItems(numbers.toTypedArray()) { _, which ->
          val chosen = numbers[which]
          if (CallVaultSettings.addExcludedNumber(this, chosen, displayName)) {
            renderExcludedCount()
            onExcludedListChangedCallback?.invoke()
            Toast.makeText(this, "تم استبعاد $chosen.", Toast.LENGTH_SHORT).show()
          }
        }
        .setNegativeButton("إلغاء", null)
        .show()
    }
  }

  private fun showShortThresholdDialog() {
    val currentSec = CallVaultSettings.getShortRecordingThresholdSeconds(this)
    val container =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(48, 28, 48, 16)
      }

    val etSeconds =
      EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(currentSec.toString())
        setSelection(text.length)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#64748B"))
        hint = "المدة بالثواني (1 - 3600)"
        setPadding(24, 22, 24, 22)
        setBackgroundResource(R.drawable.bg_search_field)
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
        lp.topMargin = 18
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

    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
      .setTitle("تحديد مدة التسجيلات القصيرة (بالثواني)")
      .setView(container)
      .setPositiveButton("حفظ") { _, _ ->
        val entered = etSeconds.text?.toString()?.trim()?.toIntOrNull()
        if (entered == null || entered <= 0) {
          Toast.makeText(this, "يرجى إدخال مدة صحيحة بالثواني.", Toast.LENGTH_SHORT).show()
        } else {
          CallVaultSettings.setShortRecordingThresholdSeconds(this, entered)
          renderShortThreshold()
          Toast.makeText(this, "تم ضبط الحد على ${entered.coerceIn(1, 3600)} ثانية.", Toast.LENGTH_SHORT).show()
        }
      }
      .setNegativeButton("إلغاء", null)
      .show()
  }
}
