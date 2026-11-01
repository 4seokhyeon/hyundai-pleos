package com.shsh.pleos.playground.data

import android.net.Uri
import android.util.Log
import android.util.Xml
import com.shsh.pleos.playground.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.io.StringReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

data class ParkingSample(val name: String, val address: String, val fee: String, val referenceDate: String,
    val latitude: Double?, val longitude: Double?) {
    val hasCoordinates: Boolean get() = validFacilityCoordinates(latitude, longitude)
}
class PublicDataException(val reason: String) : Exception(reason)

/** Paged standard parking data, not a live availability lookup. */
class ParkingRepository {
    suspend fun sample(): List<ParkingSample> = page(1, 5).items

    suspend fun page(pageIndex: Int, pageSize: Int = 1000): FacilityPage<ParkingSample> = withTimeout(30_000) {
        require(pageIndex in 1..1000 && pageSize in 1..1000)
        withContext(Dispatchers.IO) {
            if (!BuildConfig.DEBUG || BuildConfig.PUBLIC_DATA_API_KEY.isBlank()) throw PublicDataException("key_missing")
            val keyLooksEncoded = Regex("%[0-9A-Fa-f]{2}").containsMatchIn(BuildConfig.PUBLIC_DATA_API_KEY)
            // Accept either portal representation. Uri.decode preserves literal '+' unlike form URLDecoder.
            val requestKey = if (keyLooksEncoded) Uri.decode(BuildConfig.PUBLIC_DATA_API_KEY) else BuildConfig.PUBLIC_DATA_API_KEY
            if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(requestKey)) throw PublicDataException("key_repeatedly_encoded")
            Log.i("SHSH_PARKING", "Request diagnostics; keyPresent=true, keyLooksPercentEncoded=$keyLooksEncoded, decodedBeforeQuery=$keyLooksEncoded; queryEncoding=once")
            val uri = Uri.parse("https://api.data.go.kr/openapi/tn_pubr_prkplce_info_api").buildUpon()
                .appendQueryParameter("serviceKey", requestKey)
                .appendQueryParameter("pageNo", pageIndex.toString()).appendQueryParameter("numOfRows", pageSize.toString())
                .appendQueryParameter("type", "json").build()
            Log.i("SHSH_PARKING", "Page requested; page=$pageIndex, rows=$pageSize; no vehicle location sent")
            val connection = URL(uri.toString()).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000; connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/json")
                val http = connection.responseCode
                val stream = if (http in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > 8 * 1024 * 1024) throw PublicDataException("response_too_large")
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString("UTF-8")
                } ?: ""
                currentCoroutineContext().ensureActive()
                val trimmed = raw.trimStart()
                val bodyKind = when {
                    trimmed.isEmpty() -> "empty"
                    trimmed.startsWith("{") -> "json"
                    trimmed.startsWith("<") -> "markup"
                    else -> "text"
                }
                val json = if (bodyKind == "json") runCatching { JSONObject(raw) }.getOrNull() else null
                val jsonCode = json?.optJSONObject("header")?.optString("resultCode")
                    ?: json?.optJSONObject("response")?.optJSONObject("header")?.optString("resultCode")
                    ?: json?.optJSONObject("error")?.optString("code")
                    ?: json?.optString("code")?.takeIf { it.isNotBlank() }
                val xmlCode = if (bodyKind == "markup") runCatching {
                    val parser = Xml.newPullParser().apply { setInput(StringReader(raw)) }
                    var found: String? = null
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name in setOf("returnReasonCode", "resultCode")) {
                            found = parser.nextText(); break
                        }
                        parser.next()
                    }
                    found
                }.getOrNull() else null
                // Emit only recognised error constants; never echo arbitrary server text or credentials.
                val candidate = jsonCode ?: xmlCode
                val apiCode = candidate?.takeIf { it in setOf("00", "01", "02", "03", "04", "05", "10", "11", "12", "20", "21", "22", "23", "29", "30", "31", "32", "33", "99", "401", "403", "SERVICE_ACCESS_DENIED_ERROR", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR", "DEADLINE_HAS_EXPIRED_ERROR", "UNREGISTERED_IP_ERROR") }
                Log.i("SHSH_PARKING", "Response diagnostics; http=$http, bodyKind=$bodyKind, apiCode=${apiCode ?: "unavailable"}")
                if (http !in 200..299) throw PublicDataException(if (apiCode != null) "http_${http}_api_$apiCode" else "http_$http")
                if (bodyKind == "markup") throw PublicDataException(if (apiCode != null) "api_$apiCode" else "non_json_response")
                val root = json ?: throw PublicDataException("response_format")
                if (pageSize <= 5) logStructure(root)
                // The portal's observed response has header/body directly at the root.
                val response = when {
                    root.optJSONObject("header") != null && root.optJSONObject("body") != null -> root
                    root.optJSONObject("response") != null -> root.getJSONObject("response")
                    else -> throw PublicDataException("response_format")
                }
                val code = response.optJSONObject("header")?.optString("resultCode") ?: throw PublicDataException("header_missing")
                if (code == "03") return@withContext FacilityPage(emptyList<ParkingSample>(), 0)
                if (code != "00") throw PublicDataException(if (code.matches(Regex("[A-Za-z0-9_-]{1,40}"))) "api_$code" else "unknown_api_code")
                val body = response.optJSONObject("body") ?: throw PublicDataException("body_missing")
                val totalCount = body.optInt("totalCount", -1).takeIf { it >= 0 } ?: throw PublicDataException("total_count_missing")
                if (body.has("pageNo") && body.optInt("pageNo", -1) != pageIndex) throw PublicDataException("page_number_mismatch")
                val items = when (val value = body.opt("items")) {
                    is JSONArray -> value
                    is JSONObject -> value.optJSONArray("item") ?: value.optJSONObject("item")?.let { JSONArray().put(it) }
                    else -> if (body.optInt("totalCount", -1) == 0) JSONArray() else null
                } ?: throw PublicDataException("items_format")
                if (items.length() > pageSize) throw PublicDataException("row_limit_ignored")
                val rows = (0 until items.length()).map { index ->
                    val row = items.getJSONObject(index)
                    fun text(key: String) = row.optString(key).takeUnless { it.isBlank() || it == "null" }
                    val lat = text("latitude")?.toDoubleOrNull()
                    val lon = text("longitude")?.toDoubleOrNull()
                    ParkingSample(text("prkplceNm") ?: "주차장명 확인 필요",
                        text("rdnmadr") ?: text("lnmadr") ?: "주소 확인 필요",
                        text("parkingchrgeInfo") ?: "요금 확인 필요",
                        text("referenceDate") ?: "기준일 확인 필요",
                        lat, lon)
                }
                Log.i("SHSH_PARKING", "Sample received; count=${rows.size}, withCoordinates=${rows.count { it.hasCoordinates }}")
                FacilityPage(rows, totalCount)
            } catch (error: CancellationException) { throw error }
            catch (error: PublicDataException) { throw error }
            catch (_: Exception) { throw PublicDataException("network_or_format") }
            finally { connection.disconnect() }
        }
    }
    private fun logStructure(root: JSONObject) {
        fun keys(node: JSONObject): List<String> = node.keys().asSequence().take(30)
            .map { if (it.matches(Regex("[A-Za-z_][A-Za-z0-9_-]{0,39}"))) it else "other_field" }.toList()
        fun kind(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> "object"
            is JSONArray -> "array"
            is Number -> "number"
            is Boolean -> "boolean"
            else -> "string"
        }
        val fields = keys(root)
        Log.i("SHSH_PARKING", "Response shape; rootFields=${fields.joinToString(",")}")
        fields.filter { it != "other_field" }.forEach { field ->
            val value = root.opt(field)
            val childFields = when (value) {
                is JSONObject -> keys(value).joinToString(",")
                is JSONArray -> value.optJSONObject(0)?.let { keys(it).joinToString(",") } ?: "none"
                else -> "none"
            }
            Log.i("SHSH_PARKING", "Response shape; field=$field, type=${kind(value)}, childFields=$childFields; values omitted")
        }
    }

}
