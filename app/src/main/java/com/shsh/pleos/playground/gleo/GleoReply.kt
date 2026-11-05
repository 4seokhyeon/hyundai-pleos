package com.shsh.pleos.playground.gleo

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shsh.pleos.playground.data.Song
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class GleoReply(val status: String, val message: String, val data: String? = null, val reason: String? = null, val functionName: String = GleoSearchContract.ACTION) {
    companion object {
        fun asking() = GleoReply("asking", "A song title or artist is required to search for karaoke videos.",
            JSONArray().put(JSONObject().put("missingField", "query")).toString(), reason = "query_missing")
        fun askingSelection() = GleoReply("asking", "A result number is required to play a song from the current karaoke search results.",
            JSONArray().put(JSONObject().put("missingField", "index")).toString(), reason = "index_missing", functionName = GleoSearchContract.SELECT_ACTION)
        fun fail(message: String, reason: String = "request_failed") = GleoReply("fail", message, reason = reason)
        fun results(songs: List<Song>): GleoReply {
            val data = JSONArray().put(JSONObject().put("resultCount", songs.size).put("playbackStarted", false))
            songs.take(5).forEachIndexed { index, song ->
                data.put(JSONObject().put("index", index + 1).put("videoId", song.videoId)
                    .put("title", song.title.take(300)))
            }
            // A completed search is terminal. Selection is a separate SELECT_SONG request,
            // so a later song query is not kept inside an outstanding selection question.
            return GleoReply("success", if (songs.isEmpty()) "The search completed, but no matching videos were found."
                else "The search completed. ${songs.size} results are displayed. Playback has not started.", data.toString(), reason = if (songs.isEmpty()) "no_matches" else "search_completed")
        }
    }
}

/** Local diagnostics never broadcast a fabricated Gleo conversation. */
object GleoDiagnostics {
    private val mutable = MutableStateFlow("검증 전 · 이 메뉴는 Gleo의 실제 음성 호출을 대신하지 않습니다")
    val state = mutable.asStateFlow()
    private val external = MutableStateFlow("이번 앱 실행 중 외부 검색 명령 수신 기록 없음")
    val externalState = external.asStateFlow()
    fun externalEntry(actionMatched: Boolean) {
        external.value = if (actionMatched) "외부 노래 명령 수신 · 입력 확인 중" else "외부 진입 수신 · 검색 명령과 action 불일치"
    }
    fun externalReply(reply: GleoReply, dispatched: Boolean) {
        external.value = if (dispatched) "외부 응답 송신 · ${reply.status} · 원인 ${reply.reason ?: "없음"} · Gleo 수신 확인은 별도"
            else "외부 응답 송신 실패 · ${reply.status} · 원인 ${reply.reason ?: "없음"}"
    }
    fun started() { mutable.value = "로컬 명령 전달 중 · Gleo로 응답 방송하지 않음" }
    fun received(reply: GleoReply) {
        mutable.value = when (reply.status) {
            "asking" -> "asking 확인 · 추가 입력 또는 결과 번호 선택 필요"
            "success" -> "success 확인 · 화면과 실제 실행 결과를 확인하세요"
            else -> "fail 확인 · 원인 코드: ${reply.reason ?: "request_failed"}"
        }
    }
}

fun sendGleoReply(context: Context, reply: GleoReply, diagnostic: Boolean) {
    if (diagnostic) {
        GleoDiagnostics.received(reply)
        Log.i("SHSH_GLEO", "Diagnostic reply; action=${reply.functionName}, status=${reply.status}, broadcast=false, reason=${reply.reason}; values omitted")
        return
    }
    try {
        val json = JSONObject().put("functionName", reply.functionName)
            .put("status", reply.status).put("message", reply.message).put("mode", "voice")
        reply.data?.let { json.put("data", it) }
        context.applicationContext.sendBroadcast(Intent(GleoSearchContract.RESPONSE_ACTION)
            .putExtra("packageName", context.packageName).putExtra("response", json.toString()))
        GleoDiagnostics.externalReply(reply, dispatched = true)
        Log.i("SHSH_GLEO", "Callback dispatched; action=${reply.functionName}, status=${reply.status}, reason=${reply.reason}; delivery not confirmed; values omitted")
    } catch (error: Exception) {
        GleoDiagnostics.externalReply(reply, dispatched = false)
        Log.e("SHSH_GLEO", "Callback dispatch failed; type=${error.javaClass.simpleName}; values omitted")
    }
}

/** Exactly one terminal/asking reply, with an independent deadline even if network I/O is slow. */
class GleoSearchSession(context: Context, val request: GleoSearchContract.Request, onExpired: () -> Unit) {
    private val app = context.applicationContext
    private val answered = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        if (respond(GleoReply.fail("The search could not finish within the action time limit.", reason = "action_timeout"))) onExpired()
    }
    val active: Boolean get() = !answered.get() && SystemClock.elapsedRealtime() < request.deadline
    init {
        handler.postDelayed(timeout, (request.deadline - SystemClock.elapsedRealtime()).coerceIn(0, GleoSearchContract.BUDGET_MS))
    }
    fun respond(reply: GleoReply): Boolean {
        if (!answered.compareAndSet(false, true)) return false
        handler.removeCallbacks(timeout)
        val actual = if (reply.status == "success" && SystemClock.elapsedRealtime() >= request.deadline)
            GleoReply.fail("The search exceeded the action time limit.", reason = "action_timeout") else reply
        sendGleoReply(app, actual.copy(functionName = request.function), request.diagnostic)
        return true
    }
}
