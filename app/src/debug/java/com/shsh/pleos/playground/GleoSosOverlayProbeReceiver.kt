package com.shsh.pleos.playground

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.shsh.pleos.playground.gleo.GleoReply
import com.shsh.pleos.playground.gleo.sendGleoReply

/** Opt-in debug headless SOS. Only OS-authenticated system Gleo may use the prepared session. */
class GleoSosOverlayProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isProbe = intent.action == ACTION || intent.action == "${context.packageName}.$ACTION"
        val isSos = intent.action == "RESTROOM_SOS" || intent.action == "${context.packageName}.RESTROOM_SOS"
        if ((!isProbe && !isSos) || !BuildConfig.SOS_OVERLAY_ENABLED) return
        if (Build.VERSION.SDK_INT < 34) {
            Log.i(TAG, "Overlay probe received; sender identity API unavailable")
            return
        }
        val sender = sentFromPackage
        val uid = sentFromUid
        if (sender == null || uid < 0) {
            Log.i(TAG, "Overlay probe received; Android did not disclose sender identity")
            return
        }

        // Only the emulator's built-in Gleo app can get a reply. No identity
        // claim from Intent extras is used.
        if (sender != GLEO_PACKAGE) {
            Log.i(TAG, "Overlay probe received; unexpected Android sender package=$sender")
            return
        }
        @Suppress("DEPRECATION")
        val app = runCatching { context.packageManager.getApplicationInfo(sender, 0) }.getOrNull()
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        if (app == null || app.uid != uid || (app.flags and systemFlags) == 0) {
            Log.i(TAG, "Overlay probe received; built-in Gleo sender not verified")
            return
        }
        Log.i(TAG, "Overlay probe received; built-in Gleo sender verified; no Activity launch")
        if (isSos) {
            val request = runCatching { com.shsh.pleos.playground.gleo.GleoSosContract.readPublic(intent) }.getOrNull()
            if (request == null || request.operation != "FIND") {
                sendGleoReply(context, GleoReply("fail", "Request a restroom parking search, then use the displayed options.",
                    reason = "overlay_invalid_operation", functionName = "RESTROOM_SOS"), diagnostic = false)
                return
            }
            val result = if (request.diagnostic) "diagnostic_no_route_change" else SosOverlayService.requestSearch()
            Log.i("SHSH_SOS_OVERLAY", "Gleo overlay request; result=$result")
            sendGleoReply(context, GleoReply(if (result == "accepted") "success" else "fail",
                when (result) {
                    "accepted" -> "The restroom parking search has started in a small overlay. No destination has been changed yet."
                    "overlay_session_not_ready" -> "Prepare the Noraehaja debug SOS session while parked before using it."
                    "overlay_permission" -> "Allow Noraehaja to display over other apps before using this feature."
                    "overlay_window_unavailable" -> "The overlay window could not be opened. Check overlay permission and platform support."
                    "overlay_busy" -> "An SOS search or display check is already in progress."
                    "vehicle_restricted_or_unknown" -> "The vehicle does not currently allow this operation."
                    else -> "The SOS search could not start. Check the prepared session."
                }, reason = "overlay_$result", functionName = "RESTROOM_SOS"), diagnostic = request.diagnostic)
            return
        }
        sendGleoReply(context, GleoReply("success", "The restroom SOS voice connection was checked. The navigation route was not changed.",
            reason = "overlay_probe_confirmed", functionName = ACTION), diagnostic = false)
    }

    private companion object {
        const val TAG = "SHSH_SOS_PROBE"
        const val ACTION = "CHECK_SOS_OVERLAY"
        const val GLEO_PACKAGE = "ai.umos.ambientai"
    }
}
