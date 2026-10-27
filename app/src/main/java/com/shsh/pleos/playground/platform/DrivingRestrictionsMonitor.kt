package com.shsh.pleos.playground.platform

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Start/close on main thread. Unknown and disconnected states always deny playback. */
class DrivingRestrictionsMonitor(private val context: android.content.Context, private val onChanged: (Boolean, String) -> Unit) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var closed = false

    fun start() {
        onChanged(false, "차량 제한 상태 연결 중")
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (closed) return@createCar
                if (!ready) {
                    manager = null
                    onChanged(false, "차량 서비스 연결 해제 · 재생 중지")
                } else {
                    try {
                        val next = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        manager = next
                        next.registerListener { restrictions ->
                            // Conservative probe: allow only when distraction optimization is not required.
                            val allowed = !restrictions.isRequiresDistractionOptimization
                            val detail = "requiresDO=${restrictions.isRequiresDistractionOptimization}, flags=${restrictions.activeRestrictions}"
                            Log.i("SHSH_UX", "Restrictions changed; allowed=$allowed, $detail")
                            onChanged(allowed, detail)
                        }
                        val current = next.currentCarUxRestrictions
                        onChanged(!current.isRequiresDistractionOptimization,
                            "requiresDO=${current.isRequiresDistractionOptimization}, flags=${current.activeRestrictions}")
                        Log.i("SHSH_UX", "Initial restrictions received")
                    } catch (error: Exception) {
                        Log.e("SHSH_UX", "Restriction query failed", error)
                        onChanged(false, "제한 상태 조회 실패")
                    }
                }
            }
        } catch (error: Exception) {
            Log.e("SHSH_UX", "Car connection failed", error)
            onChanged(false, "차량 제한 상태 확인 실패")
        }
    }

    fun close() {
        closed = true
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null; car = null
    }
}
