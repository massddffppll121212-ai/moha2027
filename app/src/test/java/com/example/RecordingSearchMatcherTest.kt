package com.example

import android.net.Uri
import java.util.Calendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecordingSearchMatcherTest {

  private fun sampleCall(
    callerName: String = "أحمد محمد",
    phoneNumber: String = "+966501234567",
    timestampMs: Long,
  ): CallRecordingItem {
    return CallRecordingItem(
      id = "call_1",
      uri = Uri.EMPTY,
      fileName = "Call_Incoming_0501234567_20261126_100000.mp3",
      callerName = callerName,
      phoneNumber = phoneNumber,
      direction = CallDirection.INCOMING,
      timestampMs = timestampMs,
      durationMs = 30_000L,
      sizeBytes = 1024L,
    )
  }

  private fun sampleNote(title: String = "ملاحظة عمل", timestampMs: Long): VoiceNoteItem {
    return VoiceNoteItem(
      id = "note_1",
      uri = Uri.EMPTY,
      fileName = "VoiceNote_20261126_100000.mp3",
      title = title,
      timestampMs = timestampMs,
      durationMs = 15_000L,
      sizeBytes = 512L,
    )
  }

  @Test
  fun matchesByContactNameAndPhoneAndTranscript() {
    val now = Calendar.getInstance().timeInMillis
    val call = sampleCall(timestampMs = now)

    assertTrue(RecordingSearchMatcher.matchesCall(call, "", "أحمد", now))
    assertTrue(RecordingSearchMatcher.matchesCall(call, "", "050123", now))
    assertTrue(RecordingSearchMatcher.matchesCall(call, "اجتماع الغد", "اجتماع", now))
    assertFalse(RecordingSearchMatcher.matchesCall(call, "", "خالد", now))
  }

  @Test
  fun matchesByNumericArabicAndRelativeDates() {
    val cal =
      Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 26, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
      }
    val callTime = cal.timeInMillis
    val yesterdayTime =
      Calendar.getInstance().apply {
        timeInMillis = callTime
        add(Calendar.DAY_OF_YEAR, -1)
      }.timeInMillis

    val call = sampleCall(timestampMs = callTime)
    val note = sampleNote(timestampMs = yesterdayTime)

    assertTrue(RecordingSearchMatcher.matchesCall(call, "", "26/11/2026", callTime))
    assertTrue(RecordingSearchMatcher.matchesCall(call, "", "٢٦/١١", callTime))
    assertTrue(RecordingSearchMatcher.matchesCall(call, "", "2026-11-26", callTime))
    assertTrue(RecordingSearchMatcher.matchesCall(call, "", "اليوم", callTime))
    assertTrue(RecordingSearchMatcher.matchesCall(call, "", "أحمد 26/11", callTime))
    assertFalse(RecordingSearchMatcher.matchesCall(call, "", "25/11/2026", callTime))

    assertTrue(RecordingSearchMatcher.matchesVoiceNote(note, "", "أمس", callTime))
    assertTrue(RecordingSearchMatcher.matchesVoiceNote(note, "", "ملاحظة 25/11", callTime))
  }
}
