package com.arena.bpdiary

/**
 * Расчёт времени приёма по числу приёмов в день.
 *
 * Правила: приёмы распределяются равномерно от выбранного первого приёма,
 * интервал между ними — не больше 12 часов (два приёма в день = утро и вечер),
 * последний приём — не позже 22:00. Время округляется до 5 минут.
 */
object MedSchedule {

    /** Последний приём — не позже 22:00. */
    const val LAST_HOUR = 22

    /** Сколько приёмов в день можно задать. */
    const val MAX_PER_DAY = 6

    /** Первый приём по умолчанию — 08:00. */
    const val FIRST_HOUR = 8

    /** Раньше 5:00 и позже 21:00 первый приём задавать нельзя. */
    const val MIN_FIRST_HOUR = 5
    const val MAX_FIRST_HOUR = 21

    private const val MAX_GAP_MIN = 12 * 60

    /** Время приёма в минутах от полуночи, округлённое до 5 минут вверх. */
    private fun round5(minutes: Int): Int = ((minutes + 4) / 5) * 5

    /**
     * Времена приёма: список пар «час — минута», отсортированный по возрастанию.
     * Для одного приёма возвращает только первый приём.
     */
    fun slots(perDay: Int, firstHour: Int = FIRST_HOUR, firstMinute: Int = 0): List<Pair<Int, Int>> {
        val count = perDay.coerceIn(1, MAX_PER_DAY)
        val start = firstHour.coerceIn(MIN_FIRST_HOUR, MAX_FIRST_HOUR) * 60 +
            firstMinute.coerceIn(0, 59)
        val end = LAST_HOUR * 60
        val result = ArrayList<Pair<Int, Int>>()
        if (count == 1) {
            result += start / 60 to start % 60
            return result
        }
        val step = minOf((end - start) / (count - 1), MAX_GAP_MIN).coerceAtLeast(5)
        for (i in 0 until count) {
            val minutes = round5(start + step * i).coerceAtMost(end)
            val slot = minutes / 60 to minutes % 60
            if (result.isEmpty() || result.last() != slot) result += slot
        }
        return result
    }

    /** «08:00, 15:00, 22:00» — для подписи в окне препарата, карточке и PDF. */
    fun describe(slots: List<Pair<Int, Int>>): String =
        slots.joinToString(", ") { String.format("%02d:%02d", it.first, it.second) }
}
