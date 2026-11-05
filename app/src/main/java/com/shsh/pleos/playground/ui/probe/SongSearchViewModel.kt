package com.shsh.pleos.playground.ui.probe

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shsh.pleos.playground.data.Song
import com.shsh.pleos.playground.data.SongSearchException
import com.shsh.pleos.playground.data.SongRepository
import com.shsh.pleos.playground.data.SttRepository
import com.shsh.pleos.playground.gleo.GleoReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SongSearchUiState(val query: String = "", val busy: Boolean = false, val listening: Boolean = false,
    val speechTranscript: String? = null,
    val message: String = "곡 제목과 가수를 입력하거나 음성 검색을 누르세요", val songs: List<Song> = emptyList(), val error: String? = null)

class SongSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SongRepository(application)
    private val stt = SttRepository(application)
    private val mutableState = MutableStateFlow(SongSearchUiState())
    val uiState = mutableState.asStateFlow()
    private var work: Job? = null
    fun edit(text: String) { if (!mutableState.value.busy) mutableState.update { it.copy(query = text.take(120), speechTranscript = null, error = null) } }
    fun search() {
        if (mutableState.value.busy) return
        val query = mutableState.value.query.trim()
        if (query.isEmpty()) { mutableState.update { it.copy(message = "곡 제목을 입력하세요") }; return }
        work = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, speechTranscript = null, error = null, message = "웅키 채널에서 검색 중", songs = emptyList()) }
            try { performSearch(query) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(error) }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    private suspend fun performSearch(query: String, canPublish: () -> Boolean = { true }): List<Song> {
        val songs = repository.search(query)
        if (!canPublish()) throw CancellationException("Song search action expired")
        mutableState.update { it.copy(songs = songs, message = if (songs.isEmpty()) "웅키 채널에서 재생 가능한 결과가 없어요 · 곡명·가수를 바꿔주세요" else "웅키 채널 검색 결과 · 원하는 영상을 선택하세요") }
        return songs
    }

    /** Reuses the touch/STT search path; Gleo owns its own speech recognition. */
    fun searchFromGleo(query: String, canPublish: () -> Boolean, onReply: (GleoReply) -> Unit) {
        if (mutableState.value.busy) {
            onReply(GleoReply.fail("Another song search is in progress. Please try again after it finishes.", reason = "search_busy"))
            return
        }
        // Set busy synchronously so a second command cannot launch a concurrent search.
        mutableState.update { it.copy(query = query, busy = true, listening = false, speechTranscript = null, error = null,
            songs = emptyList(), message = "Gleo에서 전달받은 곡을 웅키 채널에서 검색 중") }
        work = viewModelScope.launch {
            try {
                val songs = performSearch(query, canPublish)
                onReply(GleoReply.results(songs))
            } catch (error: CancellationException) {
                onReply(GleoReply.fail("The song search was cancelled before completion.", reason = "search_cancelled"))
                throw error
            } catch (error: Exception) {
                fail(error)
                onReply(GleoReply.fail("The karaoke search could not be completed. Check the connection and try again.", reason = "search_failed"))
            } finally { mutableState.update { it.copy(busy = false, listening = false) } }
        }
    }

    fun voiceSearch() {
        if (mutableState.value.busy) return
        work = viewModelScope.launch {
            mutableState.update { it.copy(query = "", busy = true, listening = true, speechTranscript = "", error = null, songs = emptyList(), message = "음성 검색 준비 중") }
            try {
                stt.initialize()
                val result = stt.recognize { message, text ->
                    mutableState.update { it.copy(message = message, query = text?.take(120) ?: it.query,
                        speechTranscript = text?.take(300) ?: it.speechTranscript) }
                }
                mutableState.update { it.copy(speechTranscript = result.text.take(300)) }
                val query = result.text.trim().replace(Regex("\\s*(찾아\\s*줘|틀어\\s*줘|검색해\\s*줘)[.!?。]*$"), "").trim().take(120)
                if (query.isBlank()) {
                    mutableState.update { it.copy(listening = false, message = "인식된 곡명이 없습니다 · 다시 말하거나 직접 입력하세요") }
                    Log.i("SHSH_SEARCH", "Voice recognition ended; no usable query, search not requested")
                } else if (!result.completed) {
                    // An interim transcript is not a final command. Keep it editable, without issuing an API call.
                    mutableState.update { it.copy(query = query, listening = false,
                        message = "최종 인식 결과 없이 종료됐습니다 · 입력된 임시 곡명을 확인·수정한 뒤 곡 검색을 누르세요") }
                    Log.i("SHSH_SEARCH", "Voice recognition ended; final=false, chars=${query.length}, confirmation required")
                } else {
                    mutableState.update { it.copy(query = query, listening = false, message = "인식된 곡으로 검색 중") }
                    Log.i("SHSH_SEARCH", "Voice recognition ended; final=true, chars=${query.length}")
                    performSearch(query)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(error) }
            finally { stt.close(); mutableState.update { it.copy(busy = false, listening = false) } }
        }
    }
    private fun fail(error: Exception) {
        // Exceptions may contain request URLs; log type only, never tokens or speech text.
        Log.e("SHSH_SEARCH", "Search failed; type=${error.javaClass.simpleName}, reason=${(error as? SongSearchException)?.reason ?: "non_api_error"}")
        // Record stack locations without exception messages (which may contain URLs or input).
        var cause: Throwable? = error
        repeat(4) { depth ->
            val current = cause ?: return@repeat
            Log.e("SHSH_SEARCH", "Cause[$depth]=${current.javaClass.name}")
            current.stackTrace.take(10).forEach { frame ->
                Log.e("SHSH_SEARCH", "at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
            }
            cause = current.cause.takeUnless { it === current }
        }
        val message = when (error) {
            is SongSearchException -> error.message ?: "검색 실패"
            else -> "검색 처리 실패: ${error.javaClass.simpleName} · SHSH_SEARCH의 Cause와 at 로그를 확인하세요"
        }
        mutableState.update { it.copy(message = message, error = "노래를 검색하지 못했어요. 인터넷 연결을 확인하거나 곡명과 가수를 바꿔서 다시 시도해 주세요.") }
    }
    fun clearError() { mutableState.update { it.copy(error = null, message = "곡 제목과 가수를 입력하거나 음성 검색을 누르세요") } }
    fun cancel() { work?.cancel(); stt.stop(); mutableState.update { it.copy(message = "검색 중지") } }
    override fun onCleared() { work?.cancel(); stt.close(); super.onCleared() }
}
