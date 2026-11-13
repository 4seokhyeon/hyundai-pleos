package com.shsh.pleos.playground

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import com.shsh.pleos.playground.platform.AppLog as Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.shsh.pleos.playground.platform.DrivingRestrictionsMonitor
import com.shsh.pleos.playground.platform.YouTubePlayerPage

/** Debug-only native View comparison. Same video/page/provider, no Compose and no recording. */
class PlayerRenderProbeActivity : ComponentActivity() {
    private lateinit var frame: FrameLayout
    private lateinit var status: TextView
    private var web: WebView? = null
    private var monitor: DrivingRestrictionsMonitor? = null
    private var videoId: String? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoId=intent.getStringExtra("video_id")?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
        if(videoId==null) { finish();return }
        status=TextView(this).apply { text="플레이어 단독 검증 · 차량 상태 확인 중";setTextColor(Color.WHITE);textSize=22f }
        frame=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val bar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;setPadding(20,12,20,12);setBackgroundColor(Color.rgb(13,24,30))
            addView(status,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
            addView(Button(this@PlayerRenderProbeActivity).apply { text="닫기";setOnClickListener { finish() } })
        }
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            addView(bar,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(frame,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        }
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadPlayer() {
        if(web!=null || !resumed) return
        val id=videoId ?: return
        val instance=WebView(this)
        web=instance
        instance.setBackgroundColor(Color.BLACK)
        instance.settings.apply {
            javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=false
            allowFileAccess=false;allowContentAccess=false
        }
        WebView.getCurrentWebViewPackage()?.let { provider ->
            Log.i("SHSH_PLAYER_NATIVE","Provider package=${provider.packageName}, version=${provider.versionName}")
        }
        instance.addOnLayoutChangeListener { _,l,t,r,b,_,_,_,_ ->
            Log.i("SHSH_PLAYER_NATIVE","Layout width=${r-l}, height=${b-t}, hardware=${instance.isHardwareAccelerated}")
        }
        instance.webViewClient=object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView,request: WebResourceRequest): Boolean = request.isForMainFrame
            override fun onRenderProcessGone(view: WebView,detail: RenderProcessGoneDetail): Boolean {
                Log.w("SHSH_PLAYER_NATIVE","Renderer gone; crashed=${detail.didCrash()}")
                runOnUiThread { status.text="WebView 렌더러 종료 · 닫고 로그를 확인해 주세요";closePlayer() }
                return true
            }
        }
        instance.webChromeClient=object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val text=message.message()
                if(text.startsWith("SHSH_PLAYER_NATIVE:")) {
                    Log.i("SHSH_PLAYER_NATIVE",text)
                    runOnUiThread {
                        if(web!==instance) return@runOnUiThread
                        when(text.removePrefix("SHSH_PLAYER_NATIVE:")) {
                            "Ready" -> status.text="플레이어 준비 완료 · 재생 요청 중"
                            "State=1" -> status.text="영상 재생 중 · 영상 표시 여부를 확인해 주세요"
                            "State=3" -> status.text="버퍼링 중"
                            "AutoplayBlocked" -> status.text="재생 차단 · YouTube 재생 버튼을 눌러주세요"
                        }
                    }
                }
                return true
            }
        }
        frame.addView(instance,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        status.text="같은 영상 · 기본 Android WebView로 재생 요청 중"
        instance.loadDataWithBaseURL(YouTubePlayerPage.BASE_URL,YouTubePlayerPage.html(id,true,"SHSH_PLAYER_NATIVE"),"text/html","UTF-8",null)
    }

    private fun closePlayer() {
        val instance=web ?: return
        web=null;frame.removeView(instance)
        instance.stopLoading();instance.loadUrl("about:blank");instance.onPause();instance.destroy()
    }
    override fun onResume() {
        super.onResume();resumed=true
        monitor=DrivingRestrictionsMonitor(this) { allowed,_ ->
            if(allowed && resumed) loadPlayer()
            else { closePlayer();status.text="차량 이용 제한 또는 상태 확인 중" }
        }.also { it.start() }
    }
    override fun onPause() {
        resumed=false;monitor?.close();monitor=null;closePlayer()
        super.onPause()
    }
    override fun onDestroy() { closePlayer();super.onDestroy() }
}
