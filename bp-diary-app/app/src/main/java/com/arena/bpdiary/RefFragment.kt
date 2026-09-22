package com.arena.bpdiary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arena.bpdiary.databinding.DialogDrugBinding
import com.arena.bpdiary.databinding.FragmentRefBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.arena.bpdiary.databinding.ItemDrugBinding

class RefFragment : Fragment() {

    private var _b: FragmentRefBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: DrugsAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentRefBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DrugsAdapter(items = emptyList(), onClick = { showDrug(it) })
        b.drugs.layoutManager = LinearLayoutManager(requireContext())
        b.drugs.adapter = adapter
        b.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c2: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilter(s?.toString() ?: "")
            }
        })
        applyFilter("")
    }

    private fun applyFilter(q: String) {
        val list = DrugRepository.search(q)
        adapter.submit(list)
        b.tvCount.text = getString(R.string.ref_count_fmt, list.size)
        b.emptyRef.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDrug(d: DrugRef) {
        val v = DialogDrugBinding.inflate(layoutInflater)
        v.tvGroup.text = d.group
        v.tvAction.text = d.action
        v.tvInd.text = d.indications
        v.tvGen.text = d.generics
        v.tvContra.text = d.contras
        v.tvSide.text = d.sideEffects
        if (d.note.isBlank()) {
            v.noteCard.visibility = View.GONE
        } else {
            v.noteCard.visibility = View.VISIBLE
            v.noteCard.text = "💡 ${d.note}"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(d.name)
            .setView(v.root)
            .setPositiveButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

class DrugsAdapter(
    private var items: List<DrugRef>,
    private val onClick: (DrugRef) -> Unit
) : RecyclerView.Adapter<DrugsAdapter.VH>() {

    class VH(val b: ItemDrugBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDrugBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = items[position]
        h.b.tvName.text = d.name
        h.b.tvGroup.text = d.group
        h.b.root.setOnClickListener { onClick(d) }
    }

    fun submit(list: List<DrugRef>) {
        items = list
        notifyDataSetChanged()
    }
}
