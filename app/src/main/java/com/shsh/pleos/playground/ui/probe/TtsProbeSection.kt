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
import com.shsh.pleos.playground.data.TtsRepository

@Composable
fun TtsProbeSection(model: TtsProbeViewModel, stopStt: () -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            model.stop()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("TextToSpeech 3.3.1 · ON_DEVICE", style = MaterialTheme.typography.titleLarge)
        Text(state.status, style = MaterialTheme.typography.headlineSmall)
        Text(state.detail)
        Text("테스트 문장: ${TtsRepository.TEST_SENTENCE}")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { stopStt(); model.speak() }, enabled = state.ready && !state.busy) { Text("음성 테스트") }
            Button(onClick = model::stop, enabled = state.ready && state.busy) { Text("중지") }
            Button(onClick = model::initialize, enabled = !state.busy) { Text("TTS 다시 초기화") }
        }
    }
}
