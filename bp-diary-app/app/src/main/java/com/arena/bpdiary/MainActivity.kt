package com.arena.bpdiary

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.arena.bpdiary.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        askNotificationPermission()
        repairAlarms()

        if (savedInstanceState == null) {
            show(DiaryFragment())
        }

        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.action_diary -> show(DiaryFragment())
                R.id.action_chart -> show(ChartFragment())
                R.id.action_meds -> show(MedsFragment())
                R.id.action_ref -> show(RefFragment())
                R.id.action_reminders -> show(RemindersFragment())
                R.id.action_memo -> show(MemoFragment())
            }
            true
        }
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .commit()
    }

    /** Старые сборки могли повесить несколько приёмов на один id будильника. */
    private fun repairAlarms() {
        val obsolete = Store(this).repairAlarmIds()
        obsolete?.forEach { ReminderScheduler.cancel(this, it) }
        ReminderScheduler.rescheduleAll(this)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
