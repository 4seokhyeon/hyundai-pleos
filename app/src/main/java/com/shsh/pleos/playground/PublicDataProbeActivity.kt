package com.shsh.pleos.playground

import android.os.Bundle
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.shsh.pleos.playground.data.LocationRepository
import kotlin.math.roundToInt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import com.shsh.pleos.playground.ui.sos.FacilityDataScreen
import com.shsh.pleos.playground.ui.sos.SosScreen
import com.shsh.pleos.playground.data.RestroomCandidate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shsh.pleos.playground.platform.DrivingRestrictionsMonitor
import com.shsh.pleos.playground.ui.karaoke.Action
import com.shsh.pleos.playground.ui.probe.PublicDataProbeViewModel
import com.shsh.pleos.playground.ui.theme.PleosSdkPlaygroundTheme

class PublicDataProbeActivity : ComponentActivity() {
    private lateinit var model: PublicDataProbeViewModel
    private var monitor: DrivingRestrictionsMonitor? = null
    private var allowed by mutableStateOf(false)
    private var showDiagnostics by mutableStateOf(false)
    private var showData by mutableStateOf(false)
    private var selected by mutableStateOf<RestroomCandidate?>(null)
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.onLocationPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showData = intent.getBooleanExtra("open_data", false)
        model = ViewModelProvider(this)[PublicDataProbeViewModel::class.java]
        Log.i("SHSH_SOS", "Public-data probe opened; no automatic requests")
        setContent {
            val state by model.uiState.collectAsStateWithLifecycle()
            BackHandler(enabled = showDiagnostics || showData || selected != null) {
                when { showDiagnostics -> showDiagnostics = false; selected != null -> selected = null; else -> { model.cancel(); showData = false } }
            }
            PleosSdkPlaygroundTheme {
                if (!showDiagnostics && !showData) {
                    SosScreen(state, allowed, selected, onSelect = { selected = it },
                        onFind = {
                            if (allowed) {
                                selected = null
                                if (ContextCompat.checkSelfPermission(this@PublicDataProbeActivity, LocationRepository.PERMISSION) == PackageManager.PERMISSION_GRANTED) model.findNearby()
                                else locationPermission.launch(LocationRepository.PERMISSION)
                            }
                        }, onCancel = model::cancel, onData = { model.cancel(); showData = true }, onHome = { finish() })
                    return@PleosSdkPlaygroundTheme
                }
                if (!showDiagnostics) {
                    FacilityDataScreen(state = state, allowed = allowed,
                        parkingKey = model.parkingConfigured, restroomKey = model.restroomConfigured,
                        onCollect = { source -> if (allowed) model.collectDataset(source) },
                        onCancel = model::cancel, onDiagnostics = { showDiagnostics = true }, onHome = { finish() },
                        onSos = { model.cancel(); showData = false })
                    return@PleosSdkPlaygroundTheme
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("화장실 SOS · 공공데이터 연결 확인", style = MaterialTheme.typography.headlineMedium)
                        Text("주차장 키: ${if (model.parkingConfigured) "설정됨" else "미설정"} · 경기 화장실 키: ${if (model.restroomConfigured) "설정됨" else "미설정"}")
                        Text("조회된 첫 페이지 5건 안에서 마지막 위치 기준 직선거리를 비교합니다. 주변 전체 검색·빈자리 조회가 아닙니다. 좌표는 외부 API로 보내지 않습니다.")
                        Action("차량 마지막 위치 조회 · 거리 계산", enabled = allowed && !state.busy && !state.cacheLoading,
                            onClick = {
                                if (allowed) {
                                    if (ContextCompat.checkSelfPermission(this@PublicDataProbeActivity, LocationRepository.PERMISSION) == PackageManager.PERMISSION_GRANTED) model.queryReferenceLocation()
                                    else locationPermission.launch(LocationRepository.PERMISSION)
                                }
                            })
                        if (!allowed) Text("플랫폼 이용 제한 또는 상태 확인 중 · 조회와 결과 표시를 중지합니다")
                        Action("주차장 API 5건 조회", enabled = allowed && model.parkingConfigured && !state.busy && !state.cacheLoading,
                            onClick = { if (allowed) model.queryParking() })
                        Action("화장실 공식 샘플 조회 · 키 없음", secondary = true, enabled = allowed && !state.busy && !state.cacheLoading,
                            onClick = { if (allowed) model.queryRestroomSample() })
                        Action("화장실 API 5건 조회 · 발급키", enabled = allowed && model.restroomConfigured && !state.busy && !state.cacheLoading,
                            onClick = { if (allowed) model.queryRestrooms() })
                        Action("조회 취소", secondary = true, enabled = state.busy, onClick = model::cancel)
                        if (allowed) {
                            Text(state.message)
                            Text(state.locationMessage)
                            state.reference?.let { reference ->
                                val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(reference.measuredAtMillis))
                                Text("거리 기준 측위 시각: $time · 이동 경로/도보거리가 아닌 직선거리")
                            }
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (state.rows.isNotEmpty()) Text("주차장 조회 결과", style = MaterialTheme.typography.titleLarge)
                            state.parkingDistances.forEach { comparison ->
                                val row = comparison.facility
                                HorizontalDivider()
                                Text(row.name, style = MaterialTheme.typography.titleLarge)
                                Text(row.address)
                                Text("마지막 위치 기준 직선거리: ${formatDistance(comparison.meters)}")
                                Text("요금 구분: ${row.fee} · 좌표: ${if (row.hasCoordinates) "있음" else "확인 필요"}")
                                Text("데이터 기준일: ${row.referenceDate}")
                            }
                            if (state.restrooms.isNotEmpty()) Text("화장실 조회 결과", style = MaterialTheme.typography.titleLarge)
                            state.restroomDistances.forEach { comparison ->
                                val row = comparison.facility
                                HorizontalDivider()
                                Text(row.name, style = MaterialTheme.typography.titleLarge)
                                Text(row.address)
                                Text("마지막 위치 기준 직선거리: ${formatDistance(comparison.meters)}")
                                Text("등록된 개방시간: ${row.registeredHours ?: "확인 필요"}")
                                Text("좌표: ${if (row.hasCoordinates) "있음" else "확인 필요"} · 데이터 기준일: ${row.referenceDate}")
                                Text("현재 개방 여부·주차 가능 여부는 확인되지 않았습니다.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                        Text("출처: 경기데이터드림 공중화장실 현황 / 공공데이터포털 전국주차장정보표준데이터", style = MaterialTheme.typography.bodySmall)
                        Action("데이터 준비 화면으로", secondary = true, enabled = !state.busy, onClick = { showDiagnostics = false })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        allowed = false
        monitor = DrivingRestrictionsMonitor(this) { permit, _ ->
            allowed = permit
            if (!permit) { selected = null; model.cancel() }
        }.also { it.start() }
    }

    override fun onPause() {
        allowed = false; selected = null; model.cancel(); monitor?.close(); monitor = null
        super.onPause()
    }
    private fun formatDistance(meters: Double?): String = when {
        meters == null -> "확인 필요 · 위치 또는 시설 좌표 없음"
        meters < 1000 -> "${meters.roundToInt()}m"
        else -> "${String.format(java.util.Locale.KOREA, "%.1f", meters / 1000)}km"
    }
}
