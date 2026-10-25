package com.shsh.pleos.playground.data

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DoorReading(val access: String, val min: Int, val max: Int, val value: Int?)
class DoorUnsupportedException(message: String) : Exception(message)

class VehicleRepository(context: Context) {
    private val vehicle = Vehicle(context.applicationContext)
    private var initialized = false
    private val area = DoorArea(DoorValue.ROW_1_LEFT)

    // initialize() returning does not prove that the vehicle service is connected.
    fun initialize() {
        if (initialized) return
        Log.i("SHSH_SDK", "Vehicle.initialize requested; SDK=2.3.2")
        vehicle.initialize()
        initialized = true
        Log.i("SHSH_SDK", "initialize returned; waiting for API response")
    }

    suspend fun readDoor(): DoorReading = withTimeout(10_000) {
        val door = vehicle.getDoor()
        Log.i("SHSH_REPO", "checkDoorPositionCapabilities requested")
        val capability = suspendCancellableCoroutine<Pair<CapabilityConfig, Map<DoorArea, AreaBounds<Int>>>> { continuation ->
            door.checkDoorPositionCapabilities(
                onComplete = { config, areas ->
                    if (continuation.isActive) continuation.resume(config to areas)
                },
                onFailed = { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            )
        }
        val (config, areas) = capability
        val bounds = areas[area]
        Log.i("SHSH_REPO", "Door capability response: access=${config.accessType}, selectedAreaSupported=${bounds != null}")
        if (config.accessType != AccessType.READ && config.accessType != AccessType.READ_WRITE) {
            throw DoorUnsupportedException("문 위치 읽기 미지원: ${config.accessType}")
        }
        //
        if (bounds == null) throw DoorUnsupportedException("ROW_1_LEFT 영역이 지원 목록에 없습니다")
        val value = suspendCancellableCoroutine<Int?> { continuation ->
            door.getCurrentDoorPosition(
                area = area,
                onComplete = { position ->
                    if (continuation.isActive) continuation.resume(position)
                },
                onFailed = { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            )
        }
        Log.i("SHSH_REPO", "Door position response: area=ROW_1_LEFT, value=$value, range=${bounds.minValue}..${bounds.maxValue}")
        DoorReading(config.accessType.name, bounds.minValue, bounds.maxValue, value)
    }

    fun close() {
        try {
            vehicle.release()
            Log.i("SHSH_SDK", "Vehicle released")
        } catch (error: Exception) {
            Log.e("SHSH_ERROR", "Vehicle release failed", error)
        }
    }
}
