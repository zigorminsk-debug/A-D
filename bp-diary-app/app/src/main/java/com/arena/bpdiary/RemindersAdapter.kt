package com.arena.bpdiary

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.arena.bpdiary.databinding.ItemReminderBinding

class RemindersAdapter(
    private var items: List<Reminder>,
    private val onClickToggle: (Reminder, Boolean) -> Unit,
    private val onLongClick: (Reminder) -> Unit
) : RecyclerView.Adapter<RemindersAdapter.VH>() {

    class VH(val b: ItemReminderBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemReminderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        h.b.tvTime.text = String.format("%02d:%02d", r.hour, r.minute)
        h.b.tvLabel.text = r.label
        h.b.swOn.setOnCheckedChangeListener(null)
        h.b.swOn.isChecked = r.enabled
        h.b.swOn.setOnCheckedChangeListener { _, checked ->
            if (checked != r.enabled) onClickToggle(r, checked)
        }
        h.b.root.setOnLongClickListener { onLongClick(r); true }
    }

    fun submit(list: List<Reminder>) {
        items = list
        notifyDataSetChanged()
    }
}
