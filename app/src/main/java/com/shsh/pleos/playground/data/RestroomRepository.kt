package com.shsh.pleos.playground.data

import android.os.SystemClock
import android.net.Uri
import com.shsh.pleos.playground.platform.AppLog as Log
import android.util.Xml
import com.shsh.pleos.playground.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException
import java.io.IOException
import javax.net.ssl.SSLException
import org.xmlpull.v1.XmlPullParserException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.xmlpull.v1.XmlPullParser

data class RestroomSample(val name: String, val address: String, val registeredHours: String?,
    val referenceDate: String, val latitude: Double?, val longitude: Double?) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite()
        && latitude in -90.0..90.0 && longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0)
}

/** Confirmed Publtolt XML contract; paged static facility data, not live opening status. */
class RestroomRepository {
    suspend fun sample(withoutKey: Boolean = false): List<RestroomSample> = page(1, 5, withoutKey).items

    suspend fun page(pageIndex: Int, pageSize: Int = 1000, withoutKey: Boolean = false): FacilityPage<RestroomSample> = withTimeout(30_000) {
        require(pageIndex in 1..1000 && pageSize in 1..1000)
        withContext(Dispatchers.IO) {
            if (!BuildConfig.DEBUG || (!withoutKey && BuildConfig.GG_DATA_API_KEY.isBlank())) throw PublicDataException("gg_key_missing")
            val originalKey = if (withoutKey) "" else BuildConfig.GG_DATA_API_KEY
            val keyLooksEncoded = Regex("%[0-9A-Fa-f]{2}").containsMatchIn(originalKey)
            val key = if (keyLooksEncoded) Uri.decode(originalKey) else originalKey
            Log.i("SHSH_RESTROOM", "Request diagnostics; keyPresent=${!withoutKey}, keyLooksPercentEncoded=$keyLooksEncoded; queryEncoding=once, format=xml, mode=${if (withoutKey) "official_sample" else "issued_key"}")
            if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(key)) throw PublicDataException("gg_key_repeatedly_encoded")
            val uri = Uri.parse("https://openapi.gg.go.kr/Publtolt").buildUpon()
                .apply { if (!withoutKey) appendQueryParameter("KEY", key) }
                .appendQueryParameter("Type", "xml")
                .appendQueryParameter("pIndex", pageIndex.toString()).appendQueryParameter("pSize", pageSize.toString()).build()
            Log.i("SHSH_RESTROOM", "Page requested; page=$pageIndex, rows=$pageSize; no vehicle location sent")
            val connection = URL(uri.toString()).openConnection() as HttpURLConnection
            val started = SystemClock.elapsedRealtime()
            var stage = "setup"
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000; connection.readTimeout = 15000
                // XML is selected by Type=xml. Keep content negotiation broad for this comparison.
                connection.setRequestProperty("Accept", "*/*")
                Log.i("SHSH_RESTROOM", "Request profile; accept=any, formatParameter=xml; TLS verification enabled")
                stage = "connect_or_headers"
                val http = connection.responseCode
                Log.i("SHSH_RESTROOM", "Headers received; mode=${if (withoutKey) "official_sample" else "issued_key"}, http=$http, elapsedMs=${SystemClock.elapsedRealtime() - started}")
                stage = "read_body"
                val input = if (http in 200..299) connection.inputStream else connection.errorStream
                val raw = input?.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 8 * 1024 * 1024) throw PublicDataException("response_too_large")
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                } ?: ""
                Log.i("SHSH_RESTROOM", "Response diagnostics; http=$http; response text omitted")
                if (http !in 200..299) {
                    val code = runCatching {
                        val errorParser = Xml.newPullParser().apply { setInput(StringReader(raw)) }
                        var found: String? = null
                        while (errorParser.eventType != XmlPullParser.END_DOCUMENT) {
                            currentCoroutineContext().ensureActive()
                            if (errorParser.eventType == XmlPullParser.START_TAG && errorParser.name == "CODE") {
                                found = errorParser.nextText().trim(); break
                            }
                            errorParser.next()
                        }
                        found?.takeIf { it.matches(Regex("(?:INFO|ERROR)-[0-9]{3}")) }
                    }.getOrElse { error -> if (error is CancellationException) throw error else null }
                    val kind = when {
                        raw.isBlank() -> "empty"
                        raw.trimStart().startsWith("<") -> "markup"
                        raw.trimStart().startsWith("{") -> "json"
                        else -> "text"
                    }
                    Log.i("SHSH_RESTROOM", "Error diagnostics; http=$http, bodyKind=$kind, apiCode=${code ?: "unavailable"}")
                    throw PublicDataException(if (code != null) "http_${http}_api_$code" else "http_$http")
                }
                stage = "xml_parse"
                val parser = Xml.newPullParser().apply { setInput(StringReader(raw)) }
                val rows = mutableListOf<RestroomSample>()
                val fields = setOf("PBCTLT_PLC_NM", "REFINE_ROADNM_ADDR", "REFINE_LOTNO_ADDR", "OPEN_TM_INFO", "DATA_STD_DE", "REFINE_WGS84_LAT", "REFINE_WGS84_LOGT")
                var row: MutableMap<String, String>? = null
                var code: String? = null
                var rootSeen = false
                var totalCount: Int? = null
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    currentCoroutineContext().ensureActive()
                    if (event == XmlPullParser.START_TAG) {
                        val tag = parser.name
                        when {
                            tag == "Publtolt" -> rootSeen = true
                            tag == "row" -> row = mutableMapOf()
                            tag == "list_total_count" && row == null -> totalCount = parser.nextText().trim().toIntOrNull()
                            tag == "CODE" && row == null -> code = parser.nextText().trim()
                            tag in fields && row != null -> row[tag] = parser.nextText().trim()
                        }
                    } else if (event == XmlPullParser.END_TAG && parser.name == "row") {
                        if (rows.size >= pageSize) throw PublicDataException("row_limit_ignored")
                        val values = row ?: throw PublicDataException("row_format")
                        fun text(field: String) = values[field]?.takeUnless { it.isBlank() || it.equals("null", true) }
                        val hours = text("OPEN_TM_INFO")?.takeUnless { value -> value.replace(Regex("[\\s:~～-]"), "").isEmpty() }
                        val date = text("DATA_STD_DE")?.let { if (it.matches(Regex("[0-9]{8}"))) "${it.take(4)}-${it.substring(4,6)}-${it.takeLast(2)}" else it }
                        rows += RestroomSample(text("PBCTLT_PLC_NM") ?: "화장실명 확인 필요",
                            text("REFINE_ROADNM_ADDR") ?: text("REFINE_LOTNO_ADDR") ?: "주소 확인 필요",
                            hours, date ?: "기준일 확인 필요",
                            text("REFINE_WGS84_LAT")?.toDoubleOrNull(), text("REFINE_WGS84_LOGT")?.toDoubleOrNull())
                        row = null
                    }
                    event = parser.next()
                }
                val safeCode = code?.takeIf { it.matches(Regex("(?:INFO|ERROR)-[0-9]{3}")) }
                Log.i("SHSH_RESTROOM", "API status; code=${safeCode ?: "unavailable"}")
                if (code == "INFO-200") return@withContext FacilityPage(emptyList<RestroomSample>(), 0)
                if (code != "INFO-000") throw PublicDataException(safeCode?.let { "api_$it" } ?: "result_code_missing")
                if (!rootSeen) throw PublicDataException("response_format")
                Log.i("SHSH_RESTROOM", "Sample received; mode=${if (withoutKey) "official_sample" else "issued_key"}, count=${rows.size}, withCoordinates=${rows.count { it.hasCoordinates }}, withRegisteredHours=${rows.count { it.registeredHours != null }}")
                val total = totalCount?.takeIf { it >= 0 } ?: throw PublicDataException("total_count_missing")
                FacilityPage(rows.toList(), total)
            } catch (error: CancellationException) { throw error }
            catch (error: PublicDataException) { throw error }
            catch (error: Exception) {
                val causes = generateSequence(error as Throwable?) { it.cause }.take(5).toList()
                val reason = when {
                    causes.any { it is UnknownHostException } -> "dns_failed"
                    causes.any { it is SSLException } -> "tls_failed"
                    causes.any { it is SocketTimeoutException } -> if (stage == "read_body") "body_read_timeout" else "connect_or_headers_timeout"
                    causes.any { it is ConnectException } -> "connection_failed"
                    causes.any { it is XmlPullParserException } -> "xml_parse_failed"
                    causes.any { it is IOException } -> "network_io_failed"
                    else -> "unexpected_response"
                }
                val types = causes.joinToString(">") { it.javaClass.simpleName.take(60) }
                Log.e("SHSH_RESTROOM", "Failure diagnostics; mode=${if (withoutKey) "official_sample" else "issued_key"}, stage=$stage, reason=$reason, types=$types, elapsedMs=${SystemClock.elapsedRealtime() - started}; messages omitted")
                throw PublicDataException(reason)
            }
            finally { connection.disconnect() }
        }
    }
}
