package com.shsh.pleos.playground.data

import android.content.Context
import com.shsh.pleos.playground.platform.AppLog as Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.constants.RoutePointType
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.helper.NaviHelper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NaviRepository(context: Context) {
    private val appContext = context.applicationContext
    private var helper: NaviHelper? = null
    private var routeListener: NaviHelperEventListener? = null

    suspend fun connect() {
        close()
        val instance = NaviHelper(appContext)
        helper = instance
        try {
            withTimeout(15_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val completed = AtomicBoolean(false)
                    continuation.invokeOnCancellation { completed.set(true) }
                    Log.i("SHSH_NAVI", "NaviHelper.initialize requested; SDK=3.0.5")
                    instance.initialize(
                        onConnected = {
                            if (completed.compareAndSet(false, true)) {
                                Log.i("SHSH_NAVI", "Navi service initial connection confirmed")
                                continuation.resume(Unit)
                            }
                        },
                        onFailed = { error ->
                            if (completed.compareAndSet(false, true)) {
                                Log.e("SHSH_NAVI", "Navi service connection failed", error)
                                continuation.resumeWithException(error)
                            }
                        }
                    )
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    suspend fun readRouteState(): RouteStateInfo {
        val instance = checkNotNull(helper) { "NaviHelper 연결 객체가 없습니다" }
        check(routeListener == null) { "이미 내비 상태를 조회하고 있습니다" }
        try {
            return withTimeout(10_000) {
                suspendCancellableCoroutine { continuation ->
                    val completed = AtomicBoolean(false)
                    continuation.invokeOnCancellation { completed.set(true) }
                    val listener = object : NaviHelperEventListener {
                        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                            if (completed.compareAndSet(false, true)) {
                                Log.i("SHSH_NAVI", "Route state event received: isRouting=${routeStateInfo.isRouting}, naviMode=${routeStateInfo.naviMode}")
                                continuation.resume(routeStateInfo)
                            }
                        }

                        override fun onError(errorCode: NaviErrorCode) {
                            if (completed.compareAndSet(false, true)) {
                                Log.e("SHSH_NAVI", "Navi error event while waiting: $errorCode")
                                continuation.resumeWithException(IllegalStateException("Navi 오류 이벤트: $errorCode"))
                            }
                        }
                    }
                    routeListener = listener
                    instance.addListener(listener)
                    Log.i("SHSH_NAVI", "getRouteStateInfo requested; waiting for onRouteStateInfo")
                    instance.getRouteStateInfo()
                }
            }
        } finally {
            removeRouteListener(instance)
        }
    }

    /** A matching destination callback confirms the destination, not that guidance has started. */
    suspend fun requestDestination(destination: CachedFacility, maySend: () -> Boolean, onSent: () -> Unit) {
        require(validFacilityCoordinates(destination.latitude, destination.longitude))
        val instance = checkNotNull(helper) { "NaviHelper is not connected" }
        check(routeListener == null)
        try {
            withTimeout(10_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val completed = AtomicBoolean(false)
                    val sent = AtomicBoolean(false)
                    val requestEventSeen = AtomicBoolean(false)
                    continuation.invokeOnCancellation { completed.set(true) }
                    val listener = object : NaviHelperEventListener {
                        private fun checkDestination() {
                            if (!completed.get() && sent.get()) {
                                try { instance.getDestinationInfo() }
                                catch (error: Exception) {
                                    if (completed.compareAndSet(false, true)) continuation.resumeWithException(error)
                                }
                            }
                        }
                        private fun requestEvent() {
                            if (sent.get()) { requestEventSeen.set(true); checkDestination() }
                        }
                        override fun onDestinationChanged() = requestEvent()
                        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = requestEvent()
                        override fun onRoutingCompleted() = requestEvent()
                        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                            if (!sent.get() || !requestEventSeen.get()) return
                            val distance = FacilityReference(destination.latitude, destination.longitude, 0)
                                .distanceMetersTo(destinationInfo.latitude, destinationInfo.longitude)
                            if (distance != null && distance <= 30 && destinationInfo.name.trim() == destination.name.trim()
                                && completed.compareAndSet(false, true)) {
                                Log.i("SHSH_NAVI", "SOS destination confirmed; matchingCoordinates=true; guidance completion not asserted")
                                continuation.resume(Unit)
                            }
                        }
                        override fun onError(errorCode: NaviErrorCode) {
                            if (sent.get() && completed.compareAndSet(false, true)) {
                                Log.w("SHSH_NAVI", "SOS route error; code=$errorCode; coordinates omitted")
                                continuation.resumeWithException(IllegalStateException("navi_error_$errorCode"))
                            }
                        }
                    }
                    routeListener = listener
                    instance.addListener(listener)
                    if (!maySend() || !continuation.isActive) {
                        completed.set(true)
                        continuation.cancel()
                        return@suspendCancellableCoroutine
                    }
                    // Public data has no Navigation-provider POI ID. Never invent an ID.
                    val route = RouteInfo(latitude = destination.latitude, longitude = destination.longitude,
                        poiName = destination.name, poiId = "", address = destination.address, poiSubId = "")
                    sent.set(true)
                    instance.requestRoute(route)
                    onSent()
                    Log.i("SHSH_NAVI", "SOS requestRoute dispatched; source=public_data_coordinates; poiIdPresent=false; values omitted")
                    if (!completed.get()) instance.getDestinationInfo()
                }
            }
        } finally { removeRouteListener(instance) }
    }

    data class SosStop(val name: String, val latitude: Double, val longitude: Double) {
        fun matches(facility: CachedFacility): Boolean =
            name.trim() == facility.name.trim() &&
                (FacilityReference(latitude, longitude, 0).distanceMetersTo(facility.latitude, facility.longitude)
                    ?: Double.POSITIVE_INFINITY) <= 30
    }

    data class SosRoute(val destination: SosStop, val waypoints: List<SosStop>)

    suspend fun readActiveSosRoute(): SosRoute? = readActiveSosRoute(readRouteState())

    suspend fun readActiveSosRoute(state: RouteStateInfo): SosRoute? {
        if (!state.isRouting && state.naviMode != NaviMode.ROUTE_GUIDING && state.naviMode != NaviMode.SIMULATION_GUIDING)
            return null
        val instance = checkNotNull(helper)
        check(routeListener == null)
        return try {
            suspendCancellableCoroutine<SosRoute?> { continuation ->
                val terminal = AtomicBoolean(false)
                continuation.invokeOnCancellation { terminal.set(true) }
                val destination = AtomicReference<SosStop?>()
                val waypoints = AtomicReference<List<SosStop>?>()
                fun complete() {
                    val stop = destination.get() ?: return
                    val points = waypoints.get() ?: return
                    if (terminal.compareAndSet(false, true)) continuation.resume(SosRoute(stop, points))
                }
                val listener = object : NaviHelperEventListener {
                    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                        if (!validFacilityCoordinates(destinationInfo.latitude, destinationInfo.longitude)) {
                            if (terminal.compareAndSet(false, true)) continuation.resume(null)
                            return
                        }
                        destination.set(SosStop(destinationInfo.name, destinationInfo.latitude, destinationInfo.longitude))
                        complete()
                    }
                    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
                        waypoints.set(waypointInfoList.map { SosStop(it.name, it.latitude, it.longitude) })
                        complete()
                    }
                    override fun onError(errorCode: NaviErrorCode) {
                        if (terminal.compareAndSet(false, true))
                            continuation.resumeWithException(IllegalStateException("navi_error_$errorCode"))
                    }
                }
                routeListener = listener
                instance.addListener(listener)
                instance.getDestinationInfo()
                instance.getWaypointInfo()
            }
        } finally { removeRouteListener(instance) }
    }

    /** Reads a driving ETA without setting a destination or changing the active route. */
    suspend fun readParkingEtaSeconds(parking: CachedFacility): Int? {
        require(validFacilityCoordinates(parking.latitude, parking.longitude))
        val instance = checkNotNull(helper)
        check(routeListener == null)
        return try {
            suspendCancellableCoroutine<Int?> { continuation ->
                val done = AtomicBoolean(false)
                continuation.invokeOnCancellation { done.set(true) }
                val listener = object : NaviHelperEventListener {
                    override fun onTripInfo(tripInfo: TripInfo) {
                        // The SDK callback has no request ID. Only accept a response
                        // whose destination matches the requested facility.
                        val stop = tripInfo.summary.routePoints.lastOrNull { it.pointType == RoutePointType.DESTINATION }
                            ?: return
                        val distance = FacilityReference(parking.latitude, parking.longitude, 0)
                            .distanceMetersTo(stop.latitude, stop.longitude)
                        if (distance == null || distance > 30) return
                        if (done.compareAndSet(false, true))
                            continuation.resume(tripInfo.eta.takeIf { it > 0 })
                    }

                    override fun onError(errorCode: NaviErrorCode) {
                        if (done.compareAndSet(false, true)) continuation.resume(null)
                    }
                }
                routeListener = listener
                instance.addListener(listener)
                instance.requestTrip(TripRequest(
                    latitude = parking.latitude, longitude = parking.longitude,
                    poiId = "", poiName = parking.name, poiSubId = null
                ))
            }
        } finally { removeRouteListener(instance) }
    }

    /** Confirms the new first waypoint and checks that all previous stops were preserved. */
    suspend fun addSosWaypoint(parking: CachedFacility, route: SosRoute, maySend: () -> Boolean, onSent: () -> Unit) {
        require(validFacilityCoordinates(parking.latitude, parking.longitude))
        val instance = checkNotNull(helper)
        check(routeListener == null)
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val terminal = AtomicBoolean(false)
                val sent = AtomicBoolean(false)
                val changeSeen = AtomicBoolean(false)
                val destinationMatches = AtomicBoolean(false)
                val waypointsMatch = AtomicBoolean(false)
                continuation.invokeOnCancellation { terminal.set(true) }
                fun complete() {
                    if (destinationMatches.get() && waypointsMatch.get() && terminal.compareAndSet(false, true))
                        continuation.resume(Unit)
                }
                val listener = object : NaviHelperEventListener {
                    override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
                        if (!sent.get() || terminal.get() || waypointChangedInfo.cmdType != WaypointCmdType.ADD ||
                            waypointChangedInfo.index != WaypointIndex.FIRST || !changeSeen.compareAndSet(false, true)) return
                        try {
                            instance.getDestinationInfo()
                            instance.getWaypointInfo()
                        } catch (error: Exception) {
                            if (terminal.compareAndSet(false, true)) continuation.resumeWithException(error)
                        }
                    }
                    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                        if (!changeSeen.get()) return
                        val actual = SosStop(destinationInfo.name, destinationInfo.latitude, destinationInfo.longitude)
                        destinationMatches.set(actual == route.destination)
                        if (actual != route.destination)
                            Log.w("SHSH_NAVI", "SOS addWaypoint destination mismatch; values omitted")
                        complete()
                    }
                    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
                        if (!changeSeen.get()) return
                        val actual = waypointInfoList.map { SosStop(it.name, it.latitude, it.longitude) }
                        waypointsMatch.set(actual.firstOrNull()?.matches(parking) == true && actual.drop(1) == route.waypoints)
                        complete()
                    }
                    override fun onError(errorCode: NaviErrorCode) {
                        if (sent.get() && terminal.compareAndSet(false, true))
                            continuation.resumeWithException(IllegalStateException("navi_error_$errorCode"))
                    }
                }
                routeListener = listener
                instance.addListener(listener)
                check(maySend() && continuation.isActive)
                sent.set(true)
                onSent()
                instance.addWaypoint(RequestWaypointInfo(
                    latitude = parking.latitude, longitude = parking.longitude, poiName = parking.name,
                    waypointIndex = WaypointIndex.FIRST, poiId = "", address = parking.address, poiSubId = ""
                ))
                Log.i("SHSH_NAVI", "SOS addWaypoint dispatched; verifying original destination; values omitted")
            }
        } finally { removeRouteListener(instance) }
    }

    private fun removeRouteListener(instance: NaviHelper) {
        val listener = routeListener ?: return
        routeListener = null
        try {
            instance.removeListener(listener)
            Log.i("SHSH_NAVI", "Route state listener removed")
        } catch (error: Exception) {
            Log.e("SHSH_NAVI", "Route state listener removal failed", error)
        }
    }

    fun close() {
        val instance = helper ?: return
        helper = null
        removeRouteListener(instance)
        try {
            instance.release()
            Log.i("SHSH_NAVI", "NaviHelper released")
        } catch (error: Exception) {
            Log.e("SHSH_NAVI", "NaviHelper release failed", error)
        }
    }
}
