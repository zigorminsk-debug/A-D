package com.arena.bpdiary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.arena.bpdiary.databinding.FragmentChartBinding

class ChartFragment : Fragment() {

    private var _b: FragmentChartBinding? = null
    private val b get() = _b!!
    private val store by lazy { Store(requireContext()) }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentChartBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.chipGroup.setOnCheckedStateChangeListener { _, _ -> refresh() }
        b.btnChartPdf.setOnClickListener { exportPdf() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun periodDays(): Int = when (b.chipGroup.checkedChipId) {
        R.id.chip7 -> 7
        R.id.chip30 -> 30
        R.id.chip90 -> 90
        else -> Int.MAX_VALUE
    }

    private fun periodLabel(): String = when (b.chipGroup.checkedChipId) {
        R.id.chip7 -> getString(R.string.period_7)
        R.id.chip30 -> getString(R.string.period_30)
        R.id.chip90 -> getString(R.string.period_90)
        else -> getString(R.string.period_all)
    }

    /** Тот же набор, что нарисован на графике (новые сверху, как в хранилище). */
    private fun visibleRecords(): List<BpRecord> {
        val days = periodDays()
        val cutoff = if (days == Int.MAX_VALUE) 0L else System.currentTimeMillis() - days * 86_400_000L
        return store.records().filter { it.time >= cutoff }
    }

    private fun refresh() {
        val asc = visibleRecords().sortedBy { it.time }

        b.chart.visibility = if (asc.isEmpty()) View.GONE else View.VISIBLE
        b.legend.visibility = b.chart.visibility
        b.btnChartPdf.visibility = b.chart.visibility
        b.emptyChart.visibility = if (asc.isEmpty()) View.VISIBLE else View.GONE

        if (asc.isNotEmpty()) {
            b.chart.records = asc
            val avg = { f: (BpRecord) -> Int -> asc.map(f).average().toInt() }
            val pulse = BpClassifier.pulseAvg(asc)
            b.tvChartAvg.text = if (pulse == null) {
                getString(R.string.chart_avg_fmt_no_pulse, asc.size, avg { it.sys }, avg { it.dia })
            } else {
                getString(R.string.chart_avg_fmt, asc.size, avg { it.sys }, avg { it.dia }, pulse)
            }
        } else {
            b.tvChartAvg.text = ""
        }
    }

    private fun exportPdf() {
        val days = periodDays()
        val from = if (days == Int.MAX_VALUE) 0L else System.currentTimeMillis() - days * 86_400_000L
        PdfShare.start(this, visibleRecords(), getString(R.string.pdf_period_fmt, periodLabel()), from)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
