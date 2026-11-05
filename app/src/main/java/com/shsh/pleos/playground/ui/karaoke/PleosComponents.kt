@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.shsh.pleos.playground.ui.karaoke

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shsh.pleos.playground.R
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shsh.pleos.playground.ui.theme.PleosColors as C

@Composable
fun Glyph(kind: String, color: Color = C.Mint, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, Offset(x,y), Offset(a,b), 1.8f, StrokeCap.Round)
            fun path(vararg points: Pair<Float, Float>, closed: Boolean = false, filled: Boolean = false) {
                val p = Path().apply { moveTo(points[0].first, points[0].second); points.drop(1).forEach { lineTo(it.first,it.second) }; if (closed) close() }
                if (filled) drawPath(p,color) else drawPath(p,color,style=stroke)
            }
            when (kind) {
                "mic" -> { drawRoundRect(color,Offset(9f,3f),Size(6f,12f),CornerRadius(3f),style=stroke); drawArc(color,0f,180f,false,Offset(5.5f,4.5f),Size(13f,13f),style=stroke); line(12f,17.5f,12f,21f); line(8.5f,21f,15.5f,21f) }
                "search" -> { drawCircle(color,6.5f,Offset(10.5f,10.5f),style=stroke); line(15.5f,15.5f,20.5f,20.5f) }
                "toilet" -> { line(7f,4f,17f,4f); path(8f to 4f,8f to 9f,10f to 13f,13f to 14f,17f to 14f); path(17f to 9f,19f to 9f,19f to 12f,17f to 17f,12f to 19f); line(12f,14f,12f,20f); line(7f,20f,18f,20f) }
                "queue" -> { line(5f,6f,16f,6f); line(5f,12f,13f,12f); line(5f,18f,11f,18f); path(17f to 15f,21f to 18f,17f to 21f,closed=true) }
                "play" -> path(9f to 6f,18f to 12f,9f to 18f,closed=true,filled=true)
                "pause" -> { drawRoundRect(color,Offset(6f,5f),Size(4f,14f),CornerRadius(1f)); drawRoundRect(color,Offset(14f,5f),Size(4f,14f),CornerRadius(1f)) }
                "record" -> drawCircle(color,7f,Offset(12f,12f))
                "upload" -> { line(12f,16f,12f,4f); path(7f to 9f,12f to 4f,17f to 9f); path(5f to 15f,5f to 20f,19f to 20f,19f to 15f) }
                "spark" -> { path(12f to 2f,13.4f to 7.6f,19f to 9f,13.4f to 10.4f,12f to 16f,10.6f to 10.4f,5f to 9f,10.6f to 7.6f,closed=true); path(19f to 15f,20f to 17f,22f to 18f,20f to 19f,19f to 21f,18f to 19f,16f to 18f,18f to 17f,closed=true) }
                "car" -> { path(4f to 15f,5.5f to 9f,18.5f to 9f,20f to 15f,20f to 19f,17f to 19f,17f to 17f,7f to 17f,7f to 19f,4f to 19f,closed=true); path(7f to 9f,8f to 6f,16f to 6f,17f to 9f); drawCircle(color,1f,Offset(8f,14f)); drawCircle(color,1f,Offset(16f,14f)) }
                "nav" -> { path(4f to 11f,20f to 4f,13f to 20f,11f to 13f,closed=true); line(11f,13f,20f,4f) }
                "alert" -> { path(12f to 3f,2.5f to 20f,21.5f to 20f,closed=true); line(12f,9f,12f,14f); drawCircle(color,.9f,Offset(12f,17.5f)) }
                "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
                "check" -> path(5f to 12f,9.5f to 16.5f,19f to 7f)
                "retry" -> { drawArc(color,40f,290f,false,Offset(4f,4f),Size(16f,16f),style=stroke); path(20f to 7f,20f to 12f,15f to 12f) }
                "chevron" -> path(9f to 5f,16f to 12f,9f to 19f)
                "menu" -> listOf(6f,12f,18f).forEach { line(4f,it,20f,it) }
                else -> { path(9f to 18f,9f to 6f,19f to 4f,19f to 16f); drawCircle(color,2.5f,Offset(6.5f,18f),style=stroke); drawCircle(color,2.5f,Offset(16.5f,16f),style=stroke) }
            }
        }
    }
}

@Composable
fun Action(label: String, enabled: Boolean = true, secondary: Boolean = false, recording: Boolean = false,
    amber: Boolean = false, icon: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = when { recording -> C.Recording; amber -> C.Amber; secondary -> C.Card; else -> C.Mint }
    val fg = if (secondary || recording) C.Text else C.Background
    Button(onClick=onClick, enabled=enabled, modifier=modifier.heightIn(min=80.dp), shape=RoundedCornerShape(17.dp),
        border=if (secondary) BorderStroke(1.dp,C.Border) else null,
        colors=ButtonDefaults.buttonColors(containerColor=bg,contentColor=fg,disabledContainerColor=C.Card,disabledContentColor=C.Muted.copy(alpha=.5f)),
        contentPadding=PaddingValues(horizontal=24.dp,vertical=14.dp)) {
        if (icon != null) { Glyph(icon,if(enabled) fg else C.Muted.copy(alpha=.5f)); Spacer(Modifier.width(12.dp)) }
        Text(label,fontSize=26.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

@Composable fun Eyebrow(text: String, color: Color = C.Mint) {
    Text(text,color=color,fontSize=20.sp,lineHeight=30.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp)
}
@Composable fun Chip(label: String, color: Color = C.Muted, icon: String? = null) {
    Surface(color=color.copy(alpha=.1f),shape=CircleShape,border=BorderStroke(1.dp,color.copy(alpha=.25f))) {
        Row(Modifier.heightIn(min=52.dp).padding(horizontal=18.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(icon!=null) Glyph(icon,color,Modifier.size(28.dp))
            Text(label,color=color,fontSize=22.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold)
        }
    }
}
@Composable fun Notice(title: String, message: String, color: Color = C.Amber) {
    Surface(color=color.copy(alpha=.07f),shape=RoundedCornerShape(17.dp),border=BorderStroke(1.dp,color.copy(alpha=.2f))) {
        Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Glyph("alert",color)
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(title,color=color,style=MaterialTheme.typography.titleSmall)
                if(message.isNotBlank()) Text(message,color=C.Muted,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun DesignShell(title: String, allowed: Boolean? = null, onHome: (() -> Unit)? = null,
    onDiagnostics: (() -> Unit)? = null, onData: (() -> Unit)? = null, onGuide: (() -> Unit)? = null,
    receivedCommand: String? = null, speechTranscript: String? = null, speechListening: Boolean = false,
    immersiveContent: Boolean = false,
    content: @Composable BoxScope.() -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(),color=C.Background) {
        Box {
            Canvas(Modifier.matchParentSize()) {
                val gap=48.dp.toPx()
                var x=0f; while(x<size.width) { drawLine(C.Text.copy(alpha=.012f),Offset(x,0f),Offset(x,size.height)); x+=gap }
                var y=0f; while(y<size.height) { drawLine(C.Text.copy(alpha=.012f),Offset(0f,y),Offset(size.width,y)); y+=gap }
            }
            Column(Modifier.fillMaxSize()) {
                if (!immersiveContent) {
                    BoxWithConstraints(Modifier.fillMaxWidth().background(C.Background)) {
                        val wide=maxWidth>=1360.dp
                        val compact=maxWidth<800.dp
                        Row(Modifier.fillMaxWidth().heightIn(min=112.dp).padding(horizontal=if(wide) 40.dp else 24.dp,vertical=14.dp),
                            horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
                            Surface(color=C.Mint,shape=RoundedCornerShape(15.dp)) { Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) { Glyph("music",C.Background) } }
                            if(!compact) Text(stringResource(R.string.app_name),fontSize=30.sp,fontWeight=FontWeight.ExtraBold)
                            if(wide) { Box(Modifier.width(1.dp).height(24.dp).background(C.Border)); Text("카라오케 & SOS",color=C.Muted,fontSize=22.sp) }
                            Text(title,Modifier.weight(1f).padding(horizontal=12.dp),color=C.Text,fontSize=24.sp,lineHeight=34.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Chip(if(allowed==true) "이용 가능" else if(allowed==false) (if(compact) "제한 확인" else "이용 제한 · 확인 중") else "상태 확인",if(allowed==true) C.Green else C.Amber,"car")
                            Box {
                                IconButton(onClick={menu=true},modifier=Modifier.size(72.dp).background(C.Card,RoundedCornerShape(18.dp)).semantics { contentDescription="화면 메뉴 열기" }) { Glyph("menu",C.Text,Modifier.size(32.dp)) }
                                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                    if(onHome!=null) DropdownMenuItem(text={Text("홈으로")},onClick={menu=false;onHome()})
                                    if(onData!=null) DropdownMenuItem(text={Text("시설 데이터 관리")},onClick={menu=false;onData()})
                                    if(onGuide!=null) DropdownMenuItem(text={Text("디자인 시스템")},onClick={menu=false;onGuide()})
                                    if(onDiagnostics!=null) DropdownMenuItem(text={Text("개발용 SDK 검증")},onClick={menu=false;onDiagnostics()})
                                    DropdownMenuItem(text={Text("닫기")},onClick={menu=false})
                                }
                            }
                        }
                    }
                    HorizontalDivider(color=C.Border)
                }
                if (!immersiveContent && allowed == true && (speechListening || speechTranscript != null || receivedCommand != null)) {
                    val appSpeech = receivedCommand == null && (speechListening || speechTranscript != null)
                    val caption = if (appSpeech) speechTranscript?.takeIf { it.isNotBlank() }
                        ?: if (speechListening) "말씀해 주세요…" else "인식된 말이 없어요"
                        else receivedCommand.orEmpty()
                    Column(Modifier.fillMaxWidth().background(C.Card).padding(horizontal=32.dp,vertical=16.dp),
                        verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if (appSpeech) (if (speechListening) "듣고 있어요" else "인식한 말") else "Gleo가 전달한 내용",
                            color=if(appSpeech && speechListening) C.Mint else C.Muted,fontSize=20.sp,lineHeight=28.sp)
                        Text(caption,color=C.Text,fontSize=40.sp,lineHeight=54.sp,fontWeight=FontWeight.SemiBold,
                            minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(),content=content)
            }
        }
    }
}

/** Decorative activity indicator; not a microphone amplitude measurement. */
@Composable fun Waveform(color: Color = C.Mint) {
    val transition=rememberInfiniteTransition(label="listening")
    val progress by transition.animateFloat(.45f,1f,infiniteRepeatable(tween(800),RepeatMode.Reverse),label="wave")
    Canvas(Modifier.widthIn(max=340.dp).fillMaxWidth().height(100.dp).semantics { contentDescription="처리 중 표시" }) {
        val bars=listOf(18,34,52,28,66,42,72,48,30,56,38,20)
        val gap=size.width/(bars.size+1)
        bars.forEachIndexed { i,h ->
            val factor=if(i%2==0) progress else 1.45f-progress
            val height=h.dp.toPx()*factor
            drawLine(color.copy(alpha=.6f+.4f*factor),Offset(gap*(i+1),size.height/2-height/2),Offset(gap*(i+1),size.height/2+height/2),6.dp.toPx(),StrokeCap.Round)
        }
    }
}

@Composable fun Orb(icon: String, color: Color = C.Mint, modifier: Modifier = Modifier) {
    Box(modifier.size(180.dp),contentAlignment=Alignment.Center) {
        Surface(Modifier.size(178.dp),shape=CircleShape,color=color.copy(alpha=.025f),border=BorderStroke(1.dp,color.copy(alpha=.08f))) {}
        Surface(Modifier.size(156.dp),shape=CircleShape,color=color.copy(alpha=.1f),border=BorderStroke(1.dp,color.copy(alpha=.35f))) {
            Box(contentAlignment=Alignment.Center) { Glyph(icon,color,Modifier.size(58.dp)) }
        }
    }
}

@Composable private fun StateVisual(icon: String,color: Color,busy: Boolean) {
    if(busy && icon in setOf("upload","spark")) {
        Box(Modifier.size(190.dp),contentAlignment=Alignment.Center) {
            CircularProgressIndicator(Modifier.fillMaxSize(),color=color,strokeWidth=6.dp,trackColor=C.Border)
            Glyph(icon,color,Modifier.size(62.dp))
        }
    } else Orb(icon,color)
}

@Composable fun CenterState(eyebrow: String,title: String,copy: String,icon: String,color: Color=C.Mint,
    busy: Boolean=false,action: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=1240.dp
        if(wide) Row(Modifier.fillMaxSize().padding(48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(40.dp)) {
            StateVisual(icon,color,busy)
            Column(Modifier.weight(1.3f),verticalArrangement=Arrangement.spacedBy(14.dp)) { Eyebrow(eyebrow,color); Text(title,style=MaterialTheme.typography.headlineLarge); Text(copy,color=C.Muted) }
            Column(Modifier.weight(.8f),horizontalAlignment=Alignment.CenterHorizontally) { if(busy && icon !in setOf("upload","spark")) Waveform(color); action() }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)) {
            StateVisual(icon,color,busy); Eyebrow(eyebrow,color); Text(title,style=MaterialTheme.typography.headlineMedium); Text(copy,color=C.Muted)
            if(busy && icon !in setOf("upload","spark")) Waveform(color)
            action()
        }
    }
}

@Composable fun RestrictionScreen(onHome: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize()) {
        Surface(color=C.Amber) { Row(Modifier.fillMaxWidth().padding(horizontal=32.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Glyph("alert",C.Background,Modifier.size(34.dp)); Text("현재 영상과 점수판을 표시할 수 없어요",color=C.Background,style=MaterialTheme.typography.titleMedium)
        } }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if(maxWidth>=1200.dp) Row(Modifier.fillMaxSize().padding(32.dp),horizontalArrangement=Arrangement.spacedBy(32.dp)) {
                Surface(Modifier.weight(1.35f).fillMaxHeight(),color=C.Panel,shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,C.Border)) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        Orb("pause",C.Muted); Spacer(Modifier.height(18.dp)); Text("재생을 잠시 멈췄어요",color=C.Muted)
                    }
                }
                Column(Modifier.weight(.85f).fillMaxHeight().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Eyebrow("DRIVING RESTRICTIONS",C.Amber)
                    Text("안전하게\n잠시 멈췄어요",style=MaterialTheme.typography.headlineMedium)
                    Text("주행 제한 또는 차량 상태를 확인하고 있어요. 허용 상태에서 직접 다시 시작해 주세요.",color=C.Muted,style=MaterialTheme.typography.bodyMedium)
                    Action("음성으로 곡 찾기",enabled=false,icon="mic",onClick={})
                    if(onHome!=null) Action("홈으로",secondary=true,onClick=onHome)
                }
            } else CenterState("DRIVING RESTRICTIONS","안전하게 잠시 멈췄어요","주행 제한 또는 차량 상태를 확인하고 있어요. 허용 상태에서 직접 다시 시작해 주세요.","pause",C.Amber) {
                Action("음성으로 곡 찾기",enabled=false,icon="mic",onClick={})
                if(onHome!=null) { Spacer(Modifier.height(12.dp)); Action("홈으로",secondary=true,onClick=onHome) }
            }
        }
    }
}

@Composable fun ErrorScreen(title: String,copy: String,onCancel: () -> Unit,onRetry: () -> Unit) {
    CenterState("다시 시도할 수 있어요",title,copy,"alert",C.Amber) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Action("취소",secondary=true,icon="close",onClick=onCancel); Action("다시 시도",icon="retry",onClick=onRetry)
        }
    }
}

@Composable fun DesignSystem(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(40.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Eyebrow("PLEOS UI FOUNDATION · DARK ONLY")
        Text("한눈에 읽히고,\n바로 누르는 인터페이스",style=MaterialTheme.typography.headlineLarge)
        Text("차량 안의 짧은 시선과 큰 터치를 기준으로 설계했어요.",color=C.Muted)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            listOf("배경" to C.Background,"표면" to C.Card,"민트" to C.Mint,"제한" to C.Amber,"녹음" to C.Recording).forEach { (label,color) ->
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { Surface(Modifier.size(68.dp,42.dp),color=color,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,C.Border)) {}; Text(label,color=C.Muted,fontSize=22.sp) }
            }
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Action("주요 동작",onClick={}); Action("보조 동작",secondary=true,onClick={}); Chip("이용 가능",C.Green); Chip("확인 필요",C.Amber)
        }
        Notice("상태 배너","시설의 등록 정보와 실제 이용 상태를 구분해요.")
        Action("돌아가기",secondary=true,onClick=onBack)
    }
}
