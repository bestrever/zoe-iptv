package com.zoe.iptv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.zoe.iptv.R
import com.zoe.iptv.model.Channel

/**
 * 播放页左侧频道面板：编号 + 名称，当前播放的高亮显示。
 * 电视端靠焦点导航（item 可聚焦），手机端靠触控点击。
 */
class PlayerChannelAdapter(
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<PlayerChannelAdapter.VH>() {

    var channels: List<Channel> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    var selected: Int = -1
        set(v) {
            val old = field
            field = v
            if (old in channels.indices) notifyItemChanged(old)
            if (v in channels.indices) notifyItemChanged(v)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_player_channel, parent, false)
        return VH(v, onPick)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        h.bind(channels[pos], pos == selected, pos)
    }

    override fun getItemCount(): Int = channels.size

    class VH(v: View, onPick: (Int) -> Unit) : RecyclerView.ViewHolder(v) {
        private val num: TextView = v.findViewById(R.id.channelNum)
        private val name: TextView = v.findViewById(R.id.channelName)

        init {
            v.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onPick(p)
            }
        }

        fun bind(ch: Channel, isSel: Boolean, pos: Int) {
            num.text = "%03d".format(pos + 1)
            name.text = ch.name
            val color = if (isSel) 0xFF4DA6FF.toInt() else 0xFFFFFFFF.toInt()
            num.setTextColor(color)
            name.setTextColor(color)
            name.paint.isFakeBoldText = isSel
        }
    }
}
