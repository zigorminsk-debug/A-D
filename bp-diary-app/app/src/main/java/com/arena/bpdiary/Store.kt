package com.arena.bpdiary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ================= Измерения =================

data class BpRecord(
    val id: String,
    val time: Long,
    val sys: Int,
    val dia: Int,
    val pulse: Int,
    val note: String
)

// ================= Напоминания об измерении =================

data class Reminder(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val label: String
)

// ================= Препараты =================

/** Время приёма: собственный id = id будильника в AlarmManager. */
data class MedTime(val id: Int, val hour: Int, val minute: Int, val enabled: Boolean = true)

data class Med(
    val id: Int,
    val name: String,
    val dose: String,
    val times: List<MedTime>,
    val enabled: Boolean = true
)

/** Отметка о приёме: status = "taken" | "skipped". */
data class MedLog(
    val id: Long,
    val medId: Int,
    val medName: String,
    val dose: String,
    val status: String,
    val time: Long
)

class Store(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("bp_store", Context.MODE_PRIVATE)

    private fun nextId(): Int = allocateId()

    /**
     * Уникальный id будильника. Старая формула (currentTimeMillis shr 2) выдавала
     * один и тот же id всем слотам, созданным в одном вызове, и AlarmManager
     * оставлял только последний приём.
     */
    fun allocateId(): Int {
        val used = HashSet<Int>()
        reminders().forEach { used.add(it.id) }
        meds().forEach { m ->
            used.add(m.id)
            m.times.forEach { used.add(it.id) }
        }
        var seq = sp.getInt("id_seq", 1000)
        var guard = 0
        do {
            seq = if (seq >= 0x7FFFFFFE) 1 else seq + 1
            guard++
        } while ((seq == 0 || seq in used) && guard < 200_000)
        sp.edit().putInt("id_seq", seq).commit()
        return seq
    }

    /**
     * Чинит уже сохранённые одинаковые id слотов. Возвращает id, чьи будильники
     * надо снять, либо null, если дублей нет.
     */
    fun repairAlarmIds(): List<Int>? {
        val seen = HashSet<Int>()
        val obsolete = ArrayList<Int>()
        var changed = false
        var seq = sp.getInt("id_seq", 1000)
        fun fresh(): Int {
            do {
                seq = if (seq >= 0x7FFFFFFE) 1 else seq + 1
            } while (seq == 0 || seq in seen)
            seen.add(seq)
            return seq
        }
        val newRems = reminders().map { r ->
            if (r.id == 0 || !seen.add(r.id)) {
                changed = true
                if (r.id != 0) obsolete.add(r.id)
                r.copy(id = fresh())
            } else r
        }
        val newMeds = meds().map { m ->
            var mid = m.id
            if (mid == 0 || !seen.add(mid)) {
                changed = true
                if (mid != 0) obsolete.add(mid)
                mid = fresh()
            }
            val times = m.times.map { t ->
                if (t.id == 0 || !seen.add(t.id)) {
                    changed = true
                    if (t.id != 0) obsolete.add(t.id)
                    t.copy(id = fresh())
                } else t
            }
            if (mid != m.id || times != m.times) m.copy(id = mid, times = times) else m
        }
        if (!changed) return null
        sp.edit().putInt("id_seq", seq).commit()
        saveReminders(newRems)
        saveMeds(newMeds)
        return obsolete.distinct()
    }

    // ---------- Измерения ----------

    fun records(): MutableList<BpRecord> {
        val arr = JSONArray(sp.getString("records", "[]"))
        val list = ArrayList<BpRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                BpRecord(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    time = o.getLong("time"),
                    sys = o.getInt("sys"),
                    dia = o.getInt("dia"),
                    pulse = o.optInt("pulse", 0),
                    note = o.optString("note", "")
                )
            )
        }
        list.sortByDescending { it.time }
        return list
    }

    private fun saveRecords(list: List<BpRecord>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("time", r.time)
                    .put("sys", r.sys)
                    .put("dia", r.dia)
                    .put("pulse", r.pulse)
                    .put("note", r.note)
            )
        }
        sp.edit().putString("records", arr.toString()).apply()
    }

    fun addRecord(sys: Int, dia: Int, pulse: Int, note: String, time: Long = System.currentTimeMillis()) {
        val list = records()
        list.add(BpRecord(UUID.randomUUID().toString(), time, sys, dia, pulse, note))
        saveRecords(list)
    }

    fun updateRecord(r: BpRecord) {
        saveRecords(records().map { if (it.id == r.id) r else it })
    }

    fun deleteRecord(id: String) {
        saveRecords(records().filter { it.id != id })
    }

    // ---------- Напоминания об измерении ----------

    fun reminders(): MutableList<Reminder> {
        val arr = JSONArray(sp.getString("reminders", "[]"))
        val list = ArrayList<Reminder>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Reminder(
                    id = o.getInt("id"),
                    hour = o.getInt("hour"),
                    minute = o.getInt("minute"),
                    enabled = o.optBoolean("enabled", true),
                    label = o.optString("label", "")
                )
            )
        }
        list.sortWith(compareBy({ it.hour }, { it.minute }))
        return list
    }

    private fun saveReminders(list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("hour", r.hour)
                    .put("minute", r.minute)
                    .put("enabled", r.enabled)
                    .put("label", r.label)
            )
        }
        sp.edit().putString("reminders", arr.toString()).apply()
    }

    fun addReminder(hour: Int, minute: Int, label: String): Reminder {
        val list = reminders()
        val r = Reminder(nextId(), hour, minute, true, label)
        list.add(r)
        saveReminders(list)
        return r
    }

    fun updateReminder(r: Reminder) {
        saveReminders(reminders().map { if (it.id == r.id) r else it })
    }

    fun deleteReminder(id: Int) {
        saveReminders(reminders().filter { it.id != id })
    }

    // ---------- Препараты ----------

    fun meds(): MutableList<Med> {
        val arr = JSONArray(sp.getString("meds", "[]"))
        val list = ArrayList<Med>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val times = ArrayList<MedTime>()
            val tArr = o.getJSONArray("times")
            for (j in 0 until tArr.length()) {
                val t = tArr.getJSONObject(j)
                times.add(
                    MedTime(
                        id = t.getInt("id"),
                        hour = t.getInt("hour"),
                        minute = t.getInt("minute"),
                        enabled = t.optBoolean("enabled", true)
                    )
                )
            }
            list.add(
                Med(
                    id = o.getInt("id"),
                    name = o.getString("name"),
                    dose = o.optString("dose", ""),
                    times = times.sortedWith(compareBy({ it.hour }, { it.minute })),
                    enabled = o.optBoolean("enabled", true)
                )
            )
        }
        list.sortBy { it.name }
        return list
    }

    private fun saveMeds(list: List<Med>) {
        val arr = JSONArray()
        list.forEach { m ->
            val tArr = JSONArray()
            m.times.forEach { t ->
                tArr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("hour", t.hour)
                        .put("minute", t.minute)
                        .put("enabled", t.enabled)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("dose", m.dose)
                    .put("enabled", m.enabled)
                    .put("times", tArr)
            )
        }
        sp.edit().putString("meds", arr.toString()).apply()
    }

    fun addMed(name: String, dose: String, times: List<Pair<Int, Int>>): Med {
        val med = Med(
            id = nextId(),
            name = name,
            dose = dose,
            times = times.map { MedTime(nextId(), it.first, it.second) }
        )
        val list = meds()
        list.add(med)
        saveMeds(list)
        return med
    }

    fun updateMed(med: Med) {
        saveMeds(meds().map { if (it.id == med.id) med else it })
    }

    fun deleteMed(id: Int) {
        saveMeds(meds().filter { it.id != id })
    }

    // ---------- История приёмов ----------

    fun logs(): MutableList<MedLog> {
        val arr = JSONArray(sp.getString("medlogs", "[]"))
        val list = ArrayList<MedLog>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                MedLog(
                    id = o.getLong("id"),
                    medId = o.optInt("medId", 0),
                    medName = o.optString("medName", ""),
                    dose = o.optString("dose", ""),
                    status = o.optString("status", "taken"),
                    time = o.getLong("time")
                )
            )
        }
        list.sortByDescending { it.time }
        return list
    }

    fun addLog(medId: Int, medName: String, dose: String, status: String) {
        val list = logs()
        list.add(0, MedLog(System.currentTimeMillis(), medId, medName, dose, status, System.currentTimeMillis()))
        // храним не более 200 последних отметок
        saveLogs(list.take(200))
    }

    fun deleteLog(id: Long) {
        saveLogs(logs().filter { it.id != id })
    }

    private fun saveLogs(list: List<MedLog>) {
        val arr = JSONArray()
        list.forEach { l ->
            arr.put(
                JSONObject()
                    .put("id", l.id)
                    .put("medId", l.medId)
                    .put("medName", l.medName)
                    .put("dose", l.dose)
                    .put("status", l.status)
                    .put("time", l.time)
            )
        }
        sp.edit().putString("medlogs", arr.toString()).apply()
    }

    // ---------- Резервная копия (экспорт / импорт) ----------

    /** Полная копия всех данных одним JSON-файлом. */
    fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "bp-diary")
        root.put("schema", 1)
        root.put("exported", System.currentTimeMillis())

        val recs = JSONArray()
        records().forEach { r ->
            recs.put(
                JSONObject()
                    .put("id", r.id)
                    .put("time", r.time)
                    .put("sys", r.sys)
                    .put("dia", r.dia)
                    .put("pulse", r.pulse)
                    .put("note", r.note)
            )
        }
        root.put("records", recs)

        val rems = JSONArray()
        reminders().forEach { r ->
            rems.put(
                JSONObject()
                    .put("id", r.id)
                    .put("hour", r.hour)
                    .put("minute", r.minute)
                    .put("enabled", r.enabled)
                    .put("label", r.label)
            )
        }
        root.put("reminders", rems)

        val meds = JSONArray()
        meds().forEach { m ->
            val tArr = JSONArray()
            m.times.forEach { t ->
                tArr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("hour", t.hour)
                        .put("minute", t.minute)
                        .put("enabled", t.enabled)
                )
            }
            meds.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("dose", m.dose)
                    .put("enabled", m.enabled)
                    .put("times", tArr)
            )
        }
        root.put("meds", meds)

        val logs = JSONArray()
        logs().forEach { l ->
            logs.put(
                JSONObject()
                    .put("id", l.id)
                    .put("medId", l.medId)
                    .put("medName", l.medName)
                    .put("dose", l.dose)
                    .put("status", l.status)
                    .put("time", l.time)
            )
        }
        root.put("logs", logs)
        return root.toString(2)
    }

    /** Восстановление из копии: заменяет ВСЕ данные. true — успех. */
    fun importJson(text: String): Boolean {
        return try {
            val root = JSONObject(text)
            if (root.optString("app") != "bp-diary") return false
            val records = root.optJSONArray("records") ?: JSONArray()
            for (i in 0 until records.length()) {
                val o = records.optJSONObject(i) ?: return false
                if (!o.has("time") || !o.has("sys") || !o.has("dia")) return false
                // старые копии не содержали id — без него каждое чтение порождало новый UUID
                if (o.optString("id").isBlank()) o.put("id", UUID.randomUUID().toString())
            }
            val e = sp.edit()
            e.putString("records", records.toString())
            e.putString("reminders", root.optJSONArray("reminders")?.toString() ?: "[]")
            e.putString("meds", root.optJSONArray("meds")?.toString() ?: "[]")
            e.putString("medlogs", root.optJSONArray("logs")?.toString() ?: "[]")
            e.commit()
            repairAlarmIds()
            true
        } catch (ex: Exception) {
            false
        }
    }
}
