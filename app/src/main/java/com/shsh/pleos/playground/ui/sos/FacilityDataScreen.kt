package com.shsh.pleos.playground.ui.sos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shsh.pleos.playground.data.FacilitySource
import com.shsh.pleos.playground.data.DatasetRestore
import com.shsh.pleos.playground.data.DatasetProgress
import com.shsh.pleos.playground.ui.karaoke.Action
import com.shsh.pleos.playground.ui.karaoke.DesignShell
import com.shsh.pleos.playground.ui.probe.PublicDataProbeState
import com.shsh.pleos.playground.ui.theme.PleosColors as C
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun FacilityDataScreen(state: PublicDataProbeState, allowed: Boolean, parkingKey: Boolean, restroomKey: Boolean,
    onCollect: (FacilitySource) -> Unit, onCancel: () -> Unit, onDiagnostics: () -> Unit, onHome: () -> Unit, onSos: () -> Unit) {
    DesignShell("화장실 SOS · 데이터 준비", allowed, onHome = onHome, onDiagnostics = onDiagnostics) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("주변 탐색을 위한\n데이터를 준비해요", style = MaterialTheme.typography.headlineLarge)
                Text("01  데이터 준비   →   02  주변 후보   →   03  길안내", color = C.Mint, style = MaterialTheme.typography.labelLarge)
                if (!allowed) {
                    Text("플랫폼 이용 제한 또는 상태 확인 중이에요. 수집은 중단되며 허용 상태에서 직접 다시 시작할 수 있어요.", color = C.Amber)
                } else {
                    Text("화장실은 경기 지역, 주차장은 전국 제공기관 자료를 기기에 저장해요. 수집할 때 차량 위치는 전송하지 않아요.", color = C.Muted)
                    if (state.cacheLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("기기에 저장된 자료 확인 중…", color = C.Muted)
                    }
                    state.collection?.let { progress ->
                        CollectionProgress(progress)
                        Action("수집 취소", secondary = true, onClick = onCancel)
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val restroom: @Composable () -> Unit = {
                            SourceCard(FacilitySource.RESTROOM, state.datasets[FacilitySource.RESTROOM], restroomKey,
                                !state.busy && !state.cacheLoading, state.collection?.source == FacilitySource.RESTROOM,
                                onCollect = { onCollect(FacilitySource.RESTROOM) })
                        }
                        val parking: @Composable () -> Unit = {
                            SourceCard(FacilitySource.PARKING, state.datasets[FacilitySource.PARKING], parkingKey,
                                !state.busy && !state.cacheLoading, state.collection?.source == FacilitySource.PARKING,
                                onCollect = { onCollect(FacilitySource.PARKING) })
                        }
                        if (maxWidth >= 1160.dp) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.weight(1f)) { restroom() }; Column(Modifier.weight(1f)) { parking() }
                        } else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) { restroom(); parking() }
                    }
                    if (!state.cacheLoading) Text(state.message, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                    if (FacilitySource.entries.all { state.datasets[it]?.summary != null }) {
                        Surface(color = C.Mint.copy(alpha = .08f), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, C.Mint.copy(alpha = .25f))) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("두 자료가 기기에 준비됐어요", color = C.Mint, style = MaterialTheme.typography.titleLarge)
                                Text("저장 자료로 주변 화장실을 찾고 인근 주차장 등록 정보를 확인할 수 있어요.", color = C.Muted)
                                Action("주변 화장실 찾기", icon = "search", onClick = onSos)
                            }
                        }
                    }
                    Text("수집 시각은 기기에 받은 시각이며 시설별 데이터 기준일과 달라요. 개방시간·요금은 등록 정보로, 현재 개방이나 빈자리를 보장하지 않아요.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = C.Border)
                    TextButton(onClick = onDiagnostics, enabled = !state.busy && !state.cacheLoading) { Text("개발용 · 5건 조회와 위치 거리 확인") }
                }
            }
    }
}

@Composable
private fun SourceCard(source: FacilitySource, restored: DatasetRestore?, configured: Boolean, idle: Boolean,
    collecting: Boolean, onCollect: () -> Unit) {
    val summary = restored?.summary
    Surface(Modifier.fillMaxWidth(), color = C.Card, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, C.Border)) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(if (source == FacilitySource.RESTROOM) "RESTROOM DATA" else "PARKING DATA", color = C.Mint, style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(source.title, style = MaterialTheme.typography.headlineSmall)
                Text(when { collecting -> "수집 중"; summary != null -> "저장 완료"; restored?.problem != null -> "다시 수집 필요"; else -> "준비 전" },
                    color = if (summary != null && !collecting) C.Green else C.Amber, style = MaterialTheme.typography.labelMedium)
            }
            Text(source.coverage, color = C.Muted)
            if (summary != null) {
                Text("검색용 ${summary.savedRows}건", style = MaterialTheme.typography.headlineMedium)
                Text("원본 수신 ${summary.sourceRows}건 · 좌표 제외 ${summary.excludedCoordinates}건", color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                Text("전체 ${summary.pages}페이지 확인", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                Text("기기 수집 시각 ${formatTime(summary.savedAtMillis)}", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                if (System.currentTimeMillis() - summary.savedAtMillis > 86_400_000) Text("하루 이상 지난 저장본이에요. 필요할 때 갱신해 주세요.", color = C.Amber, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(restored?.problem ?: "처음 한 번 전체 자료를 준비해 주세요.", color = C.Muted)
            }
            Action(if (summary == null) "자료 수집" else "자료 갱신", enabled = idle && configured, onClick = onCollect)
            if (!configured) Text("인증키 설정이 필요해요.", color = C.Amber, style = MaterialTheme.typography.bodySmall)
            Text("갱신이 끝나기 전까지 이전 완성본을 유지해요.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CollectionProgress(progress: DatasetProgress) {
    Surface(color = C.Raised, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (progress.saving) "${progress.source.title} 저장 중" else "${progress.source.title} 수집 중", style = MaterialTheme.typography.titleLarge)
            val fraction = progress.fraction
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("${progress.page} / ${progress.totalPages?.toString() ?: "확인 중"} 페이지 · ${progress.received} / ${progress.total?.toString() ?: "확인 중"}건", color = C.Muted)
            Text("좌표가 없거나 유효하지 않아 제외한 항목 ${progress.excluded}건", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            Text("전체 건수 검증과 기기 저장이 끝나야 완료로 표시해요.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatTime(millis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
