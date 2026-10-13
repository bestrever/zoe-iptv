package com.zoe.iptv

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.zoe.iptv.data.PlaylistHolder
import com.zoe.iptv.databinding.ActivityPlayerBinding
import com.zoe.iptv.ui.PlayerChannelAdapter
import kotlin.math.abs

/**
 * 横屏全屏网页播放器（电视 + 手机）。
 *
 * 原理：m3u 里的地址是网页（如央视频 tv/home），直接用全屏 WebView 打开，
 * 由网页自己的播放器（JS/WASM 解密 + MSE）完成播放，App 只负责：
 *  1. 页面加载后注入 JS：自动播放、点全屏按钮、兜底把 <video> 铺满视口；
 *  2. WebChromeClient.onShowCustomView 接管 HTML5 全屏视频；
 *  3. 切台：遥控器上下键 / 频道键、手机屏幕上下滑动；
 *  4. 频道面板：遥控器 OK/菜单键、手机点按呼出，电视端焦点导航。
 *
 * 内核是系统 WebView（Chromium/Chrome 内核），随系统更新，
 * 不占 APK 体积，电视盒子视频兼容性最好。
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var panelAdapter: PlayerChannelAdapter
    private lateinit var gestures: GestureDetector
    private var chromeClient: WebChromeClient? = null

    private var index: Int = 0
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var bannerHide: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUi()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        index = intent.getIntExtra(EXTRA_INDEX, 0)

        // 左侧频道面板
        panelAdapter = PlayerChannelAdapter { pos ->
            hidePanel()
            playAt(pos)
        }
        binding.panelList.layoutManager = LinearLayoutManager(this)
        binding.panelList.adapter = panelAdapter
        panelAdapter.channels = PlaylistHolder.channels
        panelAdapter.selected = index

        // 手机端手势：点按呼出/收起面板，上下滑动切台。
        // OnTouchListener 返回 false，不吞事件，WebView 照常响应触控。
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                togglePanel()
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dy) > abs(dx) && abs(dy) > 120 && abs(vy) > 200) {
                    if (binding.channelPanel.visibility == View.VISIBLE) hidePanel()
                    if (dy < 0) playAt(index + 1) else playAt(index - 1) // 上滑下一个，下滑上一个
                    return true
                }
                return false
            }
        })
        binding.webView.setOnTouchListener { _, ev ->
            gestures.onTouchEvent(ev)
            false
        }

        setupWebView()
        playAt(index)
    }

    // ---------- WebView 设置 ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv: WebView = binding.webView
        val s: WebSettings = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false // 允许 JS 自动播放
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        // 桌面版 Chrome UA：央视频 tv/home 是桌面页面，移动 UA 可能拿不到播放器
        s.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/126.0.0.0 Safari/537.36"
        wv.setBackgroundColor(0xFF000000.toInt())

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                // 所有跳转都留在 WebView 内，不抛给外部浏览器
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                binding.pageLoading.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.pageLoading.visibility = View.GONE
                injectAutoPlay(view)
            }
        }

        chromeClient = object : WebChromeClient() {
            // 网页 <video> 请求全屏时回调：把 video 视图搬到全屏容器
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                binding.webView.visibility = View.GONE
                binding.customViewContainer.visibility = View.VISIBLE
                binding.customViewContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                hideSystemUi()
            }

            override fun onHideCustomView() {
                customView?.let { binding.customViewContainer.removeView(it) }
                customView = null
                customViewCallback = null
                binding.customViewContainer.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
                hideSystemUi()
            }
        }
        wv.webChromeClient = chromeClient
    }

    // ---------- 播放控制 ----------

    private fun playAt(i: Int) {
        val list = PlaylistHolder.channels
        if (list.isEmpty()) {
            finish()
            return
        }
        // 切台前先退出可能存在的网页全屏态
        if (customView != null) chromeClient?.onHideCustomView()
        index = (i + list.size) % list.size
        val ch = list[index]
        title = ch.name
        panelAdapter.selected = index
        binding.panelList.scrollToPosition(index)
        showBanner("%03d · %s".format(index + 1, ch.name))
        binding.pageLoading.visibility = View.VISIBLE
        binding.webView.loadUrl(ch.url)
    }

    /**
     * 页面加载后注入 JS：找 <video> 自动播放并全屏。
     * 策略（按顺序，哪个成用哪个）：
     *  1. 调 requestFullscreen（走 onShowCustomView 全屏容器）；
     *  2. 点页面自带的全屏按钮；
     *  3. 兜底：直接把 video 元素 position:fixed 铺满视口。
     * video 可能是懒加载的，每 1.5s 重试一次，最多 40 次。
     */
    private fun injectAutoPlay(view: WebView) {
        val js = """
            (function(){
              function go(){
                var v = document.querySelector('video');
                if(!v) return false;
                try{ v.muted = false; }catch(e){}
                try{ var p = v.play(); if(p && p.catch) p.catch(function(){}); }catch(e){}
                if(!document.fullscreenElement){
                  try{
                    var sels = ['.vcp-fullscreen-btn','[class*=fullscreen i]','[class*=full-screen i]',
                                 '.prism-fullscreen-btn','.vjs-fullscreen-control','[aria-label*=全屏]','[title*=全屏]'];
                    for(var i=0;i<sels.length;i++){
                      var b = document.querySelector(sels[i]);
                      if(b){ b.click(); break; }
                    }
                  }catch(e){}
                  try{ (v.requestFullscreen || v.webkitRequestFullscreen || function(){}).call(v); }catch(e){}
                }
                try{
                  v.style.setProperty('position','fixed','important');
                  v.style.setProperty('inset','0','important');
                  v.style.setProperty('width','100vw','important');
                  v.style.setProperty('height','100vh','important');
                  v.style.setProperty('z-index','2147483647','important');
                  v.style.setProperty('background','#000','important');
                  v.style.setProperty('object-fit','contain','important');
                }catch(e){}
                return true;
              }
              var n = 0;
              go();
              var t = setInterval(function(){ if(go() || ++n > 40) clearInterval(t); }, 1500);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    // ---------- 频道面板 ----------

    private fun isPanelOpen(): Boolean =
        binding.channelPanel.visibility == View.VISIBLE

    private fun showPanel() {
        binding.channelPanel.visibility = View.VISIBLE
        panelAdapter.selected = index
        binding.panelList.scrollToPosition(index)
        // 电视端：把焦点给当前频道行，上下键直接导航
        binding.panelList.post {
            binding.panelList.findViewHolderForAdapterPosition(index)
                ?.itemView?.requestFocus()
        }
    }

    private fun hidePanel() {
        binding.channelPanel.visibility = View.GONE
        hideSystemUi()
    }

    private fun togglePanel() {
        if (isPanelOpen()) hidePanel() else showPanel()
    }

    private fun showBanner(text: String) {
        binding.channelBanner.text = text
        binding.channelBanner.visibility = View.VISIBLE
        bannerHide?.let { uiHandler.removeCallbacks(it) }
        bannerHide = Runnable { binding.channelBanner.visibility = View.GONE }
        uiHandler.postDelayed(bannerHide!!, 2500)
    }

    // ---------- 按键 ----------
    // dispatchKeyEvent 比 onKeyDown 先到：WebView 会吞掉上下键（翻页），
    // 所以切台逻辑必须在这里拦截，否则焦点在 WebView 上时切不了台。

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val panelOpen = isPanelOpen()
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (panelOpen) {
                        hidePanel()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MENU -> {
                    togglePanel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!panelOpen) {
                        showPanel()
                        return true
                    }
                    // 面板打开时放行，让焦点行的点击事件正常触发
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (!panelOpen) {
                        playAt(index - 1)
                        return true
                    }
                    // 面板打开时放行，上下键在列表里导航
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (!panelOpen) {
                        playAt(index + 1)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 先退视频全屏，再退页面历史，最后退出
            if (customView != null) {
                chromeClient?.onHideCustomView()
                return true
            }
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- 沉浸式全屏 ----------

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------- 生命周期 ----------

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        hideSystemUi()
    }

    override fun onDestroy() {
        bannerHide?.let { uiHandler.removeCallbacks(it) }
        binding.webView.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INDEX = "index"
    }
}
