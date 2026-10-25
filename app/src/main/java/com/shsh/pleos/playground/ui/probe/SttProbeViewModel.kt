package com.shsh.pleos.playground.ui.probe

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shsh.pleos.playground.data.SttRepository
import com.shsh.pleos.playground.data.SttSdkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SttProbeUiState(
    val initializing: Boolean = false,
    val ready: Boolean = false,
    val listening: Boolean = false,
    val status: String = "초기화 전",
    val detail: String = "",
    val text: String = ""
)

class SttProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SttRepository(application)
    private val mutableState = MutableStateFlow(SttProbeUiState())
    val uiState = mutableState.asStateFlow()
    private var recognitionJob: Job? = null

    init { initialize() }

    fun initialize() {
        if (mutableState.value.initializing || recognitionJob?.isActive == true) return
        mutableState.value = SttProbeUiState(initializing = true, status = "STT 초기화 중")
        viewModelScope.launch {
            try {
                repository.initialize()
                mutableState.value = SttProbeUiState(ready = true, status = "STT 초기화 성공", detail = "인식 시작 버튼을 누를 때만 마이크 입력을 요청합니다")
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) fail("초기화 시간 초과", error, false)
                else throw error
            } catch (error: Exception) { fail("STT 초기화 실패", error, false) }
        }
    }

    fun recognize() {
        if (!mutableState.value.ready || mutableState.value.initializing || recognitionJob?.isActive == true) return
        mutableState.update { it.copy(listening = true, status = "음성 인식 요청 중", detail = "최대 20초 · 중지 버튼으로 취소 가능", text = "") }
        recognitionJob = viewModelScope.launch {
            try {
                val result = repository.recognize { status, text ->
                    mutableState.update { it.copy(status = status, text = text ?: it.text) }
                }
                mutableState.update { it.copy(
                    listening = false,
                    status = when {
                        result.completed && result.text.isNotBlank() -> "최종 인식 결과 수신 성공"
                        result.completed -> "최종 결과 수신 · 텍스트 없음"
                        else -> "인식 종료 · 최종 결과 미수신"
                    },
                    detail = "인식 결과는 아래 화면에서 확인하세요. 차량 명령은 실행하지 않습니다.",
                    text = result.text
                ) }
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) fail("인식 시간 초과", error, true)
                else throw error
            } catch (error: Exception) { fail("음성 인식 실패", error, true) }
        }
    }

    fun stop() {
        if (recognitionJob?.isActive != true) return
        recognitionJob?.cancel()
        repository.stop()
        mutableState.update { it.copy(listening = false, status = "인식 취소", detail = "사용자 또는 화면 비활성화로 중지했습니다") }
        Log.i("SHSH_STT", "Stop requested by UI/lifecycle")
    }

    private fun fail(status: String, error: Exception, ready: Boolean) {
        val detail = when (error) {
            is SttSdkException -> "SDK 오류: ${error.type.name}"
            is TimeoutCancellationException -> "제한 시간 내 결과 없음 · 마이크 입력과 서비스 상태 확인 필요"
            else -> error.message ?: error.javaClass.simpleName
        }
        mutableState.update { it.copy(initializing = false, listening = false, ready = ready, status = status, detail = detail) }
        Log.e("SHSH_STT", "$status: $detail", error)
    }

    override fun onCleared() {
        recognitionJob?.cancel()
        repository.close()
        super.onCleared()
    }
}
