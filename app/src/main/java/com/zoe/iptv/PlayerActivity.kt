package com.zoe.iptv

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.zoe.iptv.data.PlaylistHolder
import com.zoe.iptv.databinding.ActivityPlayerBinding

/**
 * 全屏网页播放器。
 *
 * 原理：m3u 里的地址是网页（如央视频 tv/home），直接用全屏 WebView 打开，
 * 由网页自己的播放器（JS/WASM 解密 + MSE）完成播放，App 只负责：
 *  1. 页面加载完后注入 JS：自动播放、尝试让 <video> 全屏；
 *  2. WebChromeClient.onShowCustomView 接管 HTML5 全屏视频；
 *  3. 遥控器上下键 / 频道键切台，返回键先退全屏再退出。
 *
 * 内核是系统 WebView（Chromium/Chrome 内核），随系统更新，
 * 不占 APK 体积，电视盒子视频兼容性最好。
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var index: Int = 0

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUi()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        index = intent.getIntExtra(EXTRA_INDEX, 0)
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

        wv.webChromeClient = object : WebChromeClient() {
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
    }

    // ---------- 播放控制 ----------

    private fun playAt(i: Int) {
        val list = PlaylistHolder.channels
        if (list.isEmpty()) {
            finish()
            return
        }
        index = (i + list.size) % list.size
        val ch = list[index]
        title = ch.name
        binding.pageLoading.visibility = View.VISIBLE
        binding.webView.loadUrl(ch.url)
    }

    /**
     * 页面加载后注入 JS：找 <video> 自动播放并尝试全屏。
     * 延迟多试几次，因为很多站点的 video 元素是懒加载的。
     */
    private fun injectAutoPlay(view: WebView) {
        val js = """
            (function(){
              var tries = 0;
              function go(){
                tries++;
                var v = document.querySelector('video');
                if(v){
                  try{ v.muted = false; }catch(e){}
                  try{ var p = v.play(); if(p && p.catch) p.catch(function(){}); }catch(e){}
                  try{
                    if(v.requestFullscreen) v.requestFullscreen();
                    else if(v.webkitRequestFullscreen) v.webkitRequestFullscreen();
                  }catch(e){}
                  return true;
                }
                // 兜底：点页面上看起来像播放按钮的东西
                var btn = document.querySelector('[class*="play-btn"],[class*="playBtn"],.vcp-play-btn');
                if(btn){ try{ btn.click(); }catch(e){} }
                if(tries < 6) setTimeout(go, 1500);
                return false;
              }
              go();
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    // ---------- 按键 ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // 先退视频全屏，再退页面
                if (customView != null) {
                    (binding.webView.webChromeClient as? WebChromeClient)
                        ?.onHideCustomView()
                    return true
                }
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                    return true
                }
            }
            // 遥控器：上下 / 频道加减 = 切台
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                playAt(index - 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                playAt(index + 1)
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
