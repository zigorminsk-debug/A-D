package com.arena.bpdiary

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.arena.bpdiary.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var uiReady = false

    companion object {
        private const val STATE_TAB = "tab"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            b = ActivityMainBinding.inflate(layoutInflater)
            setContentView(b.root)

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
            uiReady = true
        } catch (e: Exception) {
            showStartupError(e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!uiReady) return
        try {
            AppUpdater.onHostResume(this)
        } catch (_: Exception) {
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::b.isInitialized) outState.putInt(STATE_TAB, b.bottomNav.selectedId)
    }

    private fun show(f: Fragment) {
        try {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, f)
                .commitNow()
        } catch (e: Exception) {
            showStartupError(e)
        }
    }

    private fun showStartupError(e: Exception) {
        val text = android.widget.TextView(this).apply {
            setPadding(32, 48, 32, 48)
            textSize = 14f
            setTextColor(0xFF1A1A1A.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
            text = "Не удалось открыть дневник.\n\n${e.javaClass.simpleName}: ${e.message}"
        }
        setContentView(text)
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

}
