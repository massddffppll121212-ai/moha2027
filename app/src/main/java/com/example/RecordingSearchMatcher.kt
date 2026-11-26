package com.example

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object RecordingSearchMatcher {

  private val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
  private val extendedArabicDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

  fun normalizeDigits(input: String): String {
    if (input.isEmpty()) return input
    val sb = StringBuilder(input.length)
    for (ch in input) {
      val idx1 = arabicDigits.indexOf(ch)
      if (idx1 >= 0) {
        sb.append(('0'.code + idx1).toChar())
        continue
      }
      val idx2 = extendedArabicDigits.indexOf(ch)
      if (idx2 >= 0) {
        sb.append(('0'.code + idx2).toChar())
        continue
      }
      sb.append(ch)
    }
    return sb.toString()
  }

  fun matchesCall(
    item: CallRecordingItem,
    transcript: String,
    rawQuery: String,
    nowMs: Long = System.currentTimeMillis(),
  ): Boolean {
    val trimmed = rawQuery.trim()
    if (trimmed.isEmpty()) return true

    val dateStrings = buildDateSearchCandidates(item.timestampMs, nowMs)
    val searchableFields =
      listOf(
        item.callerName,
        item.phoneNumber,
        item.fileName,
        transcript,
      ) + dateStrings

    return matchesFields(searchableFields, trimmed)
  }

  fun matchesVoiceNote(
    note: VoiceNoteItem,
    transcript: String,
    rawQuery: String,
    nowMs: Long = System.currentTimeMillis(),
  ): Boolean {
    val trimmed = rawQuery.trim()
    if (trimmed.isEmpty()) return true

    val dateStrings = buildDateSearchCandidates(note.timestampMs, nowMs)
    val searchableFields =
      listOf(
        note.title,
        note.fileName,
        transcript,
      ) + dateStrings

    return matchesFields(searchableFields, trimmed)
  }

  private fun matchesFields(fields: List<String>, rawQuery: String): Boolean {
    val normalizedQuery = normalizeDigits(rawQuery)
    val normalizedFields = fields.map { normalizeDigits(it) }

    if (normalizedFields.any { it.contains(normalizedQuery, ignoreCase = true) }) {
      return true
    }

    val tokens = normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.size <= 1) return false

    return tokens.all { token ->
      normalizedFields.any { field -> field.contains(token, ignoreCase = true) }
    }
  }

  fun buildDateSearchCandidates(
    timestampMs: Long,
    nowMs: Long = System.currentTimeMillis(),
  ): List<String> {
    if (timestampMs <= 0L) return emptyList()
    val date = Date(timestampMs)
    val arLocale = Locale.forLanguageTag("ar")
    val enLocale = Locale.US

    val patterns =
      listOf(
        SimpleDateFormat("yyyy-MM-dd", enLocale),
        SimpleDateFormat("dd/MM/yyyy", enLocale),
        SimpleDateFormat("d/M/yyyy", enLocale),
        SimpleDateFormat("dd-MM-yyyy", enLocale),
        SimpleDateFormat("yyyy/MM/dd", enLocale),
        SimpleDateFormat("dd/MM", enLocale),
        SimpleDateFormat("d/M", enLocale),
        SimpleDateFormat("dd-MM", enLocale),
        SimpleDateFormat("dd MMM yyyy", enLocale),
        SimpleDateFormat("d MMM yyyy", enLocale),
        SimpleDateFormat("dd MMMM yyyy", arLocale),
        SimpleDateFormat("d MMMM yyyy", arLocale),
        SimpleDateFormat("dd MMM", arLocale),
        SimpleDateFormat("EEEE", arLocale),
        SimpleDateFormat("EEEE", enLocale),
        SimpleDateFormat("HH:mm", enLocale),
      )

    val results = ArrayList<String>(patterns.size + 4)
    results.add(formatTimestamp(timestampMs))
    for (fmt in patterns) {
      results.add(fmt.format(date))
    }

    val targetCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val todayCal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val yesterdayCal =
      Calendar.getInstance().apply {
        timeInMillis = nowMs
        add(Calendar.DAY_OF_YEAR, -1)
      }

    if (isSameDay(targetCal, todayCal)) {
      results.add("اليوم")
      results.add("today")
    } else if (isSameDay(targetCal, yesterdayCal)) {
      results.add("أمس")
      results.add("امس")
      results.add("البارحة")
      results.add("yesterday")
    }

    return results
  }

  private fun isSameDay(a: Calendar, b: Calendar): Boolean {
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
      a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
  }
}
