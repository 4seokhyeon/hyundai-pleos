package com.shsh.pleos.playground.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TtsSdkException(val type: ErrorType) : Exception(type.name)

class TtsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var engine: TextToSpeech? = null
    private var eventListener: EventListener? = null
    private var focus: AudioFocusRequest? = null

    suspend fun initialize() {
        close()
        val instance = TextToSpeech(appContext, Mode.ON_DEVICE)
        engine = instance
        try {
            withTimeout(15_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val completed = AtomicBoolean(false)
                    continuation.invokeOnCancellation { completed.set(true) }
                    Log.i("SHSH_TTS", "Initialize requested; SDK=3.3.1, mode=ON_DEVICE")
                    instance.initialize(object : OnInitializeListener {
                        override fun onSuccess() {
                            if (completed.compareAndSet(false, true)) {
                                Log.i("SHSH_TTS", "Initialization callback: onSuccess")
                                continuation.resume(Unit)
                            }
                        }
                        override fun onFailure(errorType: ErrorType) {
                            if (completed.compareAndSet(false, true)) {
                                Log.e("SHSH_TTS", "Initialization callback: onFailure=$errorType")
                                continuation.resumeWithException(TtsSdkException(errorType))
                            }
                        }
                    })
                }
            }
            instance.setLocale(Locale.KOREA)
            instance.setAudioAttributes(attributes)
            Log.i("SHSH_TTS", "Locale ko-KR and speech audio attributes configured")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    suspend fun speak(onProgress: (String) -> Unit): Boolean {
        val instance = checkNotNull(engine) { "TTS 초기화가 필요합니다" }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                Log.i("SHSH_TTS", "Audio focus change=$change")
                if (change < 0) stop()
            }, Handler(Looper.getMainLooper()))
            .build()
        focus = request
        try {
            val granted = audio.requestAudioFocus(request)
            Log.i("SHSH_TTS", "Audio focus request result=$granted; usage=ASSISTANT")
            check(granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "오디오 포커스를 얻지 못했습니다" }
            return withTimeout(30_000) {
                suspendCancellableCoroutine { continuation ->
                    val completed = AtomicBoolean(false)
                    continuation.invokeOnCancellation { completed.set(true) }
                    val listener = object : EventListener {
                        override fun onReady() {
                            if (!completed.get()) {
                                Log.i("SHSH_TTS", "Playback event: onReady")
                                onProgress("합성 요청 준비됨")
                            }
                        }
                        override fun onStart() {
                            if (!completed.get()) {
                                Log.i("SHSH_TTS", "Playback event: onStart")
                                onProgress("재생 시작 이벤트 수신")
                            }
                        }
                        override fun onUpdatedRms(rms: Double) = Unit
                        override fun onDone() {
                            if (completed.compareAndSet(false, true)) {
                                Log.i("SHSH_TTS", "Playback event: onDone")
                                continuation.resume(true)
                            }
                        }
                        override fun onStop() {
                            if (completed.compareAndSet(false, true)) {
                                Log.i("SHSH_TTS", "Playback event: onStop")
                                continuation.resume(false)
                            }
                        }
                        override fun onError(errorType: ErrorType) {
                            if (completed.compareAndSet(false, true)) {
                                Log.e("SHSH_TTS", "Playback event: onError=$errorType")
                                continuation.resumeWithException(TtsSdkException(errorType))
                            }
                        }
                    }
                    eventListener = listener
                    instance.addEventListener(listener)
                    Log.i("SHSH_TTS", "speak requested; fixed test sentence, mode=ON_DEVICE")
                    instance.speak(TEST_SENTENCE)
                }
            }
        } finally {
            // Also stop any pending synthesis after cancellation or timeout.
            stop()
            eventListener?.let {
                try { instance.removeEventListener(it) }
                catch (error: Exception) { Log.e("SHSH_TTS", "Listener removal failed", error) }
            }
            eventListener = null
            audio.abandonAudioFocusRequest(request)
            focus = null
        }
    }

    fun stop() {
        try { engine?.stop() }
        catch (error: Exception) { Log.e("SHSH_TTS", "Stop failed", error) }
    }

    fun close() {
        val instance = engine ?: return
        engine = null
        try {
            instance.stop()
            eventListener?.let { instance.removeEventListener(it) }
            instance.release()
            Log.i("SHSH_TTS", "TextToSpeech released")
        } catch (error: Exception) {
            Log.e("SHSH_TTS", "Release failed", error)
        } finally {
            eventListener = null
            focus?.let { audio.abandonAudioFocusRequest(it) }
            focus = null
        }
    }

    companion object {
        const val TEST_SENTENCE = "안녕하세요. 플레오스 음성 출력 테스트입니다."
    }
}
