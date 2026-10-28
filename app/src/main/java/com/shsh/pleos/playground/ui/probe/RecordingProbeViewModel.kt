package com.shsh.pleos.playground.ui.probe

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shsh.pleos.playground.data.ScoringRepository
import com.shsh.pleos.playground.data.ScoringException
import com.shsh.pleos.playground.data.ScoreResult
import kotlinx.coroutines.TimeoutCancellationException
import com.shsh.pleos.playground.data.RecordingRepository
import com.shsh.pleos.playground.data.RecordingResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecordingProbeUiState(
    val busy: Boolean = false,
    val exporting: Boolean = false,
    val scoring: Boolean = false,
    val inputKind: String? = null,
    val scoreStatus: String = "평가 전 · 파일 전송 버튼을 눌러야 전송됩니다",
    val score: ScoreResult? = null,
    val recording: Boolean = false,
    val playing: Boolean = false,
    val hasFile: Boolean = false,
    val status: String = "녹음 전 · 표준 Android API 지원 검증",
    val detail: String = "정차 상태에서만 테스트하세요. 자동 서버 전송은 하지 않습니다"
)

class RecordingProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RecordingRepository(application)
    private val scoringRepository = ScoringRepository()
    private var scoringJob: Job? = null
    private val mutableState = MutableStateFlow(RecordingProbeUiState())
    val uiState = mutableState.asStateFlow()
    private var job: Job? = null
    private var result: RecordingResult? = null
    fun hasPermission() = repository.hasPermission()

    fun permissionResult(granted: Boolean) {
        Log.i("SHSH_RECORD", "Runtime permission result; granted=$granted")
        mutableState.update { it.copy(status = if (granted) "권한 허용됨 · 녹음 시작을 다시 누르세요" else "마이크 권한 거절됨") }
    }

    fun start() = startRecording(null)

    fun startWithCondition(kind: String) {
        if (kind !in setOf("vocal_only", "mixed")) return
        startRecording(kind)
    }

    private fun startRecording(kind: String?) {
        if (mutableState.value.busy || mutableState.value.playing || mutableState.value.exporting || mutableState.value.scoring) return
        if (!hasPermission()) { permissionResult(false); return }
        repository.delete(result?.file); result = null
        mutableState.value = RecordingProbeUiState(busy = true, inputKind = kind, status = "마이크 초기화 중")
        Log.i("SHSH_RECORD", "Recording started by user; inputKind=${kind ?: "unknown"}")
        job = viewModelScope.launch {
            try {
                val saved = repository.record {
                    mutableState.update { it.copy(recording = true, status = "녹음 중 · 최대 15초") }
                }
                result = saved
                mutableState.value = RecordingProbeUiState(hasFile = true, inputKind = kind, status = "WAV 저장 완료 · 청취 확인 필요",
                    detail = "${"%.2f".format(saved.durationSeconds)}초 · 16kHz mono PCM16 · ${saved.file.length()} bytes\n파일: ${saved.file.name}\n피크: ${saved.peak} (0이면 무음 입력 확인 필요)")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                Log.e("SHSH_RECORD", "Recording failed; type=${error.javaClass.simpleName}", error)
                mutableState.value = RecordingProbeUiState(status = "녹음 실패", detail = error.message ?: "SHSH_RECORD 로그 확인")
            } finally {
                mutableState.update { it.copy(busy = false, recording = false) }
            }
        }
    }

    fun finish() { repository.requestStop(); Log.i("SHSH_RECORD", "User requested recording finish") }

    fun play() {
        val file = result?.file ?: return
        if (mutableState.value.busy || mutableState.value.playing || mutableState.value.exporting || mutableState.value.scoring) return
        mutableState.update { it.copy(playing = true, status = "녹음 재생 준비 중") }
        try {
            repository.play(file, { playbackEnded() }, { playbackEnded("재생 실패") })
        } catch (error: Exception) { playbackEnded("재생 실패"); Log.e("SHSH_RECORD", "Playback failed", error) }
    }

    private fun playbackEnded(status: String = "재생 종료 · 실제 목소리 청취 여부를 확인하세요") {
        mutableState.update { it.copy(playing = false, status = status) }
    }

    fun stopPlayback() { repository.stopPlayback(); playbackEnded("재생 중지") }
    fun delete() {
        if (mutableState.value.busy || mutableState.value.exporting || mutableState.value.scoring) return
        repository.stopPlayback(); repository.delete(result?.file); result = null
        mutableState.value = RecordingProbeUiState(status = "녹음 삭제됨")
        Log.i("SHSH_RECORD", "Local recording deleted")
    }


    fun export(uri: Uri) {
        val file = result?.file ?: return
        if (mutableState.value.busy || mutableState.value.playing || mutableState.value.exporting || mutableState.value.scoring) return
        mutableState.update { it.copy(exporting = true, status = "WAV 내보내기 중") }
        viewModelScope.launch {
            try {
                val copied = withContext(Dispatchers.IO) {
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: error("저장 위치를 열 수 없습니다")
                    stream.use { output -> file.inputStream().use { it.copyTo(output) } }
                }
                mutableState.update { it.copy(status = "WAV 내보내기 완료 · 파일을 직접 청취·평가하세요") }
                Log.i("SHSH_RECORD", "WAV export completed; bytes=$copied")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableState.update { it.copy(status = "WAV 내보내기 실패") }
                Log.e("SHSH_RECORD", "WAV export failed; type=${error.javaClass.simpleName}")
            } finally { mutableState.update { it.copy(exporting = false) } }
        }
    }


    fun scoreRecording() {
        val file = result?.file ?: return
        if (mutableState.value.busy || mutableState.value.playing || mutableState.value.exporting || mutableState.value.scoring) return
        val inputKind = mutableState.value.inputKind ?: return
        mutableState.update { it.copy(scoring = true, score = null, scoreStatus = "파일 전송 준비 중") }
        scoringJob = viewModelScope.launch {
            try {
                val score = scoringRepository.score(file, inputKind) { status ->
                    mutableState.update { it.copy(scoreStatus = status) }
                }
                mutableState.update { it.copy(score = score, scoreStatus = "평가 완료 · 선택한 녹음 조건의 품질 예측값") }
            } catch (error: CancellationException) {
                mutableState.update { it.copy(scoreStatus = if (error is TimeoutCancellationException) "평가 시간 초과 · 서버 상태 확인 필요" else "평가 취소 · 이미 전송된 서버 작업은 정리 요청") }
                if (error !is TimeoutCancellationException) throw error
            } catch (error: Exception) {
                val message = (error as? ScoringException)?.message ?: "평가 처리 실패 · SHSH_SCORE 로그를 확인하세요"
                mutableState.update { it.copy(scoreStatus = message) }
                Log.e("SHSH_SCORE", "Evaluation failed; reason=${(error as? ScoringException)?.reason ?: "client_error"}")
            } finally { mutableState.update { it.copy(scoring = false) } }
        }
    }

    fun cancelScoring() {
        if (scoringJob?.isActive == true) {
            scoringJob?.cancel()
            mutableState.update { it.copy(scoreStatus = "평가 취소 요청 · 연결 정리 중") }
            Log.i("SHSH_SCORE", "Evaluation canceled by UI/lifecycle")
        }
    }

    fun leave() {
        cancelScoring()
        val interrupted = mutableState.value.busy
        job?.cancel(); repository.requestStop(); repository.stopPlayback()
        mutableState.update { it.copy(recording = false, playing = false,
            status = if (interrupted) "화면 비활성화로 녹음 취소" else it.status) }
    }

    override fun onCleared() { leave(); repository.delete(result?.file); super.onCleared() }
}
