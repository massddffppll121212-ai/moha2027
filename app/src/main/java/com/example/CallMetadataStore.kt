package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject

data class SavedCallMetadata(
  val fileName: String,
  val callerName: String,
  val phoneNumber: String,
  val direction: String,
)

object CallMetadataStore {
  private const val PREFS_NAME = "donkeyear_call_metadata"
  private const val KEY_METADATA_JSON = "call_metadata_map_json"
  private val lock = Any()

  fun saveMetadata(
    context: Context,
    fileName: String,
    callerName: String,
    phoneNumber: String,
    direction: String,
  ) {
    val cleanFileName = fileName.trim()
    if (cleanFileName.isEmpty()) return
    synchronized(lock) {
      val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val root =
        try {
          JSONObject(prefs.getString(KEY_METADATA_JSON, null) ?: "{}")
        } catch (_: Exception) {
          JSONObject()
        }
      val entry =
        JSONObject().apply {
          put("callerName", callerName.trim())
          put("phoneNumber", phoneNumber.trim())
          put("direction", direction.trim())
        }
      root.put(cleanFileName, entry)
      prefs.edit().putString(KEY_METADATA_JSON, root.toString()).apply()
    }
  }

  fun getMetadata(context: Context, fileName: String): SavedCallMetadata? {
    val cleanFileName = fileName.trim()
    if (cleanFileName.isEmpty()) return null
    synchronized(lock) {
      val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val raw = prefs.getString(KEY_METADATA_JSON, null) ?: return null
      return try {
        val root = JSONObject(raw)
        val obj = root.optJSONObject(cleanFileName) ?: return null
        SavedCallMetadata(
          fileName = cleanFileName,
          callerName = obj.optString("callerName", "").trim(),
          phoneNumber = obj.optString("phoneNumber", "").trim(),
          direction = obj.optString("direction", "").trim(),
        )
      } catch (_: Exception) {
        null
      }
    }
  }

  fun deleteMetadata(context: Context, fileName: String) {
    val cleanFileName = fileName.trim()
    if (cleanFileName.isEmpty()) return
    synchronized(lock) {
      val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val raw = prefs.getString(KEY_METADATA_JSON, null) ?: return
      try {
        val root = JSONObject(raw)
        if (root.has(cleanFileName)) {
          root.remove(cleanFileName)
          prefs.edit().putString(KEY_METADATA_JSON, root.toString()).apply()
        }
      } catch (_: Exception) {
      }
    }
  }

  fun resolveCallerName(context: Context, phoneNumber: String?): String? {
    val number = phoneNumber?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    // 1. Check ContactsContract.PhoneLookup first (persisted contact book name)
    val hasContactsPermission =
      ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED
    if (hasContactsPermission) {
      try {
        val lookupUri =
          Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver
          .query(
            lookupUri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
          )
          ?.use { cursor ->
            if (cursor.moveToFirst()) {
              val contactName = cursor.getString(0)?.trim()
              if (!contactName.isNullOrEmpty()) return contactName
            }
          }
      } catch (_: Exception) {
      }
    }

    // 2. Fallback to CallLog.Calls.CACHED_NAME
    val hasCallLog =
      ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED
    if (hasCallLog) {
      try {
        val digits = number.filter { it.isDigit() }
        val suffix = if (digits.length >= 7) digits.takeLast(8) else number
        context.contentResolver
          .query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME),
            "${CallLog.Calls.NUMBER} LIKE ?",
            arrayOf("%$suffix%"),
            "${CallLog.Calls.DATE} DESC",
          )
          ?.use { cursor ->
            if (cursor.moveToFirst()) {
              val cached = cursor.getString(0)?.trim()
              if (!cached.isNullOrEmpty()) return cached
            }
          }
      } catch (_: Exception) {
      }
    }

    return null
  }

  data class RecentCallLogInfo(val number: String?, val callerName: String?)

  fun findRecentCallLogNear(context: Context, aroundTimestampMs: Long): RecentCallLogInfo? {
    val hasCallLog =
      ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasCallLog) return null

    return try {
      context.contentResolver
        .query(
          CallLog.Calls.CONTENT_URI,
          arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
          ),
          null,
          null,
          "${CallLog.Calls.DATE} DESC",
        )
        ?.use { cursor ->
          var checked = 0
          while (cursor.moveToNext() && checked < 5) {
            checked++
            val number = cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }
            val cachedName = cursor.getString(1)?.trim()?.takeIf { it.isNotEmpty() }
            val dateMs = cursor.getLong(2)
            if (aroundTimestampMs <= 0L || kotlin.math.abs(dateMs - aroundTimestampMs) <= 180_000L) {
              val resolvedName = cachedName ?: resolveCallerName(context, number)
              return RecentCallLogInfo(number = number, callerName = resolvedName)
            }
          }
          null
        }
    } catch (_: Exception) {
      null
    }
  }
}
