package com.shsh.pleos.playground.ui.probe

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RecordingProbeSection(model: RecordingProbeViewModel, stopOtherAudio: () -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    var parked by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.permissionResult(it) }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { model.leave(); parked = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.leave() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("마이크 녹음 검증 · AudioRecord", style = MaterialTheme.typography.titleLarge)
        Text("Pleos 실차 지원 미확인 · 앱 내부 WAV 저장 · 자동 업로드 없음")
        Row {
            Checkbox(checked = parked, onCheckedChange = { parked = it }, enabled = !state.busy && !state.playing)
            Text("차량이 주차되어 있으며 녹음 테스트에 동의합니다")
        }
        Text("이 체크는 진단용 확인입니다. 자동 주행 제한 연동은 다음 단계에서 검증합니다.")
        Text(state.status, style = MaterialTheme.typography.headlineSmall)
        Text(state.detail)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (!model.hasPermission()) permission.launch(Manifest.permission.RECORD_AUDIO)
                else { stopOtherAudio(); model.start() }
            }, enabled = parked && !state.busy && !state.playing) { Text("녹음 시작") }
            Button(onClick = model::finish, enabled = state.recording) { Text("종료 및 저장") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { stopOtherAudio(); model.play() }, enabled = parked && state.hasFile && !state.busy && !state.playing) { Text("녹음 듣기") }
            Button(onClick = model::stopPlayback, enabled = state.playing) { Text("재생 중지") }
            Button(onClick = model::delete, enabled = state.hasFile && !state.busy && !state.playing) { Text("녹음 삭제") }
        }
    }
}
