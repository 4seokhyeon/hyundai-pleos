package com.shsh.pleos.playground.ui.probe

import android.app.Application
import com.shsh.pleos.playground.platform.AppLog as Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shsh.pleos.playground.data.TtsRepository
import com.shsh.pleos.playground.data.TtsSdkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

 data class TtsProbeUiState(
    val busy: Boolean = false,
    val ready: Boolean = false,
    val status: String = "초기화 전",
    val detail: String = ""
)

class TtsProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TtsRepository(application)
    private val mutableState = MutableStateFlow(TtsProbeUiState())
    val uiState = mutableState.asStateFlow()
    private var speechJob: Job? = null

    init { initialize() }

    fun initialize() {
        if (mutableState.value.busy) return
        mutableState.value = TtsProbeUiState(busy = true, status = "TTS 초기화 중", detail = "ON_DEVICE · 초기화 콜백 대기")
        viewModelScope.launch {
            try {
                repository.initialize()
                mutableState.value = TtsProbeUiState(ready = true, status = "TTS 초기화 성공", detail = "음성 테스트 버튼을 눌러 실제 재생을 확인하세요")
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) fail("초기화 응답 시간 초과", error, false)
                else throw error
            } catch (error: Exception) {
                fail("TTS 초기화 실패", error, false)
            }
        }
    }

    fun speak() {
        if (mutableState.value.busy || !mutableState.value.ready) return
        mutableState.value = mutableState.value.copy(busy = true, status = "음성 요청 중", detail = "고정 문장 1회 재생")
        speechJob = viewModelScope.launch {
            try {
                val done = repository.speak { status -> mutableState.update { it.copy(status = status) } }
                mutableState.update { it.copy(busy = false, status = if (done) "재생 완료 이벤트 수신" else "재생 중지 이벤트 수신", detail = "실제로 음성이 들렸는지는 별도 확인이 필요합니다") }
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) fail("재생 응답 시간 초과", error, true)
                else throw error
            } catch (error: Exception) {
                fail("음성 출력 실패", error, true)
            }
        }
    }

    fun stop() {
        if (speechJob?.isActive != true) return
        speechJob?.cancel()
        repository.stop()
        mutableState.update { it.copy(busy = false, status = "재생 취소", detail = "중지 버튼 또는 화면 비활성화로 취소했습니다") }
        Log.i("SHSH_TTS", "Stop requested by UI/lifecycle")
    }

    private fun fail(status: String, error: Exception, ready: Boolean) {
        val detail = when (error) {
            is TtsSdkException -> "SDK 오류: ${error.type.name}"
            is TimeoutCancellationException -> "제한 시간 내 콜백 없음 · 서비스 상태 확인 필요"
            else -> error.message ?: error.javaClass.simpleName
        }
        mutableState.value = TtsProbeUiState(ready = ready, status = status, detail = detail)
        Log.e("SHSH_TTS", "$status: $detail", error)
    }

    override fun onCleared() {
        speechJob?.cancel()
        repository.close()
        super.onCleared()
    }
}
