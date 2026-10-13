package com.zoe.iptv.data

import com.zoe.iptv.model.Channel

/**
 * 进程内播放列表持有者。Activity 之间传整个列表用 Intent 会超限，
 * 所以列表放这里单例，Intent 只传下标。
 */
object PlaylistHolder {
    var channels: List<Channel> = emptyList()
}
