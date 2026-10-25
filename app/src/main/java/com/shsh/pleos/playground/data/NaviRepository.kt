package com.shsh.pleos.playground.data

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
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
