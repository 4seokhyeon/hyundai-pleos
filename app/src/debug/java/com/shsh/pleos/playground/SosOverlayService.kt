package com.shsh.pleos.playground

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.shsh.pleos.playground.data.*
import com.shsh.pleos.playground.platform.DrivingRestrictionsMonitor
import kotlinx.coroutines.*
import kotlin.math.ceil

/** Debug, user-started session. No Activity is launched when Gleo requests a search. */
class SosOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navi by lazy { NaviRepository(this) }
    private val location by lazy { LocationRepository(this) }
    private var monitor: DrivingRestrictionsMonitor? = null
    private var allowed = false
    private var operation: Job? = null
    private var panel: View? = null
    private var selection: Selection? = null
    private var sent = false
    private var used = false

    private data class Option(val candidate: RestroomCandidate, val driveSeconds: Int) {
        val score: Double get() = driveSeconds + checkNotNull(candidate.parkingDistanceMeters) / 1.2
        val parking: CachedFacility get() = checkNotNull(candidate.parking)
    }
    private data class Selection(val route: NaviRepository.SosRoute, val reference: FacilityReference,
        val options: List<Option>, val expiresAt: Long)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "SOS 검증 세션", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, SosOverlayService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(2007, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle("화면 없는 SOS 검증 준비")
            .setContentText("Gleo 요청 대기 · 종료 버튼으로 세션을 끌 수 있어요")
            .setOngoing(true).addAction(0, "종료", stop).build())
        active = this
        Log.i(TAG, "SOS overlay session created; canDrawOverlays=${Settings.canDrawOverlays(this)}")
        monitor = DrivingRestrictionsMonitor(this) { permit, _ ->
            allowed = permit
            Log.i(TAG, "SOS overlay vehicle state; allowed=$permit")
            if (!permit) {
                if (!sent) operation?.cancel()
                selection = null
                hidePanel()
            }
        }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) stopSelf()
        return START_NOT_STICKY
    }

    /** Main thread only; caller must authenticate the Gleo broadcaster first. */
    private fun search(): String {
        if (!Settings.canDrawOverlays(this)) return "overlay_permission"
        if (!allowed) return "vehicle_restricted_or_unknown"
        if (operation?.isActive == true) return "overlay_busy"
        used = false
        sent = false
        selection = null
        if (runCatching { showStatus("화장실과 주차장을 찾는 중", "현재 내비 목적지를 유지한 채 후보를 비교해요.") }.isFailure) return "overlay_window_unavailable"
        operation = scope.launch {
            try {
                withTimeout(25_000) {
                    navi.connect()
                    val route = navi.readActiveSosRoute() ?: error("안내 중인 내비 경로가 없어요.")
                    check(route.waypoints.size < 2) { "경유지 슬롯이 부족해요." }
                    val reference = recentLocation()
                    val candidates = FacilityDatasetRepository(this@SosOverlayService).nearby(reference)
                        .filter { it.parking != null && it.parkingDistanceMeters != null }
                        .filter { !route.destination.matches(checkNotNull(it.parking)) && route.waypoints.none { stop -> stop.matches(checkNotNull(it.parking)) } }
                    // One parking stop may serve several toilets. Keep its shortest access pair.
                    val options = candidates.groupBy { checkNotNull(it.parking).let { parking -> Triple(parking.name, parking.latitude, parking.longitude) } }
                        .values.map { group -> group.minBy { checkNotNull(it.parkingDistanceMeters) } }
                    val ranked = mutableListOf<Option>()
                    for (candidate in options) {
                        ensureActive()
                        check(allowed) { "차량이 현재 이 동작을 허용하지 않아요." }
                        val eta = withTimeoutOrNull(2_000) { navi.readParkingEtaSeconds(checkNotNull(candidate.parking)) }
                        if (eta != null) ranked += Option(candidate, eta)
                    }
                    check(navi.readActiveSosRoute() == route) { "내비 경로가 바뀌었어요. 다시 요청해 주세요." }
                    check(allowed) { "차량 상태를 다시 확인해 주세요." }
                    val chosen = ranked.sortedWith(compareBy<Option> { it.score }.thenBy { it.driveSeconds }).take(3)
                    selection = Selection(route, reference, chosen, SystemClock.elapsedRealtime() + 45_000)
                    Log.i(TAG, "Overlay candidates ready; compared=${ranked.size}, eligible=${options.size}, displayed=${chosen.size}; values omitted")
                    when {
                        chosen.isEmpty() -> showStatus("확인 가능한 후보가 없어요", "경로는 바꾸지 않았어요. 위치·저장 자료·내비 응답을 확인해 주세요.")
                        options.size == 1 && chosen.size == 1 -> addSelected(chosen.single())
                        else -> showOptions(chosen, ranked.size < options.size)
                    }
                }
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) showFailure("조회 시간이 초과됐어요")
                else throw error
            } catch (error: Exception) {
                Log.w(TAG, "Overlay lookup failed; type=${error.javaClass.simpleName}; values omitted")
                showFailure("후보를 확인하지 못했어요")
            } finally { navi.close(); location.close() }
        }
        return "accepted"
    }

    private suspend fun recentLocation(): FacilityReference {
        val fix = location.readLastKnownLocation() ?: error("위치를 확인하지 못했어요.")
        check(validFacilityCoordinates(fix.latitude, fix.longitude))
        check(System.currentTimeMillis() - fix.timestampMillis in 0L..120_000L) { "최신 위치가 필요해요." }
        return FacilityReference(fix.latitude, fix.longitude, fix.timestampMillis)
    }

    /** Display-only probe: never reads location or calls Navigation. */
    private fun preview(): String {
        if (!Settings.canDrawOverlays(this)) return "overlay_permission"
        if (!allowed) return "vehicle_restricted_or_unknown"
        if (operation?.isActive == true || panel != null) return "overlay_busy"
        operation = scope.launch {
            delay(10_000)
            if (!allowed || !Settings.canDrawOverlays(this@SosOverlayService)) {
                Log.i(TAG, "Display probe skipped; allowed=$allowed, canDrawOverlays=${Settings.canDrawOverlays(this@SosOverlayService)}")
                return@launch
            }
            try {
                showStatus("오버레이 표시 확인", "내비 위에 이 창이 보이면 표시가 가능한 상태예요. 위치 조회·경로 변경은 하지 않았어요.")
                Log.i(TAG, "Display probe requested; no location or route calls")
                val probePanel = panel
                delay(15_000)
                if (panel === probePanel) hidePanel()
            } catch (error: Exception) {
                Log.w(TAG, "Display probe failed; type=${error.javaClass.simpleName}")
            }
        }
        return "scheduled"
    }

    private fun select(option: Option) {
        if (operation?.isActive == true || used) return
        operation = scope.launch {
            try {
                withTimeout(15_000) {
                    navi.connect()
                    addSelected(option)
                }
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) showFailure("경유지 확인 시간이 초과됐어요")
                else throw error
            } catch (error: Exception) {
                Log.w(TAG, "Overlay waypoint failed; sent=$sent, type=${error.javaClass.simpleName}; values omitted")
                showFailure("경유지 추가를 확인하지 못했어요")
            } finally { navi.close(); location.close() }
        }
    }

    private suspend fun addSelected(option: Option) {
        val snapshot = selection ?: error("후보가 만료됐어요.")
        check(!used && option in snapshot.options && allowed)
        check(SystemClock.elapsedRealtime() < snapshot.expiresAt)
        val recent = recentLocation()
        check((snapshot.reference.distanceMetersTo(recent.latitude, recent.longitude) ?: Double.POSITIVE_INFINITY) <= 100)
        check(navi.readActiveSosRoute() == snapshot.route) { "내비 경로가 바뀌었어요." }
        used = true
        showStatus("${option.parking.name} 경유지 추가 중", "원래 목적지는 유지해요.")
        navi.addSosWaypoint(option.parking, snapshot.route,
            maySend = { allowed && SystemClock.elapsedRealtime() < snapshot.expiresAt && Settings.canDrawOverlays(this) },
            onSent = { sent = true })
        Log.i(TAG, "Overlay waypoint confirmed; original stops preserved; values omitted")
        showStatus("다음 경유지에 추가했어요", "${option.parking.name}\n${option.candidate.restroom.name} 이용 · 원래 목적지 유지")
        val completedSelection = selection
        scope.launch { delay(5_000); if (selection === completedSelection && used) hidePanel() }
    }

    private fun showFailure(title: String) {
        if (!allowed) { hidePanel(); return }
        runCatching { showStatus(title, if (sent) "이미 요청됐을 수 있어요. 내비에서 경유지를 확인한 뒤 다시 요청해 주세요."
            else "기존 경로는 그대로예요. 위치·시설 자료·내비 상태를 확인해 주세요.") }
    }

    private fun container(title: String, detail: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply { setColor(Color.rgb(13, 33, 40)); cornerRadius = dp(18).toFloat() }
        }
        box.addView(label(title, 21f, true))
        box.addView(label(detail, 16f))
        return box
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(0, dp(4), 0, dp(8))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun showStatus(title: String, detail: String) {
        if (!allowed || !Settings.canDrawOverlays(this)) return
        val box = container(title, detail)
        closeButton(box)
        attach(box)
    }

    private fun showOptions(options: List<Option>, partial: Boolean) {
        val box = container("화장실 가까운 주차장", "예상 도착 시간 기준 · 우회 시간 미반영" +
            if (partial) "\n일부 후보 조회 실패 · 자동 추가 안 함" else "")
        options.forEachIndexed { index, option ->
            box.addView(Button(this).apply {
                text = "${index + 1}. ${option.parking.name}\n${option.candidate.restroom.name}\n차량 약 ${ceil(option.driveSeconds / 60.0).toInt()}분 · 화장실까지 직선 ${option.candidate.parkingDistanceMeters!!.toInt()}m"
                textSize = 17f
                isAllCaps = false
                minHeight = dp(72)
                setOnClickListener { select(option) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        box.addView(label("개방·빈자리·실제 도보 진입은 미확인", 14f))
        closeButton(box)
        attach(box)
        val snapshot = selection
        scope.launch { delay(45_000); if (selection === snapshot && !used) { selection = null; hidePanel() } }
    }

    private fun closeButton(box: LinearLayout) {
        box.addView(Button(this).apply {
            text = "닫기"; isAllCaps = false
            setOnClickListener {
                hidePanel()
                if (!sent) { operation?.cancel(); selection = null }
            }
        })
    }

    private fun attach(view: View) {
        hidePanel()
        if (!allowed || !Settings.canDrawOverlays(this)) return
        val window = getSystemService(WindowManager::class.java)
        val width = minOf(dp(460), (resources.displayMetrics.widthPixels * .44).toInt())
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = dp(16); y = dp(80) }
        try {
            window.addView(view, params)
            panel = view
            Log.i(TAG, "Overlay addView accepted; type=${params.type}, width=${params.width}, x=${params.x}, y=${params.y}")
            view.post {
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                Log.i(TAG, "Overlay view state; attached=${view.isAttachedToWindow}, shown=${view.isShown}, display=${view.display?.displayId}, width=${view.width}, height=${view.height}, x=${position[0]}, y=${position[1]}")
            }
        }
        catch (error: Exception) {
            Log.w(TAG, "Overlay attach failed; type=${error.javaClass.simpleName}")
            throw error
        }
    }

    private fun hidePanel() {
        panel?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        panel = null
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        Log.i(TAG, "SOS overlay session destroyed")
        if (active === this) active = null
        scope.cancel()
        monitor?.close()
        hidePanel()
        navi.close()
        location.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SHSH_SOS_OVERLAY"
        private const val CHANNEL = "sos-overlay-debug"
        const val STOP = "com.shsh.pleos.playground.STOP_SOS_OVERLAY"
        private var active: SosOverlayService? = null
        fun requestSearch(): String = active?.search() ?: "overlay_session_not_ready"
        fun requestPreview(): String = active?.preview() ?: "overlay_session_not_ready"
        fun sessionStatus(): String = active?.let { "세션 실행 중 · 차량 허용: ${it.allowed}" } ?: "세션 꺼짐"
    }
}
