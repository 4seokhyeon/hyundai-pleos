package com.shsh.pleos.playground

import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import com.shsh.pleos.playground.ui.karaoke.KaraokeHome
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shsh.pleos.playground.ui.probe.RecordingProbeSection
import com.shsh.pleos.playground.ui.probe.RecordingProbeViewModel
import com.shsh.pleos.playground.ui.probe.LocationProbeSection
import com.shsh.pleos.playground.ui.probe.LocationProbeViewModel
import com.shsh.pleos.playground.ui.probe.NaviProbeUiState
import com.shsh.pleos.playground.ui.probe.NaviProbeViewModel
import com.shsh.pleos.playground.ui.probe.SttProbeSection
import com.shsh.pleos.playground.ui.probe.SttProbeViewModel
import com.shsh.pleos.playground.ui.probe.TtsProbeSection
import com.shsh.pleos.playground.ui.probe.TtsProbeViewModel
import com.shsh.pleos.playground.ui.probe.ProbeUiState
import com.shsh.pleos.playground.ui.probe.VehicleProbeViewModel
import com.shsh.pleos.playground.ui.theme.PleosSdkPlaygroundTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("SHSH_APP", "Karaoke home started; dark design applied")
        enableEdgeToEdge()
        setContent {
            var diagnostics by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = diagnostics) { diagnostics = false }
            if (!diagnostics) {
                PleosSdkPlaygroundTheme {
                    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                            val openKaraoke: (String) -> Unit = { action ->
                                Log.i("SHSH_APP", "User opened Figma-style karaoke; entry=$action")
                                if (Build.VERSION.SDK_INT >= 29) {
                                    startActivity(Intent(this@MainActivity, VideoProbeActivity::class.java).putExtra("entry_action", action))
                                } else android.widget.Toast.makeText(this@MainActivity, "Android 10 이상에서 이용할 수 있어요", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            KaraokeHome(onStart = { openKaraoke("search") },
                                onVoice = { openKaraoke("voice") }, onVocal = { openKaraoke("vocal_only") },
                                onDiagnostics = { diagnostics = true })
                        }
                    }
                }
                return@setContent
            }
            val model: VehicleProbeViewModel = viewModel()
            val state by model.uiState.collectAsStateWithLifecycle()
            val recordingModel: RecordingProbeViewModel = viewModel()
            val locationModel: LocationProbeViewModel = viewModel()
            val sttModel: SttProbeViewModel = viewModel()
            val ttsModel: TtsProbeViewModel = viewModel()
            val naviModel: NaviProbeViewModel = viewModel()
            val naviState by naviModel.uiState.collectAsStateWithLifecycle()
            DisposableEffect(Unit) {
                onDispose { recordingModel.leave(); sttModel.stop(); ttsModel.stop() }
            }
            PleosSdkPlaygroundTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(Modifier.padding(padding)) {
                        TextButton(onClick = { diagnostics = false }) { Text("← 홈으로") }
                        ProbeScreen(state, model::refresh, naviState, naviModel::connect, ttsModel, sttModel, locationModel, recordingModel, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeScreen(
    state: ProbeUiState,
    onRefresh: () -> Unit,
    naviState: NaviProbeUiState,
    onNaviConnect: () -> Unit,
    ttsModel: TtsProbeViewModel,
    sttModel: SttProbeViewModel,
    locationModel: LocationProbeViewModel,
    recordingModel: RecordingProbeViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val recordingState by recordingModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Pleos SDK 검증", style = MaterialTheme.typography.headlineLarge)
        Button(onClick = {
            recordingModel.leave(); sttModel.stop(); ttsModel.stop()
            context.startActivity(Intent(context, VideoProbeActivity::class.java))
        }, enabled = Build.VERSION.SDK_INT >= 29 && !recordingState.busy && !recordingState.playing) {
            Text("한국곡 영상·주행 제한 검증 열기")
        }
        RecordingProbeSection(recordingModel) { sttModel.stop(); ttsModel.stop() }
        HorizontalDivider()
        LocationProbeSection(locationModel)
        HorizontalDivider()
        if (!recordingState.busy && !recordingState.playing) {
            SttProbeSection(sttModel, ttsModel::stop)
            HorizontalDivider()
            TtsProbeSection(ttsModel, sttModel::stop)
        } else {
            Text("녹음 검증 중에는 STT·TTS 조작을 잠시 숨깁니다")
        }
        HorizontalDivider()
        Text("NaviHelper 3.0.5 · 경로 상태 조회", style = MaterialTheme.typography.titleLarge)
        Text(naviState.status, style = MaterialTheme.typography.headlineSmall)
        Text(if (naviState.connected) "서비스 최초 연결: 확인됨" else "서비스 최초 연결: 미확인")
        Text(naviState.detail, style = MaterialTheme.typography.bodyLarge)
        Text(naviState.result, style = MaterialTheme.typography.titleLarge)
        Text("경로 활성 여부는 차량의 주행·정차 여부가 아닙니다.")
        Button(onClick = onNaviConnect, enabled = !naviState.busy) {
            Text(if (naviState.busy) "연결/조회 중…" else "Navi 재연결 및 경로 상태 조회")
        }
        HorizontalDivider()
        Text("Vehicle SDK 2.3.2 · 에뮬레이터 차량 데이터 · Mock 아님")
        Text("앞줄 왼쪽 문 위치 · ROW_1_LEFT", style = MaterialTheme.typography.titleLarge)
        Text(state.status, style = MaterialTheme.typography.headlineSmall)
        Text(state.detail, style = MaterialTheme.typography.bodyLarge)
        Text(state.result, style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onRefresh, enabled = !state.busy) {
            Text(if (state.busy) "확인 중…" else "차량 상태 다시 조회", style = MaterialTheme.typography.titleLarge)
        }
        Text("이 화면은 SDK 조회 검증용입니다. 실제 차량의 지원 여부는 별도 확인이 필요합니다.")
    }
}
