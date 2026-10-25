package com.shsh.pleos.playground.ui.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SttProbeSection(model: SttProbeViewModel, stopTts: () -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SpeechToText 3.3.1 · ON_DEVICE", style = MaterialTheme.typography.titleLarge)
        Text(state.status, style = MaterialTheme.typography.headlineSmall)
        Text(state.detail)
        Text(if (state.text.isBlank()) "인식 텍스트: 없음" else "인식 텍스트: ${state.text}", style = MaterialTheme.typography.titleLarge)
        Text("테스트 예시: 안녕하세요. 오늘 날씨가 좋습니다.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { stopTts(); model.recognize() }, enabled = state.ready && !state.initializing && !state.listening) { Text("인식 시작") }
            Button(onClick = model::stop, enabled = state.listening) { Text("인식 중지") }
            Button(onClick = model::initialize, enabled = !state.initializing && !state.listening) { Text("STT 다시 초기화") }
        }
    }
}
