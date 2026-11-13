package com.shsh.pleos.playground

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import com.shsh.pleos.playground.gleo.GleoDiagnostics
import com.shsh.pleos.playground.gleo.GleoReply
import com.shsh.pleos.playground.gleo.GleoSearchContract
import com.shsh.pleos.playground.gleo.sendGleoReply

/** Exported Gleo boundary. Only registered app actions can be forwarded. */
class GleoEntryActivity : ComponentActivity() {
    private fun handleSos() {
        val contract = com.shsh.pleos.playground.gleo.GleoSosContract
        val diagnostic = GleoSearchContract.diagnostic(intent)
        if (!diagnostic) GleoDiagnostics.externalEntry(true)
        Log.i("SHSH_GLEO", "Entry opened; action=RESTROOM_SOS, diagnostic=$diagnostic; values omitted")
        if (BuildConfig.SOS_OVERLAY_ENABLED) {
            Log.w("SHSH_SOS_PROBE", "Legacy SOS Activity request rejected; refresh Gleo schema to component=service")
            sendGleoReply(this, contract.reply("fail",
                "Refresh the Gleo app schema before using the headless restroom SOS service.", "overlay_stale_activity_schema"), diagnostic)
            return
        }
        val request = try { contract.readPublic(intent) }
        catch (_: Exception) {
            sendGleoReply(this, contract.reply("fail", "Supply a valid SOS operation and candidate number.", "invalid_parameters"), diagnostic)
            return
        }
        if (Build.VERSION.SDK_INT < 29) {
            sendGleoReply(this, contract.reply("fail", "The SOS screen requires Android 10 or later.", "unsupported_android"), diagnostic)
            return
        }
        try { startActivity(contract.forward(this, request)) }
        catch (error: Exception) {
            Log.w("SHSH_GLEO", "SOS workspace launch failed; type=${error.javaClass.simpleName}; values omitted")
            sendGleoReply(this, contract.reply("fail", "The SOS screen could not be opened.", "workspace_launch_failed"), diagnostic)
        }
    }

    private fun openAppHome(request: GleoSearchContract.Request) {
        val reply = try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            GleoReply("success", "Opening the Noraehaja app home screen.",
                JSONArray().put(JSONObject().put("destination", "app_home")).toString(),
                reason = "home_launch_requested", functionName = request.function)
        } catch (error: Exception) {
            Log.w("SHSH_GLEO", "Home launch failed; type=${error.javaClass.simpleName}")
            GleoReply.fail("The Noraehaja app home screen could not be opened.", reason = "home_launch_failed")
                .copy(functionName = request.function)
        }
        sendGleoReply(this, reply, request.diagnostic)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (com.shsh.pleos.playground.gleo.GleoSosContract.matches(intent.action)) {
                handleSos()
                return
            }
            val function = GleoSearchContract.functionName(intent.action) ?: GleoSearchContract.ACTION
            val diagnostic = GleoSearchContract.diagnostic(intent)
            if (!diagnostic) GleoDiagnostics.externalEntry(GleoSearchContract.matchesAction(intent.action))
            val hasQuery = runCatching { intent.hasExtra(GleoSearchContract.PARAMETER) }.getOrDefault(false)
            val hasPacked = runCatching { intent.hasExtra(GleoSearchContract.PACKED_PARAMETERS) }.getOrDefault(false)
            Log.i("SHSH_GLEO", "Entry opened; actionMatched=${GleoSearchContract.matchesAction(intent.action)}, hasQueryExtra=$hasQuery, hasPackedExtra=$hasPacked; values omitted")
            if (!GleoSearchContract.matchesAction(intent.action)) {
                Log.w("SHSH_GLEO", "Unsupported entry action ignored; values omitted")
                return
            }
            when (val input = GleoSearchContract.readPublic(intent)) {
                GleoSearchContract.Input.Invalid -> sendGleoReply(this,
                    GleoReply.fail("The command parameters are invalid. Supply a valid query, operation, or positive integer result index as required.", reason = "invalid_parameters").copy(functionName = function), diagnostic)
                is GleoSearchContract.Input.Accepted -> {
                    val request = input.request
                    Log.i("SHSH_GLEO", "Command received; action=${request.function}, hasQuery=${request.query != null}, diagnostic=$diagnostic; values omitted")
                    if (request.opensHome) openAppHome(request)
                    else if (request.selectsSong && request.index == null)
                        sendGleoReply(this, GleoReply.askingSelection().copy(functionName = request.function), diagnostic)
                    else if (request.function == GleoSearchContract.ACTION && request.query == null) sendGleoReply(this, GleoReply.asking(), diagnostic)
                    else if (Build.VERSION.SDK_INT < 29) sendGleoReply(this, GleoReply.fail("This search screen requires Android 10 or later.", reason = "unsupported_android").copy(functionName = function), diagnostic)
                    else try { startActivity(GleoSearchContract.forward(this, request)) }
                    catch (error: Exception) {
                        Log.e("SHSH_GLEO", "Workspace launch failed; type=${error.javaClass.simpleName}; values omitted")
                        sendGleoReply(this, GleoReply.fail("The karaoke search screen could not be opened.", reason = "workspace_launch_failed").copy(functionName = function), diagnostic)
                    }
                }
            }
        } finally { finish() } // Required for Theme.NoDisplay; never holds the assistant microphone.
    }
}
