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
    val enabled: Boolean = true,
    /** Сколько приёмов в день задал пациент. 0 — посчитать по числу включённых времён. */
    val perDay: Int = 0
) {
    /** Число приёмов в день для показа: у старых записей считается по временам. */
    val dosesPerDay: Int get() = if (perDay > 0) perDay else times.count { it.enabled }
}

/** Отметка о приёме: status = "taken" | "skipped". */
data class MedLog(
    val id: Long,
    val medId: Int,
    val medName: String,
    val dose: String,
    val status: String,
    val time: Long,
    /** Какой приём отметили. −1 — старая отметка без слота, её сопоставляем по времени. */
    val slotHour: Int = -1,
    val slotMinute: Int = -1
)

class Store(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("bp_store", Context.MODE_PRIVATE)

    private fun nextId(): Int = allocateId()

    /** null — строка в хранилище битая, её нельзя затирать пустым списком. */
    private fun readArray(key: String): JSONArray? {
        val raw = sp.getString(key, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Битую строку хранилища сохраняем в «…_broken» и продолжаем с пустого списка:
     * иначе запись в этот раздел становится невозможной навсегда, и новые данные молча теряются.
     */
    private fun ensureWritable(key: String): Boolean {
        if (readArray(key) != null) return true
        val raw = sp.getString(key, null)
        if (raw != null) sp.edit().putString(key + "_broken", raw).apply()
        sp.edit().putString(key, "[]").commit()
        return true
    }

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
        val arr = readArray("records") ?: return ArrayList()
        val list = ArrayList<BpRecord>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                if (!o.has("time") || !o.has("sys") || !o.has("dia")) continue
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
            } catch (_: Exception) {
            }
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
        if (readArray("records") == null) return
        val list = records()
        list.add(BpRecord(UUID.randomUUID().toString(), time, sys, dia, pulse, note))
        saveRecords(list)
    }

    fun updateRecord(r: BpRecord) {
        if (readArray("records") == null) return
        saveRecords(records().map { if (it.id == r.id) r else it })
    }

    fun deleteRecord(id: String) {
        if (readArray("records") == null) return
        saveRecords(records().filter { it.id != id })
    }

    fun patientName(): String = sp.getString("patient_name", "").orEmpty().trim()

    fun patientAge(): Int = sp.getInt("patient_age", 0)

    fun savePatient(name: String, age: Int) {
        sp.edit().putString("patient_name", name.trim()).putInt("patient_age", age).apply()
    }

    // ---------- Напоминания об измерении ----------

    fun reminders(): MutableList<Reminder> {
        val arr = readArray("reminders") ?: return ArrayList()
        val list = ArrayList<Reminder>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                list.add(
                    Reminder(
                        id = o.optInt("id", 0),
                        hour = o.optInt("hour", 8),
                        minute = o.optInt("minute", 0),
                        enabled = o.optBoolean("enabled", true),
                        label = o.optString("label", "")
                    )
                )
            } catch (_: Exception) {
            }
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
        if (readArray("reminders") == null) {
            return Reminder(0, hour, minute, false, label)
        }
        val list = reminders()
        val r = Reminder(nextId(), hour, minute, true, label)
        list.add(r)
        saveReminders(list)
        return r
    }

    fun updateReminder(r: Reminder) {
        if (readArray("reminders") == null) return
        saveReminders(reminders().map { if (it.id == r.id) r else it })
    }

    fun deleteReminder(id: Int) {
        if (readArray("reminders") == null) return
        saveReminders(reminders().filter { it.id != id })
    }

    // ---------- Препараты ----------

    fun meds(): MutableList<Med> {
        val arr = readArray("meds") ?: return ArrayList()
        val list = ArrayList<Med>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val times = ArrayList<MedTime>()
                val tArr = o.optJSONArray("times") ?: JSONArray()
                for (j in 0 until tArr.length()) {
                    val t = tArr.optJSONObject(j) ?: continue
                    times.add(
                        MedTime(
                            id = t.optInt("id", 0),
                            hour = t.optInt("hour", 8),
                            minute = t.optInt("minute", 0),
                            enabled = t.optBoolean("enabled", true)
                        )
                    )
                }
                list.add(
                    Med(
                        id = o.optInt("id", 0),
                        name = o.optString("name", ""),
                        dose = o.optString("dose", ""),
                        times = times.sortedWith(compareBy({ it.hour }, { it.minute })),
                        enabled = o.optBoolean("enabled", true),
                        perDay = o.optInt("perDay", 0)
                    )
                )
            } catch (_: Exception) {
            }
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
                    .put("perDay", m.perDay)
                    .put("times", tArr)
            )
        }
        sp.edit().putString("meds", arr.toString()).apply()
    }

    fun addMed(
        name: String,
        dose: String,
        times: List<Pair<Int, Int>>,
        perDay: Int = 0
    ): Med {
        if (!ensureWritable("meds")) {
            return Med(0, name, dose, emptyList(), false)
        }
        val med = Med(
            id = nextId(),
            name = name,
            dose = dose,
            times = times.map { MedTime(nextId(), it.first, it.second) },
            perDay = perDay
        )
        val list = meds()
        list.add(med)
        saveMeds(list)
        return med
    }

    fun updateMed(med: Med) {
        if (readArray("meds") == null) ensureWritable("meds")
        saveMeds(meds().map { if (it.id == med.id) med else it })
    }

    fun deleteMed(id: Int) {
        if (readArray("meds") == null) ensureWritable("meds")
        saveMeds(meds().filter { it.id != id })
    }

    // ---------- История приёмов ----------

    fun logs(): MutableList<MedLog> {
        val arr = readArray("medlogs") ?: return ArrayList()
        val list = ArrayList<MedLog>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                if (!o.has("time")) continue
                list.add(
                    MedLog(
                        id = o.optLong("id", o.getLong("time")),
                        medId = o.optInt("medId", 0),
                        medName = o.optString("medName", ""),
                        dose = o.optString("dose", ""),
                        status = o.optString("status", "taken"),
                        time = o.getLong("time"),
                        slotHour = o.optInt("slotHour", -1),
                        slotMinute = o.optInt("slotMinute", -1)
                    )
                )
            } catch (_: Exception) {
            }
        }
        list.sortByDescending { it.time }
        return list
    }

    fun addLog(
        medId: Int,
        medName: String,
        dose: String,
        status: String,
        slotHour: Int = -1,
        slotMinute: Int = -1
    ) {
        if (readArray("medlogs") == null) return
        val list = logs()
        list.add(
            0,
            MedLog(
                System.currentTimeMillis(),
                medId,
                medName,
                dose,
                status,
                System.currentTimeMillis(),
                slotHour,
                slotMinute
            )
        )
        // храним не более 200 последних отметок
        saveLogs(list.take(200))
    }

    fun deleteLog(id: Long) {
        if (readArray("medlogs") == null) return
        saveLogs(logs().filter { it.id != id })
    }

    private fun saveLogs(list: List<MedLog>) {
        val arr = JSONArray()
        list.forEach { l ->
            arr.put(logJson(l))
        }
        sp.edit().putString("medlogs", arr.toString()).apply()
    }

    private fun logJson(l: MedLog): JSONObject {
        return JSONObject()
            .put("id", l.id)
            .put("medId", l.medId)
            .put("medName", l.medName)
            .put("dose", l.dose)
            .put("status", l.status)
            .put("time", l.time)
            .put("slotHour", l.slotHour)
            .put("slotMinute", l.slotMinute)
    }

    // ---------- Резервная копия (экспорт / импорт) ----------

    /** Полная копия всех данных одним JSON-файлом. */
    fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "bp-diary")
        root.put("schema", 1)
        root.put("exported", System.currentTimeMillis())
        root.put("patientName", patientName())
        root.put("patientAge", patientAge())

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
                    .put("perDay", m.perDay)
                    .put("times", tArr)
            )
        }
        root.put("meds", meds)

        val logs = JSONArray()
        logs().forEach { l -> logs.put(logJson(l)) }
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
            if (root.has("patientName") || root.has("patientAge")) {
                e.putString("patient_name", root.optString("patientName").trim())
                e.putInt("patient_age", root.optInt("patientAge", 0))
            }
            e.commit()
            repairAlarmIds()
            true
        } catch (ex: Exception) {
            false
        }
    }
}
