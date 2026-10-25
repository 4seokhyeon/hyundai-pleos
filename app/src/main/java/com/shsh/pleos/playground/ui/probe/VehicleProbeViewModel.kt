package com.shsh.pleos.playground.ui.probe

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shsh.pleos.playground.data.DoorUnsupportedException
import com.shsh.pleos.playground.data.VehicleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProbeUiState(
    val busy: Boolean = false,
    val status: String = "연결 전",
    val detail: String = "",
    val result: String = "미조회"
)

class VehicleProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VehicleRepository(application)
    private val mutableState = MutableStateFlow(ProbeUiState(status = "연결 전", result = "미조회"))
    val uiState = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.value = ProbeUiState(true, "연결 및 지원 확인 중", "SDK 초기화 후 실제 API 응답을 기다립니다", "조회 중")
            Log.i("SHSH_VM", "Door probe started")
            try {
                repository.initialize()
                val reading = repository.readDoor()
                val result = reading.value?.let { "문 위치 원시값: $it" } ?: "현재값 없음 (null)"
                mutableState.value = ProbeUiState(
                    status = if (reading.value != null) "차량 상태 조회 성공" else "API 응답 수신 · 현재값 없음",
                    detail = "접근: ${reading.access} · 지원 범위: ${reading.min}..${reading.max}",
                    result = result
                )
                Log.i("SHSH_VM", "Door probe finished; valueAvailable=${reading.value != null}")
            } catch (error: TimeoutCancellationException) {
                fail("응답 시간 초과", "10초 안에 응답하지 않았습니다. Pleos 상태를 확인하고 다시 조회하세요", error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // VehicleException/ErrorCode are not publicly referenceable in the published 2.3.2 AAR.
                // Keep the SDK's Exception detail instead of inferring a category from its text.
                val status = when (error) {
                    is DoorUnsupportedException -> "지원되지 않는 기능 또는 영역"
                    is SecurityException -> "권한 부족"
                    else -> "SDK 연결 또는 조회 실패 · 원인 확인 필요"
                }
                fail(status, error.message ?: error.javaClass.simpleName, error)
            }
        }
    }

    private fun fail(status: String, detail: String, error: Exception) {
        mutableState.value = ProbeUiState(status = status, detail = detail, result = "조회 결과 없음")
        Log.e("SHSH_ERROR", "Door probe failed: $status", error)
    }

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
