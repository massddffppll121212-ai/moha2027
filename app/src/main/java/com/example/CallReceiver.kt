package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

class CallReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    if (intent == null) return

    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
      @Suppress("DEPRECATION")
      val outgoingNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
      prefs.edit()
        .putString(KEY_PENDING_DIRECTION, "Outgoing")
        .putString(KEY_PENDING_NUMBER, outgoingNumber)
        .putLong(KEY_PENDING_TIMESTAMP, System.currentTimeMillis())
        .apply()
      return
    }

    if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
      val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
      @Suppress("DEPRECATION")
      val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

      when (state) {
        TelephonyManager.EXTRA_STATE_RINGING -> {
          val editor =
            prefs.edit()
              .putString(KEY_PENDING_DIRECTION, "Incoming")
              .putLong(KEY_PENDING_TIMESTAMP, System.currentTimeMillis())
          if (!incomingNumber.isNullOrBlank()) {
            editor.putString(KEY_PENDING_NUMBER, incomingNumber)
          }
          editor.apply()
        }

        TelephonyManager.EXTRA_STATE_OFFHOOK -> {
          if (!CallVaultSettings.isAutoCallRecordingEnabled(context)) return

          val hasMicPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
              PackageManager.PERMISSION_GRANTED
          if (!hasMicPermission) return

          val isRecent =
            System.currentTimeMillis() - prefs.getLong(KEY_PENDING_TIMESTAMP, 0L) < 90_000L
          val direction =
            if (isRecent) {
              prefs.getString(KEY_PENDING_DIRECTION, "Outgoing") ?: "Outgoing"
            } else {
              "Outgoing"
            }
          val number =
            incomingNumber?.takeIf { it.isNotBlank() }
              ?: if (isRecent) prefs.getString(KEY_PENDING_NUMBER, null) else null

          if (CallVaultSettings.isNumberExcluded(context, number)) {
            return
          }

          if (!CallRecordingService.isRecordingFlow.value) {
            val serviceIntent =
              Intent(context, CallRecordingService::class.java).apply {
                action = CallRecordingService.ACTION_START_RECORDING
                putExtra(CallRecordingService.EXTRA_CALL_DIRECTION, direction)
                if (!number.isNullOrBlank()) {
                  putExtra(CallRecordingService.EXTRA_PHONE_NUMBER, number)
                }
              }
            ContextCompat.startForegroundService(context, serviceIntent)
          }
        }

        TelephonyManager.EXTRA_STATE_IDLE -> {
          prefs.edit().clear().apply()
          if (CallRecordingService.isRecordingFlow.value) {
            val stopIntent =
              Intent(context, CallRecordingService::class.java).apply {
                action = CallRecordingService.ACTION_STOP_RECORDING
              }
            context.startService(stopIntent)
          }
        }
      }
    }
  }

  companion object {
    private const val PREFS_NAME = "callvault_call_state"
    private const val KEY_PENDING_DIRECTION = "pending_direction"
    private const val KEY_PENDING_NUMBER = "pending_number"
    private const val KEY_PENDING_TIMESTAMP = "pending_timestamp"
  }
}
