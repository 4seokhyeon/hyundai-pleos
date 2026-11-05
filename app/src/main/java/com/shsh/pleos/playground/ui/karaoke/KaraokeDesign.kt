@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shsh.pleos.playground.ui.karaoke

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shsh.pleos.playground.PublicDataProbeActivity
import com.shsh.pleos.playground.data.Song
import com.shsh.pleos.playground.data.ScoreResult
import com.shsh.pleos.playground.ui.probe.RecordingProbeUiState
import com.shsh.pleos.playground.ui.probe.SongSearchUiState
import com.shsh.pleos.playground.ui.theme.PleosColors as C

typealias WorkspaceOverlay = @Composable () -> Unit

@Composable
private fun MicButton(enabled: Boolean,onClick: () -> Unit) {
    Box(Modifier.size(248.dp),contentAlignment=Alignment.Center) {
        listOf(248,210).forEach { diameter -> Surface(Modifier.size(diameter.dp),shape=CircleShape,color=C.Mint.copy(alpha=.025f),border=BorderStroke(1.dp,C.Mint.copy(alpha=.16f))) {} }
        Button(onClick=onClick,enabled=enabled,modifier=Modifier.size(174.dp).semantics { contentDescription="음성으로 노래 검색" },shape=CircleShape,
            colors=ButtonDefaults.buttonColors(containerColor=C.Mint,contentColor=C.Background),contentPadding=PaddingValues(0.dp)) { Glyph("mic",C.Background,Modifier.size(64.dp)) }
    }
}

@Composable
private fun QueuePanel(onSos: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(C.Panel)) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min=maxHeight).padding(horizontal=36.dp,vertical=32.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) { Eyebrow("UP NEXT"); Text("예약 목록",style=MaterialTheme.typography.headlineSmall) }
            Text("0곡",color=C.Muted,fontSize=22.sp,lineHeight=32.sp)
        }
        Surface(color=C.Card,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,C.Border)) {
            Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Glyph("queue",C.Muted,Modifier.size(36.dp)); Text("예약한 곡이 없어요",style=MaterialTheme.typography.titleMedium)
                Text("검색한 곡을 바로 재생해 보세요. 예약 기능은 준비 중이에요.",color=C.Muted,style=MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.weight(1f))
        Action("화장실 SOS",amber=true,icon="toilet",modifier=Modifier.fillMaxWidth(),onClick=onSos)
    }
    }
}

@Composable
private fun HomeLayout(onVoice: () -> Unit,onSos: () -> Unit,onVocal: () -> Unit,enabled: Boolean=true,
    searchField: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=960.dp
        val hero: @Composable () -> Unit = {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min=maxHeight).padding(horizontal=if(wide) 48.dp else 28.dp,vertical=32.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) { Eyebrow("VOICE KARAOKE"); Text("어떤 노래를\n불러 볼까요?",fontSize=if(wide) 56.sp else 48.sp,fontWeight=FontWeight.Bold,lineHeight=if(wide) 70.sp else 62.sp,letterSpacing=(-.5).sp) }
                    if(wide) Column(horizontalAlignment=Alignment.CenterHorizontally) { MicButton(enabled,onVoice); Text("노래 이름을 말해 보세요",color=C.Text,fontSize=26.sp) }
                }
                if(!wide) MicButton(enabled,onVoice)
                Spacer(Modifier.weight(1f))
                searchField()
                TextButton(onClick=onVocal,enabled=enabled) { Text("반주 없이 목소리만 녹음하기 →",color=C.Muted,fontSize=22.sp,lineHeight=32.sp) }
            }
            }
        }
        if(wide) Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1.38f)) { hero() }
            Box(Modifier.width(1.dp).fillMaxHeight().background(C.Border))
            Box(Modifier.weight(.62f)) { QueuePanel(onSos) }
        } else Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { hero() }
            Action("화장실 SOS",amber=true,icon="toilet",modifier=Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=12.dp),onClick=onSos)
        }
    }
}

@Composable
fun KaraokeHome(onStart: () -> Unit,onDiagnostics: () -> Unit,onVoice: () -> Unit=onStart,onVocal: () -> Unit=onStart,allowed: Boolean?=null) {
    val context=LocalContext.current
    var guide by remember { mutableStateOf(false) }
    val openSos: () -> Unit = { context.startActivity(Intent(context,PublicDataProbeActivity::class.java)) }
    LaunchedEffect(allowed) { if(allowed==false) guide=false }
    BackHandler(enabled=guide) { guide=false }
    DesignShell(if(guide) "디자인 시스템" else "노래 검색 홈",allowed,onDiagnostics=onDiagnostics,onData={context.startActivity(Intent(context,PublicDataProbeActivity::class.java).putExtra("open_data",true))},onGuide={guide=true}) {
        when {
            allowed==false -> RestrictionScreen()
            guide -> DesignSystem { guide=false }
            else -> HomeLayout(onVoice,openSos,onVocal) {
                Surface(onClick=onStart,color=C.Card,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,C.Border),modifier=Modifier.fillMaxWidth().heightIn(min=96.dp)) {
                    Row(Modifier.padding(24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        Glyph("search",C.Muted,Modifier.size(32.dp)); Text("곡명·가수 검색",Modifier.weight(1f),fontSize=28.sp,lineHeight=40.sp,fontWeight=FontWeight.SemiBold)
                        Text("터치해서 입력",color=C.Muted,fontSize=22.sp,lineHeight=32.sp)
                    }
                }
            }
        }
    }
}

@Composable private fun SearchField(search: SongSearchUiState,enabled: Boolean,onEdit: (String)->Unit,onSearch: ()->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
        OutlinedTextField(search.query,onEdit,Modifier.weight(1f).heightIn(min=88.dp),enabled=enabled,textStyle=MaterialTheme.typography.bodyLarge,singleLine=true,placeholder={Text("웅키 채널에서 곡명·가수 검색")},
            leadingIcon={Glyph("search",C.Muted)},shape=RoundedCornerShape(20.dp),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={if(enabled) onSearch()}))
        Action("검색",enabled=enabled,icon="search",onClick=onSearch)
    }
}

@Composable private fun SearchResults(search: SongSearchUiState,idle: Boolean,onEdit: (String)->Unit,onSearch: ()->Unit,onVoice: ()->Unit,onSong: (Song)->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns=if(maxWidth>=1240.dp) 2 else 1
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=36.dp,vertical=28.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) { Chip("검색어 · ${search.query}",C.Mint,"mic"); Text("이 곡이 맞나요?",style=MaterialTheme.typography.headlineMedium) }
                Action("다시 말하기",secondary=true,icon="mic",enabled=idle,onClick=onVoice)
            }
            SearchField(search,idle,onEdit,onSearch)
            search.songs.chunked(columns).forEachIndexed { rowIndex,row ->
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    row.forEachIndexed { index,song ->
                        Surface(Modifier.weight(1f),color=C.Card,shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,C.Border)) {
                            Row(Modifier.heightIn(min=216.dp).padding(20.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically) {
                                Surface(Modifier.width(80.dp).heightIn(min=148.dp),color=C.Raised,shape=RoundedCornerShape(16.dp)) {
                                    Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) { Glyph("music",C.Mint,Modifier.size(38.dp)); Spacer(Modifier.height(12.dp)); Text("${rowIndex*columns+index+1}번",color=C.Mint,fontSize=28.sp,lineHeight=38.sp) }
                                }
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                                    Text(song.title,style=MaterialTheme.typography.titleMedium,minLines=2,maxLines=3,overflow=TextOverflow.Ellipsis)
//                                    Text(song.channelTitle,color=C.Muted,fontSize=22.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                        Action("예약",secondary=true,enabled=false,icon="queue",modifier=Modifier.weight(.85f),onClick={})
                                        Action("바로 재생",enabled=idle,icon="play",modifier=Modifier.weight(1f),onClick={onSong(song)})
                                    }
                                }
                            }
                        }
                    }
                    if(row.size<columns) Spacer(Modifier.weight(1f))
                }
            }
            Text("Gleo에 “노래하자에서 3번 재생해줘”라고 말해 보세요. 웅키 채널 · YouTube 제공 · 예약 기능은 준비 중이에요.",color=C.Muted,style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun PlayerLayout(song: Song?,status: String,recording: RecordingProbeUiState,playerReady: Boolean,idle: Boolean,
    onRecord: ()->Unit,onFinish: ()->Unit,onClose: ()->Unit,onSos: ()->Unit,onPlay: ()->Unit,player: @Composable ()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=960.dp
        val recordingFocus=recording.busy
        val compact=maxWidth<800.dp
        val barHeight=if(compact) 176.dp else 112.dp
        val videoWidth=if(recordingFocus || !wide) maxWidth else maxWidth*(1.63f/2.5f)
        val videoHeight=when {
            recordingFocus -> (maxHeight-barHeight).coerceAtLeast(0.dp)
            wide -> maxHeight
            else -> minOf(360.dp,maxHeight*.48f)
        }
        // Keep this call site fixed. Resizing for recording must not replace the native WebView.
        Box(Modifier.width(videoWidth).height(videoHeight).align(Alignment.TopStart)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(if(recordingFocus) 0.dp else 24.dp)) {
                val width=minOf(maxWidth,maxHeight*16f/9f)
                val frame=if(recordingFocus) Modifier.fillMaxSize() else Modifier.width(width).aspectRatio(16f/9f).align(Alignment.Center)
                // Keep the embedded WebView out of a rounded Surface's clipping layer.
                // Only this container changes; the existing player call site stays mounted.
                Box(frame.background(androidx.compose.ui.graphics.Color.Black)) { player() }
            }
        }
        if(!recordingFocus) {
            val panelModifier=if(wide) Modifier.width(maxWidth-videoWidth).fillMaxHeight().align(Alignment.TopEnd)
                else Modifier.fillMaxWidth().height(maxHeight-videoHeight).align(Alignment.BottomStart)
            Column(panelModifier.background(C.Panel).verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                Chip("공식 YouTube 플레이어",C.Muted)
                Notice("플랫폼 허용 상태에서 재생해요","주행 제한이 적용되면 영상과 소리를 멈춰요.")
                Eyebrow("NOW PLAYING")
                Text(song?.title ?: "선택한 노래",style=MaterialTheme.typography.headlineSmall,maxLines=3,overflow=TextOverflow.Ellipsis)
//                Text(song?.channelTitle.orEmpty(),color=C.Muted,style=MaterialTheme.typography.bodyMedium)
                Text(status,color=C.Muted,style=MaterialTheme.typography.bodySmall)
                Text("반주와 주변 소리도 녹음에 들어갈 수 있어요.",color=C.Muted,style=MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Action("녹음 시작",recording=true,icon="record",enabled=idle&&playerReady,modifier=Modifier.fillMaxWidth(),onClick=onRecord)
                if(!playerReady) {
                    Action("영상·소리 재생",secondary=true,enabled=idle,icon="play",modifier=Modifier.fillMaxWidth(),onClick=onPlay)
                    Text("재생이 시작되면 녹음할 수 있어요.",color=C.Amber,style=MaterialTheme.typography.bodySmall)
                }
                Action("화장실 SOS",amber=true,icon="toilet",enabled=idle,onClick=onSos)
                Action("재생 중지 · 곡 목록으로",secondary=true,modifier=Modifier.fillMaxWidth(),onClick=onClose)
            }
        }
        if(recordingFocus) {
            Surface(Modifier.fillMaxWidth().height(barHeight).align(Alignment.BottomStart),color=C.Panel) {
                val label: @Composable ()->Unit = {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(16.dp).background(C.Recording,CircleShape))
                        Text(if(recording.recording) "녹음 중" else "녹음 준비 중",color=C.Recording,fontSize=32.sp,lineHeight=42.sp,fontWeight=FontWeight.Bold)
                        Text("최대 15초",color=C.Muted,fontSize=22.sp)
                    }
                }
                if(compact) Column(Modifier.fillMaxSize().padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    label()
                    Action("녹음 종료 및 저장",secondary=true,enabled=recording.recording,modifier=Modifier.fillMaxWidth(),onClick=onFinish)
                } else Row(Modifier.fillMaxSize().padding(horizontal=32.dp,vertical=16.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    label()
                    Action("녹음 종료 및 저장",secondary=true,enabled=recording.recording,onClick=onFinish)
                }
            }
        }
    }
}

@Composable private fun ScoreScreen(score: ScoreResult,condition: String,onList: ()->Unit,onAgain: ()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=1280.dp
        val gauge: @Composable ()->Unit = {
            Box(Modifier.size(if(wide) 320.dp else 230.dp),contentAlignment=Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke=10.dp.toPx(); val inset=stroke/2
                    val area=androidx.compose.ui.geometry.Size(size.width-stroke,size.height-stroke)
                    drawArc(C.Border,-90f,360f,false,topLeft=androidx.compose.ui.geometry.Offset(inset,inset),size=area,style=Stroke(stroke))
                    drawArc(C.Mint,-90f,360f*score.funScore/100f,false,topLeft=androidx.compose.ui.geometry.Offset(inset,inset),size=area,style=Stroke(stroke,cap=StrokeCap.Round))
                }
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Eyebrow("AI SCORE"); Text("${score.funScore}",fontSize=if(wide) 94.sp else 76.sp,fontWeight=FontWeight.Bold,lineHeight=104.sp); Text("점",color=C.Muted)
                }
            }
        }
        val copy: @Composable ()->Unit = {
            Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
                Eyebrow("VOCAL REVIEW"); Text("AI 재미 점수",style=MaterialTheme.typography.headlineLarge); Chip(condition,C.Mint)
                Notice("공모전 시연용 환산 점수예요","원곡의 음정·박자 정확도나 가창 실력 점수가 아니에요. 반주와 녹음 환경에 따라 달라질 수 있어요.")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) { Action("곡 목록으로",secondary=true,onClick=onList); Action("한 번 더 부르기",icon="mic",onClick=onAgain) }
            }
        }
        if(wide) Row(Modifier.fillMaxSize().padding(horizontal=90.dp,vertical=32.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(80.dp)) { gauge(); Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { copy() } }
        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(28.dp)) { gauge(); copy() }
    }
}

@Composable private fun ConsentOverlay(title: String,eyebrow: String,copy: String,confirm: String,recording: Boolean=false,onDismiss: ()->Unit,onConfirm: ()->Unit) {
    // In-app overlay keeps the official player mounted while consent is shown.
    Box(Modifier.fillMaxSize().background(C.Background.copy(alpha=.86f)).clickable(onClick=onDismiss),contentAlignment=Alignment.Center) {
        Surface(Modifier.widthIn(max=900.dp).fillMaxWidth().padding(28.dp).clickable(onClick={}),color=C.Raised,shape=RoundedCornerShape(28.dp),border=BorderStroke(1.dp,C.Border)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(36.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                Glyph(if(recording) "record" else "upload",if(recording) C.Recording else C.Mint,Modifier.size(42.dp)); Eyebrow(eyebrow)
                Text(title,style=MaterialTheme.typography.headlineMedium); Text(copy,color=C.Muted)
                if(eyebrow!="BACK TO SONGS") Chip("자동으로 업로드되지 않아요",C.Mint)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Action("취소",secondary=true,onClick=onDismiss); Action(confirm,recording=recording,icon=if(recording) "record" else "upload",onClick=onConfirm)
                }
            }
        }
    }
}

@Composable
fun KaraokeWorkspace(allowed: Boolean,mode: String?,hasPlayer: Boolean,playerReady: Boolean,status: String,
    search: SongSearchUiState,recording: RecordingProbeUiState,selectedSong: Song?,playerError: String?,
    onMode: (String)->Unit,onEdit: (String)->Unit,onSearch: ()->Unit,onVoice: ()->Unit,onSearchCancel: ()->Unit,onSong: (Song)->Unit,
    onConsent: (Boolean)->Unit,onUploadConsent: (Boolean)->Unit,onRecord: ()->Unit,onFinish: ()->Unit,onListen: ()->Unit,onStopListen: ()->Unit,onDelete: ()->Unit,
    onRecordConsentOpen: ()->Unit,onScore: ()->Unit,onCancelScore: ()->Unit,onClosePlayer: ()->Unit,onHome: ()->Unit,onClearErrors: ()->Unit,
    receivedCommand: String? = null, onPlay: ()->Unit = {},
    onOverlayChanged: (WorkspaceOverlay?) -> Unit, player: @Composable ()->Unit) {
    val context=LocalContext.current
    val idle=!recording.busy&&!recording.playing&&!recording.exporting&&!recording.scoring&&!search.busy
    val condition=if(recording.inputKind=="vocal_only") "무반주" else "반주 포함"
    var recordDialog by remember { mutableStateOf(false) }
    var uploadDialog by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    LaunchedEffect(allowed) { if(!allowed) {recordDialog=false;uploadDialog=false;resetDialog=false;guide=false} }
    LaunchedEffect(search.busy) {
        if(search.busy) { recordDialog=false;uploadDialog=false;resetDialog=false;guide=false }
    }
    val requestRecord: ()->Unit = { onRecordConsentOpen();recordDialog=true }
    val openSos: ()->Unit = { context.startActivity(Intent(context,PublicDataProbeActivity::class.java)) }
    val failure=playerError ?: search.error ?: recording.scoreError ?: recording.error
    val recordingFocus=allowed && hasPlayer && recording.busy && failure==null && !guide
    BackHandler(enabled=recordDialog||uploadDialog||resetDialog||guide) { recordDialog=false;uploadDialog=false;resetDialog=false;guide=false }
    val title=when { guide->"디자인 시스템";!allowed->"주행 제한";failure!=null->"오류 상태";recording.scoring->if(recording.analysisStarted) "채점 · 분석 중" else "채점 · 업로드 중";recording.score!=null->"채점 · 결과";hasPlayer->"노래방 재생";search.listening->"음성 검색 · 듣는 중";search.busy->"노래 검색 중";recording.busy->"노래 녹음";recording.hasFile->"녹음 확인";search.songs.isNotEmpty()->"음성 검색 · 결과";else->"노래 검색" }
    val overlay: WorkspaceOverlay? = if(allowed && (recordDialog || uploadDialog || resetDialog)) ({
        if(recordDialog&&allowed) ConsentOverlay("마이크 녹음 동의","RECORDING CONSENT","최대 15초 동안 마이크로 내 노래를 녹음해요. 반주와 주변 소리가 섞일 수 있어요. 녹음 후 직접 확인하고, 별도로 전송을 선택할 수 있어요.","동의하고 녹음 시작",true,
            onDismiss={recordDialog=false;onConsent(false)},onConfirm={recordDialog=false;onConsent(true);onRecord()})
        if(uploadDialog&&allowed) ConsentOverlay("녹음 파일 전송 동의","RECORDING UPLOAD","AI 재미 점수 평가를 위해 이 녹음을 채점 서버로 보냅니다. 서버는 처리 후 임시 녹음 파일을 정리해요.","동의하고 전송",
            onDismiss={uploadDialog=false;onUploadConsent(false)},onConfirm={uploadDialog=false;onUploadConsent(true);onScore()})
        if(resetDialog&&allowed) ConsentOverlay("곡 검색으로 돌아갈까요?","BACK TO SONGS","현재 녹음과 평가 결과는 삭제돼요.","곡 검색으로",
            onDismiss={resetDialog=false},onConfirm={resetDialog=false;onMode("mixed")})
    }) else null
    SideEffect { onOverlayChanged(overlay) }
    DisposableEffect(Unit) { onDispose { onOverlayChanged(null) } }
    DesignShell(title,allowed,immersiveContent=recordingFocus,receivedCommand=receivedCommand,speechTranscript=search.speechTranscript,speechListening=search.listening,onHome=onHome,onData={context.startActivity(Intent(context,PublicDataProbeActivity::class.java).putExtra("open_data",true))},onGuide={if(idle) { if(hasPlayer) onClosePlayer(); guide=true }}) {
        when {
            !allowed -> RestrictionScreen(onHome)
            guide -> DesignSystem { guide=false }
            failure!=null -> ErrorScreen(when {playerError!=null->"영상 재생 불가";search.error!=null->"노래 검색 실패";recording.scoreError!=null->"채점 실패";else->"녹음 확인 필요"},failure,
                onCancel=onClearErrors,onRetry={onClearErrors();when {playerError!=null->onClosePlayer();search.error!=null->onSearch();recording.scoreError!=null->uploadDialog=true;else->requestRecord()}})
            recording.scoring -> CenterState(if(recording.analysisStarted) "AI ANALYSIS" else "AUDIO UPLOAD",if(recording.analysisStarted) "분석 중" else "업로드 중",
                if(recording.analysisStarted) "AI가 녹음 품질을 분석하고 있어요." else "동의한 녹음 파일을 채점 서버로 보내고 있어요.",if(recording.analysisStarted) "spark" else "upload",busy=true) {
                Text(if(recording.analysisStarted) "완료되면 결과를 보여드릴게요." else "전송이 끝나면 분석을 시작해요.",color=C.Muted,style=MaterialTheme.typography.bodySmall); Spacer(Modifier.height(18.dp)); Action("취소",secondary=true,icon="close",onClick=onCancelScore)
            }
            recording.score!=null -> ScoreScreen(recording.score,condition,onList={resetDialog=true},onAgain={onDelete(); if(selectedSong!=null && mode!="vocal_only") onSong(selectedSong)})
            hasPlayer -> PlayerLayout(selectedSong,status,recording,playerReady,idle,requestRecord,onFinish,onClosePlayer,openSos,onPlay,player)
            search.listening -> CenterState("듣는 중","듣고 있어요","곡명이나 가수를 편하게 말해 주세요.","mic",busy=true) { Action("취소",secondary=true,icon="close",onClick=onSearchCancel) }
            search.busy -> CenterState("SONG SEARCH","노래를 찾고 있어요","검색 결과를 가져오고 있어요.","search",busy=true) { Action("취소",secondary=true,icon="close",onClick=onSearchCancel) }
            recording.busy -> CenterState("RECORDING","목소리를 담고 있어요","최대 15초 동안 녹음해요. 주변 소리도 함께 들어갈 수 있어요.","record",C.Recording,busy=true) { Action("녹음 종료 및 저장",secondary=true,enabled=recording.recording,onClick=onFinish) }
            recording.hasFile -> CenterState("LISTEN BEFORE YOU SEND","내 노래를 들어 볼까요?","$condition 녹음이에요. 먼저 들어 보고 채점을 요청해 주세요.","mic") {
                Text(recording.status,color=C.Muted,style=MaterialTheme.typography.bodySmall); Spacer(Modifier.height(16.dp))
                Action(if(recording.playing) "청취 중지" else "녹음 듣기",secondary=true,icon=if(recording.playing) "pause" else "play",onClick=if(recording.playing) onStopListen else onListen)
                Spacer(Modifier.height(12.dp)); Action("채점 요청",icon="upload",enabled=idle,onClick={uploadDialog=true})
                Spacer(Modifier.height(12.dp)); TextButton(enabled=idle,onClick=onDelete) { Text("다시 녹음") }
            }
            mode=="vocal_only" -> CenterState("VOICE ONLY","나의 목소리로","반주를 끄고 최대 15초 동안 불러 주세요. 녹음 파일은 직접 전송하기 전까지 기기에만 있어요.","mic") {
                Action("녹음 시작",recording=true,icon="record",enabled=idle,onClick=requestRecord); Spacer(Modifier.height(12.dp)); Action("곡 검색으로",secondary=true,onClick={onMode("mixed")})
            }
            search.songs.isNotEmpty() -> SearchResults(search,idle,onEdit,onSearch,onVoice,onSong)
            else -> HomeLayout(onVoice,openSos,{onMode("vocal_only")},idle) {
                SearchField(search,idle,onEdit,onSearch)
                Text(search.message,color=C.Muted,style=MaterialTheme.typography.bodySmall)
            }
        }

    }
}
