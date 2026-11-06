package app.vpngate.client

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.vpngate.client.databinding.ItemServerBinding

class ServerAdapter(private val onClick: (Server) -> Unit) :
    RecyclerView.Adapter<ServerAdapter.VH>() {

    private var items: List<Server> = emptyList()

    class VH(val b: ItemServerBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<Server>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val probeText = when (s.probe) {
            Server.VERIFIED -> "✔ دست‌دهی تأیید شد"
            Server.REACHABLE -> "پاسخ‌گو"
            Server.UNREACHABLE -> "✖ بسته/فیلتر"
            else -> "تست‌نشده"
        }
        val rtt = if (s.rttMs > 0) "${s.rttMs}ms" else "${s.pingMs}ms*"
        val speed = String.format("%.1f", s.speedMbps)

        holder.b.tvTitle.text = "${position + 1}. ${flagEmoji(s.countryCode)} ${s.countryName}"
        holder.b.tvSub.text = "سرعت $speed Mbps · تاخیر $rtt · ${s.proto.uppercase()} ${s.port}"
        holder.b.tvExtra.text = "$probeText · ${s.host} · ${s.sessions} کاربر"
        holder.b.root.setOnClickListener { onClick(s) }
    }
}
