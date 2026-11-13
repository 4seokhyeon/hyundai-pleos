package com.shsh.pleos.playground.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import com.shsh.pleos.playground.platform.AppLog as Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

 data class RecordingResult(val file: File, val durationSeconds: Double, val peak: Int)

/** Standard Android API probe, not a verified Pleos recording SDK. */
class RecordingRepository(context: Context) {
    private val app = context.applicationContext
    private val stopRequested = AtomicBoolean(false)
    private var player: MediaPlayer? = null

    fun hasPermission() = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun requestStop() { stopRequested.set(true) }

    @SuppressLint("MissingPermission")
    suspend fun record(onStarted: () -> Unit): RecordingResult = withContext(Dispatchers.IO) {
        check(hasPermission()) { "마이크 권한이 없습니다" }
        stopRequested.set(false)
        val minBytes = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "16kHz 모노 PCM 녹음을 지원하지 않습니다: $minBytes" }
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBytes * 2, 6400)).build()
        val directory = File(app.noBackupFilesDir, "recordings")
        var target: File? = null
        var completed = false
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 초기화 실패" }
            check(directory.isDirectory || directory.mkdirs()) { "녹음 폴더 생성 실패" }
            val file = File(directory, "probe_${UUID.randomUUID()}.wav")
            target = file
            var samples = 0
            var peak = 0
            RandomAccessFile(file, "rw").use { output ->
                output.write(ByteArray(44))
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "마이크 시작 실패" }
                Log.i("SHSH_RECORD", "Recording started; rate=16000, channels=1, maxSeconds=15")
                withContext(Dispatchers.Main) { onStarted() }
                val deadline = SystemClock.elapsedRealtime() + 20000
                val buffer = ShortArray(1600)
                while (!stopRequested.get() && samples < 16000 * 15) {
                    currentCoroutineContext().ensureActive()
                    check(SystemClock.elapsedRealtime() < deadline) { "오디오 수신 시간 초과" }
                    val count = recorder.read(buffer, 0, minOf(buffer.size, 16000 * 15 - samples), AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "오디오 읽기 실패: $count" }
                    if (count == 0) { delay(20); continue }
                    val bytes = ByteArray(count * 2)
                    for (i in 0 until count) {
                        val value = buffer[i].toInt()
                        peak = maxOf(peak, kotlin.math.abs(value))
                        bytes[i * 2] = (value and 255).toByte()
                        bytes[i * 2 + 1] = ((value shr 8) and 255).toByte()
                    }
                    output.write(bytes)
                    samples += count
                }
                currentCoroutineContext().ensureActive()
                check(samples >= 16000 * 3) { "녹음이 3초보다 짧습니다. 다시 녹음하세요" }
                writeHeader(output, samples * 2)
            }
            val seconds = (file.length() - 44) / 32000.0
            completed = true
            Log.i("SHSH_RECORD", "WAV saved; durationSeconds=$seconds, bytes=${file.length()}, nonSilent=${peak > 0}")
            RecordingResult(file, seconds, peak)
        } finally {
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            recorder.release()
            if (!completed) target?.delete()
            Log.i("SHSH_RECORD", "Recorder released; completed=$completed")
        }
    }

    fun play(file: File, onDone: () -> Unit, onError: () -> Unit) {
        stopPlayback()
        val next = MediaPlayer()
        player = next
        try {
            next.setDataSource(file.absolutePath)
            next.setOnCompletionListener { stopPlayback(); onDone() }
            next.setOnErrorListener { _, _, _ -> stopPlayback(); onError(); true }
            next.setOnPreparedListener { it.start(); Log.i("SHSH_RECORD", "Playback started") }
            next.prepareAsync()
        } catch (error: Exception) { stopPlayback(); throw error }
    }

    fun stopPlayback() { player?.release(); player = null }
    fun delete(file: File?) { file?.delete() }

    private fun writeHeader(out: RandomAccessFile, bytes: Int) {
        fun le(value: Int, count: Int) { repeat(count) { out.write((value ushr (it * 8)) and 255) } }
        out.seek(0)
        out.writeBytes("RIFF"); le(36 + bytes, 4); out.writeBytes("WAVEfmt ")
        le(16, 4); le(1, 2); le(1, 2); le(16000, 4); le(32000, 4); le(2, 2); le(16, 2)
        out.writeBytes("data"); le(bytes, 4)
    }
}
