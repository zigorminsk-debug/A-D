package com.arena.bpdiary

import java.util.Calendar

/**
 * Что ещё не сделано сегодня. Утро 5–11 и вечер 17–22 — те же часы,
 * что в сводке и PDF, чтобы строка не спорила со средними.
 */
object TodayPlan {

    const val MORNING_FROM = 5
    const val MORNING_TO = 11
    const val EVENING_FROM = 17
    const val EVENING_TO = 22
    private const val DUE_EARLY_MS = 15L * 60L * 1000L

    enum class State { EARLY, DUE, MISSED, DONE }

    data class Period(val state: State, val latest: BpRecord?, val count: Int)

    data class Dose(
        val medId: Int,
        val name: String,
        val dose: String,
        val hour: Int,
        val minute: Int
    )

    data class Day(
        val morning: Period,
        val evening: Period,
        val dueDoses: List<Dose>,
        val nextDoses: List<Dose>,
        val allDosesDone: Boolean
    )

    fun build(
        records: List<BpRecord>,
        meds: List<Med>,
        logs: List<MedLog>,
        now: Long = System.currentTimeMillis()
    ): Day {
        val dayStart = startOfDay(now)
        val dayEnd = Calendar.getInstance().apply {
            timeInMillis = dayStart
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val today = records.filter { it.time in dayStart until dayEnd }
        val hour = hourOf(now)
        val morningRecs = today.filter { hourOf(it.time) in MORNING_FROM..MORNING_TO }
        val eveningRecs = today.filter { hourOf(it.time) in EVENING_FROM..EVENING_TO }

        val slots = meds.filter { it.enabled }.flatMap { med ->
            med.times.filter { it.enabled }.map { t ->
                Slot(med, t.hour, t.minute, at(dayStart, t.hour, t.minute))
            }
        }.sortedWith(compareBy({ it.hour }, { it.minute }, { it.med.name }))

        val done = slots.map { slotDone(it, slots, logs, dayStart, dayEnd) }
        val due = ArrayList<Dose>()
        val next = ArrayList<Dose>()
        var nextAt = Long.MAX_VALUE
        slots.forEachIndexed { i, slot ->
            if (done[i]) return@forEachIndexed
            val item = slot.toDose()
            if (now + DUE_EARLY_MS >= slot.at) {
                due.add(item)
            } else if (slot.at < nextAt) {
                nextAt = slot.at
                next.clear()
                next.add(item)
            } else if (slot.at == nextAt) {
                next.add(item)
            }
        }

        return Day(
            morning = period(morningRecs, hour, MORNING_FROM, MORNING_TO),
            evening = period(eveningRecs, hour, EVENING_FROM, EVENING_TO),
            dueDoses = due,
            nextDoses = next,
            allDosesDone = slots.isNotEmpty() && done.all { it }
        )
    }

    private fun period(recs: List<BpRecord>, hourNow: Int, from: Int, to: Int): Period {
        if (recs.isNotEmpty()) {
            return Period(State.DONE, recs.maxByOrNull { it.time }, recs.size)
        }
        val state = when {
            hourNow < from -> State.EARLY
            hourNow <= to -> State.DUE
            else -> State.MISSED
        }
        return Period(state, null, 0)
    }

    private data class Slot(val med: Med, val hour: Int, val minute: Int, val at: Long) {
        fun toDose() = Dose(med.id, med.name, med.dose, hour, minute)
    }

    private fun slotDone(
        slot: Slot,
        slots: List<Slot>,
        logs: List<MedLog>,
        dayStart: Long,
        dayEnd: Long
    ): Boolean {
        val mine = logs.filter {
            it.medId == slot.med.id && it.time in dayStart until dayEnd &&
                (it.status == "taken" || it.status == "skipped")
        }
        if (mine.any { it.slotHour == slot.hour && it.slotMinute == slot.minute }) return true
        val sameMed = slots.filter { it.med.id == slot.med.id }.sortedBy { it.at }
        val index = sameMed.indexOfFirst { it.hour == slot.hour && it.minute == slot.minute && it.at == slot.at }
        if (index < 0) return false
        val start = if (index == 0) dayStart else (sameMed[index - 1].at + slot.at) / 2
        val end = if (index == sameMed.lastIndex) dayEnd else (slot.at + sameMed[index + 1].at) / 2
        return mine.any { it.slotHour < 0 && it.time in start until end }
    }

    private fun startOfDay(now: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun at(dayStart: Long, hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = dayStart
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun hourOf(time: Long): Int {
        return Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.HOUR_OF_DAY)
    }
}
