package com.zoe.iptv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.zoe.iptv.data.PlaylistGrouper
import com.zoe.iptv.data.PlaylistHolder
import com.zoe.iptv.data.PlaylistParser
import com.zoe.iptv.data.Prefs
import com.zoe.iptv.databinding.ActivityPlayerBinding
import com.zoe.iptv.ui.PlayerChannelAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import java.net.URL
import kotlin.math.abs

/**
 * 主界面：横屏全屏播放器（电视 + 手机），打开 App 直接进入。
 *
 * 播放内核是打包进 APK 的火狐 GeckoView（不依赖系统 WebView，
 * 各设备体验一致），m3u 里的网页地址由 GeckoView 打开，
 * 网页自己的播放器（JS/WASM 解密 + MSE）完成播放。App 只负责：
 *  1. 内置扩展（assets/zoeext）：页面里自动播放 <video> 并全屏；
 *  2. PermissionDelegate 放行自动播放；
 *  3. 切台：遥控器上下键 / 频道键、手机屏幕上下滑动；
 *  4. 频道面板：分组 + 台标 + 编号，遥控器 OK/菜单键、手机点按呼出，
 *     长按屏幕进设置配直播源。
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var panelAdapter: PlayerChannelAdapter
    private lateinit var prefs: Prefs
    private lateinit var gestures: GestureDetector

    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null

    private var index: Int = 0
    private var loading: Boolean = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var bannerHide: Runnable? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUi()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)

        setupGeckoView()
        setupPanel()
        setupGestures()
        loadPlaylist()
    }

    // ---------- GeckoView ----------

    private fun setupGeckoView() {
        // 桌面 UA 模式：央视频 tv/home 是桌面页面
        val settings = GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .build()
        val rt = GeckoRuntime.create(this)
        runtime = rt
        val s = GeckoSession(settings)

        // 自动播放放行（有声 / 无声都允许）
        s.setPermissionDelegate(object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                val p = perm.permission
                return if (p == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    p == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                ) {
                    GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    )
                } else {
                    GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                    )
                }
            }
        })

        // 全屏回调：进入全屏时关掉频道面板，避免遮挡
        s.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (fullScreen && isPanelOpen()) {
                    hidePanel()
                }
            }
        })

        // 加载进度条
        s.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                setLoading(true)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                setLoading(false)
            }
        })

        // 内置扩展：页面内自动播放 + 全屏
        try {
            rt.webExtensionController.installBuiltIn("resource://android/assets/zoeext/")
        } catch (e: Exception) {
            Log.w(TAG, "installBuiltIn failed", e)
        }

        session = s
        s.open(rt)
        binding.geckoView.setSession(s)
    }

    // ---------- 频道面板 ----------

    private fun setupPanel() {
        panelAdapter = PlayerChannelAdapter { globalIndex ->
            hidePanel()
            playAt(globalIndex)
        }
        binding.panelList.layoutManager = LinearLayoutManager(this)
        binding.panelList.adapter = panelAdapter
    }

    private fun isPanelOpen(): Boolean =
        binding.channelPanel.visibility == View.VISIBLE

    private fun showPanel() {
        binding.channelPanel.animate().cancel()
        binding.channelPanel.visibility = View.VISIBLE
        // 从左侧滑入
        val w = binding.channelPanel.width.toFloat()
        binding.channelPanel.translationX = if (w > 0) -w else -dp(340)
        binding.channelPanel.animate().translationX(0f).setDuration(220).start()
        panelAdapter.selectedIndex = index
        val pp = panelAdapter.panelPositionOf(index)
        if (pp != -1) {
            binding.panelList.scrollToPosition(pp)
            // 电视端：把焦点给当前频道行，上下键直接导航
            binding.panelList.post {
                binding.panelList.findViewHolderForAdapterPosition(pp)
                    ?.itemView?.requestFocus()
            }
        }
    }

    private fun hidePanel() {
        binding.channelPanel.animate().cancel()
        val w = binding.channelPanel.width.toFloat()
        val off = if (w > 0) -w else -dp(340)
        binding.channelPanel.animate().translationX(off).setDuration(200)
            .withEndAction {
                binding.channelPanel.visibility = View.GONE
                binding.channelPanel.translationX = 0f
            }.start()
        hideSystemUi()
    }

    private fun dp(v: Int): Float =
        v * resources.displayMetrics.density

    /** 加载指示：菊花 + 文字联动 */
    private fun setLoading(show: Boolean) {
        val vis = if (show) View.VISIBLE else View.GONE
        binding.pageLoading.visibility = vis
        binding.loadingText.visibility = vis
    }

    private fun togglePanel() {
        if (isPanelOpen()) hidePanel() else showPanel()
    }

    // ---------- 手势（手机端） ----------
    // 点按=面板开关，长按=进设置，上下滑动=切台。
    // OnTouchListener 返回 false，不吞事件，GeckoView 照常响应触控。

    private fun setupGestures() {
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                togglePanel()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                openSettings()
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dy) > abs(dx) && abs(dy) > 120 && abs(vy) > 200) {
                    if (isPanelOpen()) hidePanel()
                    if (dy < 0) playAt(index + 1) else playAt(index - 1) // 上滑下一个，下滑上一个
                    return true
                }
                return false
            }
        })
        binding.geckoView.setOnTouchListener { _, ev ->
            gestures.onTouchEvent(ev)
            false
        }
    }

    // ---------- 播放列表 ----------

    private fun loadPlaylist() {
        if (loading) return
        loading = true
        setLoading(true)
        ioScope.launch {
            val list = try {
                val url = prefs.playlistUrl
                val text = if (url.isBlank()) {
                    assets.open("sample.m3u").bufferedReader().readText()
                } else {
                    URL(url).readText()
                }
                PlaylistParser.parse(text)
            } catch (e: Exception) {
                Log.w(TAG, "load playlist failed", e)
                emptyList()
            }
            withContext(Dispatchers.Main) {
                loading = false
                PlaylistHolder.channels = list
                if (list.isEmpty()) {
                    setLoading(false)
                    showBanner("播放列表为空，长按屏幕去设置里配置直播源")
                    return@withContext
                }
                panelAdapter.items = PlaylistGrouper.group(list)
                binding.panelTitle.text = "频道列表 · ${list.size}"
                playAt(prefs.lastIndex.coerceIn(list.indices))
            }
        }
    }

    private fun playAt(i: Int) {
        val list = PlaylistHolder.channels
        if (list.isEmpty()) return
        index = (i + list.size) % list.size
        val ch = list[index]
        prefs.lastIndex = index
        title = ch.name
        panelAdapter.selectedIndex = index
        val pp = panelAdapter.panelPositionOf(index)
        if (pp != -1) binding.panelList.scrollToPosition(pp)
        showBanner("%03d · %s".format(index + 1, ch.name))
        setLoading(true)
        session?.loadUri(ch.url)
    }

    /** 底部半透明圆角提示条：淡入，2.5 秒后淡出 */
    private fun showBanner(text: String) {
        binding.channelBanner.animate().cancel()
        binding.channelBanner.text = text
        binding.channelBanner.visibility = View.VISIBLE
        binding.channelBanner.alpha = 0f
        binding.channelBanner.animate().alpha(1f).setDuration(150).start()
        bannerHide?.let { uiHandler.removeCallbacks(it) }
        bannerHide = Runnable {
            binding.channelBanner.animate().alpha(0f).setDuration(300)
                .withEndAction {
                    binding.channelBanner.visibility = View.GONE
                    binding.channelBanner.alpha = 1f
                }.start()
        }
        uiHandler.postDelayed(bannerHide!!, 2500)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ---------- 按键 ----------
    // dispatchKeyEvent 比 onKeyDown 先到：GeckoView 会吞掉上下键，
    // 所以切台逻辑必须在这里拦截，否则焦点在网页上时切不了台。

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val panelOpen = isPanelOpen()
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (panelOpen) {
                        hidePanel()
                        return true
                    }
                    finish()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (panelOpen) {
                        hidePanel()
                    } else {
                        openSettings() // 遥控器菜单键直达设置
                    }
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

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        // 从设置页返回时若地址变了（列表被清空）就重载
        if (PlaylistHolder.channels.isEmpty()) loadPlaylist()
    }

    override fun onDestroy() {
        bannerHide?.let { uiHandler.removeCallbacks(it) }
        ioScope.cancel()
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "session.close failed", e)
        }
        session = null
        try {
            runtime?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "runtime.shutdown failed", e)
        }
        runtime = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "zoe-iptv"
    }
}
