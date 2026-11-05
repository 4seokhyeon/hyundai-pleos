package com.shsh.pleos.playground

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.shsh.pleos.playground.gleo.GleoDiagnostics
import com.shsh.pleos.playground.gleo.GleoReply
import com.shsh.pleos.playground.gleo.GleoSearchContract
import com.shsh.pleos.playground.gleo.sendGleoReply

/** Exported search/numbered playback boundary. No recording, STT, or arbitrary routing. */
class GleoEntryActivity : ComponentActivity() {
    private fun handleSos() {
        val contract = com.shsh.pleos.playground.gleo.GleoSosContract
        val diagnostic = GleoSearchContract.diagnostic(intent)
        if (!diagnostic) GleoDiagnostics.externalEntry(true)
        Log.i("SHSH_GLEO", "Entry opened; action=RESTROOM_SOS, diagnostic=$diagnostic; values omitted")
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
                    GleoReply.fail("The search parameters are invalid. Supply a valid query or a positive integer result index.", reason = "invalid_parameters").copy(functionName = function), diagnostic)
                is GleoSearchContract.Input.Accepted -> {
                    val request = input.request
                    Log.i("SHSH_GLEO", "Command received; action=${request.function}, hasQuery=${request.query != null}, diagnostic=$diagnostic; values omitted")
                    if (request.function == GleoSearchContract.SELECT_ACTION && request.index == null)
                        sendGleoReply(this, GleoReply.askingSelection(), diagnostic)
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
