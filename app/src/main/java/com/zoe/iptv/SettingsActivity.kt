package com.zoe.iptv

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zoe.iptv.data.PlaylistHolder
import com.zoe.iptv.data.Prefs
import com.zoe.iptv.databinding.ActivitySettingsBinding

/** 播放列表地址设置：留空则用内置示例 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.playlistUrl.setText(prefs.playlistUrl)

        binding.btnSave.setOnClickListener {
            prefs.playlistUrl = binding.playlistUrl.text.toString()
            PlaylistHolder.channels = emptyList() // 触发 MainActivity 重载
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnReset.setOnClickListener {
            prefs.playlistUrl = ""
            binding.playlistUrl.setText("")
            PlaylistHolder.channels = emptyList()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
