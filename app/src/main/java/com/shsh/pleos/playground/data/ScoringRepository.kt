package com.shsh.pleos.playground.data

import com.shsh.pleos.playground.platform.AppLog as Log
import com.shsh.pleos.playground.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class ScoreResult(val rawMos: Double, val model: String, val inferenceSeconds: Double, val inputKind: String) {
    val funScore: Int = FunScorePolicy.convert(rawMos)
    val scoreRuleVersion: String = FunScorePolicy.VERSION
}
class ScoringException(val reason: String, message: String) : Exception(message)

/** Local debug emulator endpoint; no YouTube credentials or automatic retries. */
class ScoringRepository {
    private val endpoint = "http://10.0.2.2:8091"

    suspend fun score(file: File, inputKind: String, onProgress: (String) -> Unit): ScoreResult {
        require(inputKind in setOf("vocal_only", "mixed")) { "Recording condition must be selected" }
        if (!BuildConfig.DEBUG) throw ScoringException("debug_only", "현재 채점 연결은 debug 에뮬레이터 전용입니다")
        if (!file.isFile || file.length() !in 45L..(6L * 1024 * 1024)) {
            throw ScoringException("invalid_file", "녹음 파일이 없거나 크기 제한을 초과했습니다")
        }
        var jobId: String? = null
        try {
            return withTimeout(120_000) {
                onProgress("Mac 채점 서버 연결 확인 중")
                val health = request("GET", "/health")
                if (health.optString("status") != "ready") throw ScoringException("not_ready", "채점 서버의 모델 준비가 끝나지 않았습니다")
                onProgress("동의한 녹음 파일을 Mac 서버로 전송 중")
                Log.i("SHSH_SCORE", "Upload requested by user; bytes=${file.length()}, inputKind=$inputKind")
                val accepted = request("POST", "/scores", file, inputKind = inputKind)
                val id = accepted.optString("job_id")
                if (!Regex("[0-9a-f]{32}").matches(id)) throw ScoringException("invalid_job", "서버 작업 응답 형식 오류")
                jobId = id
                Log.i("SHSH_SCORE", "Upload accepted; polling started")
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val body = request("GET", "/scores/$id")
                    when (body.optString("status")) {
                        "queued" -> onProgress("평가 대기 중")
                        "processing" -> onProgress("SingMOS 평가 중 · 녹음 품질 예측")
                        "succeeded" -> {
                            val result = body.optJSONObject("result") ?: throw ScoringException("invalid_result", "평가 결과 형식 오류")
                            val raw = result.optDouble("raw_mos", Double.NaN)
                            val seconds = result.optJSONObject("timing")?.optDouble("inference_seconds", Double.NaN) ?: Double.NaN
                            val model = result.optString("model")
                            if (!raw.isFinite() || !seconds.isFinite() || model !in setOf("singmos_pro", "singmos_v1")) {
                                throw ScoringException("invalid_result", "유효한 모델 점수를 받지 못했습니다")
                            }
                            Log.i("SHSH_SCORE", "Score received; rawMos=$raw, inferenceSeconds=$seconds")
                            val score = ScoreResult(raw, model, seconds, body.optString("input_kind", "unknown"))
                            Log.i("SHSH_SCORE", "Fun score calculated; score=${score.funScore}, rule=${score.scoreRuleVersion}, rawMos=${score.rawMos}")
                            return@withTimeout score
                        }
                        "failed" -> throw ScoringException("evaluation_failed", "서버 평가 실패 · 무음 여부와 서버 SHSH_SCORE 로그를 확인하세요")
                        else -> throw ScoringException("invalid_status", "서버 평가 상태 형식 오류")
                    }
                    delay(1000)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } finally {
            // Delete metadata/result after success or cancellation; active server work cleans up on exit.
            jobId?.let { id ->
                withContext(NonCancellable) {
                    try { request("DELETE", "/scores/$id", cleanup = true) }
                    catch (_: Exception) { Log.w("SHSH_SCORE", "Remote cleanup not confirmed; server expiry still applies") }
                }
            }
        }
    }

    private suspend fun request(method: String, path: String, file: File? = null, cleanup: Boolean = false, inputKind: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(endpoint + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = if (cleanup) 2000 else 5000
            connection.readTimeout = if (cleanup) 2000 else 15000
            connection.setRequestProperty("Accept", "application/json")
            if (file != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "audio/wav")
                connection.setRequestProperty("X-Input-Kind", requireNotNull(inputKind))
                connection.setFixedLengthStreamingMode(file.length())
                connection.outputStream.use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 128_000) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, 128_001 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                if (output.size() > 128_000) throw ScoringException("response_too_large", "채점 서버 응답이 너무 큽니다")
                output.toByteArray().toString(Charsets.UTF_8)
            } ?: "{}"
            currentCoroutineContext().ensureActive()
            val body = JSONObject(raw)
            if (code !in 200..299) {
                val reason = when (code) { 404 -> "job_missing"; 413 -> "file_size"; 415, 422 -> "audio_format"; 503 -> "server_busy"; else -> "http_$code" }
                throw ScoringException(reason, "채점 서버 오류 $code · 서버 로그와 파일 형식을 확인하세요")
            }
            body
        } catch (error: CancellationException) { throw error }
        catch (error: ScoringException) { throw error }
        catch (_: Exception) { throw ScoringException("connection_or_response", "Mac 채점 서버 연결 실패 · 서버 ready, 8091 포트, debug 앱 설치를 확인하세요") }
        finally { connection.disconnect() }
    }
}
