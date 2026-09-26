package com.arena.bpdiary

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment

/**
 * Общие правила для окон с полями ввода: клавиатура не должна закрывать кнопки.
 * Раньше в «Замер 1 из 2» клавиатура накрывала «Дальше» и «Сохранить среднее»,
 * и кнопку приходилось доставать, вручную убирая клавиатуру.
 */
object Dialogs {

    /** Окно сжимается под клавиатуру — тогда кнопки остаются над ней, а не под ней. */
    fun attachToIme(dialog: Dialog?) {
        val window = dialog?.window ?: return
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /** Скрыть клавиатуру: перед закрытием окна и при прокрутке формы. */
    fun hideKeyboard(ctx: Context?, anchor: View?) {
        val context = ctx
            ?: anchor?.context
            ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(anchor?.windowToken, 0)
    }

    /** Вариант для фрагмента: якорем служит его корневое представление. */
    fun hideKeyboard(fragment: Fragment?) {
        hideKeyboard(fragment?.context, fragment?.view)
    }
}
