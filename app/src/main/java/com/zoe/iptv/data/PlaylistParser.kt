package com.zoe.iptv.data

import com.zoe.iptv.model.Channel

/**
 * 极简 m3u 解析器。支持：
 *   #EXTM3U
 *   #EXTINF:-1 tvg-logo="..." group-title="...",频道名
 *   https://网页地址…
 * 地址行只要是 http(s) 开头就收录（网页地址同样支持，不要求是 .m3u8）。
 */
object PlaylistParser {

    private val attrRegexCache = mutableMapOf<String, Regex>()

    fun parse(text: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var pendingName = ""
        var pendingLogo = ""
        var pendingGroup = ""

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingName = line.substringAfter(",", "").trim().ifEmpty { "未知频道" }
                    pendingLogo = attr(line, "tvg-logo")
                    pendingGroup = attr(line, "group-title")
                }
                line.startsWith("#") -> { /* 其他标签忽略 */ }
                line.startsWith("http://", ignoreCase = true) ||
                        line.startsWith("https://", ignoreCase = true) -> {
                    channels += Channel(
                        name = pendingName.ifEmpty { line },
                        url = line,
                        logo = pendingLogo,
                        group = pendingGroup
                    )
                    pendingName = ""
                    pendingLogo = ""
                    pendingGroup = ""
                }
                // 非 http 行（如相对路径）忽略，避免误收录
            }
        }
        return channels
    }

    private fun attr(line: String, key: String): String {
        val regex = attrRegexCache.getOrPut(key) { Regex("$key=\"([^\"]*)\"") }
        return regex.find(line)?.groupValues?.getOrNull(1).orEmpty()
    }
}
