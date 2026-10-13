package com.zoe.iptv.data

import android.content.Context
import android.content.SharedPreferences

/** 播放列表地址存这里；留空 = 用 assets 内置示例 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("zoe-iptv", Context.MODE_PRIVATE)

    var playlistUrl: String
        get() = sp.getString(KEY_URL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_URL, v.trim()).apply()

    /** 上次播放的频道下标，下次打开直接继续播 */
    var lastIndex: Int
        get() = sp.getInt(KEY_INDEX, 0)
        set(v) = sp.edit().putInt(KEY_INDEX, v).apply()

    companion object {
        private const val KEY_URL = "playlist_url"
        private const val KEY_INDEX = "last_index"
    }
}
