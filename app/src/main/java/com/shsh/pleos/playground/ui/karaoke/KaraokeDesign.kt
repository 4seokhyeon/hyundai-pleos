@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.shsh.pleos.playground.ui.karaoke

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shsh.pleos.playground.data.Song
import com.shsh.pleos.playground.ui.probe.RecordingProbeUiState
import com.shsh.pleos.playground.ui.probe.SongSearchUiState
import com.shsh.pleos.playground.ui.theme.PleosColors as C

@Composable
fun Action(label: String, enabled: Boolean = true, secondary: Boolean = false, recording: Boolean = false, amber: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = when { recording -> C.Recording; amber -> C.Amber; secondary -> C.Raised; else -> C.Mint },
            contentColor = if (secondary) C.Text else C.Background),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) { Text(label) }
}

@Composable
private fun Glyph(kind: String, color: Color = C.Mint, modifier: Modifier = Modifier.size(32.dp)) {
    Canvas(modifier) {
        val u = size.minDimension / 32f
        val line = 2.2f * u
        if (kind == "mic") {
            drawRoundRect(color, Offset(12*u, 3*u), Size(8*u, 16*u), CornerRadius(4*u), style = Stroke(line))
            drawArc(color, 0f, 180f, false, Offset(7*u, 8*u), Size(18*u, 17*u), style = Stroke(line))
            drawLine(color, Offset(16*u, 25*u), Offset(16*u, 29*u), line)
            drawLine(color, Offset(10*u, 29*u), Offset(22*u, 29*u), line)
        } else if (kind == "search") {
            drawCircle(color, 9*u, Offset(13*u, 13*u), style = Stroke(line))
            drawLine(color, Offset(20*u, 20*u), Offset(28*u, 28*u), line)
        } else if (kind == "menu") {
            listOf(9,16,23).forEach { y -> drawLine(color, Offset(7*u,y*u),Offset(25*u,y*u),line) }
        } else if (kind == "play") {
            drawPath(Path().apply { moveTo(11*u,6*u); lineTo(26*u,16*u); lineTo(11*u,26*u); close() }, color)
        } else {
            drawLine(color, Offset(13*u,7*u), Offset(13*u,25*u), line)
            drawLine(color, Offset(24*u,4*u), Offset(24*u,22*u), line)
            drawLine(color, Offset(13*u,7*u), Offset(24*u,4*u), line)
            drawCircle(color, 4*u, Offset(9*u,25*u)); drawCircle(color,4*u,Offset(20*u,22*u))
        }
    }
}

@Composable
private fun Eyebrow(text: String, color: Color = C.Mint) {
    Text(text, color = color, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp))
}

@Composable
private fun Chip(label: String, color: Color) {
    Surface(color = color.copy(alpha = .10f), shape = CircleShape, border = BorderStroke(1.dp, color.copy(alpha = .14f))) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DesignShell(title: String, state: String, stateColor: Color, onHome: (() -> Unit)? = null,
    onDiagnostics: (() -> Unit)? = null, content: @Composable () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = C.Background) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = C.Panel) {
                Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 32.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(color = C.Mint, shape = RoundedCornerShape(12.dp)) { Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Glyph("music", C.Background, Modifier.size(26.dp)) } }
                    Text("Pleos", fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    Text("카라오케 & SOS", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(title, color = C.Muted, style = MaterialTheme.typography.bodySmall) }
                    Chip(state, stateColor)
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(56.dp).semantics { contentDescription = "화면 메뉴 열기" }.background(C.Card, RoundedCornerShape(16.dp))) { Glyph("menu", C.Text) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (onHome != null) DropdownMenuItem(text = { Text("홈으로") }, onClick = { menu = false; onHome() })
                            if (onDiagnostics != null) DropdownMenuItem(text = { Text("개발용 SDK 검증") }, onClick = { menu = false; onDiagnostics() })
                            DropdownMenuItem(text = { Text("닫기") }, onClick = { menu = false })
                        }
                    }
                }
            }
            HorizontalDivider(color = C.Border.copy(alpha = .55f))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
        }
    }
}

@Composable
private fun Split(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val panelLeft: @Composable () -> Unit = {
            Box(Modifier.fillMaxWidth().heightIn(min = 500.dp).background(C.Background)) {
                Canvas(Modifier.matchParentSize()) {
                    val gap = 56.dp.toPx()
                    var x = 0f
                    while (x < size.width) { drawLine(C.Border.copy(alpha = .09f), Offset(x,0f),Offset(x,size.height)); x += gap }
                    var y = 0f
                    while (y < size.height) { drawLine(C.Border.copy(alpha = .09f), Offset(0f,y),Offset(size.width,y)); y += gap }
                }
                Column(Modifier.fillMaxWidth().padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp), content = left)
            }
        }
        val panelRight: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().heightIn(min = 500.dp).background(C.Panel).padding(32.dp), verticalArrangement = Arrangement.spacedBy(22.dp), content = right)
        }
        if (maxWidth >= 800.dp) Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(2.2f)) { panelLeft() }
            Box(Modifier.width(1.dp).height(500.dp).background(C.Border.copy(alpha = .5f)))
            Column(Modifier.weight(1f).background(C.Panel)) { panelRight() }
        } else Column { panelLeft(); panelRight() }
    }
}

@Composable
private fun MicButton(enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(224.dp), contentAlignment = Alignment.Center) {
        listOf(224,204,184).forEachIndexed { i, diameter ->
            Surface(Modifier.size(diameter.dp), shape = CircleShape, color = C.Mint.copy(alpha = .025f + i*.02f), border = BorderStroke(1.dp,C.Mint.copy(alpha = .05f))) {}
        }
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.size(160.dp).semantics { contentDescription = "음성으로 노래 검색" }, shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = C.Mint,contentColor = C.Background), contentPadding = PaddingValues(0.dp)) { Glyph("mic", if (enabled) C.Background else C.Muted, Modifier.size(54.dp)) }
    }
}

@Composable
private fun SosDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Text("SOS", color = C.Amber, style = MaterialTheme.typography.titleLarge) },
        title = { Text("화장실 SOS 준비 중") }, text = { Text("주변 시설 조회와 내비 연결을 준비하고 있어요. 아직 실제 후보나 길안내를 제공하지 않아요.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } })
}

@Composable
private fun QueuePanel(onSos: () -> Unit) {
    Eyebrow("UP NEXT")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("예약 목록", style = MaterialTheme.typography.titleLarge)
        Text("0곡", color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
    Surface(Modifier.fillMaxWidth(), color = C.Card, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp,C.Border)) {
        Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Glyph("music", C.Muted)
            Text("아직 예약한 곡이 없어요", style = MaterialTheme.typography.titleMedium)
            Text("현재는 검색한 곡을 바로 재생할 수 있어요. 예약 기능은 준비 중이에요.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    Action("화장실 SOS", amber = true, onClick = onSos)
    Text("시설 탐색 · 준비 중", color = C.Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun KaraokeHome(onStart: () -> Unit, onDiagnostics: () -> Unit, onVoice: () -> Unit = onStart, onVocal: () -> Unit = onStart) {
    var sos by remember { mutableStateOf(false) }
    DesignShell("노래 검색 홈", "정차 후 이용", C.Amber, onDiagnostics = onDiagnostics) {
        Split(left = {
            Eyebrow("VOICE KARAOKE")
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val headline: @Composable () -> Unit = { Text("어떤 노래를\n불러 볼까요?", style = MaterialTheme.typography.headlineLarge) }
                val mic: @Composable () -> Unit = { Column(horizontalAlignment = Alignment.CenterHorizontally) { MicButton(true, onVoice); Text("노래 이름을 말해 보세요", style = MaterialTheme.typography.bodyMedium) } }
                if (maxWidth >= 600.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { headline() }; mic()
                } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { headline(); mic() }
            }
            Surface(onClick = onStart, color = C.Card, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp,C.Border), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Glyph("search", C.Muted)
                    Column { Text("곡명 또는 가수 검색", style = MaterialTheme.typography.titleMedium); Text("터치해서 입력", color = C.Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
            TextButton(onClick = onVocal) { Text("반주 없이 목소리만 녹음하기  →") }
        }, right = { QueuePanel { sos = true } })
    }
    if (sos) SosDialog { sos = false }
}

@Composable
private fun Notice(title: String, message: String) {
    Surface(Modifier.fillMaxWidth(), color = C.Amber.copy(alpha = .07f), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp,C.Amber.copy(alpha = .2f))) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = C.Amber, style = MaterialTheme.typography.titleMedium)
            Text(message, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Listening(onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Glyph("mic", modifier = Modifier.size(64.dp)); Eyebrow("VOICE SEARCH")
        Text("듣고 있어요", style = MaterialTheme.typography.headlineLarge)
        Text("곡명이나 가수를 편하게 말해 주세요.", color = C.Muted)
        LinearProgressIndicator(Modifier.widthIn(max = 360.dp).fillMaxWidth(), color = C.Mint)
        Action("취소", secondary = true, onClick = onCancel)
    }
}

@Composable
fun KaraokeWorkspace(
    allowed: Boolean, mode: String?, hasPlayer: Boolean, playerReady: Boolean, status: String,
    search: SongSearchUiState, recording: RecordingProbeUiState,
    consent: Boolean, uploadConsent: Boolean,
    onMode: (String) -> Unit, onEdit: (String) -> Unit, onSearch: () -> Unit, onVoice: () -> Unit,
    onSearchCancel: () -> Unit, onSong: (Song) -> Unit, onConsent: (Boolean) -> Unit,
    onUploadConsent: (Boolean) -> Unit, onRecord: () -> Unit, onFinish: () -> Unit,
    onListen: () -> Unit, onStopListen: () -> Unit, onDelete: () -> Unit,
    onRecordConsentOpen: () -> Unit, onScore: () -> Unit, onCancelScore: () -> Unit, onClosePlayer: () -> Unit, onHome: () -> Unit,
    player: @Composable () -> Unit
) {
    val idle = !recording.busy && !recording.playing && !recording.exporting && !recording.scoring && !search.busy
    val condition = if (recording.inputKind == "vocal_only") "무반주" else "반주 포함"
    var sos by remember { mutableStateOf(false) }
    var recordDialog by remember { mutableStateOf(false) }
    var uploadDialog by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }
    LaunchedEffect(allowed) { if (!allowed) { recordDialog = false; uploadDialog = false; resetDialog = false; sos = false } }
    val title = when { !allowed -> "이용 제한"; recording.scoring -> "AI 분석"; recording.score != null -> "분석 결과"; recording.busy -> "노래 녹음"; hasPlayer -> "노래방 재생"; recording.hasFile -> "녹음 확인"; mode == "vocal_only" -> "무반주 녹음"; else -> "노래 검색" }
    DesignShell(title, if (allowed) "이용 가능" else "이용 제한 · 확인 중", if (allowed) C.Green else C.Amber, onHome = onHome) {
        if (!allowed) {
            Column(Modifier.fillMaxWidth().padding(48.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Notice("현재 영상과 점수판을 표시할 수 없어요", "주행 제한 또는 이용 상태를 확인하고 있어요. 플랫폼에서 허용된 상태에서 다시 시작해 주세요.")
                Text("안전한 이용을 위해\n잠시 멈췄어요", style = MaterialTheme.typography.headlineLarge)
                Action("음성으로 곡 찾기", enabled = false, onClick = {})
                Text("이용 가능 상태에서 다시 시작할 수 있어요. 자동으로 재생하지 않아요.", color = C.Muted)
                Action("홈으로", secondary = true, onClick = onHome)
            }
        } else if (recording.scoring) {
            Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
                Eyebrow("ANALYZING YOUR VOICE")
                CircularProgressIndicator(Modifier.size(76.dp), color = C.Mint, strokeWidth = 5.dp)
                Text(if (recording.scoreStatus.contains("전송")) "업로드 중" else "분석 중", style = MaterialTheme.typography.headlineLarge)
                Text(recording.scoreStatus, color = C.Muted)
                Text("평가 중에도 취소할 수 있어요.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                Action("취소", secondary = true, onClick = onCancelScore)
            }
        } else if (recording.score != null) {
            val score = recording.score
            Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Eyebrow("YOUR VOICE, YOUR MOMENT")
                Text("AI 재미 점수", style = MaterialTheme.typography.headlineMedium)
                Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawArc(C.Border, 135f, 270f, false, style = Stroke(12.dp.toPx()))
                        drawArc(C.Mint, 135f, 270f * score.funScore / 100f, false, style = Stroke(12.dp.toPx()))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${score.funScore}", fontSize = 76.sp, lineHeight = 88.sp, fontWeight = FontWeight.Bold, color = C.Mint)
                        Text("/ 100점", color = C.Muted)
                    }
                }
                Chip(condition, C.Mint)
                Text("공모전 시연용 환산 점수예요. 녹음 환경에 따라 달라질 수 있어요.", color = C.Muted)
                Text("원곡의 음정·박자 정확도나 가창 실력 점수가 아니에요.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action("곡 목록으로", secondary = true, enabled = idle, onClick = { resetDialog = true })
                    Action("한 번 더 부르기", enabled = idle, onClick = onDelete)
                }
                Text("원점수 ${"%.4f".format(score.rawMos)} · ${score.scoreRuleVersion} · 추론 ${"%.2f".format(score.inferenceSeconds)}초", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Split(left = {
                when {
                    hasPlayer -> {
                        Eyebrow("OFFICIAL YOUTUBE PLAYER")
                        if (recording.busy) Chip("●  녹음 중 · 최대 15초", C.Recording)
                        player()
                        Text(status, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                        Action("재생 중지 · 곡 목록으로", secondary = true, onClick = onClosePlayer)
                    }
                    search.listening -> Listening(onSearchCancel)
                    recording.busy -> {
                        Eyebrow("RECORDING", C.Recording)
                        Text("목소리를 담고 있어요", style = MaterialTheme.typography.headlineLarge)
                        Chip("●  녹음 중 · 최대 15초", C.Recording)
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.Recording)
                        Text(recording.status, color = C.Muted)
                    }
                    recording.hasFile -> {
                        Eyebrow("LISTEN BEFORE YOU SEND")
                        Text("내 노래를\n들어 볼까요?", style = MaterialTheme.typography.headlineLarge)
                        Chip(condition, C.Mint)
                        Text(recording.status, color = C.Muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Action(if (recording.playing) "청취 중지" else "녹음 듣기", secondary = true, enabled = !recording.exporting, onClick = if (recording.playing) onStopListen else onListen)
                            Action("다시 녹음", secondary = true, enabled = idle, onClick = onDelete)
                        }
                    }
                    mode == "vocal_only" -> {
                        Eyebrow("VOICE ONLY")
                        Text("반주 없이,\n나의 목소리로", style = MaterialTheme.typography.headlineLarge)
                        Glyph("mic", modifier = Modifier.size(80.dp))
                        Text("최대 15초 녹음 · 다른 앱과 외부 스피커의 반주도 꺼주세요.", color = C.Muted)
                        if (recording.status.contains("실패") || recording.status.contains("권한")) Notice("녹음을 확인해 주세요",recording.status)
                    }
                    else -> {
                        Eyebrow("VOICE KARAOKE")
                        if (search.songs.isEmpty()) {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                if (maxWidth >= 600.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("어떤 노래를\n불러 볼까요?", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) { MicButton(idle, onVoice); Text("노래 이름을 말해 보세요", style = MaterialTheme.typography.bodyMedium) }
                                } else Column { Text("어떤 노래를\n불러 볼까요?",style = MaterialTheme.typography.headlineLarge); MicButton(idle,onVoice) }
                            }
                        } else { Chip("검색어 · ${search.query}",C.Mint); Text("이 곡이 맞나요?",style = MaterialTheme.typography.headlineMedium) }
                        OutlinedTextField(value = search.query, onValueChange = onEdit, modifier = Modifier.fillMaxWidth(), enabled = idle, singleLine = true,
                            label = { Text("곡명 또는 가수 검색") }, shape = RoundedCornerShape(18.dp), leadingIcon = { Glyph("search",C.Muted) })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Action("검색", enabled = idle,onClick = onSearch)
                            if (search.songs.isNotEmpty()) Action("음성 검색", secondary = true,enabled = idle,onClick = onVoice)
                            if (search.busy) Action("취소", secondary = true,onClick = onSearchCancel)
                            TextButton(onClick = { onMode("vocal_only") },enabled = idle) { Text("무반주 녹음 →") }
                        }
                        if (search.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(search.message,color = C.Muted,style = MaterialTheme.typography.bodySmall)
                        if (status.contains("오류") || status.contains("허용되지") || status.contains("중단")) Notice("영상 재생을 확인해 주세요",status)
                        if (recording.status.contains("실패") || recording.status.contains("권한")) Notice("녹음을 확인해 주세요",recording.status)
                        search.songs.forEachIndexed { index,song ->
                            Surface(onClick = { onSong(song) }, enabled = idle, color = C.Card, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp,C.Border)) {
                                Row(Modifier.fillMaxWidth().padding(22.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    Text("%02d".format(index+1),color = C.Mint,style = MaterialTheme.typography.labelMedium)
                                    Box(Modifier.size(54.dp).background(C.Raised,RoundedCornerShape(12.dp)),contentAlignment = Alignment.Center) { Glyph("music",C.Muted) }
                                    Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(song.title,maxLines = 2,overflow = TextOverflow.Ellipsis,style = MaterialTheme.typography.titleMedium); Text(song.channelTitle,color = C.Muted,style = MaterialTheme.typography.bodySmall) }
                                    Glyph("play")
                                }
                            }
                        }
                    }
                }
            },right = {
                if (!hasPlayer && !recording.busy && !recording.hasFile && mode != "vocal_only") QueuePanel { sos = true }
                else {
                    Eyebrow(if (hasPlayer) "NOW PLAYING" else "MY RECORDING")
                    Text(if (hasPlayer) "노래하는 시간" else if (recording.hasFile) "AI 재미 점수" else "목소리만으로",style = MaterialTheme.typography.headlineSmall)
                    if (hasPlayer) Notice("이용 허용 상태에서만 재생돼요", "녹음에는 스피커의 반주와 주변 소리가 함께 들어갈 수 있어요.")
                    if (recording.busy) {
                        Text(recording.status,color = C.Recording)
                        Action("녹음 종료 및 저장",secondary = true,enabled = recording.recording,onClick = onFinish)
                    } else if (recording.hasFile && !hasPlayer) {
                        Text("저장된 녹음 · $condition",color = C.Muted)
                        Text("듣고 확인한 뒤 직접 전송해 주세요. 자동 업로드는 하지 않아요.",color = C.Muted,style = MaterialTheme.typography.bodyMedium)
                        Action("평가를 위해 전송",enabled = idle,onClick = { uploadDialog = true })
                        Text(recording.scoreStatus,color = C.Muted,style = MaterialTheme.typography.bodySmall)
                    } else {
                        Action("●  녹음 시작",recording = true,enabled = idle && (mode == "vocal_only" || playerReady),onClick = { onRecordConsentOpen(); recordDialog = true })
                        if (hasPlayer && !playerReady) Text("공식 플레이어의 재생 버튼을 눌러주세요.",color = C.Muted,style = MaterialTheme.typography.bodySmall)
                    }
                    Action("곡 검색으로",secondary = true,enabled = idle,onClick = { resetDialog = true })
                    Action("화장실 SOS",amber = true,enabled = idle,onClick = { sos = true })
                }
            })
        }
    }
    if (sos) SosDialog { sos = false }
    if (recordDialog && allowed) AlertDialog(onDismissRequest = { recordDialog = false },title = { Text("마이크 녹음 동의") },
        text = { Text("최대 15초 동안 마이크로 내 노래를 녹음해요. 반주와 주변 소리가 섞일 수 있어요. 녹음 후 직접 확인하고 별도로 전송할 수 있어요.") },
        confirmButton = { TextButton(onClick = { recordDialog = false; onConsent(true); onRecord() }) { Text("동의하고 녹음 시작",color = C.Recording) } },
        dismissButton = { TextButton(onClick = { recordDialog = false; onConsent(false) }) { Text("동의 안 함") } })
    if (uploadDialog && allowed) AlertDialog(onDismissRequest = { uploadDialog = false },title = { Text("녹음 파일 전송 동의") },
        text = { Text("AI 재미 점수 평가를 위해 이 녹음을 Mac 채점 서버에 보냅니다. 서버는 처리 후 임시 녹음 파일을 정리해요.") },
        confirmButton = { TextButton(onClick = { uploadDialog = false; onUploadConsent(true); onScore() }) { Text("동의하고 전송") } },
        dismissButton = { TextButton(onClick = { uploadDialog = false; onUploadConsent(false) }) { Text("취소") } })
    if (resetDialog && allowed) AlertDialog(onDismissRequest = { resetDialog = false },title = { Text("곡 검색으로 돌아갈까요?") },
        text = { Text("현재 녹음과 평가 결과는 삭제돼요.") },
        confirmButton = { TextButton(onClick = { resetDialog = false; onMode("mixed") }) { Text("곡 검색으로") } },
        dismissButton = { TextButton(onClick = { resetDialog = false }) { Text("취소") } })
}
