package com.zoe.iptv.data

import com.zoe.iptv.model.Channel

/** 频道面板的行：分组头 或 频道条目 */
sealed interface PanelItem {
    data class Header(val title: String) : PanelItem
    data class Entry(val channel: Channel, val globalIndex: Int) : PanelItem
}

/**
 * 把频道按 group-title 分组，保持分组首次出现的顺序；
 * 没有分组的频道归入“其他”。
 */
object PlaylistGrouper {

    fun group(channels: List<Channel>): List<PanelItem> {
        val order = linkedMapOf<String, MutableList<Pair<Channel, Int>>>()
        channels.forEachIndexed { i, ch ->
            val g = ch.group.ifBlank { "其他" }
            order.getOrPut(g) { mutableListOf() } += ch to i
        }
        val items = mutableListOf<PanelItem>()
        for ((title, list) in order) {
            items += PanelItem.Header(title)
            for ((ch, i) in list) items += PanelItem.Entry(ch, i)
        }
        return items
    }
}
