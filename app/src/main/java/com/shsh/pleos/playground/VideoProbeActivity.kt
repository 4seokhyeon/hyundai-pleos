package com.shsh.pleos.playground

import com.shsh.pleos.playground.ui.karaoke.KaraokeWorkspace
import com.shsh.pleos.playground.data.Song
import android.annotation.SuppressLint
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.shsh.pleos.playground.ui.probe.RecordingProbeViewModel
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shsh.pleos.playground.ui.probe.SongSearchViewModel
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shsh.pleos.playground.platform.DrivingRestrictionsMonitor
import com.shsh.pleos.playground.ui.theme.PleosSdkPlaygroundTheme

/** Foreground-only playback/microphone mixing probe; explicit scoring upload, no audio extraction. */
class VideoProbeActivity : ComponentActivity() {
    private var allowed by mutableStateOf(false)
    private var detail by mutableStateOf("제한 상태 미확인")
    private var loadedId by mutableStateOf<String?>(null)
    private var status by mutableStateOf("곡을 검색해서 선택하세요")
    private var selectedSong by mutableStateOf<Song?>(null)
    private var playerError by mutableStateOf<String?>(null)
    private lateinit var searchModel: SongSearchViewModel
    private lateinit var recordingModel: RecordingProbeViewModel
    private var playerState by mutableStateOf(-1)
    private var uploadConsent by mutableStateOf(false)
    private var recordingMode by mutableStateOf<String?>(null)
    private var consent by mutableStateOf(false)
    private var captureRequested by mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        recordingModel.permissionResult(it)
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) recordingModel.export(uri)
    }
    private var web: WebView? = null
    private var monitor: DrivingRestrictionsMonitor? = null
    private var resumed = false
    private var voiceEntryConsumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordingMode = if (intent.getStringExtra("entry_action") == "vocal_only") "vocal_only" else "mixed"
        searchModel = ViewModelProvider(this)[SongSearchViewModel::class.java]
        recordingModel = ViewModelProvider(this)[RecordingProbeViewModel::class.java]
        // WebView's media session owns playback focus. A second native focus owner
        // can receive LOSS as the embedded player starts and incorrectly destroy it.
        Log.i("SHSH_APP", "Karaoke workspace started; dark design applied")
        Log.i("SHSH_PLAYER", "Audio focus owner=WebView media session; native request disabled")
        setContent {
            val search by searchModel.uiState.collectAsStateWithLifecycle()
            val recording by recordingModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(allowed) {
                if (allowed && resumed && !voiceEntryConsumed && intent.getStringExtra("entry_action") == "voice") {
                    voiceEntryConsumed = true
                    searchModel.voiceSearch()
                }
            }
            LaunchedEffect(recording.error) {
                if (recording.error != null && loadedId != null) stopPlayer("녹음 오류로 재생 중지")
            }
            LaunchedEffect(recording.busy) {
                if (recording.busy) uploadConsent = false
            }
            LaunchedEffect(recording.busy, captureRequested) {
                if (captureRequested && !recording.busy) {
                    captureRequested = false
                    stopPlayer("녹음 종료 · 반주 중지, 녹음 듣기로 혼입을 확인하세요")
                }
            }
            PleosSdkPlaygroundTheme {
                KaraokeWorkspace(
                    allowed = allowed && resumed, mode = recordingMode,
                    hasPlayer = loadedId != null, playerReady = playerState == 1,
                    status = status, search = search, recording = recording,
                    selectedSong = selectedSong, playerError = playerError,
                    onMode = { mode ->
                        searchModel.cancel()
                        stopPlayer("녹음 모드 변경")
                        recordingModel.delete()
                        recordingMode = mode.takeIf { it.isNotEmpty() }
                        Log.i("SHSH_APP", "Karaoke mode selected; mode=${recordingMode ?: "selection"}")
                    },
                    onEdit = searchModel::edit, onSearch = searchModel::search,
                    onVoice = searchModel::voiceSearch, onSearchCancel = searchModel::cancel,
                    onSong = { song ->
                        stopPlayer("사용자가 새 곡을 선택함")
                        playerState = -1; consent = false; playerError = null; selectedSong = song; loadedId = song.videoId
                        status = "${song.title} · 공식 플레이어에서 재생을 누르세요"
                    },
                    onConsent = { consent = it }, onUploadConsent = { uploadConsent = it },
                    onRecord = {
                        if (allowed && resumed && consent) {
                            if (!recordingModel.hasPermission()) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else recordingMode?.let { mode ->
                                if (mode == "vocal_only" || (loadedId != null && playerState == 1)) {
                                    recordingModel.startWithCondition(mode)
                                    captureRequested = mode == "mixed" && recordingModel.uiState.value.busy
                                } else status = "재생 중단으로 녹음을 시작하지 않았어요 · 영상을 다시 재생해 주세요"
                            }
                        }
                    },
                    onFinish = recordingModel::finish,
                    onListen = recordingModel::play, onStopListen = recordingModel::stopPlayback,
                    onDelete = { recordingModel.delete(); uploadConsent = false; consent = false },
                    onRecordConsentOpen = {},
                    onScore = { if (allowed && resumed && uploadConsent) recordingModel.scoreRecording() }, onCancelScore = recordingModel::cancelScoring,
                    onClosePlayer = { stopPlayer("사용자가 플레이어를 닫음") }, onHome = { finish() },
                    onClearErrors = { playerError = null; searchModel.clearError(); recordingModel.clearErrors() },
                    player = {
                        loadedId?.let { id ->
                            androidx.compose.runtime.key(id) {
                                AndroidView(factory = { createPlayer(id) }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume(); resumed = true; allowed = false
        monitor = DrivingRestrictionsMonitor(this) { permit, description ->
            allowed = permit; detail = description
            if (!permit) { searchModel.cancel(); stopPlayer("주행 제한 또는 상태 미확인으로 중지") }
        }.also { it.start() }
    }

    override fun onPause() {
        resumed = false; allowed = false
        searchModel.cancel()
        stopPlayer("화면 비활성화로 중지")
        monitor?.close(); monitor = null
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && loadedId != null) stopPlayer("창 포커스 상실로 중지")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPlayer(id: String): WebView = WebView(this).apply {
        web = this
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val text = message.message()
                if (text.startsWith("SHSH_PLAYER:")) {
                    Log.i("SHSH_PLAYER", text)
                    // Only diagnostic status, never commands or an exposed JS bridge.
                    post {
                        if (web !== this@apply) return@post
                        val event = text.removePrefix("SHSH_PLAYER:")
                        val errorCode = event.removePrefix("Error=").toIntOrNull()
                        if (event.startsWith("State=")) {
                            playerState = event.removePrefix("State=").toIntOrNull() ?: -1
                            if (captureRequested && playerState != 1) {
                                stopPlayer("재생 중단으로 녹음 취소 · 영상을 다시 선택하세요")
                                return@post
                            }
                        }
                        if (event.startsWith("Error=")) {
                            val message = when (errorCode) {
                                101, 150 -> "이 영상은 앱 내 삽입 재생이 허용되지 않습니다 · 다른 검색 결과를 선택하세요"
                                100 -> "삭제되었거나 비공개인 영상입니다 · 다른 결과를 선택하세요"
                                153 -> "YouTube 플레이어 식별 오류 153 · 설정 확인이 필요합니다"
                                else -> "영상 재생 오류 ${errorCode ?: "unknown"} · 다른 결과를 선택하거나 로그를 확인하세요"
                            }
                            stopPlayer(message)
                            playerError = message
                        } else {
                            status = when (event) {
                                "Ready" -> "플레이어 준비 완료 · 화면의 재생 버튼을 누르세요"
                                "State=1" -> "영상 재생 중"
                                "State=0" -> "영상 재생 종료"
                                else -> event
                            }
                        }
                    }
                }
                return true
            }
        }
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;height:100vh;background:#071014"><div id="player"></div><script>
            function onYouTubeIframeAPIReady(){new YT.Player('player',{
              width:'100%',height:'100%',videoId:'$id',
              playerVars:{playsinline:1,autoplay:0,origin:'https://com.shsh.pleos.playground'},
              events:{onReady:function(){console.log('SHSH_PLAYER:Ready');},
                onStateChange:function(e){console.log('SHSH_PLAYER:State='+e.data);},
                onError:function(e){console.log('SHSH_PLAYER:Error='+e.data);}}
            });}
            </script><script src="https://www.youtube.com/iframe_api"></script></body></html>
        """.trimIndent()
        // App identifier as WebView base URL / Referer; no audio URL extraction.
        loadDataWithBaseURL("https://com.shsh.pleos.playground/", html, "text/html", "UTF-8", null)
        Log.i("SHSH_PLAYER", "Official IFrame player load requested")
    }

    private fun stopPlayer(reason: String) {
        captureRequested = false
        uploadConsent = false
        consent = false
        playerState = -1
        if (::recordingModel.isInitialized) recordingModel.leave()
        val previous = web
        web = null; loadedId = null
        previous?.apply { stopLoading(); loadUrl("about:blank"); onPause(); destroy() }
        status = reason
        Log.i("SHSH_PLAYER", "Player stopped; reason=$reason")
    }
}
