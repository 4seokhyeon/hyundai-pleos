package com.shsh.pleos.playground.ui.sos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shsh.pleos.playground.data.RestroomCandidate
import com.shsh.pleos.playground.data.FacilitySource
import com.shsh.pleos.playground.ui.karaoke.*
import com.shsh.pleos.playground.ui.probe.SosNavigationState
import com.shsh.pleos.playground.ui.probe.PublicDataProbeState
import com.shsh.pleos.playground.ui.theme.PleosColors as C
import java.util.Locale
import kotlin.math.roundToInt

fun formatFacilityDistance(meters: Double): String = if(meters<1000) "${meters.roundToInt()}m" else String.format(Locale.KOREA,"%.1fkm",meters/1000)

@Composable
fun SosScreen(state: PublicDataProbeState,allowed: Boolean,selected: RestroomCandidate?,onSelect: (RestroomCandidate?)->Unit,
    onFind: ()->Unit,onCancel: ()->Unit,onData: ()->Unit,onHome: ()->Unit,
    navigation: SosNavigationState,onNavigate: (String)->Unit) {
    val ready=FacilitySource.entries.all { state.datasets[it]?.summary!=null }
    DesignShell(if(selected!=null) "화장실 SOS · 확인" else if(state.nearbySearching) "화장실 SOS · 찾는 중" else "화장실 SOS · 후보",allowed,onHome=onHome,onData=onData) {
        when {
            !allowed -> RestrictionScreen(onHome)
            state.cacheLoading -> CenterState("LOCAL DATA","저장 자료 확인 중","기기에 준비된 시설 자료를 읽고 있어요.","toilet",C.Amber,busy=true) {}
            state.nearbySearching -> CenterState("NEARBY SEARCH","화장실을 찾을게요","차량 마지막 위치와 기기 저장 자료를 비교해요. 위치는 외부 API로 보내지 않아요.","toilet",C.Amber,busy=true) { Action("취소",secondary=true,icon="close",onClick=onCancel) }
            state.nearbyError!=null -> ErrorScreen("주변 검색을 완료하지 못했어요",state.nearbyError,onCancel=onCancel,onRetry=onFind)
            selected!=null -> ConfirmCandidate(selected,navigation,onNavigate,onBack={onSelect(null)})
            !ready -> CenterState("SOS DATA PREPARATION","화장실 SOS를 준비해요","먼저 화장실과 주차장 자료를 기기에 준비해 주세요.","toilet",C.Amber) { Action("데이터 준비 열기",icon="upload",onClick=onData) }
            !state.nearbyCompleted -> CenterState("SOS NEARBY SEARCH","가까운 화장실을 찾을까요?","경기 지역 화장실을 마지막 위치 기준 5km 안에서 찾아요. 음악은 이 화면에 들어올 때 멈춰요.","toilet",C.Amber) {
                Action("현재 위치로 찾기",icon="search",onClick=onFind)
                Spacer(Modifier.height(16.dp)); Text("Gleo에 “노래하자에서 가까운 화장실 찾아줘”라고 말할 수도 있어요.",color=C.Muted,style=MaterialTheme.typography.bodySmall)
            }
            state.candidates.isEmpty() -> CenterState("NO NEARBY CANDIDATES","주변 후보가 없어요","경기 화장실 저장 자료에서 마지막 위치 기준 5km 안의 후보를 찾지 못했어요. 에뮬레이터 위치와 자료 수집 상태를 확인해 주세요.","toilet",C.Amber) {
                Action("위치 다시 확인",icon="retry",enabled=!navigation.busy,onClick=onFind); Spacer(Modifier.height(12.dp)); Action("데이터 관리",secondary=true,onClick=onData)
            }
            else -> CandidateResults(state,onSelect,onFind,!navigation.busy)
        }
    }
}

@Composable
private fun CandidateResults(state: PublicDataProbeState,onSelect: (RestroomCandidate)->Unit,onFind: ()->Unit,enabled: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns=if(maxWidth>=1680.dp) 3 else if(maxWidth>=1120.dp) 2 else 1
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=40.dp,vertical=28.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) { Eyebrow("NEARBY CANDIDATES"); Text("주변 화장실 후보",style=MaterialTheme.typography.headlineMedium) }
                Action("다시 찾기",secondary=true,icon="retry",enabled=enabled,onClick=onFind)
            }
            Text("${state.locationMessage} · 5km 안 ${state.candidates.size}개 후보 · 모든 거리는 직선거리",color=C.Muted,style=MaterialTheme.typography.bodySmall)
            state.candidates.chunked(columns).forEachIndexed { rowIndex,row ->
                Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    row.forEachIndexed { index,candidate ->
                        Surface(onClick={onSelect(candidate)},enabled=enabled,modifier=Modifier.weight(1f),color=C.Card,shape=RoundedCornerShape(23.dp),border=BorderStroke(1.dp,C.Border)) {
                            Column(Modifier.heightIn(min=420.dp).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                    Surface(color=C.Mint.copy(alpha=.1f),shape=RoundedCornerShape(17.dp)) { Box(Modifier.size(64.dp),contentAlignment=Alignment.Center) { Glyph("toilet",modifier=Modifier.size(34.dp)) } }
                                    Glyph("chevron",C.Muted)
                                }
                                Text("후보 ${rowIndex*columns+index+1}",color=C.Muted,fontSize=22.sp)
                                Text(candidate.restroom.name,style=MaterialTheme.typography.headlineSmall,maxLines=3,overflow=TextOverflow.Ellipsis)
                                Text("직선 ${formatFacilityDistance(candidate.distanceMeters)}",style=MaterialTheme.typography.titleMedium)
                                Text("등록 개방시간 ${candidate.restroom.hours ?: "확인 필요"}",color=C.Muted,style=MaterialTheme.typography.bodySmall)
                                Chip("현재 개방 확인 필요",C.Amber)
                                Text(if(candidate.parking!=null) "인근 주차장 · 화장실에서 직선 ${formatFacilityDistance(candidate.parkingDistanceMeters!!)}" else "300m 내 등록 주차장 없음",color=C.Muted,style=MaterialTheme.typography.bodySmall)
                                if(candidate.parking!=null) Text("요금 ${candidate.parking.fee ?: "확인 필요"} · 빈자리 확인 필요",color=C.Muted,fontSize=22.sp)
                            }
                        }
                    }
                    repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text("출처: 경기데이터드림·공공데이터포털. 담장·출입구와 도보 경로는 확인되지 않았어요.",color=C.Muted,style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConfirmCandidate(candidate: RestroomCandidate,navigation: SosNavigationState,onNavigate: (String)->Unit,onBack: ()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=1280.dp
        val details: @Composable ()->Unit = {
            Surface(color=C.Panel,shape=RoundedCornerShape(26.dp),border=BorderStroke(1.dp,C.Border)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    Eyebrow("DESTINATION CHECK"); Text("이곳을 확인해 주세요",style=MaterialTheme.typography.headlineMedium)
                    Chip("현재 개방 확인 필요",C.Amber)
                    Text(candidate.restroom.name,style=MaterialTheme.typography.headlineSmall)
                    Text(candidate.restroom.address,color=C.Muted)
                    Text("마지막 위치에서 직선 ${formatFacilityDistance(candidate.distanceMeters)}",color=C.Mint)
                    Text("등록 개방시간 ${candidate.restroom.hours ?: "확인 필요"}",color=C.Muted,style=MaterialTheme.typography.bodyMedium)
                    Text("자료 기준일 ${candidate.restroom.referenceDate}",color=C.Muted,style=MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color=C.Border)
                    Text(candidate.parking?.name ?: "300m 내 등록 주차장 없음",style=MaterialTheme.typography.titleMedium)
                    candidate.parking?.let { parking ->
                        Text(parking.address,color=C.Muted,style=MaterialTheme.typography.bodySmall)
                        Text("화장실에서 직선 ${formatFacilityDistance(candidate.parkingDistanceMeters!!)} · 요금 ${parking.fee ?: "확인 필요"}",color=C.Muted,style=MaterialTheme.typography.bodySmall)
                    }
                    Notice("이용 전 확인해 주세요","실시간 개방·주차 빈자리와 도보 진입 가능 여부는 확인되지 않았어요.")
                    Text(navigation.message,color=if(navigation.confirmed) C.Mint else C.Muted,style=MaterialTheme.typography.bodyMedium)
                    if(navigation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        Action("화장실로 내비 안내",enabled=!navigation.busy,icon="nav",modifier=Modifier.fillMaxWidth(),onClick={onNavigate("RESTROOM")})
                        if(candidate.parking!=null) Action("인근 주차장으로 안내",secondary=true,enabled=!navigation.busy,icon="nav",modifier=Modifier.fillMaxWidth(),onClick={onNavigate("PARKING")})
                        Action("다른 곳 보기",secondary=true,enabled=!navigation.busy,modifier=Modifier.fillMaxWidth(),onClick=onBack)
                    }
                }
            }
        }
        if(wide) Row(Modifier.fillMaxSize().padding(32.dp),horizontalArrangement=Arrangement.spacedBy(40.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1.25f).fillMaxHeight()) { details() }
            Column(Modifier.weight(.75f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(24.dp)) { Orb("toilet"); Eyebrow("LOCATION SUMMARY"); Text("시설 위치 요약",style=MaterialTheme.typography.headlineSmall); Text("화장실 또는 인근 주차장을\n목적지로 선택해 주세요.",color=C.Muted) }
        } else Box(Modifier.fillMaxSize().padding(24.dp)) { details() }
    }
}
