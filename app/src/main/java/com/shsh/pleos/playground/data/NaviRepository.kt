package com.shsh.pleos.playground.data

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.helper.NaviHelper
import java.util.concurrent.atomic.AtomicBoolean
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
