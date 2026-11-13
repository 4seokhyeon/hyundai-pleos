package com.shsh.pleos.playground

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.shsh.pleos.playground.data.LocationRepository

/** Explicit, stationary preparation; never opened automatically from Gleo. */
class SosOverlaySetupActivity : ComponentActivity() {
    private lateinit var status: TextView
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 36, 36, 36) }
        status = TextView(this).apply { textSize = 20f }
        box.addView(TextView(this).apply {
            text = "화면 없는 화장실 SOS · 디버그 검증\n\n내비 안내를 켠 주차 상태에서 검증해 주세요.\n검색 결과를 내비 위에 최대 3개 표시합니다. 후보가 정확히 하나이고 전체 조회가 완료되면 자동으로 경유지에 추가합니다. 다른 경우는 한 번 눌러 선택합니다.\n\n예상 도착 시간 기준이며 경로 우회·화장실 개방·주차 빈자리는 확인하지 않습니다. 차량 주행 제한은 그대로 적용됩니다. 위치와 시설 자료는 기존 앱 설정을 사용합니다."
            textSize = 20f
        })
        box.addView(status)
        fun action(label: String, run: () -> Unit) {
            box.addView(Button(this).apply { text = label; textSize = 20f; setOnClickListener { run() } })
        }
        action("다른 앱 위에 표시 허용 설정") {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                .onFailure { status.text = "이 플랫폼에서 표시 권한 설정을 열지 못했어요." }
        }
        action("위치 권한 확인") { locationPermission.launch(LocationRepository.PERMISSION) }
        action("조건에 동의하고 SOS 세션 시작") {
            if (!Settings.canDrawOverlays(this)) { status.text = "먼저 다른 앱 위에 표시를 허용해 주세요."; return@action }
            if (ContextCompat.checkSelfPermission(this, LocationRepository.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                status.text = "먼저 위치 권한을 허용해 주세요."; return@action
            }
            try {
                ContextCompat.startForegroundService(this, Intent(this, SosOverlayService::class.java))
                status.text = "세션 시작을 요청했어요. 내비로 돌아간 뒤 Gleo에 요청해 주세요."
            } catch (_: Exception) { status.text = "SOS 세션을 시작하지 못했어요. 플랫폼 서비스 지원 상태를 확인해 주세요." }
        }
        action("10초 후 오버레이 표시 확인") {
            status.text = when (val result = SosOverlayService.requestPreview()) {
                "scheduled" -> "10초 안에 내비로 돌아가세요. 작은 테스트창을 15초간 표시해요. 경로는 바꾸지 않아요."
                "overlay_session_not_ready" -> "먼저 SOS 세션을 시작해 주세요."
                "overlay_permission" -> "다른 앱 위에 표시를 먼저 허용해 주세요."
                "vehicle_restricted_or_unknown" -> "차량 허용 상태를 확인하지 못했어요. 주차 상태인지 확인해 주세요."
                else -> "다른 SOS 창이나 작업을 닫은 뒤 다시 시도해 주세요. ($result)"
            }
        }
        action("SOS 세션 종료") { stopService(Intent(this, SosOverlayService::class.java)); status.text = "종료했어요." }
        action("뒤로") { finish() }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onResume() { super.onResume(); updateStatus() }
    private fun updateStatus() {
        if (::status.isInitialized) status.text = "${SosOverlayService.sessionStatus()}\n표시 허용: ${Settings.canDrawOverlays(this)} · 위치 허용: ${ContextCompat.checkSelfPermission(this, LocationRepository.PERMISSION) == PackageManager.PERMISSION_GRANTED}"
    }
}
