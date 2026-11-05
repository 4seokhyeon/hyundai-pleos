package com.shsh.pleos.playground.ui.probe

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shsh.pleos.playground.data.CachedFacility
import com.shsh.pleos.playground.data.NaviRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class SosNavigationState(val busy: Boolean = false, val sent: Boolean = false,
    val confirmed: Boolean = false, val message: String = "목적지를 선택해 주세요")

class SosNavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NaviRepository(application)
    private val mutable = MutableStateFlow(SosNavigationState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun send(destination: CachedFacility, maySend: () -> Boolean, budgetMillis: Long = 20_000,
        onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (mutable.value.busy) { onResult(false, "navigation_busy"); return }
        mutable.value = SosNavigationState(busy = true, message = "내비 서비스 연결 중")
        job = viewModelScope.launch {
            try {
                withTimeout(budgetMillis.coerceAtLeast(1)) {
                    repository.connect()
                    repository.requestDestination(destination, maySend) {
                        mutable.update { it.copy(sent = true, message = "목적지 전달 요청 · 내비 응답 확인 중") }
                    }
                }
                mutable.update { it.copy(confirmed = true, message = "내비 목적지 확인 완료 · 경로와 안내 시작 여부는 내비에서 확인하세요") }
                onResult(true, "destination_confirmed")
            } catch (error: TimeoutCancellationException) {
                mutable.update { it.copy(message = if (it.sent) "목적지를 요청했지만 확인 응답이 없어요. 내비에서 확인한 뒤 다시 시도하세요." else "내비 연결 시간이 초과됐어요. 내비 앱 상태를 확인하세요.") }
                onResult(false, "navigation_unconfirmed")
            } catch (error: CancellationException) {
                mutable.update { it.copy(message = if (it.sent) "응답 확인이 중단됐어요. 이미 요청한 목적지는 내비에서 확인하세요." else "목적지 전달을 중지했어요.") }
                onResult(false, "navigation_cancelled")
                throw error
            } catch (error: Exception) {
                val reason = if (error is SecurityException) "navigation_permission" else "navigation_error"
                mutable.update { it.copy(message = if (error is SecurityException) "내비 경로 요청 권한을 확인해 주세요." else "내비에서 목적지 전달을 확인하지 못했어요. 내비 앱과 경로 요청 지원 상태를 확인하세요.") }
                Log.w("SHSH_NAVI", "SOS navigation failed; reason=$reason, type=${error.javaClass.simpleName}; values omitted")
                onResult(false, reason)
            } finally {
                repository.close()
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun report(message: String) {
        if (!mutable.value.busy) mutable.value = SosNavigationState(message = message)
    }
    fun reset() { if (!mutable.value.busy) mutable.value = SosNavigationState() }

    /** Stop pending work before dispatch. An already sent route is never silently cancelled. */
    fun leave() { if (!mutable.value.sent) job?.cancel() }
    fun cancelWaiting() { job?.cancel() }
    override fun onCleared() { job?.cancel(); repository.close(); super.onCleared() }
}
