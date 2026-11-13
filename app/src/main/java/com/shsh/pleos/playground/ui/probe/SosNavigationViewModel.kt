package com.shsh.pleos.playground.ui.probe

import android.app.Application
import android.util.Log
import ai.pleos.playground.navi.constants.NaviMode
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shsh.pleos.playground.data.CachedFacility
import com.shsh.pleos.playground.data.NaviRepository
import com.shsh.pleos.playground.data.RestroomCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

data class SosNavigationState(val busy: Boolean = false, val sent: Boolean = false,
    val confirmed: Boolean = false, val message: String = "목적지를 선택해 주세요")

class SosNavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NaviRepository(application)
    private val mutable = MutableStateFlow(SosNavigationState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    private data class CandidateEta(val candidate: RestroomCandidate, val driveSeconds: Int, val walkSeconds: Int) {
        val score: Long get() = driveSeconds.toLong() + walkSeconds
    }

    fun recommend(candidates: List<RestroomCandidate>, isCurrent: () -> Boolean,
        onRecommended: (RestroomCandidate, NaviRepository.SosRoute) -> Unit) {
        if (mutable.value.busy || !isCurrent()) return
        val options = candidates.filter { it.parking != null && it.parkingDistanceMeters != null }
        if (options.isEmpty()) {
            report("연결 주차장이 있는 화장실 후보가 없어요.")
            return
        }
        mutable.value = SosNavigationState(busy = true, message = "내비에서 후보별 예상 운전 시간을 확인 중")
        job = viewModelScope.launch {
            try {
                withTimeout(20_000) {
                    repository.connect()
                    val route = repository.readActiveSosRoute()
                        ?: throw WaypointUnavailable("내비 안내를 시작한 뒤 후보를 비교해 주세요.")
                    if (route.waypoints.size >= 2)
                        throw WaypointUnavailable("현재 경로에 추가할 수 있는 경유지 슬롯이 없어요.")
                    val eligible = options.filter {
                        val parking = checkNotNull(it.parking)
                        !route.destination.matches(parking) && route.waypoints.none { stop -> stop.matches(parking) }
                    }
                    if (eligible.isEmpty()) throw WaypointUnavailable("후보 주차장은 이미 현재 경로에 있어요.")
                    val etaByParking = mutableMapOf<CachedFacility, Int?>()
                    val ranked = eligible.mapNotNull { candidate ->
                        if (!isCurrent()) throw CancellationException("candidate set changed")
                        val parking = checkNotNull(candidate.parking)
                        val eta = if (etaByParking.containsKey(parking)) etaByParking[parking] else {
                            withTimeoutOrNull(2_000) { repository.readParkingEtaSeconds(parking) }
                                .also { etaByParking[parking] = it }
                        }
                        eta ?: return@mapNotNull null
                        // Straight-line distance at an assumed walking speed, not a pedestrian route.
                        CandidateEta(candidate, eta, ceil(checkNotNull(candidate.parkingDistanceMeters) / 1.2).toInt())
                    }
                    Log.i("SHSH_NAVI", "SOS ETA comparison; matched=${ranked.size}, eligible=${eligible.size}; values omitted")
                    val best = ranked.minWithOrNull(compareBy<CandidateEta> { it.score }.thenBy { it.walkSeconds })
                        ?: throw WaypointUnavailable("후보 주차장의 예상 운전 시간을 확인하지 못했어요. 목록에서 직접 선택할 수 있어요.")
                    if (route != repository.readActiveSosRoute())
                        throw WaypointUnavailable("내비 경로가 바뀌었어요. 다시 비교해 주세요.")
                    if (!isCurrent()) throw CancellationException("candidate set changed")
                    val driveMinutes = ceil(best.driveSeconds / 60.0).toInt()
                    val walkMeters = checkNotNull(best.candidate.parkingDistanceMeters).toInt()
                    mutable.update { it.copy(message =
                        "운전 시간 확인 ${ranked.size}/${eligible.size}곳 중 추천 · 주차장까지 약 ${driveMinutes}분 · 화장실까지 직선 ${walkMeters}m") }
                    onRecommended(best.candidate, route)
                }
            } catch (error: WaypointUnavailable) {
                mutable.update { it.copy(message = error.hint) }
            } catch (error: TimeoutCancellationException) {
                mutable.update { it.copy(message = "예상 운전 시간 조회가 지연됐어요. 목록에서 직접 선택할 수 있어요.") }
            } catch (error: CancellationException) {
                mutable.update { it.copy(message = "후보 비교를 중지했어요.") }
                throw error
            } catch (error: Exception) {
                Log.w("SHSH_NAVI", "SOS ETA comparison failed; type=${error.javaClass.simpleName}; values omitted")
                mutable.update { it.copy(message = "내비 예상 운전 시간을 확인하지 못했어요. 목록에서 직접 선택할 수 있어요.") }
            } finally {
                repository.close()
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun send(destination: CachedFacility, maySend: () -> Boolean, budgetMillis: Long = 20_000,
        asWaypoint: Boolean = false, expectedRoute: NaviRepository.SosRoute? = null,
        onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (mutable.value.busy) { onResult(false, "navigation_busy"); return }
        if (expectedRoute != null && !asWaypoint) {
            report("추천 주차장은 경유지로만 추가할 수 있어요.")
            onResult(false, "recommendation_waypoint_only")
            return
        }
        mutable.value = SosNavigationState(busy = true, message = "내비 서비스 연결 중")
        job = viewModelScope.launch {
            var sendAsWaypoint = asWaypoint
            try {
                withTimeout(budgetMillis.coerceAtLeast(1)) {
                    repository.connect()
                    val state = repository.readRouteState()
                    // A new destination is only allowed when Navigation explicitly
                    // reports its no-route mode. Active or ambiguous states never fall back.
                    val noActiveRoute = !state.isRouting && state.naviMode == NaviMode.SAFE_GUIDING
                    sendAsWaypoint = asWaypoint || !noActiveRoute
                    Log.i("SHSH_NAVI", "SOS route dispatch choice; waypoint=$sendAsWaypoint, mode=${state.naviMode}, active=${state.isRouting}")
                    if (sendAsWaypoint) {
                        val route = repository.readActiveSosRoute(state)
                        val problem = when {
                            route == null && noActiveRoute -> "내비에서 목적지 안내를 시작한 뒤 다시 시도해 주세요."
                            route == null -> "현재 내비 경로를 확인할 수 없어 변경하지 않았어요."
                            expectedRoute != null && route != expectedRoute ->
                                "추천을 받은 뒤 내비 경로가 바뀌었어요. 후보를 다시 비교해 주세요."
                            route.destination.matches(destination) || route.waypoints.any { it.matches(destination) } ->
                                "선택한 장소는 이미 현재 경로에 있어요."
                            route.waypoints.size >= 2 -> "추가할 수 있는 경유지 슬롯이 없어요."
                            else -> null
                        }
                        if (problem != null) throw WaypointUnavailable(problem)
                        repository.addSosWaypoint(destination, checkNotNull(route), maySend) {
                            mutable.update { it.copy(sent = true, message = "경유지 추가 요청 · 내비 응답 확인 중") }
                        }
                    } else {
                        repository.requestDestination(destination, maySend) {
                            mutable.update { it.copy(sent = true, message = "목적지 전달 요청 · 내비 응답 확인 중") }
                        }
                    }
                }
                mutable.update { it.copy(confirmed = true, message = if (sendAsWaypoint)
                    "선택한 장소를 다음 경유지로 추가했어요 · 기존 목적지와 경유지 유지"
                    else "내비 목적지 확인 완료 · 경로와 안내 시작 여부는 내비에서 확인하세요") }
                onResult(true, if (sendAsWaypoint) "waypoint_confirmed" else "destination_confirmed")
            } catch (error: WaypointUnavailable) {
                mutable.update { it.copy(message = error.hint) }
                onResult(false, "waypoint_unavailable")
            } catch (error: TimeoutCancellationException) {
                mutable.update { it.copy(message = if (it.sent && sendAsWaypoint) "경유지 추가 여부를 확인하지 못했어요. 내비에서 확인한 뒤 다시 시도하세요." else if (it.sent) "목적지를 요청했지만 확인 응답이 없어요. 내비에서 확인한 뒤 다시 시도하세요." else "내비 연결 시간이 초과됐어요. 내비 앱 상태를 확인하세요.") }
                onResult(false, "navigation_unconfirmed")
            } catch (error: CancellationException) {
                mutable.update { it.copy(message = if (sendAsWaypoint) "경유지 추가 확인이 중단됐어요. 내비에서 상태를 확인하세요." else if (it.sent) "응답 확인이 중단됐어요. 이미 요청한 목적지는 내비에서 확인하세요." else "목적지 전달을 중지했어요.") }
                onResult(false, "navigation_cancelled")
                throw error
            } catch (error: Exception) {
                val reason = if (error is SecurityException) "navigation_permission" else "navigation_error"
                mutable.update { it.copy(message = if (error is SecurityException) "내비 경로 요청 권한을 확인해 주세요." else if (sendAsWaypoint) "경유지 추가 여부를 확인하지 못했어요. 내비에서 현재 경로를 확인하세요." else "내비에서 목적지 전달을 확인하지 못했어요. 내비 앱과 경로 요청 지원 상태를 확인하세요.") }
                Log.w("SHSH_NAVI", "SOS navigation failed; reason=$reason, type=${error.javaClass.simpleName}; values omitted")
                onResult(false, reason)
            } finally {
                repository.close()
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private class WaypointUnavailable(val hint: String) : Exception()

    fun report(message: String) {
        if (!mutable.value.busy) mutable.value = SosNavigationState(message = message)
    }
    fun reset() { if (!mutable.value.busy) mutable.value = SosNavigationState() }

    /** Stop pending work before dispatch. An already sent route is never silently cancelled. */
    fun leave() { if (!mutable.value.sent) job?.cancel() }
    fun cancelWaiting() { job?.cancel() }
    override fun onCleared() { job?.cancel(); repository.close(); super.onCleared() }
}
