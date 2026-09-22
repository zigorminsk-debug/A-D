package com.arena.bpdiary

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.arena.bpdiary.databinding.ItemRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordsAdapter(
    private var items: List<BpRecord>,
    private val onClick: (BpRecord) -> Unit,
    private val onLongClick: (BpRecord) -> Unit
) : RecyclerView.Adapter<RecordsAdapter.VH>() {

    private val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    class VH(val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ctx = h.b.root.context
        val r = items[position]
        val cat = BpClassifier.classify(ctx, r.sys, r.dia)
        val color = ContextCompat.getColor(ctx, cat.colorRes)

        h.b.tvDate.text = df.format(Date(r.time))
        h.b.tvCategory.text = cat.label
        h.b.tvCategory.setTextColor(color)
        h.b.strip.backgroundTintList = ColorStateList.valueOf(color)
        h.b.tvBp.text = ctx.getString(R.string.bp_value, r.sys, r.dia)
        if (r.pulse > 0) {
            h.b.tvPulse.visibility = android.view.View.VISIBLE
            h.b.tvPulse.text = ctx.getString(R.string.pulse_value, r.pulse)
        } else {
            h.b.tvPulse.visibility = android.view.View.GONE
        }
        if (r.note.isBlank()) {
            h.b.tvNote.visibility = android.view.View.GONE
        } else {
            h.b.tvNote.visibility = android.view.View.VISIBLE
            h.b.tvNote.text = r.note
        }
        h.b.root.setOnClickListener { onClick(r) }
        h.b.root.setOnLongClickListener { onLongClick(r); true }
    }

    fun submit(list: List<BpRecord>) {
        items = list
        notifyDataSetChanged()
    }
}
