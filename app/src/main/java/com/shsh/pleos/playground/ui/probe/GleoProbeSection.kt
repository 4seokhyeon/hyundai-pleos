package com.shsh.pleos.playground.ui.probe

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shsh.pleos.playground.BuildConfig
import com.shsh.pleos.playground.gleo.GleoDiagnostics
import com.shsh.pleos.playground.gleo.GleoSearchContract
import com.shsh.pleos.playground.ui.karaoke.Action
import org.json.JSONObject

@Composable
fun GleoProbeSection() {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val state by GleoDiagnostics.state.collectAsStateWithLifecycle()
    val externalState by GleoDiagnostics.externalState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    fun request(packed: Boolean, missing: Boolean = false, invalid: Boolean = false) {
        GleoDiagnostics.started()
        val request = Intent(GleoSearchContract.QUALIFIED_ACTION).setPackage(context.packageName)
            .putExtra(GleoSearchContract.DIAGNOSTIC, true)
        if (invalid) request.putExtra(GleoSearchContract.PACKED_PARAMETERS, "{invalid-json")
        else if (!missing) {
            if (packed) request.putExtra(GleoSearchContract.PACKED_PARAMETERS, JSONObject().put("query", query).toString())
            else request.putExtra("query", query)
        }
        try { context.startActivity(request) }
        catch (error: Exception) { Log.e("SHSH_GLEO", "Diagnostic entry failed; type=${error.javaClass.simpleName}; values omitted") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Gleo 노래 검색 연결 · 로컬 검증", style = MaterialTheme.typography.titleLarge)
        Text("실제 Gleo 음성 호출이나 스키마 승인을 확인하는 메뉴는 아니에요. 동일한 앱 진입점과 검색 UI를 확인하며 Gleo로 Callback을 보내지 않아요.")
        Text("외부 명령 수신 상태", style = MaterialTheme.typography.titleMedium)
        Text(externalState)
        Text("Gleo에 검색을 요청한 뒤 이 상태를 확인하세요. 앱 프로세스가 다시 시작되면 기록은 초기화됩니다. 외부 호출자의 신원을 인증하는 표시는 아닙니다.", style = MaterialTheme.typography.bodySmall)
        Text("로컬 검증 상태", style = MaterialTheme.typography.titleMedium)
        Text(state)
        Action("검색어 없이 요청 · asking", secondary = true, onClick = { request(packed = false, missing = true) })
        OutlinedTextField(query, { query = it.take(GleoSearchContract.MAX_QUERY) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("검색할 곡명 또는 가수") })
        Action("검색어 전달 · 개별 extra", enabled = query.isNotBlank(), onClick = { request(packed = false) })
        Action("검색어 전달 · JSON", enabled = query.isNotBlank(), onClick = { request(packed = true) })
        Action("잘못된 JSON · fail", secondary = true, onClick = { request(packed = true, invalid = true) })
        Text("검색 요청은 실제 YouTube API를 사용해요. 준비 중인 녹음이 있으면 작업을 마치거나 삭제한 뒤 요청해 주세요.", style = MaterialTheme.typography.bodySmall)
    }
}
