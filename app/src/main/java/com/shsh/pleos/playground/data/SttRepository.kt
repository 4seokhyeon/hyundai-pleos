package com.shsh.pleos.playground.data

import android.content.Context
import com.shsh.pleos.playground.platform.AppLog as Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SttSdkException(val type: ErrorType) : Exception(type.name)
data class SttReading(val text: String, val completed: Boolean)

class SttRepository(context: Context) {
    private val appContext = context.applicationContext
    private var engine: SpeechToText? = null
    private var listener: ResultListener? = null

    suspend fun initialize() {
        close()
        val instance = SpeechToText(appContext, Mode.ON_DEVICE)
        engine = instance
        try {
            withTimeout(15_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val terminal = AtomicBoolean(false)
                    continuation.invokeOnCancellation { terminal.set(true) }
                    Log.i("SHSH_STT", "Initialize requested; SDK=3.3.1, mode=ON_DEVICE")
                    instance.initialize(object : OnInitializeListener {
                        override fun onSuccess() {
                            if (terminal.compareAndSet(false, true)) {
                                Log.i("SHSH_STT", "Initialization callback: onSuccess")
                                continuation.resume(Unit)
                            }
                        }
                        override fun onFailure(errorType: ErrorType) {
                            if (terminal.compareAndSet(false, true)) {
                                Log.e("SHSH_STT", "Initialization callback: onFailure=$errorType")
                                continuation.resumeWithException(SttSdkException(errorType))
                            }
                        }
                    })
                }
            }
            instance.setLocale(Locale.KOREA)
            Log.i("SHSH_STT", "Locale ko-KR configured; microphone not started")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    suspend fun recognize(onProgress: (String, String?) -> Unit): SttReading {
        val instance = checkNotNull(engine) { "STT 초기화가 필요합니다" }
        try {
            return withTimeout(20_000) {
                suspendCancellableCoroutine { continuation ->
                    val terminal = AtomicBoolean(false)
                    val latest = AtomicReference("")
                    continuation.invokeOnCancellation { terminal.set(true) }
                    val resultListener = object : ResultListener {
                        override fun onReady() {
                            if (!terminal.get()) {
                                Log.i("SHSH_STT", "Recognition event: onReady")
                                onProgress("인식 준비 이벤트 수신", null)
                            }
                        }
                        override fun onStartedRecognition() {
                            if (!terminal.get()) {
                                Log.i("SHSH_STT", "Recognition event: onStartedRecognition")
                                onProgress("듣는 중 · 짧은 문장을 말해주세요", null)
                            }
                        }
                        override fun onUpdated(stt: String, completed: Boolean) {
                            if (terminal.get()) return
                            latest.set(stt)
                            Log.i("SHSH_STT", "Recognition event: onUpdated; completed=$completed, chars=${stt.length}")
                            if (completed) {
                                if (terminal.compareAndSet(false, true)) continuation.resume(SttReading(stt, true))
                            } else onProgress("중간 인식 결과 수신", stt)
                        }
                        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                        override fun onUpdatedRms(rms: Double) = Unit
                        override fun onEndedRecognition() {
                            if (terminal.compareAndSet(false, true)) {
                                Log.i("SHSH_STT", "Recognition event: onEndedRecognition; no final result")
                                continuation.resume(SttReading(latest.get(), false))
                            }
                        }
                        override fun onError(errorType: ErrorType) {
                            if (terminal.compareAndSet(false, true)) {
                                Log.e("SHSH_STT", "Recognition event: onError=$errorType")
                                continuation.resumeWithException(SttSdkException(errorType))
                            }
                        }
                    }
                    listener = resultListener
                    instance.addListener(resultListener)
                    Log.i("SHSH_STT", "request called by user; mode=ON_DEVICE")
                    instance.request()
                }
            }
        } finally {
            stop()
            removeListener(instance)
        }
    }

    fun stop() {
        try { engine?.stop() }
        catch (error: Exception) { Log.e("SHSH_STT", "Stop failed", error) }
    }

    private fun removeListener(instance: SpeechToText) {
        val current = listener ?: return
        listener = null
        try {
            instance.removeListener(current)
            Log.i("SHSH_STT", "Recognition listener removed")
        } catch (error: Exception) { Log.e("SHSH_STT", "Listener removal failed", error) }
    }

    fun close() {
        val instance = engine ?: return
        engine = null
        try { instance.stop() }
        catch (error: Exception) { Log.e("SHSH_STT", "Stop during release failed", error) }
        removeListener(instance)
        try {
            instance.release()
            Log.i("SHSH_STT", "SpeechToText released")
        } catch (error: Exception) { Log.e("SHSH_STT", "Release failed", error) }
    }
}
