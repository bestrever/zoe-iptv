package com.zoe.iptv.model

/**
 * 一个频道。url 是**网页地址**（如央视频 tv/home 页面），
 * 播放时用全屏 WebView 打开，由网页自己的播放器解码播放。
 */
data class Channel(
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = ""
)
