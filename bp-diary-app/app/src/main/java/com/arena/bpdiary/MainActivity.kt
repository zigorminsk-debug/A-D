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

    companion object {
        private const val STATE_TAB = "tab"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        askNotificationPermission()
        repairAlarms()

        b.bottomNav.listener = BpBottomBar.Listener { itemId ->
            when (itemId) {
                R.id.action_diary -> show(DiaryFragment())
                R.id.action_chart -> show(ChartFragment())
                R.id.action_meds -> show(MedsFragment())
                R.id.action_ref -> show(RefFragment())
                R.id.action_reminders -> show(RemindersFragment())
                R.id.action_memo -> show(MemoFragment())
            }
        }
        b.bottomNav.setMenu(R.menu.bottom_nav)
        val tab = savedInstanceState?.getInt(STATE_TAB, R.id.action_diary) ?: R.id.action_diary
        b.bottomNav.select(tab, notify = false)
        if (savedInstanceState == null) {
            show(DiaryFragment())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::b.isInitialized) outState.putInt(STATE_TAB, b.bottomNav.selectedId)
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .commit()
    }

    /** Старые сборки могли повесить несколько приёмов на один id будильника. */
    private fun repairAlarms() {
        try {
            val obsolete = Store(this).repairAlarmIds()
            obsolete?.forEach { ReminderScheduler.cancel(this, it) }
            ReminderScheduler.rescheduleAll(this)
        } catch (_: Exception) {
            // отказ будильника не должен закрывать дневник на старте
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
