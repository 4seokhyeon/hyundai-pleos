package com.shsh.pleos.playground.ui.probe

import android.app.Application
import com.shsh.pleos.playground.platform.AppLog as Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shsh.pleos.playground.BuildConfig
import com.shsh.pleos.playground.data.FacilityDatasetRepository
import com.shsh.pleos.playground.data.FacilitySource
import com.shsh.pleos.playground.data.DatasetRestore
import com.shsh.pleos.playground.data.DatasetProgress
import com.shsh.pleos.playground.data.LocationRepository
import com.shsh.pleos.playground.data.RestroomCandidate
import com.shsh.pleos.playground.data.FacilityReference
import com.shsh.pleos.playground.data.FacilityDistance
import com.shsh.pleos.playground.data.validFacilityCoordinates
import com.shsh.pleos.playground.data.ParkingRepository
import com.shsh.pleos.playground.data.ParkingSample
import com.shsh.pleos.playground.data.RestroomRepository
import com.shsh.pleos.playground.data.RestroomSample
import com.shsh.pleos.playground.data.PublicDataException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PublicDataProbeState(val busy: Boolean = false, val message: String = "자료 수집을 눌러 기기 저장본을 준비하세요 · 자동 다운로드는 하지 않습니다",
    val rows: List<ParkingSample> = emptyList(), val restrooms: List<RestroomSample> = emptyList(),
    val cacheLoading: Boolean = true,
    val nearbySearching: Boolean = false,
    val nearbyCompleted: Boolean = false,
    val nearbyError: String? = null,
    val candidates: List<RestroomCandidate> = emptyList(),
    val candidateSetId: String? = null,
    val candidateSetCreatedAtMillis: Long = 0,
    val datasets: Map<FacilitySource, DatasetRestore> = emptyMap(),
    val collection: DatasetProgress? = null,
    val reference: FacilityReference? = null, val locationMessage: String = "거리 비교 전 · 마지막 위치를 조회하세요") {
    val parkingDistances: List<FacilityDistance<ParkingSample>> get() = rows.map {
        FacilityDistance(it, reference?.distanceMetersTo(it.latitude, it.longitude))
    }.sortedBy { it.meters ?: Double.POSITIVE_INFINITY }
    val restroomDistances: List<FacilityDistance<RestroomSample>> get() = restrooms.map {
        FacilityDistance(it, reference?.distanceMetersTo(it.latitude, it.longitude))
    }.sortedBy { it.meters ?: Double.POSITIVE_INFINITY }
}

class PublicDataProbeViewModel(application: Application) : AndroidViewModel(application) {
    val parkingConfigured = BuildConfig.DEBUG && BuildConfig.PUBLIC_DATA_API_KEY.isNotBlank()
    val restroomConfigured = BuildConfig.DEBUG && BuildConfig.GG_DATA_API_KEY.isNotBlank()
    private val datasetRepository = FacilityDatasetRepository(application)
    private val location = LocationRepository(application)
    private val parking = ParkingRepository()
    private val restroom = RestroomRepository()
    private val mutableState = MutableStateFlow(PublicDataProbeState())
    val uiState = mutableState.asStateFlow()
    private var work: Job? = null

    init {
        // Restore only local complete snapshots; never download automatically on launch.
        viewModelScope.launch {
            try {
                val restored = FacilitySource.entries.associateWith { source -> datasetRepository.restore(source) }
                mutableState.update { it.copy(datasets = restored) }
            } finally { mutableState.update { it.copy(cacheLoading = false) } }
        }
    }

    fun collectDataset(source: FacilitySource) = query("SHSH_DATA", "${source.title} 자료") {
        val summary = datasetRepository.collect(source) { progress ->
            mutableState.update { it.copy(collection = progress) }
        }
        mutableState.update { it.copy(datasets = it.datasets + (source to DatasetRestore(summary)),
            message = "${source.title} 저장 완료 · 검색용 ${summary.savedRows}건 · 좌표 제외 ${summary.excludedCoordinates}건") }
    }

    fun findNearby() = query("SHSH_SOS", "주변 화장실") {
        mutableState.update { it.copy(nearbySearching = true, nearbyCompleted = false, nearbyError = null, candidates = emptyList(), candidateSetId = null) }
        val fix = location.readLastKnownLocation() ?: throw PublicDataException("location_missing")
        if (!validFacilityCoordinates(fix.latitude, fix.longitude)) throw PublicDataException("invalid_location")
        val age = System.currentTimeMillis() - fix.timestampMillis
        if (fix.timestampMillis <= 0 || age < -60_000) throw PublicDataException("invalid_location_time")
        val reference = FacilityReference(fix.latitude, fix.longitude, fix.timestampMillis)
        val candidates = datasetRepository.nearby(reference)
        mutableState.update { it.copy(reference = reference, candidates = candidates, candidateSetId = java.util.UUID.randomUUID().toString(), candidateSetCreatedAtMillis = android.os.SystemClock.elapsedRealtime(), nearbyCompleted = true,
            locationMessage = if (age > 300_000) "5분 이상 오래된 마지막 위치 기준 · 다시 확인 필요" else "마지막 위치 기준 · 측위 경과 ${age.coerceAtLeast(0) / 1000}초",
            message = "기기 자료에서 주변 후보 ${candidates.size}개 확인") }
        Log.i("SHSH_SOS", "Nearby candidates ready; count=${candidates.size}, radiusMeters=5000, parkingLinkMeters=300, stale=${age > 300_000}; coordinates omitted")
    }

    fun queryReferenceLocation() = query("SHSH_SOS", "마지막 위치") {
        mutableState.value = mutableState.value.copy(reference = null, locationMessage = "마지막 위치 조회 중")
        val fix = location.readLastKnownLocation()
        if (fix == null) {
            mutableState.value = mutableState.value.copy(locationMessage = "저장된 위치 없음 · 거리 계산 안 함", message = "위치 응답에 좌표가 없습니다")
            return@query
        }
        if (!validFacilityCoordinates(fix.latitude, fix.longitude)) throw PublicDataException("invalid_location")
        val measured = fix.timestampMillis
        val age = System.currentTimeMillis() - measured
        if (measured <= 0 || age < -60_000) throw PublicDataException("invalid_location_time")
        val reference = FacilityReference(fix.latitude, fix.longitude, measured)
        val ageSeconds = (age.coerceAtLeast(0) / 1000)
        val description = if (age > 300_000) "5분 이상 오래된 마지막 위치 · 현재 위치가 아닌 참고용 거리입니다"
            else "조회 시 측위 경과 ${ageSeconds}초 · 마지막 위치 기준이며 연속 측위는 아닙니다"
        mutableState.value = mutableState.value.copy(reference = reference, locationMessage = description,
            message = "위치 기준 설정 완료 · 조회된 시설 안에서 직선거리 계산")
        Log.i("SHSH_SOS", "Location reference ready; stale=${age > 300_000}, parkingCount=${mutableState.value.rows.size}, restroomCount=${mutableState.value.restrooms.size}; coordinates omitted")
    }

    fun onLocationPermissionResult(granted: Boolean) {
        Log.i("SHSH_LOCATION", "SOS location permission result; granted=$granted")
        mutableState.value = mutableState.value.copy(locationMessage = if (granted) "위치 권한 허용 · 현재 위치로 찾기를 눌러주세요" else "위치 권한 없음 · 거리 계산 안 함", nearbyError = if (granted) null else "위치 권한을 허용한 뒤 다시 찾아주세요.")
    }

    fun queryParking() = query("SHSH_PARKING", "주차장") {
        mutableState.value = mutableState.value.copy(rows = emptyList())
        val rows = parking.sample()
        mutableState.value = mutableState.value.copy(rows = rows, message = "주차장 응답 성공 · ${rows.size}건 · 주변 거리순이나 빈자리 정보가 아닙니다")
    }

    fun queryRestrooms() = queryRestroomsMode(false)
    fun queryRestroomSample() = queryRestroomsMode(true)

    private fun queryRestroomsMode(withoutKey: Boolean) = query("SHSH_RESTROOM", if (withoutKey) "화장실 공식 샘플(키 없음)" else "화장실 발급키") {
        mutableState.value = mutableState.value.copy(restrooms = emptyList())
        val rows = restroom.sample(withoutKey)
        val source = if (withoutKey) "공식 샘플(키 없음 · 키 인증 확인 아님)" else "발급키 요청"
        mutableState.value = mutableState.value.copy(restrooms = rows, message = "$source 성공 · ${rows.size}건 · 현재 개방 여부는 확인 필요합니다")
    }

    private fun query(tag: String, label: String, action: suspend () -> Unit) {
        if (mutableState.value.busy || mutableState.value.cacheLoading) return
        mutableState.value = mutableState.value.copy(busy = true, message = "$label API 응답 확인 중")
        work = viewModelScope.launch {
            try { action() }
            catch (error: TimeoutCancellationException) {
                mutableState.value = mutableState.value.copy(message = "$label 조회 응답 시간 초과 · 네트워크와 서비스 상태를 확인하세요", nearbyError = if (label == "주변 화장실") "위치 조회 시간이 초과됐어요. 다시 찾아주세요." else mutableState.value.nearbyError)
                Log.w(tag, "Sample failed; reason=timeout")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val reason = if (error is SecurityException) "location_permission_missing" else (error as? PublicDataException)?.reason ?: "client_error"
                val guidance = when (reason) {
                    "dataset_changed_during_download" -> "수집 도중 원본 건수가 변경됐어요 · 이전 저장본을 유지하며 다시 수집해 주세요"
                    "page_count_mismatch", "page_number_mismatch", "repeated_page", "collection_incomplete", "total_count_missing", "row_limit_ignored" -> "페이지 검증에 실패했어요 · 일부 자료를 완료로 저장하지 않았습니다"
                    "dataset_size_limit", "dataset_storage_limit", "response_too_large" -> "자료 크기 제한을 넘었어요 · 이전 저장본은 유지됩니다"
                    "cache_directory_failed", "cache_write_failed" -> "기기에 저장하지 못했어요 · 저장 공간을 확인해 주세요"
                    "cache_missing", "cache_too_large" -> "시설 자료를 다시 준비해 주세요"
                    "location_missing" -> "저장된 위치가 없어요 · 에뮬레이터 위치를 설정하고 다시 찾아주세요"
                    "location_permission_missing" -> "위치 권한을 허용한 뒤 다시 조회하세요"
                    "invalid_location", "invalid_location_time" -> "측위 좌표 또는 시각이 유효하지 않아 거리 계산을 중단했습니다"
                    "dns_failed" -> "에뮬레이터의 인터넷·DNS 연결을 확인하세요"
                    "tls_failed" -> "HTTPS 연결 실패 · 에뮬레이터 시각과 인증서/네트워크 환경을 확인하세요"
                    "connect_or_headers_timeout" -> "서버 연결 또는 HTTP 헤더 대기 시간 초과 · 인증키 오류로 확정할 수 없습니다"
                    "body_read_timeout" -> "응답 본문 수신 시간 초과 · 잠시 후 다시 시도하세요"
                    "connection_failed", "network_io_failed" -> "에뮬레이터의 서버 접속 경로를 확인하세요"
                    "xml_parse_failed", "unexpected_response" -> "응답 형식 확인 필요 · SHSH_RESTROOM 실패 단계 로그를 확인하세요"
                    "http_500" -> "서버가 HTTP 500을 반환했습니다 · 키 유무별 결과를 비교하세요"
                    else -> "해당 서비스 설정과 상세 오류 코드를 확인하세요"
                }
                mutableState.value = mutableState.value.copy(message = "$label 조회 실패 · $reason · $guidance",
                    nearbyError = if (label == "주변 화장실") guidance else mutableState.value.nearbyError,
                    locationMessage = if (tag == "SHSH_SOS") "위치 조회 실패 · 거리 기준 없음" else mutableState.value.locationMessage)
                Log.e(tag, "Sample failed; reason=$reason; credentials and response text omitted")
            } finally {
                // A cancelled older request must not clear a newer request's busy state.
                if (work === coroutineContext[Job]) mutableState.update { it.copy(busy = false, collection = null, nearbySearching = false) }
            }
        }
    }

    fun cancel() {
        mutableState.value = mutableState.value.copy(reference = null, candidates = emptyList(), candidateSetId = null, nearbySearching = false, nearbyCompleted = false, nearbyError = null, locationMessage = "거리 기준 없음 · 마지막 위치를 다시 조회하세요")
        if (work?.isActive == true) {
            work?.cancel()
            location.close()
            mutableState.value = mutableState.value.copy(busy = false, collection = null, message = "작업 취소 · 완료되지 않은 수집분은 저장하지 않으며 기존 완성본을 유지합니다",
                locationMessage = if (mutableState.value.reference == null) "거리 기준 없음 · 마지막 위치를 다시 조회하세요" else mutableState.value.locationMessage)
            Log.i("SHSH_SOS", "Public-data sample cancelled by user or lifecycle")
        }
    }
    /** Preserve a completed on-screen snapshot across Gleo/Navigation foreground transitions. */
    fun suspendWork() {
        if (mutableState.value.busy && work?.isActive == true) cancel()
    }

    override fun onCleared() { location.close(); super.onCleared() }
}
