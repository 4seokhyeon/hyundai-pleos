package com.shsh.pleos.playground

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.shsh.pleos.playground.gleo.GleoReply
import com.shsh.pleos.playground.gleo.sendGleoReply

/** Opt-in debug probe. No location, application UI launch, or Navigation APIs. */
class GleoSosOverlayProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION && intent.action != "${context.packageName}.$ACTION") return
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
        sendGleoReply(context, GleoReply("success", "The restroom SOS voice connection was checked. The navigation route was not changed.",
            reason = "overlay_probe_confirmed", functionName = ACTION), diagnostic = false)
    }

    private companion object {
        const val TAG = "SHSH_SOS_PROBE"
        const val ACTION = "CHECK_SOS_OVERLAY"
        const val GLEO_PACKAGE = "ai.umos.ambientai"
    }
}
