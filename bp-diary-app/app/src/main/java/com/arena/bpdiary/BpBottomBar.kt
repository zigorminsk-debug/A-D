package com.arena.bpdiary

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat

/**
 * Нижняя навигация на 6 разделов.
 * Material BottomNavigationView бросает IllegalArgumentException, если пунктов больше пяти,
 * и из‑за этого приложение падало сразу после запуска.
 */
class BpBottomBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    fun interface Listener {
        fun onSelected(itemId: Int)
    }

    var listener: Listener? = null
    var selectedId: Int = View.NO_ID
        private set

    private val cells = ArrayList<Cell>()

    init {
        orientation = VERTICAL
    }

    fun setMenu(menuRes: Int) {
        removeAllViews()
        cells.clear()
        val menu = MenuBuilder(context)
        SupportMenuInflater(context).inflate(menuRes, menu)
        if (menu.size() == 0) return

        val columns = if (menu.size() <= 3) menu.size() else 3
        var index = 0
        while (index < menu.size()) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
            val count = minOf(columns, menu.size() - index)
            repeat(count) {
                val item = menu.getItem(index++)
                val cell = makeCell(item.itemId, item.title, item.icon)
                row.addView(cell.root, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                cells += cell
            }
            addView(row)
        }
        if (selectedId == View.NO_ID) {
            select(menu.getItem(0).itemId, notify = false)
        } else {
            applySelection()
        }
    }

    fun select(itemId: Int, notify: Boolean = true) {
        selectedId = itemId
        applySelection()
        if (notify) listener?.onSelected(itemId)
    }

    private fun makeCell(id: Int, title: CharSequence?, icon: Drawable?): Cell {
        val d = resources.displayMetrics.density
        val iconView = ImageView(context).apply {
            val size = (22 * d).toInt()
            layoutParams = LayoutParams(size, size)
            setImageDrawable(icon?.constantState?.newDrawable()?.mutate() ?: icon?.mutate())
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(context).apply {
            text = title
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * d).toInt()
            }
        }
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            val h = (4 * d).toInt()
            val v = (8 * d).toInt()
            setPadding(h, v, h, v)
            minimumHeight = (48 * d).toInt()
            isClickable = true
            isFocusable = true
            contentDescription = title
            val out = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                foreground = ContextCompat.getDrawable(context, out.resourceId)
            }
            setOnClickListener { select(id) }
        }
        root.addView(iconView)
        root.addView(label)
        return Cell(id, root, iconView, label)
    }

    private fun applySelection() {
        val primary = ContextCompat.getColor(context, R.color.primary)
        val muted = ContextCompat.getColor(context, R.color.gray)
        val d = resources.displayMetrics.density
        for (cell in cells) {
            val on = cell.id == selectedId
            val color = if (on) primary else muted
            ImageViewCompat.setImageTintList(cell.icon, ColorStateList.valueOf(color))
            cell.label.setTextColor(color)
            cell.label.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            cell.root.background = GradientDrawable().apply {
                cornerRadius = 12f * d
                setColor(if (on) ColorUtils.setAlphaComponent(primary, 40) else 0)
            }
        }
    }

    private class Cell(
        val id: Int,
        val root: LinearLayout,
        val icon: ImageView,
        val label: TextView
    )
}
