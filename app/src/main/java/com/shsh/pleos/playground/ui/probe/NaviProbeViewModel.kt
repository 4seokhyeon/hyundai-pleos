package com.shsh.pleos.playground.ui.probe

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shsh.pleos.playground.data.NaviRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NaviProbeUiState(
    val busy: Boolean = false,
    val status: String = "연결 전",
    val detail: String = "",
    val connected: Boolean = false,
    val result: String = "내비 정보 미조회"
)

class NaviProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NaviRepository(application)
    private val mutableState = MutableStateFlow(NaviProbeUiState())
    val uiState = mutableState.asStateFlow()

    init { connect() }

    fun connect() {
        if (mutableState.value.busy) return
        mutableState.value = NaviProbeUiState(true, "연결 중", "Navi 서비스의 연결 콜백을 기다립니다")
        viewModelScope.launch {
            try {
                repository.connect()
                mutableState.value = NaviProbeUiState(
                    busy = true,
                    status = "Navi 연결 성공 · 경로 상태 조회 중",
                    detail = "연결 콜백 수신 완료 · 경로 상태 이벤트를 기다립니다",
                    connected = true,
                    result = "조회 중"
                )
                val route = repository.readRouteState()
                mutableState.value = NaviProbeUiState(
                    status = "내비 경로 상태 수신 성공",
                    detail = "onRouteStateInfo 수신 · 모드: ${route.naviMode}",
                    connected = true,
                    result = "경로 활성 여부: ${route.isRouting}"
                )
            } catch (error: TimeoutCancellationException) {
                val connected = mutableState.value.connected
                mutableState.value = NaviProbeUiState(
                    status = if (connected) "연결 성공 · 경로 상태 응답 없음" else "연결 응답 시간 초과",
                    detail = if (connected) "10초 내 onRouteStateInfo 미수신 · 내비 앱 상태와 구현을 확인해야 합니다" else "15초 내 연결 콜백 없음",
                    connected = connected
                )
                Log.e("SHSH_NAVI", if (connected) "Route state timed out; no event within 10 seconds" else "Connection timed out after 15 seconds", error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = NaviProbeUiState(
                    status = if (error is SecurityException) "권한 오류" else "연결 또는 조회 실패 · 원인 확인 필요",
                    detail = error.message ?: error.javaClass.simpleName,
                    connected = mutableState.value.connected
                )
                Log.e("SHSH_NAVI", "Navi connection/route probe failed", error)
            }
        }
    }

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
