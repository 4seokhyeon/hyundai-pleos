package com.shsh.pleos.playground.data

import android.content.Context
import android.util.AtomicFile
import com.shsh.pleos.playground.platform.AppLog as Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

enum class FacilitySource(val title: String, val coverage: String, val cacheName: String) {
    RESTROOM("공중화장실", "경기도 제공 자료", "restrooms.json"),
    PARKING("주차장", "전국 제공기관 수록 자료", "parking.json")
}

data class DatasetSummary(val source: FacilitySource, val sourceRows: Int, val savedRows: Int,
    val excludedCoordinates: Int, val pages: Int, val savedAtMillis: Long)
data class DatasetRestore(val summary: DatasetSummary?, val problem: String? = null)
data class DatasetProgress(val source: FacilitySource, val page: Int, val totalPages: Int?,
    val received: Int, val total: Int?, val excluded: Int, val saving: Boolean = false) {
    val fraction: Float? get() = total?.let { if (it == 0) .99f else (received.toFloat() / it).coerceIn(0f, .99f) }
}

/** Only a fully validated source download replaces the previous on-device snapshot. */
class FacilityDatasetRepository(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "facility-datasets")
    private val parking = ParkingRepository()
    private val restrooms = RestroomRepository()

    suspend fun restore(source: FacilitySource): DatasetRestore = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val file = AtomicFile(File(directory, source.cacheName))
            if (!file.baseFile.exists() && !File(directory, source.cacheName + ".bak").exists()) return@withLock DatasetRestore(null)
            try {
                val bytes = file.openRead().use { input ->
                    val buffer = ByteArray(8192)
                    val output = ByteArrayOutputStream()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (output.size() + size > MAX_BYTES) throw PublicDataException("cache_too_large")
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                }
                val root = JSONObject(bytes.toString(Charsets.UTF_8))
                val summary = validateSnapshot(root, source)
                Log.i("SHSH_DATA", "Cache restored; source=$source, saved=${summary.savedRows}, excluded=${summary.excludedCoordinates}")
                DatasetRestore(summary)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                Log.w("SHSH_DATA", "Cache unavailable; source=$source, reason=cache_invalid; contents omitted")
                DatasetRestore(null, "저장 자료를 읽지 못했어요 · 다시 수집해 주세요")
            }
        }
    }

    suspend fun collect(source: FacilitySource, onProgress: (DatasetProgress) -> Unit): DatasetSummary = collectionLock.withLock {
        withTimeout(300_000) {
            withContext(Dispatchers.IO) {
                Log.i("SHSH_DATA", "Collection started by user; source=$source, pageSize=$PAGE_SIZE; no vehicle location sent")
                val savedRows = JSONArray()
                val fingerprints = mutableSetOf<String>()
                var total = -1
                var pages = 1
                var pageNumber = 1
                var received = 0
                var excluded = 0
                var estimatedBytes = 0
                try {
                    while (pageNumber <= pages) {
                        currentCoroutineContext().ensureActive()
                        onProgress(DatasetProgress(source, pageNumber, if (total >= 0) pages else null, received, total.takeIf { it >= 0 }, excluded))
                        val page: FacilityPage<JSONObject> = when (source) {
                            FacilitySource.RESTROOM -> restrooms.page(pageNumber, PAGE_SIZE).let {
                                FacilityPage(it.items.map(::encodeRestroom), it.totalCount)
                            }
                            FacilitySource.PARKING -> parking.page(pageNumber, PAGE_SIZE).let {
                                FacilityPage(it.items.map(::encodeParking), it.totalCount)
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (page.totalCount !in 0..MAX_ROWS) throw PublicDataException("dataset_size_limit")
                        if (total < 0) {
                            total = page.totalCount
                            pages = maxOf(1, (total + PAGE_SIZE - 1) / PAGE_SIZE)
                        } else if (page.totalCount != total) throw PublicDataException("dataset_changed_during_download")
                        val expected = minOf(PAGE_SIZE, total - received)
                        if (page.items.size != expected) throw PublicDataException("page_count_mismatch")
                        val canonical = JSONArray(page.items).toString().toByteArray(Charsets.UTF_8)
                        val fingerprint = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) }
                        if (!fingerprints.add(fingerprint)) throw PublicDataException("repeated_page")
                        estimatedBytes += canonical.size
                        if (estimatedBytes > MAX_BYTES - 8192) throw PublicDataException("dataset_storage_limit")
                        page.items.forEach { row ->
                            currentCoroutineContext().ensureActive()
                            if (validRowCoordinates(row)) savedRows.put(row) else excluded++
                        }
                        received += page.items.size
                        Log.i("SHSH_DATA", "Page collected; source=$source, page=$pageNumber/$pages, received=$received/$total, excluded=$excluded")
                        onProgress(DatasetProgress(source, pageNumber, pages, received, total, excluded))
                        pageNumber++
                        if (pageNumber <= pages) delay(200)
                    }
                    if (received != total || savedRows.length() + excluded != total) throw PublicDataException("collection_incomplete")
                    onProgress(DatasetProgress(source, pages, pages, received, total, excluded, saving = true))
                    val now = System.currentTimeMillis()
                    val snapshot = JSONObject().put("schemaVersion", 1).put("source", source.name)
                        .put("complete", true).put("sourceRows", total).put("savedRows", savedRows.length())
                        .put("excludedCoordinates", excluded).put("pages", pages).put("savedAtMillis", now).put("rows", savedRows)
                    val summary = validateSnapshot(snapshot, source)
                    val payload = snapshot.toString().toByteArray(Charsets.UTF_8)
                    if (payload.size > MAX_BYTES) throw PublicDataException("dataset_storage_limit")
                    ioLock.withLock {
                        currentCoroutineContext().ensureActive()
                        if (!directory.isDirectory && !directory.mkdirs()) throw PublicDataException("cache_directory_failed")
                        val file = AtomicFile(File(directory, source.cacheName))
                        val output = file.startWrite()
                        var committed = false
                        try {
                            var offset = 0
                            while (offset < payload.size) {
                                currentCoroutineContext().ensureActive()
                                val size = minOf(8192, payload.size - offset)
                                output.write(payload, offset, size)
                                offset += size
                            }
                            currentCoroutineContext().ensureActive()
                            file.finishWrite(output)
                            committed = true
                        } finally {
                            if (!committed) file.failWrite(output)
                        }
                        // AtomicFile may log a rename/sync failure internally; confirm the committed bytes.
                        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(payload)
                        val actualDigest = MessageDigest.getInstance("SHA-256")
                        var readBytes = 0
                        file.openRead().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                readBytes += count
                                if (readBytes > MAX_BYTES) throw PublicDataException("cache_write_failed")
                                actualDigest.update(buffer, 0, count)
                            }
                        }
                        if (readBytes != payload.size || !MessageDigest.isEqual(expectedDigest, actualDigest.digest())) throw PublicDataException("cache_write_failed")
                    }
                    Log.i("SHSH_DATA", "Cache saved; source=$source, sourceRows=$total, saved=${summary.savedRows}, excluded=$excluded, pages=$pages")
                    summary
                } catch (error: CancellationException) {
                    Log.i("SHSH_DATA", "Collection cancelled; source=$source, received=$received; incomplete rows not published; completed snapshots retained")
                    throw error
                }
            }
        }
    }

    /** Local-only geometry for the candidate screens. No location is sent to an API. */
    suspend fun nearby(reference: FacilityReference): List<RestroomCandidate> {
        val restrooms = readFacilities(FacilitySource.RESTROOM)
        val parking = readFacilities(FacilitySource.PARKING)
        return withContext(Dispatchers.Default) {
            val closest = restrooms.mapNotNull { row ->
                currentCoroutineContext().ensureActive()
                val meters = reference.distanceMetersTo(row.latitude, row.longitude) ?: return@mapNotNull null
                if (meters <= 5000) row to meters else null
            }.distinctBy { Triple(it.first.name, it.first.latitude, it.first.longitude) }.sortedBy { it.second }.take(6)
            closest.map { (restroom, distance) ->
                val anchor = FacilityReference(restroom.latitude, restroom.longitude, reference.measuredAtMillis)
                var nearest: CachedFacility? = null
                var nearestDistance = 300.0
                parking.forEach { row ->
                    currentCoroutineContext().ensureActive()
                    val meters = anchor.distanceMetersTo(row.latitude, row.longitude)
                    if (meters != null && meters <= nearestDistance) { nearest = row; nearestDistance = meters }
                }
                RestroomCandidate(restroom, distance, nearest, if (nearest == null) null else nearestDistance)
            }
        }
    }

    private suspend fun readFacilities(source: FacilitySource): List<CachedFacility> = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val file = AtomicFile(File(directory, source.cacheName))
            if (!file.baseFile.exists() && !File(directory, source.cacheName + ".bak").exists()) throw PublicDataException("cache_missing")
            val bytes = file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > MAX_BYTES) throw PublicDataException("cache_too_large")
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            validateSnapshot(root, source)
            val rows = root.getJSONArray("rows")
            List(rows.length()) { index ->
                currentCoroutineContext().ensureActive()
                val row = rows.getJSONObject(index)
                fun optional(name: String) = row.optString(name).takeIf { it.isNotBlank() && it != "null" }
                CachedFacility(row.getString("name"), row.getString("address"), row.getDouble("latitude"), row.getDouble("longitude"),
                    row.optString("referenceDate", "확인 필요"), optional("registeredHours"), optional("fee"))
            }
        }
    }

    private fun validRowCoordinates(row: JSONObject): Boolean = validFacilityCoordinates(
        row.optDouble("latitude", Double.NaN), row.optDouble("longitude", Double.NaN))

    private suspend fun validateSnapshot(root: JSONObject, source: FacilitySource): DatasetSummary {
        if (root.optInt("schemaVersion") != 1 || root.optString("source") != source.name || !root.optBoolean("complete")) throw PublicDataException("cache_header_invalid")
        val rows = root.getJSONArray("rows")
        val sourceRows = root.getInt("sourceRows")
        val saved = root.getInt("savedRows")
        val excluded = root.getInt("excludedCoordinates")
        val pages = root.getInt("pages")
        val time = root.getLong("savedAtMillis")
        if (sourceRows !in 0..MAX_ROWS || saved != rows.length() || saved < 0 || excluded < 0 || saved + excluded != sourceRows || pages != maxOf(1, (sourceRows + PAGE_SIZE - 1) / PAGE_SIZE) || time <= 0) throw PublicDataException("cache_count_invalid")
        for (index in 0 until rows.length()) {
            currentCoroutineContext().ensureActive()
            val row = rows.getJSONObject(index)
            if (!validRowCoordinates(row) || row.optString("name").isBlank() || !row.has("address")) throw PublicDataException("cache_row_invalid")
        }
        return DatasetSummary(source, sourceRows, saved, excluded, pages, time)
    }

    private fun encodeRestroom(row: RestroomSample): JSONObject = common(row.name, row.address, row.latitude, row.longitude, row.referenceDate)
        .put("registeredHours", row.registeredHours ?: JSONObject.NULL)
    private fun encodeParking(row: ParkingSample): JSONObject = common(row.name, row.address, row.latitude, row.longitude, row.referenceDate).put("fee", row.fee)
    private fun common(name: String, address: String, latitude: Double?, longitude: Double?, date: String): JSONObject = JSONObject()
        .put("name", name).put("address", address).put("referenceDate", date)
        .put("latitude", latitude?.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("longitude", longitude?.takeIf { it.isFinite() } ?: JSONObject.NULL)

    companion object {
        private const val PAGE_SIZE = 1000
        private const val MAX_ROWS = 100_000
        private const val MAX_BYTES = 32 * 1024 * 1024
        private val ioLock = Mutex()
        private val collectionLock = Mutex()
    }
}
