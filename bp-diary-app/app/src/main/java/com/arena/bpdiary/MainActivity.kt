package com.arena.bpdiary

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.arena.bpdiary.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var uiReady = false

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val EXTRA_OPEN_NEW_RECORD = "open_new_record"
        const val EXTRA_MED_ID = "med_id"

        private const val STATE_TAB = "tab"
        private const val STATE_ABOUT_RETURN = "about_return"
    }

    private var aboutReturnTab = R.id.action_diary

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            b = ActivityMainBinding.inflate(layoutInflater)
            setContentView(b.root)

            repairAlarms()

            b.bottomNav.listener = BpBottomBar.Listener { itemId -> show(fragmentFor(itemId)) }
            b.bottomNav.setMenu(R.menu.bottom_nav)
            aboutReturnTab = savedInstanceState?.getInt(STATE_ABOUT_RETURN, R.id.action_diary)
                ?: R.id.action_diary
            val tab = savedInstanceState?.getInt(STATE_TAB, R.id.action_diary) ?: R.id.action_diary
            b.bottomNav.select(tab, notify = false)
            if (savedInstanceState == null) {
                val initialTab = intent?.getIntExtra(EXTRA_OPEN_TAB, 0) ?: 0
                if (initialTab != 0) {
                    b.bottomNav.select(initialTab, notify = false)
                    show(fragmentFor(initialTab))
                } else {
                    show(DiaryFragment())
                }
            }
            uiReady = true
            handleReminderIntent(intent)
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
        outState.putInt(STATE_ABOUT_RETURN, aboutReturnTab)
    }

    fun openRecordEdit(editRecordId: String? = null, isPair: Boolean = false) {
        if (::b.isInitialized) {
            aboutReturnTab = b.bottomNav.selectedId
            b.bottomNav.visibility = android.view.View.GONE
        }
        val f = RecordEditFragment.newInstance(editRecordId, isPair)
        show(f)
    }

    fun closeRecordEdit() {
        if (::b.isInitialized) {
            b.bottomNav.visibility = android.view.View.VISIBLE
            b.bottomNav.select(R.id.action_diary, notify = false)
        }
        show(DiaryFragment())
    }

    fun openAbout() {
        if (::b.isInitialized) aboutReturnTab = b.bottomNav.selectedId
        show(AboutFragment())
    }

    fun closeAbout() {
        val tab = aboutReturnTab
        if (::b.isInitialized) {
            b.bottomNav.visibility = android.view.View.VISIBLE
            b.bottomNav.select(tab, notify = false)
        }
        show(fragmentFor(tab))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val current = supportFragmentManager.findFragmentById(R.id.container)
        if (current is RecordEditFragment) {
            current.handleBack()
            return
        }
        if (current is AboutFragment) {
            closeAbout()
            return
        }
        super.onBackPressed()
    }

    private fun fragmentFor(itemId: Int): Fragment {
        return when (itemId) {
            R.id.action_chart -> ChartFragment()
            R.id.action_meds -> MedsFragment()
            R.id.action_ref -> RefFragment()
            R.id.action_reminders -> RemindersFragment()
            R.id.action_memo -> MemoFragment()
            else -> DiaryFragment()
        }
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
    
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
    }

    private fun handleReminderIntent(intent: android.content.Intent?) {
        if (intent == null || !::b.isInitialized) return
        val tab = intent.getIntExtra(EXTRA_OPEN_TAB, 0)
        if (tab != 0) {
            b.bottomNav.select(tab, notify = true)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_NEW_RECORD, false)) {
            val f = supportFragmentManager.findFragmentById(R.id.container)
            if (f is DiaryFragment) {
                f.view?.post { f.openNewRecordDialog() }
            }
        }
    }

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
