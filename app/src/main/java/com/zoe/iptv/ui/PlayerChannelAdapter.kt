package com.zoe.iptv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.zoe.iptv.R
import com.zoe.iptv.data.PanelItem

/**
 * 播放页左侧频道面板：分组头 + 频道行（台标 + 编号 + 名称）。
 * 电视端靠焦点导航（频道行可聚焦），手机端靠触控点击。
 * 点击回调给的是频道在全列表中的 globalIndex。
 */
class PlayerChannelAdapter(
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<PanelItem> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    /** 当前播放频道的 globalIndex，高亮用 */
    var selectedIndex: Int = -1
        set(v) {
            if (field != v) {
                field = v
                notifyDataSetChanged()
            }
        }

    override fun getItemViewType(pos: Int): Int = when (items[pos]) {
        is PanelItem.Header -> TYPE_HEADER
        is PanelItem.Entry -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inf.inflate(R.layout.item_player_group, parent, false))
        } else {
            EntryVH(inf.inflate(R.layout.item_player_channel, parent, false), onPick)
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val it = items[pos]) {
            is PanelItem.Header -> (h as HeaderVH).bind(it.title)
            is PanelItem.Entry -> (h as EntryVH).bind(it, it.globalIndex == selectedIndex)
        }
    }

    override fun getItemCount(): Int = items.size

    /** 某个 globalIndex 在面板中的行位置（滚动定位用），找不到返回 -1 */
    fun panelPositionOf(globalIndex: Int): Int =
        items.indexOfFirst { it is PanelItem.Entry && it.globalIndex == globalIndex }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(title: String) {
            (itemView as TextView).text = title
        }
    }

    class EntryVH(v: View, onPick: (Int) -> Unit) : RecyclerView.ViewHolder(v) {
        private val bar: View = v.findViewById(R.id.selectedBar)
        private val logo: ImageView = v.findViewById(R.id.channelLogo)
        private val num: TextView = v.findViewById(R.id.channelNum)
        private val name: TextView = v.findViewById(R.id.channelName)
        private var globalIndex: Int = -1

        init {
            v.setOnClickListener {
                if (globalIndex != -1) onPick(globalIndex)
            }
        }

        fun bind(entry: PanelItem.Entry, isSel: Boolean) {
            globalIndex = entry.globalIndex
            num.text = "%03d".format(entry.globalIndex + 1)
            name.text = entry.channel.name
            val color = if (isSel) 0xFF4DA6FF.toInt() else 0xFFFFFFFF.toInt()
            num.setTextColor(color)
            name.setTextColor(color)
            name.paint.isFakeBoldText = isSel
            bar.visibility = if (isSel) View.VISIBLE else View.INVISIBLE
            // 台标：圆形裁剪，有 tvg-logo 才加载，没有就清空（防止复用错乱）
            if (entry.channel.logo.isNotBlank()) {
                logo.load(entry.channel.logo) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                }
            } else {
                logo.setImageDrawable(null)
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1
    }
}
