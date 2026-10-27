package com.shsh.pleos.playground

import android.annotation.SuppressLint
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.shsh.pleos.playground.ui.probe.SongSearchViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shsh.pleos.playground.platform.DrivingRestrictionsMonitor
import com.shsh.pleos.playground.ui.theme.PleosSdkPlaygroundTheme

/** Dedicated foreground-only diagnostic; no recording or upload in this activity. */
class VideoProbeActivity : ComponentActivity() {
    private var allowed by mutableStateOf(false)
    private var detail by mutableStateOf("제한 상태 미확인")
    private var loadedId by mutableStateOf<String?>(null)
    private var status by mutableStateOf("곡을 검색해서 선택하세요")
    private lateinit var searchModel: SongSearchViewModel
    private var web: WebView? = null
    private var monitor: DrivingRestrictionsMonitor? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        searchModel = ViewModelProvider(this)[SongSearchViewModel::class.java]
        // WebView's media session owns playback focus. A second native focus owner
        // can receive LOSS as the embedded player starts and incorrectly destroy it.
        Log.i("SHSH_PLAYER", "Audio focus owner=WebView media session; native request disabled")
        setContent {
            val search by searchModel.uiState.collectAsStateWithLifecycle()
            PleosSdkPlaygroundTheme {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("한국곡 영상 재생 검증", style = MaterialTheme.typography.headlineSmall)
                    Text("차량 제한: $detail")
                    Text("한국곡 검색 · YouTube 제공 · 정차 중 재생")
                    if (loadedId == null) {
                        OutlinedTextField(value = search.query, onValueChange = searchModel::edit,
                            label = { Text("곡 제목과 가수 · 예: 아이유 좋은 날") }, singleLine = true,
                            enabled = allowed && !search.busy)
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = searchModel::search, enabled = allowed && resumed && !search.busy) { Text("곡 검색") }
                            Button(onClick = searchModel::voiceSearch, enabled = allowed && resumed && !search.busy) { Text("음성으로 곡 검색") }
                            Button(onClick = searchModel::cancel, enabled = search.busy) { Text("검색 취소") }
                        }
                        Text(search.message)
                    }
                    Text(status)
                    val id = loadedId
                    if (id != null && allowed && resumed) {
                        AndroidView(factory = { createPlayer(id) }, modifier = Modifier.fillMaxWidth().height(300.dp))
                    }
                    if (loadedId == null) {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(search.songs, key = { it.videoId }) { song ->
                                Button(onClick = {
                                    loadedId = song.videoId
                                    status = "${song.title} · 공식 플레이어에서 재생을 누르세요"
                                }, enabled = allowed && resumed && !search.busy, modifier = Modifier.fillMaxWidth()) {
                                    Text("${song.title}\n${song.channelTitle}")
                                }
                            }
                        }
                    }
                    Button(onClick = { stopPlayer("사용자 중지") }) { Text("재생 중지 및 플레이어 닫기") }
                    Button(onClick = { finish() }) { Text("SDK 검증 화면으로 돌아가기") }
                }
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
                        if (event.startsWith("Error=")) {
                            val message = when (errorCode) {
                                101, 150 -> "이 영상은 앱 내 삽입 재생이 허용되지 않습니다 · 다른 검색 결과를 선택하세요"
                                100 -> "삭제되었거나 비공개인 영상입니다 · 다른 결과를 선택하세요"
                                153 -> "YouTube 플레이어 식별 오류 153 · 설정 확인이 필요합니다"
                                else -> "영상 재생 오류 ${errorCode ?: "unknown"} · 다른 결과를 선택하거나 로그를 확인하세요"
                            }
                            stopPlayer(message)
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
            <body style="margin:0"><div id="player"></div><script>
            function onYouTubeIframeAPIReady(){new YT.Player('player',{
              width:'100%',height:'290',videoId:'$id',
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
        val previous = web
        web = null; loadedId = null
        previous?.apply { stopLoading(); loadUrl("about:blank"); onPause(); destroy() }
        status = reason
        Log.i("SHSH_PLAYER", "Player stopped; reason=$reason")
    }
}
