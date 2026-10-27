package com.shsh.pleos.playground.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.text.Html
import android.util.Log
import com.shsh.pleos.playground.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Song(val videoId: String, val title: String, val channelTitle: String)
class SongSearchException(val reason: String, message: String) : Exception(message)

class SongRepository(context: Context) {
    private val app = context.applicationContext

    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        require(query.length in 1..120) { "검색어는 1~120자로 입력하세요" }
        val key = BuildConfig.YOUTUBE_API_KEY
        if (key.isBlank()) throw SongSearchException("key_missing", "API 키 미반영 · local.properties 저장 후 Sync와 debug 앱 재빌드가 필요합니다")
        Log.i("SHSH_SEARCH", "Direct YouTube search requested; chars=${query.length}, keyConfigured=true")
        val keyword = if (query.contains("노래방") || query.contains("karaoke", ignoreCase = true)) query else "$query 노래방"
        val uri = Uri.parse("https://www.googleapis.com/youtube/v3/search").buildUpon()
            .appendQueryParameter("part", "snippet").appendQueryParameter("type", "video")
            .appendQueryParameter("q", keyword).appendQueryParameter("maxResults", "5")
            .appendQueryParameter("videoEmbeddable", "true").appendQueryParameter("videoSyndicated", "true")
            .appendQueryParameter("relevanceLanguage", "ko").appendQueryParameter("regionCode", "KR")
            .appendQueryParameter("safeSearch", "moderate").build()
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.setRequestProperty("X-Goog-Api-Key", key)
            connection.setRequestProperty("X-Android-Package", app.packageName)
            certificateFingerprint()?.let { connection.setRequestProperty("X-Android-Cert", it) }
            Log.i("SHSH_SEARCH", "Stage=request_ready")
            val code = connection.responseCode
            Log.i("SHSH_SEARCH", "Stage=response_received; http=$code")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 2_000_000) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            currentCoroutineContext().ensureActive()
            if (bytes.size > 2_000_000) throw SongSearchException("response_too_large", "검색 응답이 너무 큽니다")
            val body = JSONObject(if (bytes.isEmpty()) "{}" else bytes.toString(Charsets.UTF_8))
            if (code !in 200..299) {
                val rawReason = body.optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
                val reason = when (rawReason) {
                    "quotaExceeded", "dailyLimitExceeded", "accessNotConfigured", "keyInvalid", "ipRefererBlocked", "forbidden", "rateLimitExceeded" -> rawReason
                    else -> "http_$code"
                }
                val hint = when (reason) {
                    "quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded" -> "Google Cloud 할당량을 확인하세요"
                    "accessNotConfigured" -> "YouTube Data API v3 활성화를 확인하세요"
                    "keyInvalid" -> "API 키를 확인한 뒤 다시 빌드하세요"
                    else -> "API 키의 YouTube API 제한·앱 제한을 확인하세요"
                }
                throw SongSearchException(reason, "YouTube 검색 오류: $reason · $hint")
            }
            Log.i("SHSH_SEARCH", "Stage=response_parsed")
            val items = body.optJSONArray("items") ?: throw SongSearchException("invalid_response", "검색 결과 형식 오류")
            val songs = buildList {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
                    val snippet = item.optJSONObject("snippet") ?: continue
                    if (Regex("[A-Za-z0-9_-]{11}").matches(id)) {
                        fun text(name: String) = Html.fromHtml(snippet.optString(name), Html.FROM_HTML_MODE_LEGACY).toString()
                        add(Song(id, text("title"), text("channelTitle")))
                    }
                }
            }.distinctBy { it.videoId }
            Log.i("SHSH_SEARCH", "Direct search finished; results=${songs.size}")
            songs
        } finally { connection.disconnect() }
    }

    private fun certificateFingerprint(): String? = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signature = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
        MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString("") { "%02X".format(it.toInt() and 255) }
    }.getOrNull()
}
