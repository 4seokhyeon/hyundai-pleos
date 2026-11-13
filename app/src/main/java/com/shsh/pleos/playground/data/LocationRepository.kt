package com.shsh.pleos.playground.data

import android.content.Context
import android.content.pm.PackageManager
import com.shsh.pleos.playground.platform.AppLog as Log
import androidx.core.content.ContextCompat
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocationPrerequisites(val supported: Boolean, val permissionGranted: Boolean)

class LocationRepository(context: Context) {
    private val appContext = context.applicationContext
    private var active: FusedLocation? = null

    suspend fun inspectPrerequisites(): LocationPrerequisites = withContext(Dispatchers.IO) {
        // isSupported performs a package lookup; it does not initialize or bind the service.
        val supported = FusedLocation(appContext).isSupported()
        val granted = ContextCompat.checkSelfPermission(appContext, PERMISSION) == PackageManager.PERMISSION_GRANTED
        Log.i("SHSH_LOCATION", "Prerequisites: isSupported=$supported, permissionGranted=$granted; location not requested")
        LocationPrerequisites(supported, granted)
    }

    suspend fun readLastKnownLocation(): LocationData? {
        check(active == null) { "위치 조회가 이미 진행 중입니다" }
        val conditions = inspectPrerequisites()
        check(conditions.supported) { "FusedLocation 지원 여부를 확인하지 못했습니다" }
        if (!conditions.permissionGranted) throw SecurityException("위치 권한이 필요합니다")
        val instance = FusedLocation(appContext)
        active = instance
        try {
            Log.i("SHSH_LOCATION", "FusedLocation.initialize requested; SDK=1.1.3")
            instance.initialize()
            Log.i("SHSH_LOCATION", "initialize returned; service response not confirmed yet")
            return withTimeout(10_000) {
                suspendCancellableCoroutine { continuation ->
                    val terminal = AtomicBoolean(false)
                    continuation.invokeOnCancellation { terminal.set(true) }
                    val listener = object : FusedLocationListener {
                        override fun onChanged(location: LocationData?) {
                            if (terminal.compareAndSet(false, true)) {
                                Log.i("SHSH_LOCATION", "Last location callback: hasLocation=${location != null}; coordinates omitted")
                                continuation.resume(location)
                            }
                        }

                        override fun onError(exception: Exception) {
                            if (terminal.compareAndSet(false, true)) {
                                // Do not print SDK exception messages that might contain location data.
                                Log.e("SHSH_LOCATION", "Last location error: type=${exception.javaClass.simpleName}; details omitted")
                                continuation.resumeWithException(exception)
                            }
                        }
                    }
                    Log.i("SHSH_LOCATION", "getLastKnownLocation requested")
                    instance.getLastKnownLocation(listener)
                }
            }
        } finally {
            release(instance)
        }
    }

    private fun release(instance: FusedLocation) {
        if (active !== instance) return
        active = null
        try {
            instance.release()
            Log.i("SHSH_LOCATION", "FusedLocation released")
        } catch (error: Exception) {
            Log.e("SHSH_LOCATION", "Release failed: type=${error.javaClass.simpleName}; details omitted")
        }
    }

    fun close() { active?.let(::release) }

    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
