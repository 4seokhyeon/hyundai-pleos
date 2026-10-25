package com.shsh.pleos.playground.ui.probe

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shsh.pleos.playground.data.LocationRepository

@Composable
fun LocationProbeSection(model: LocationProbeViewModel) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.onPermissionResult(granted)
    }
    LaunchedEffect(model) { model.refresh() }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
            if (event == Lifecycle.Event.ON_STOP) model.cancelQuery()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.cancelQuery() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("FusedLocation 1.1.3 · 마지막 위치 조회", style = MaterialTheme.typography.titleLarge)
        Text(state.status, style = MaterialTheme.typography.headlineSmall)
        Text("isSupported: ${state.supported?.toString() ?: "미확인"} · 권한: ${if (state.permissionGranted) "허용" else "미허용"}")
        Text(state.detail)
        Text(state.result, style = MaterialTheme.typography.titleLarge)
        Text("좌표는 이 화면에만 표시하며 SHSH_LOCATION 로그에는 남기지 않습니다.")
        Button(
            onClick = model::queryLastLocation,
            enabled = !state.busy && state.supported == true && state.permissionGranted
        ) { Text(if (state.busy) "확인/조회 중…" else "마지막 위치 1회 조회") }
        Button(
            enabled = !state.busy && state.supported == true && !state.permissionGranted,
            onClick = {
                Log.i("SHSH_LOCATION", "Runtime permission requested by user")
                launcher.launch(LocationRepository.PERMISSION)
            }
        ) { Text("위치 권한 요청") }
        Button(onClick = model::refresh, enabled = !state.busy) { Text("지원·권한 다시 확인") }
        if (state.supported == true && !state.permissionGranted) {
            Text("권한 창이 나오지 않으면 앱 설정에서 허용 가능 여부를 확인하세요.")
            Button(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                try { context.startActivity(intent) }
                catch (error: Exception) { Log.e("SHSH_LOCATION", "Cannot open app settings", error) }
            }) { Text("앱 설정 열기") }
        }
    }
}
