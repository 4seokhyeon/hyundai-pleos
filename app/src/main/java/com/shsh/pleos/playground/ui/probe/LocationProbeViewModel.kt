package com.shsh.pleos.playground.ui.probe

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shsh.pleos.playground.data.LocationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocationProbeUiState(
    val busy: Boolean = false,
    val supported: Boolean? = null,
    val permissionGranted: Boolean = false,
    val status: String = "확인 전",
    val detail: String = "",
    val result: String = "위치 미조회"
)

class LocationProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocationRepository(application)
    private val mutableState = MutableStateFlow(LocationProbeUiState())
    val uiState = mutableState.asStateFlow()
    private var queryJob: Job? = null

    fun refresh() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, status = "지원 여부·권한 확인 중")
        viewModelScope.launch {
            try {
                val result = repository.inspectPrerequisites()
                mutableState.value = LocationProbeUiState(
                    supported = result.supported,
                    permissionGranted = result.permissionGranted,
                    status = when {
                        !result.supported -> "지원 확인 실패 또는 미지원 환경"
                        !result.permissionGranted -> "서비스 패키지 확인 · 위치 권한 필요"
                        else -> "지원·권한 조건 확인 완료"
                    },
                    detail = if (result.supported) "위치 조회 버튼을 누르면 SDK 초기화 후 마지막 위치를 요청합니다"
                        else "isSupported=false · 패키지 미설치 또는 조회 실패를 확인해야 합니다"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = LocationProbeUiState(status = "조건 확인 오류", detail = error.message ?: error.javaClass.simpleName)
                Log.e("SHSH_LOCATION", "Prerequisite inspection failed", error)
            }
        }
    }

    fun queryLastLocation() {
        val current = mutableState.value
        if (current.busy || queryJob?.isActive == true || current.supported != true || !current.permissionGranted) return
        mutableState.value = current.copy(busy = true, status = "마지막 위치 조회 중", detail = "최대 10초 동안 실제 응답을 기다립니다", result = "조회 중")
        queryJob = viewModelScope.launch {
            try {
                val location = repository.readLastKnownLocation()
                mutableState.update { it.copy(
                    busy = false,
                    status = if (location == null) "응답 수신 · 마지막 위치 없음" else "마지막 위치 수신 성공",
                    detail = "마지막으로 저장된 위치이며, 현재 시점의 새 측위 결과는 아닙니다.",
                    result = location?.let { value ->
                        "위도: ${value.latitude}\n경도: ${value.longitude}\n측정 시각(epoch ms): ${value.timestampMillis}"
                    } ?: "LocationData = null · 위치 데이터가 없거나 제공되지 않은 상태"
                ) }
            } catch (error: TimeoutCancellationException) {
                mutableState.update { it.copy(busy = false, status = "위치 응답 시간 초과", detail = "10초 내 콜백 없음 · 서비스 상태 확인 필요", result = "위치 미수신") }
                Log.e("SHSH_LOCATION", "Last location request timed out after 10 seconds")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(
                    busy = false,
                    status = if (error is SecurityException) "위치 권한 오류" else "위치 조회 실패 · 원인 확인 필요",
                    detail = "오류 종류: ${error.javaClass.simpleName} · 지원 여부와 권한을 다시 확인하세요",
                    result = "위치 미수신"
                ) }
                Log.e("SHSH_LOCATION", "Location query failed: type=${error.javaClass.simpleName}; details omitted")
            }
        }
    }

    fun cancelQuery() {
        if (queryJob?.isActive != true) return
        queryJob?.cancel()
        repository.close()
        mutableState.update { it.copy(busy = false, status = "위치 조회 취소", detail = "화면 비활성화로 진행 중인 요청을 취소했습니다", result = "위치 미조회") }
        Log.i("SHSH_LOCATION", "Location query cancelled by lifecycle")
    }

    override fun onCleared() {
        queryJob?.cancel()
        repository.close()
        super.onCleared()
    }

    fun onPermissionResult(granted: Boolean) {
        Log.i("SHSH_LOCATION", "Runtime permission result: granted=$granted")
        refresh()
    }
}
