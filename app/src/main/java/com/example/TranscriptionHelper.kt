package com.example

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class TranscriptionResult(
  val transcript: String,
  val summaryPoints: List<String>,
  val actionItems: List<String>,
  val notice: String = "",
  val savedLocally: Boolean = false,
  val syncedToCloud: Boolean = false,
)

interface CallTranscriber {
  suspend fun transcribeAudio(
    context: Context,
    uri: Uri,
    legacyFilePath: String?,
    contextPromptHint: String,
  ): Result<TranscriptionResult>
}

class GeminiCallTranscriber : CallTranscriber {

  private val structuredSchema =
    Schema.obj(
      properties =
        mapOf(
          "transcript" to
            Schema.string(
              description =
                "النص العربي الكامل للتسجيل الصوتي، مع بذل أقصى دقة لالتقاط الصوت حتى لو كان ضعيفاً أو منخفضاً"
            ),
          "summaryPoints" to
            Schema.array(
              items = Schema.string(),
              description = "ملخص المحتوى باللغة العربية في 3 نقاط رئيسية واضحة ومختصرة",
            ),
          "actionItems" to
            Schema.array(
              items = Schema.string(),
              description =
                "قائمة المهام والالتزامات المستخرجة من التسجيل باللغة العربية",
            ),
        )
    )

  private val model by lazy {
    Firebase.ai.generativeModel(
      modelName = MODEL_NAME,
      generationConfig =
        generationConfig {
          responseMimeType = "application/json"
          responseSchema = structuredSchema
        },
    )
  }

  override suspend fun transcribeAudio(
    context: Context,
    uri: Uri,
    legacyFilePath: String?,
    contextPromptHint: String,
  ): Result<TranscriptionResult> =
    withContext(Dispatchers.IO) {
      try {
        val appContext = context.applicationContext ?: context
        if (!ensureFirebaseInitialized(appContext, null)) {
          return@withContext Result.failure(
            IllegalStateException(
              "تعذر تهيئة Firebase في هذه النسخة من التطبيق.\n" +
                "السبب: ملف app/google-services.json غير موجود داخل مستودع GitHub أثناء بناء APK.\n" +
                "الحل: أضف ملف app/google-services.json إلى مجلد app/ في GitHub (أو أضف محتواه في Secret باسم GOOGLE_SERVICES_JSON) ثم أعد بناء APK."
            )
          )
        }

        val audioBytes = readAudioBytes(appContext, uri, legacyFilePath)
        if (audioBytes.isEmpty()) {
          return@withContext Result.failure(
            IllegalStateException("ملف التسجيل فارغ ولا يمكن تفريغه.")
          )
        }
        if (audioBytes.size > MAX_INLINE_AUDIO_BYTES) {
          return@withContext Result.failure(
            IllegalStateException("حجم التسجيل يتجاوز الحد الأقصى المسموح به (20 ميجابايت).")
          )
        }

        val mimeType = detectAudioMimeType(audioBytes)
        val prompt =
          """
          أنت خبير تفريغ صوتي باللغة العربية.
          استمع بعناية بالغة إلى هذا التسجيل الصوتي ($contextPromptHint).
          انتبه جيداً للأصوات حتى لو كانت ضعيفة أو بعيدة أو منخفضة، واستخرج الكلام بدقة عالية باللغة العربية.

          المطلوب إرجاع JSON منظم يحتوي على:
          1. transcript: النص الكامل لما ورد في التسجيل.
          2. summaryPoints: ملخص دقيق في 3 نقاط بالضبط.
          3. actionItems: المهام أو التذكيرات المستخرجة (أو ["لا توجد مهام محددة"] إن لم توجد).
          """
            .trimIndent()

        val response =
          withTimeout(AI_TIMEOUT_MS) {
            model.generateContent(
              content {
                inlineData(audioBytes, mimeType)
                text(prompt)
              }
            )
          }

        val jsonText =
          response.text?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(
              IllegalStateException("لم يُرجع نموذج الذكاء الاصطناعي أي نص.")
            )

        val json = JSONObject(jsonText)
        val transcript = json.optString("transcript").trim()
        val summaryArray = json.optJSONArray("summaryPoints")
        val tasksArray = json.optJSONArray("actionItems")

        val summaryPoints =
          buildList {
            if (summaryArray != null) {
              for (i in 0 until summaryArray.length()) {
                val point = summaryArray.optString(i).trim()
                if (point.isNotEmpty()) add(point)
              }
            }
          }.take(3)

        val actionItems =
          buildList {
            if (tasksArray != null) {
              for (i in 0 until tasksArray.length()) {
                val task = tasksArray.optString(i).trim()
                if (task.isNotEmpty()) add(task)
              }
            }
          }

        if (transcript.isBlank()) {
          return@withContext Result.failure(
            IllegalStateException("تعذر استخراج نص واضح من التسجيل الصوتي.")
          )
        }

        Result.success(
          TranscriptionResult(
            transcript = transcript,
            summaryPoints = summaryPoints,
            actionItems = actionItems.ifEmpty { listOf("لا توجد مهام محددة.") },
          )
        )
      } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Gemini audio transcription timed out", e)
        Result.failure(
          IllegalStateException("انتهت مهلة تحليل الصوت (60 ثانية). يرجى المحاولة مرة أخرى.")
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Gemini audio transcription failed", e)
        val raw = e.localizedMessage ?: e.message.orEmpty()
        val friendlyMessage =
          when {
            raw.contains("Default FirebaseApp is not initialized", ignoreCase = true) ->
              "تعذر تهيئة Firebase في التطبيق. تأكد من وجود ملف app/google-services.json عند بناء APK."
            raw.contains("403") || raw.contains("App Check", ignoreCase = true) ->
              "فشل التحقق الأمني (App Check) مع Firebase AI.\nالتفاصيل: $raw"
            raw.isNotBlank() -> raw
            else -> "فشل تحويل الصوت إلى نص عبر Firebase AI."
          }
        Result.failure(IllegalStateException(friendlyMessage))
      }
    }

  private fun readAudioBytes(context: Context, uri: Uri, legacyFilePath: String?): ByteArray {
    if (legacyFilePath != null) {
      val file = File(legacyFilePath)
      if (file.exists()) {
        return file.readBytes()
      }
    }
    return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
      ?: throw IllegalStateException("تعذر فتح ملف التسجيل الصوتي.")
  }

  private fun detectAudioMimeType(bytes: ByteArray): String {
    if (
      bytes.size >= 8 &&
        bytes[4] == 'f'.code.toByte() &&
        bytes[5] == 't'.code.toByte() &&
        bytes[6] == 'y'.code.toByte() &&
        bytes[7] == 'p'.code.toByte()
    ) {
      return "audio/mp4"
    }
    if (
      bytes.size >= 3 &&
        bytes[0] == 'I'.code.toByte() &&
        bytes[1] == 'D'.code.toByte() &&
        bytes[2] == '3'.code.toByte()
    ) {
      return "audio/mpeg"
    }
    if (
      bytes.size >= 4 &&
        bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte()
    ) {
      return "audio/wav"
    }
    if (
      bytes.size >= 4 &&
        bytes[0] == 'O'.code.toByte() &&
        bytes[1] == 'g'.code.toByte() &&
        bytes[2] == 'g'.code.toByte() &&
        bytes[3] == 'S'.code.toByte()
    ) {
      return "audio/ogg"
    }
    if (
      bytes.size >= 5 &&
        bytes[0] == '#'.code.toByte() &&
        bytes[1] == '!'.code.toByte() &&
        bytes[2] == 'A'.code.toByte() &&
        bytes[3] == 'M'.code.toByte() &&
        bytes[4] == 'R'.code.toByte()
    ) {
      return "audio/amr"
    }
    if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF) {
      val second = bytes[1].toInt() and 0xF6
      if (second == 0xF0) return "audio/aac"
      if ((bytes[1].toInt() and 0xE0) == 0xE0) return "audio/mpeg"
    }
    return "audio/mp4"
  }

  companion object {
    private const val TAG = "GeminiCallTranscriber"
    private const val MODEL_NAME = "gemini-3.8-flash"
    private const val AI_TIMEOUT_MS = 60_000L
    private const val MAX_INLINE_AUDIO_BYTES = 20 * 1024 * 1024
  }
}

class TranscriptionHelper(
  private val transcriber: CallTranscriber = GeminiCallTranscriber(),
  private val repository: LocalCallVaultRepository? = null,
) {
  suspend fun transcribeAndSaveCall(
    context: Context,
    recording: CallRecordingItem,
    overrideRepository: LocalCallVaultRepository? = null,
  ): Result<TranscriptionResult> {
    val appContext = context.applicationContext ?: context
    val aiResult =
      transcriber
        .transcribeAudio(
          context = appContext,
          uri = recording.uri,
          legacyFilePath = recording.legacyFilePath,
          contextPromptHint = "مكالمة هاتفية ${recording.direction.arabicLabel} - ${recording.phoneNumber}",
        )
        .getOrElse { return Result.failure(it) }

    val targetRepo = overrideRepository ?: repository ?: LocalCallVaultRepository.getInstance(appContext)
    val saveResult = targetRepo.saveCallRecord(recording = recording, transcription = aiResult)

    val savedRecord =
      saveResult.getOrElse { saveError ->
        Log.w(TAG, "Call transcription succeeded but local save failed", saveError)
        return Result.success(
          aiResult.copy(
            savedLocally = false,
            syncedToCloud = false,
            notice = "تم التفريغ بنجاح، ولكن تعذر الحفظ المحلي: ${saveError.localizedMessage ?: "خطأ"}",
          )
        )
      }

    val cloudSync = CloudSyncManager.getInstance(appContext)
    val syncedUid =
      if (cloudSync.syncState.value.isSignedIn) {
        cloudSync.pushCallRecord(savedRecord).getOrNull()
      } else {
        null
      }

    if (syncedUid != null) {
      targetRepo.markCallSynced(savedRecord.fileName, syncedUid)
    }

    return Result.success(
      aiResult.copy(
        savedLocally = true,
        syncedToCloud = syncedUid != null,
        notice =
          if (syncedUid != null) {
            "تم حفظ التفريغ محلياً ومزامنته مع السحابة بنجاح."
          } else {
            "تم حفظ النص والملخص والمهام محلياً على جهازك."
          },
      )
    )
  }

  suspend fun transcribeAndSaveVoiceNote(
    context: Context,
    note: VoiceNoteItem,
    overrideRepository: LocalCallVaultRepository? = null,
  ): Result<TranscriptionResult> {
    val appContext = context.applicationContext ?: context
    val aiResult =
      transcriber
        .transcribeAudio(
          context = appContext,
          uri = note.uri,
          legacyFilePath = note.legacyFilePath,
          contextPromptHint = "تسجيل صوتي شخصي بعنوان: ${note.title}",
        )
        .getOrElse { return Result.failure(it) }

    val targetRepo = overrideRepository ?: repository ?: LocalCallVaultRepository.getInstance(appContext)
    val saveResult = targetRepo.saveVoiceNoteRecord(note = note, transcription = aiResult)

    val savedRecord =
      saveResult.getOrElse { saveError ->
        Log.w(TAG, "Voice note transcription succeeded but local save failed", saveError)
        return Result.success(
          aiResult.copy(
            savedLocally = false,
            syncedToCloud = false,
            notice = "تم التفريغ بنجاح، ولكن تعذر الحفظ المحلي: ${saveError.localizedMessage ?: "خطأ"}",
          )
        )
      }

    val cloudSync = CloudSyncManager.getInstance(appContext)
    val syncedUid =
      if (cloudSync.syncState.value.isSignedIn) {
        cloudSync.pushVoiceNoteRecord(savedRecord).getOrNull()
      } else {
        null
      }

    if (syncedUid != null) {
      targetRepo.markVoiceNoteSynced(savedRecord.fileName, syncedUid)
    }

    return Result.success(
      aiResult.copy(
        savedLocally = true,
        syncedToCloud = syncedUid != null,
        notice =
          if (syncedUid != null) {
            "تم حفظ التفريغ محلياً ومزامنته مع السحابة بنجاح."
          } else {
            "تم حفظ النص والملخص والمهام محلياً على جهازك."
          },
      )
    )
  }

  companion object {
    private const val TAG = "TranscriptionHelper"
  }
}
