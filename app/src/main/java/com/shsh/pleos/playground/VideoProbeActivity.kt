package com.shsh.pleos.playground

import com.shsh.pleos.playground.ui.karaoke.KaraokeWorkspace
import com.shsh.pleos.playground.data.Song
import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.os.SystemClock
import com.shsh.pleos.playground.gleo.GleoReply
import com.shsh.pleos.playground.gleo.GleoSearchContract
import com.shsh.pleos.playground.gleo.GleoSearchSession
import com.shsh.pleos.playground.gleo.sendGleoReply
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.roundToInt
import com.shsh.pleos.playground.ui.karaoke.WorkspaceOverlay
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shsh.pleos.playground.ui.probe.SongSearchViewModel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shsh.pleos.playground.platform.DrivingRestrictionsMonitor
import com.shsh.pleos.playground.ui.theme.PleosSdkPlaygroundTheme

/** Foreground-only playback/microphone mixing probe; explicit scoring upload, no audio extraction. */
class VideoProbeActivity : ComponentActivity() {
    private var allowed by mutableStateOf(false)
    private var detail by mutableStateOf("제한 상태 미확인")
    private var loadedId by mutableStateOf<String?>(null)
    private var status by mutableStateOf("곡을 검색해서 선택하세요")
    private var gleoCommandSummary by mutableStateOf<String?>(null)
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
    private lateinit var playerRoot: FrameLayout
    private lateinit var nativePlayerHost: FrameLayout
    private lateinit var modalHost: ComposeView
    private var modalContent by mutableStateOf<WorkspaceOverlay?>(null)
    private var nativePlayerKey: Pair<String,Int>? = null
    private var monitor: DrivingRestrictionsMonitor? = null
    private var resumed = false
    private var voiceEntryConsumed = false
    private var gleoSession: GleoSearchSession? = null
    private var pendingGleoQuery: String? = null
    private var pendingSelection: List<Song>? = null
    private var voicePlaybackId: String? = null
    private var requestedPlaybackId: String? = null
    private var playerGeneration by mutableStateOf(0)
    private var gleoSearchStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordingMode = if (intent.getStringExtra("entry_action") == "vocal_only") "vocal_only" else "mixed"
        searchModel = ViewModelProvider(this)[SongSearchViewModel::class.java]
        recordingModel = ViewModelProvider(this)[RecordingProbeViewModel::class.java]
        // Never replay an external action after Activity/process recreation.
        if (savedInstanceState == null) acceptGleoIntent(intent)
        else GleoSearchContract.takeForwarded(intent)
        // WebView's media session owns playback focus. A second native focus owner
        // can receive LOSS as the embedded player starts and incorrectly destroy it.
        Log.i("SHSH_APP", "Karaoke workspace started; dark design applied")
        Log.i("SHSH_PLAYER", "Audio focus owner=WebView media session; native request disabled")
        playerRoot = FrameLayout(this)
        val workspaceHost = ComposeView(this).apply {
            id = View.generateViewId()
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        nativePlayerHost = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            visibility = View.GONE
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        modalHost = ComposeView(this).apply {
            id = View.generateViewId()
            visibility = View.GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        playerRoot.addView(workspaceHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        playerRoot.addView(nativePlayerHost, FrameLayout.LayoutParams(1, 1))
        playerRoot.addView(modalHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(playerRoot)
        modalHost.setContent { PleosSdkPlaygroundTheme { modalContent?.invoke() } }
        workspaceHost.setContent {
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
                    receivedCommand = gleoCommandSummary, onPlay = ::requestPlayerPlayback,
                    onOverlayChanged = { content ->
                        modalContent = content
                        modalHost.visibility = if(content==null) View.GONE else View.VISIBLE
                    },
                    selectedSong = selectedSong, playerError = playerError,
                    onMode = { mode ->
                        searchModel.cancel()
                        stopPlayer("녹음 모드 변경")
                        recordingModel.delete()
                        recordingMode = mode.takeIf { it.isNotEmpty() }
                        Log.i("SHSH_APP", "Karaoke mode selected; mode=${recordingMode ?: "selection"}")
                    },
                    onEdit = { gleoCommandSummary=null; searchModel.edit(it) },
                    onSearch = { gleoCommandSummary=null; searchModel.search() },
                    onVoice = { gleoCommandSummary=null; searchModel.voiceSearch() }, onSearchCancel = searchModel::cancel,
                    onSong = { song ->
                        finishGleo(GleoReply.fail("The voice action was replaced by a manual selection.", reason = "manual_selection"))
                        selectSong(song, voicePlayback = false)
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
                            val generation = playerGeneration
                            DisposableEffect(id, generation) {
                                onDispose {
                                    if(nativePlayerKey == (id to generation)) removeNativePlayer()
                                }
                            }
                            // Compose provides the rectangle; WebView itself is a native sibling.
                            Box(Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                                val position = coordinates.positionInWindow()
                                placeNativePlayer(id, generation, position.x.roundToInt(), position.y.roundToInt(),
                                    coordinates.size.width, coordinates.size.height)
                            })
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptGleoIntent(intent)
        startPendingGleoSearch()
    }

    private fun acceptGleoIntent(incoming: Intent) {
        val request = GleoSearchContract.takeForwarded(incoming) ?: return
        // This is the parsed function input, not Gleo's live STT transcript.
        gleoCommandSummary = if (request.function == GleoSearchContract.ACTION) "곡 검색 · ${request.query ?: "검색어 확인 필요"}"
            else "검색 결과 재생 · ${request.index?.let { "${it}번" } ?: "번호 확인 필요"}"
        // A Gleo action must not accidentally trigger the app's microphone entry mode.
        voiceEntryConsumed = true
        if (gleoSession?.active == true) {
            sendGleoReply(this, GleoReply.fail("A previous song search action is still in progress.", reason = "action_busy").copy(functionName = request.function), request.diagnostic)
            return
        }
        gleoSession?.respond(GleoReply.fail("The previous song search action has expired.", reason = "action_expired"))
        if (voicePlaybackId != null) stopPlayer("만료된 음성 재생 요청 정리")
        val remaining = request.deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0 || remaining > GleoSearchContract.BUDGET_MS) {
            sendGleoReply(this, GleoReply.fail("The song search action has expired. Please try again.", reason = "action_expired").copy(functionName = request.function), request.diagnostic)
            return
        }
        if (request.function == GleoSearchContract.ACTION && request.query == null) {
            sendGleoReply(this, GleoReply.asking(), request.diagnostic)
            return
        }
        if (request.function == GleoSearchContract.SELECT_ACTION && request.index == null) {
            sendGleoReply(this, GleoReply.askingSelection(), request.diagnostic)
            return
        }
        pendingSelection = if (request.function == GleoSearchContract.SELECT_ACTION) searchModel.uiState.value.songs else null
        pendingGleoQuery = request.query
        gleoSearchStarted = false
        gleoSession = GleoSearchSession(applicationContext, request) {
            if (gleoSession?.request?.id == request.id) {
                pendingGleoQuery = null
                pendingSelection = null
                if (voicePlaybackId != null) stopPlayer("음성 재생 확인 시간 초과 · 다시 요청해 주세요")
                if (gleoSearchStarted) searchModel.cancel()
                gleoSearchStarted = false
            }
            Log.w("SHSH_GLEO", "Action deadline reached; values omitted")
        }
        Log.i("SHSH_GLEO", "Workspace received search; waiting for foreground vehicle permission; values omitted")
    }

    private fun startPendingGleoSearch() {
        val session = gleoSession?.takeIf { it.active } ?: return
        if (!resumed || !allowed || gleoSearchStarted) return
        val recording = recordingModel.uiState.value
        if (recording.busy || recording.hasFile || recording.playing || recording.scoring || recording.exporting) {
            finishGleo(GleoReply.fail("Finish or discard the current recording before starting another song search.", reason = "recording_conflict"))
            return
        }
        if (searchModel.uiState.value.busy) {
            finishGleo(GleoReply.fail("Another song search is in progress. Please try again after it finishes.", reason = "search_busy"))
            return
        }
        if (SystemClock.elapsedRealtime() >= session.request.deadline) {
            finishGleo(GleoReply.fail("The song search action has expired. Please try again.", reason = "action_expired"))
            return
        }
        if (session.request.function == GleoSearchContract.SELECT_ACTION) {
            val snapshot = pendingSelection
            val current = searchModel.uiState.value.songs
            val index = session.request.index ?: return
            if (snapshot == null || snapshot.isEmpty() || snapshot !== current) {
                finishGleo(GleoReply.fail("There are no unchanged search results available. Search for a song again.", reason = "results_unavailable"))
                return
            }
            val song = snapshot.getOrNull(index - 1)
            if (song == null) {
                finishGleo(GleoReply("asking", "Choose a result number between 1 and ${snapshot.size}.", reason = "index_out_of_range"))
                return
            }
            pendingSelection = null
            gleoSearchStarted = true
            searchModel.clearError()
            recordingModel.clearErrors()
            recordingMode = "mixed"
            selectSong(song, voicePlayback = true)
            Log.i("SHSH_GLEO", "Numbered playback requested; index=$index; waiting for player State=1")
            return
        }
        val query = pendingGleoQuery ?: return
        pendingGleoQuery = null
        gleoSearchStarted = true
        stopPlayer("Gleo 노래 검색으로 재생 중지")
        playerError = null
        recordingMode = "mixed"
        searchModel.searchFromGleo(query, canPublish = { session.active }) { reply ->
            // Capture this session, so an older cancelled search cannot answer a newer command.
            session.respond(reply)
            if (gleoSession === session) gleoSearchStarted = false
        }
        Log.i("SHSH_GLEO", "Existing song search started; microphone=false, autoplay=false; values omitted")
    }

    private fun selectSong(song: Song, voicePlayback: Boolean) {
        stopPlayer("새 곡 선택")
        playerState = -1; consent = false; playerError = null
        selectedSong = song
        playerGeneration += 1
        voicePlaybackId = song.videoId.takeIf { voicePlayback }
        requestedPlaybackId = song.videoId
        loadedId = song.videoId
        status = "선택한 곡 재생 준비 중"
        Log.i("SHSH_PLAYER", "Playback selected; source=${if(voicePlayback) "gleo" else "touch"}, playOnReady=true")
    }

    private fun finishGleo(reply: GleoReply) {
        pendingSelection = null
        pendingGleoQuery = null
        gleoSession?.respond(reply)
        gleoSearchStarted = false
    }

    override fun onDestroy() {
        finishGleo(GleoReply.fail("The song search screen was closed before completion.", reason = "screen_closed"))
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume(); resumed = true; allowed = false
        monitor = DrivingRestrictionsMonitor(this) { permit, description ->
            allowed = permit; detail = description
            if (!permit) {
                searchModel.cancel(); stopPlayer("주행 제한 또는 상태 미확인으로 중지")
                if (gleoSearchStarted) finishGleo(GleoReply.fail("The song search stopped because the app is not in an allowed vehicle state.", reason = "vehicle_restricted"))
            } else startPendingGleoSearch()
        }.also { it.start() }
    }

    override fun onPause() {
        resumed = false; allowed = false
        finishGleo(GleoReply.fail("The song search stopped because the app left the foreground.", reason = "background"))
        searchModel.cancel()
        stopPlayer("화면 비활성화로 중지")
        monitor?.close(); monitor = null
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && loadedId != null) stopPlayer("창 포커스 상실로 중지")
    }

    private fun placeNativePlayer(id: String, generation: Int, windowX: Int, windowY: Int, width: Int, height: Int) {
        if(!allowed || !resumed || loadedId!=id || playerGeneration!=generation || width<=0 || height<=0) return
        val origin=IntArray(2)
        playerRoot.getLocationInWindow(origin)
        val left=windowX-origin[0]
        val top=windowY-origin[1]
        val current=nativePlayerHost.layoutParams as FrameLayout.LayoutParams
        if(current.width!=width || current.height!=height || current.leftMargin!=left || current.topMargin!=top) {
            nativePlayerHost.layoutParams=FrameLayout.LayoutParams(width,height).apply { leftMargin=left;topMargin=top }
            Log.i("SHSH_PLAYER","Native host bounds; width=$width, height=$height")
        }
        val key=id to generation
        if(nativePlayerKey!=key || web==null) {
            web?.let { releasePlayerView(it) }
            nativePlayerHost.removeAllViews()
            nativePlayerKey=key
            nativePlayerHost.addView(createPlayer(id),FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
            Log.i("SHSH_PLAYER","Player attached; host=native_sibling, composeInterop=false")
        }
        nativePlayerHost.visibility=View.VISIBLE
    }

    private fun removeNativePlayer() {
        nativePlayerKey=null
        if(::nativePlayerHost.isInitialized) {
            nativePlayerHost.visibility=View.GONE
            web?.let { releasePlayerView(it) }
            nativePlayerHost.removeAllViews()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPlayer(id: String): WebView = WebView(this).apply {
        web?.let { releasePlayerView(it) }
        web = this
        playerState = -1
        status = "공식 플레이어 화면 불러오는 중"
        setBackgroundColor(android.graphics.Color.BLACK)
        WebView.getCurrentWebViewPackage()?.let { provider ->
            Log.i("SHSH_PLAYER", "WebView provider; package=${provider.packageName}, version=${provider.versionName}")
        }
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right-left != oldRight-oldLeft || bottom-top != oldBottom-oldTop) {
                Log.i("SHSH_PLAYER", "WebView layout; width=${right-left}, height=${bottom-top}, attached=$isAttachedToWindow, hardware=$isHardwareAccelerated")
            }
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = requestedPlaybackId != id
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (web === view) Log.i("SHSH_PLAYER", "Player page finished; width=${view.width}, height=${view.height}")
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val text = message.message()
                if (text.startsWith("SHSH_PLAYER:")) {
                    Log.i("SHSH_PLAYER", text)
                    // Fixed player events only; no exposed JS bridge or raw JavaScript commands.
                    post {
                        if (web !== this@apply) return@post
                        val event = text.removePrefix("SHSH_PLAYER:")
                        if (event.startsWith("Layout=")) return@post
                        if (event == "PlayRequested") { status = "영상과 소리 재생 요청 중"; return@post }
                        val errorCode = event.removePrefix("Error=").toIntOrNull()
                        if (event.startsWith("State=")) {
                            playerState = event.removePrefix("State=").toIntOrNull() ?: -1
                            if (playerState == 1) requestedPlaybackId = null
                            if (playerState == 1 && voicePlaybackId == id) {
                                if (!resumed || !allowed || gleoSession?.active != true) {
                                    stopPlayer("음성 요청 만료 또는 차량 제한으로 재생 중지")
                                    return@post
                                }
                                voicePlaybackId = null
                                finishGleo(GleoReply("success", "The selected karaoke result is now playing.",
                                    org.json.JSONArray().put(org.json.JSONObject().put("index", gleoSession?.request?.index)
                                        .put("playbackStarted", true)).toString(), reason = "playback_started"))
                            }
                            if (captureRequested && playerState != 1) {
                                stopPlayer("재생 중단으로 녹음 취소 · 영상을 다시 선택하세요")
                                return@post
                            }
                        }
                        if (event == "AutoplayBlocked") {
                            requestedPlaybackId = null
                            voicePlaybackId = null
                            finishGleo(GleoReply.fail("The player blocked voice-initiated playback. Use the visible play button.", reason = "autoplay_blocked"))
                            status = "음성 재생이 차단됐어요 · 화면의 재생 버튼을 눌러주세요"
                            return@post
                        }
                        if (event.startsWith("Error=")) {
                            finishGleo(GleoReply.fail("The selected video could not be played. Choose another result.", reason = "player_error"))
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
                                "Ready" -> if (requestedPlaybackId == id) "선택한 곡 재생 시작 대기 중" else "플레이어 준비 완료 · 화면의 재생 버튼을 누르세요"
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
        val playOnReady = requestedPlaybackId == id
        val html = com.shsh.pleos.playground.platform.YouTubePlayerPage.html(id, playOnReady)
        // App identifier as WebView base URL / Referer; no audio URL extraction.
        loadDataWithBaseURL("https://com.shsh.pleos.playground/", html, "text/html", "UTF-8", null)
        Log.i("SHSH_PLAYER", "Official IFrame player load requested")
    }

    private fun requestPlayerPlayback() {
        if (!allowed || !resumed || recordingModel.uiState.value.busy) return
        val id = loadedId ?: return
        requestedPlaybackId = id
        status = "영상과 소리 재생 요청 중"
        web?.evaluateJavascript("if(window.requestPlayback){window.requestPlayback();}", null)
        Log.i("SHSH_PLAYER", "Explicit play button pressed; webPresent=${web != null}")
    }

    private fun releasePlayerView(view: WebView) {
        if (web !== view) return
        web = null
        (view.parent as? ViewGroup)?.removeView(view)
        playerState = -1
        status = "플레이어 화면이 종료됐어요"
        view.stopLoading(); view.loadUrl("about:blank"); view.onPause(); view.destroy()
        Log.i("SHSH_PLAYER", "Native player WebView released")
    }

    private fun stopPlayer(reason: String) {
        if (voicePlaybackId != null && gleoSession?.active == true) {
            finishGleo(GleoReply.fail("Playback was interrupted before it started.", reason = "playback_interrupted"))
        }
        voicePlaybackId = null
        requestedPlaybackId = null
        captureRequested = false
        uploadConsent = false
        consent = false
        playerState = -1
        if (::recordingModel.isInitialized) recordingModel.leave()
        val previous = web
        web = null; loadedId = null; nativePlayerKey=null
        if(::nativePlayerHost.isInitialized) {
            nativePlayerHost.visibility=View.GONE
            previous?.let { (it.parent as? ViewGroup)?.removeView(it) }
            nativePlayerHost.removeAllViews()
        }
        previous?.apply { stopLoading(); loadUrl("about:blank"); onPause(); destroy() }
        status = reason
        Log.i("SHSH_PLAYER", "Player stopped; reason=$reason")
    }
}
