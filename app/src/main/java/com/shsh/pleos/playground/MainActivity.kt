package com.shsh.pleos.playground

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shsh.pleos.playground.ui.probe.ProbeUiState
import com.shsh.pleos.playground.ui.probe.VehicleProbeViewModel
import com.shsh.pleos.playground.ui.theme.PleosSdkPlaygroundTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("SHSH_APP", "Vehicle SDK probe screen started")
        enableEdgeToEdge()
        setContent {
            val model: VehicleProbeViewModel = viewModel()
            val state by model.uiState.collectAsStateWithLifecycle()
            PleosSdkPlaygroundTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    ProbeScreen(state, model::refresh, Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun ProbeScreen(state: ProbeUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Pleos SDK 검증", style = MaterialTheme.typography.headlineLarge)
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
