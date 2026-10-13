package com.zoe.iptv

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.zoe.iptv.data.PlaylistHolder
import com.zoe.iptv.data.PlaylistParser
import com.zoe.iptv.data.Prefs
import com.zoe.iptv.databinding.ActivityMainBinding
import com.zoe.iptv.ui.ChannelAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val adapter = ChannelAdapter { pos -> playAt(pos) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        // TV 端列数少、字大、焦点导航；手机端列数多、触控
        val span = if (isTelevision()) 2 else 1
        binding.channelList.layoutManager = GridLayoutManager(this, span)
        binding.channelList.adapter = adapter

        binding.btnReload.setOnClickListener { loadPlaylist() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadPlaylist()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回时若地址变了就重载
        if (PlaylistHolder.channels.isEmpty()) loadPlaylist()
    }

    private fun isTelevision(): Boolean {
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun loadPlaylist() {
        binding.loadingView.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val url = prefs.playlistUrl
                val text = if (url.isBlank()) {
                    assets.open("sample.m3u").bufferedReader().readText()
                } else {
                    URL(url).readText()
                }
                PlaylistParser.parse(text)
            }
            withContext(Dispatchers.Main) {
                binding.loadingView.visibility = View.GONE
                result.onSuccess { list ->
                    PlaylistHolder.channels = list
                    adapter.submitList(list)
                    binding.emptyView.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                }.onFailure { e ->
                    binding.emptyView.visibility = View.VISIBLE
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.load_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun playAt(pos: Int) {
        if (pos !in PlaylistHolder.channels.indices) return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_INDEX, pos)
        )
    }

    // 电视端：菜单键打开设置
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 电视遥控器菜单键直达设置
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
