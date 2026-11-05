package com.shsh.pleos.playground

import android.os.Bundle
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import com.shsh.pleos.playground.data.CachedFacility
import com.shsh.pleos.playground.data.validFacilityCoordinates
import com.shsh.pleos.playground.gleo.GleoSosContract
import com.shsh.pleos.playground.gleo.GleoReply
import com.shsh.pleos.playground.gleo.sendGleoReply
import com.shsh.pleos.playground.ui.probe.SosNavigationViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
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
    private lateinit var navigation: SosNavigationViewModel
    private var resumed = false
    private var voiceRequest: GleoSosContract.Request? = null
    private var voiceJob: Job? = null
    private val voiceHandler = Handler(Looper.getMainLooper())
    private var voiceTimeout: Runnable? = null
    private data class DestinationChoice(val candidate: RestroomCandidate, val index: Int, val listId: String,
        val target: String, val facility: CachedFacility)
    private var pendingDestination by mutableStateOf<DestinationChoice?>(null)
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
        navigation = ViewModelProvider(this)[SosNavigationViewModel::class.java]
        if(savedInstanceState == null) acceptVoiceIntent(intent) else GleoSosContract.takeForwarded(intent)
        Log.i("SHSH_SOS", "Public-data probe opened; no automatic requests")
        setContent {
            val state by model.uiState.collectAsStateWithLifecycle()
            val navigationState by navigation.state.collectAsStateWithLifecycle()
            BackHandler(enabled = showDiagnostics || showData || selected != null) {
                when { showDiagnostics -> showDiagnostics = false; selected != null -> selected = null; else -> { model.cancel(); showData = false } }
            }
            PleosSdkPlaygroundTheme {
                if (!showDiagnostics && !showData) {
                    SosScreen(state, allowed, selected, onSelect = {
                        if(!navigationState.busy) { cancelVoice("manual_selection"); pendingDestination=null; navigation.reset(); selected=it }
                    },
                        onFind = {
                            if (allowed && !navigationState.busy) {
                                cancelVoice("manual_search"); pendingDestination=null; navigation.reset(); selected = null
                                if (ContextCompat.checkSelfPermission(this@PublicDataProbeActivity, LocationRepository.PERMISSION) == PackageManager.PERMISSION_GRANTED) model.findNearby()
                                else locationPermission.launch(LocationRepository.PERMISSION)
                            }
                        }, onCancel = { cancelVoice("user_cancelled"); pendingDestination=null; model.cancel() },
                        onData = { if(!navigationState.busy) { cancelVoice("screen_changed"); model.cancel(); showData = true } }, onHome = { finish() },
                        navigation=navigationState, onNavigate={ target ->
                            if(allowed && !navigationState.busy) {
                                cancelVoice("manual_navigation")
                                pendingDestination = chooseDestination(null,target,null)
                                if(pendingDestination==null) navigation.report("목록이 바뀌었거나 오래됐어요. 다시 찾은 뒤 목적지를 선택해 주세요.")
                            }
                        })
                    pendingDestination?.takeIf { allowed }?.let { choice ->
                        AlertDialog(onDismissRequest={pendingDestination=null},
                            title={Text(if(choice.target=="PARKING") "이 주차장으로 안내할까요?" else "이 화장실로 안내할까요?")},
                            text={Text("${choice.facility.name}\n${choice.facility.address}\n\n기존 내비 목적지가 변경될 수 있어요. 등록된 시설 좌표를 전달하며 실제 진입로·개방·주차 빈자리는 확인되지 않았어요.")},
                            confirmButton={TextButton(onClick={
                                pendingDestination=null
                                if(allowed && choiceIsCurrent(choice)) navigation.send(choice.facility,{allowed && resumed && choiceIsCurrent(choice)})
                                else navigation.report("목적지를 다시 선택해 주세요. 현재 목록과 차량 허용 상태를 확인하세요.")
                            }){Text("내비로 보내기")}},
                            dismissButton={TextButton(onClick={pendingDestination=null}){Text("취소")}})
                    }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptVoiceIntent(intent)
        startPendingVoice()
    }

    private fun acceptVoiceIntent(incoming: Intent) {
        val request=GleoSosContract.takeForwarded(incoming) ?: return
        if(voiceRequest!=null || navigation.state.value.busy) {
            sendGleoReply(this,GleoSosContract.reply("fail","An SOS action is already in progress.","action_busy"),request.diagnostic)
            return
        }
        showData=false; showDiagnostics=false
        voiceRequest=request
        val remaining=request.deadline-SystemClock.elapsedRealtime()
        if(remaining<=0 || remaining>6_000) { completeVoice(GleoSosContract.reply("fail","The SOS action has expired.","action_expired"));return }
        voiceTimeout=Runnable {
            if(voiceRequest?.id==request.id) cancelVoice("action_timeout")
        }.also { voiceHandler.postDelayed(it,remaining) }
        Log.i("SHSH_GLEO","SOS command received; operation=${request.operation}; values omitted")
    }

    private fun startPendingVoice() {
        val request=voiceRequest ?: return
        if(!allowed || !resumed || voiceJob?.isActive==true) return
        voiceJob=lifecycleScope.launch {
            try {
                withTimeout((request.deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
                    when(request.operation) {
                        "FIND" -> {
                            if(ContextCompat.checkSelfPermission(this@PublicDataProbeActivity,LocationRepository.PERMISSION)!=PackageManager.PERMISSION_GRANTED) {
                                completeVoice(GleoSosContract.reply("fail","Allow location permission in the SOS screen before finding restrooms.","location_permission"));return@withTimeout
                            }
                            model.uiState.first { !it.cacheLoading }
                            if(model.uiState.value.busy) { completeVoice(GleoSosContract.reply("fail","Facility data is busy. Try again after the current task.","data_busy"));return@withTimeout }
                            selected=null;pendingDestination=null;navigation.reset()
                            model.findNearby()
                            val state=model.uiState.first { !it.busy }
                            if(!state.nearbyCompleted || state.nearbyError!=null) completeVoice(GleoSosContract.reply("fail","Nearby restroom lookup could not be completed. Check location and prepared facility data.","nearby_failed"))
                            else completeVoice(GleoSosContract.results(state))
                        }
                        "READ" -> {
                            val state=model.uiState.value
                            if(!state.nearbyCompleted) completeVoice(GleoSosContract.reply("asking","Find nearby restrooms first to prepare a current candidate list.","results_unavailable"))
                            else completeVoice(GleoSosContract.results(state,selectedIndex()))
                        }
                        "SELECT", "NAVIGATE" -> handleVoiceSelection(request)
                    }
                }
            } catch (_: TimeoutCancellationException) { cancelVoice("action_timeout") }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                Log.w("SHSH_GLEO","SOS action failed; type=${error.javaClass.simpleName}; values omitted")
                completeVoice(GleoSosContract.reply("fail","The SOS action could not be completed.","sos_failed"))
            }
        }
    }

    private fun selectedIndex(): Int? = selected?.let { candidate ->
        model.uiState.value.candidates.indexOf(candidate).takeIf { it>=0 }?.plus(1)
    }

    private fun chooseDestination(index: Int?,target: String?,listId: String?): DestinationChoice? {
        val state=model.uiState.value
        if(state.busy || !state.nearbyCompleted || state.candidateSetId==null) return null
        val elapsed=SystemClock.elapsedRealtime()-state.candidateSetCreatedAtMillis
        if(state.candidateSetCreatedAtMillis<=0 || elapsed !in 0..600_000L) return null
        if(listId!=null && listId!=state.candidateSetId) return null
        val number=index ?: selectedIndex() ?: return null
        val candidate=state.candidates.getOrNull(number-1) ?: return null
        val kind=target ?: "RESTROOM"
        val facility=if(kind=="PARKING") candidate.parking ?: return null else candidate.restroom
        if(!validFacilityCoordinates(facility.latitude,facility.longitude)) return null
        return DestinationChoice(candidate,number,state.candidateSetId,kind,facility)
    }

    private fun choiceIsCurrent(choice: DestinationChoice): Boolean {
        val state=model.uiState.value
        val elapsed=SystemClock.elapsedRealtime()-state.candidateSetCreatedAtMillis
        return !state.busy && state.candidateSetId==choice.listId && elapsed in 0..600_000L && state.candidates.getOrNull(choice.index-1)==choice.candidate
    }

    private suspend fun handleVoiceSelection(request: GleoSosContract.Request) {
        val previous=pendingDestination
        val choice=chooseDestination(request.index ?: previous?.index,request.target ?: previous?.target,request.listId)
        if(choice==null) {
            completeVoice(GleoSosContract.reply("asking","Select an available candidate number from the current SOS results. A parking destination requires a linked parking lot.","candidate_unavailable",GleoSosContract.results(model.uiState.value).data));return
        }
        selected=choice.candidate
        if(request.operation=="SELECT") {
            navigation.reset()
            pendingDestination=null
            completeVoice(GleoSosContract.results(model.uiState.value,choice.index));return
        }
        if(!request.confirmed || previous!=choice || !choiceIsCurrent(choice)) {
            pendingDestination=choice
            val data=org.json.JSONArray().put(org.json.JSONObject().put("index",choice.index).put("listId",choice.listId)
                .put("target",choice.target).put("name",choice.facility.name.take(200)).put("missingField","confirmed").put("confirmationRequired",true)).toString()
            completeVoice(GleoSosContract.reply("asking","Confirm navigation to ${choice.facility.name.take(200)}. This may replace the current destination. Facility opening, parking vacancy and road entrance are unverified.","navigation_confirmation",data));return
        }
        pendingDestination=null
        val budget=(request.deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)
        val outcome=suspendCancellableCoroutine<Pair<Boolean,String>> { continuation ->
            continuation.invokeOnCancellation { navigation.cancelWaiting() }
            navigation.send(choice.facility,{allowed && resumed && choiceIsCurrent(choice)},budget) { success,reason ->
                if(continuation.isActive) continuation.resume(success to reason)
            }
        }
        completeVoice(GleoSosContract.reply(if(outcome.first) "success" else "fail",
            if(outcome.first) "The selected destination was confirmed by Navigation. Route completion and guidance start are not confirmed."
            else "The navigation destination could not be confirmed. It may already have been requested; check Navigation before retrying.",outcome.second))
    }

    private fun completeVoice(reply: GleoReply) {
        val request=voiceRequest ?: return
        voiceRequest=null
        voiceTimeout?.let(voiceHandler::removeCallbacks);voiceTimeout=null
        sendGleoReply(this,reply,request.diagnostic)
    }
    private fun cancelVoice(reason: String) {
        val active=voiceRequest!=null
        completeVoice(GleoSosContract.reply("fail","The SOS action was interrupted. Check the screen before retrying navigation.",reason))
        voiceJob?.cancel();voiceJob=null
        if(active) model.suspendWork()
    }

    override fun onResume() {
        super.onResume()
        resumed=true;allowed=false
        monitor=DrivingRestrictionsMonitor(this) { permit,_ ->
            val previouslyAllowed=allowed
            allowed=permit
            if(!permit) {
                // start() first reports unknown=false. Keep settled data until a real transition.
                if(previouslyAllowed) {
                    if(voiceJob?.isActive==true) cancelVoice("vehicle_restricted")
                    pendingDestination=null;selected=null;model.cancel();navigation.leave()
                }
            } else startPendingVoice()
        }.also { it.start() }
    }
    override fun onPause() {
        resumed=false;allowed=false
        // Navigation may open its own UI after an explicit request. Keep waiting for its response.
        if(!(voiceRequest?.operation=="NAVIGATE" && navigation.state.value.sent && navigation.state.value.busy)) cancelVoice("background")
        model.suspendWork();navigation.leave();monitor?.close();monitor=null
        super.onPause()
    }
    override fun onDestroy() {
        cancelVoice("screen_closed")
        super.onDestroy()
    }
    private fun formatDistance(meters: Double?): String = when {
        meters == null -> "확인 필요 · 위치 또는 시설 좌표 없음"
        meters < 1000 -> "${meters.roundToInt()}m"
        else -> "${String.format(java.util.Locale.KOREA, "%.1f", meters / 1000)}km"
    }
}
