package dev.onyxbox.ferry

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** E-ink styling: pure black on white, hairline borders instead of shadows, big targets, no animation. */
class Ui(private val ctx: Context) {
    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun text(s: CharSequence, sp: Float, bold: Boolean = false) = TextView(ctx).apply {
        text = s; setTextColor(Color.BLACK); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun title(s: CharSequence) = text(s, 22f, true)
    fun heading(s: CharSequence) = text(s, 17f, true).apply { setPadding(0, dp(20), 0, dp(6)) }
    fun body(s: CharSequence) = text(s, 16f).apply { setPadding(0, dp(4), 0, dp(4)) }
    fun small(s: CharSequence) = text(s, 13.5f).apply { setTextColor(0xFF333333.toInt()) }

    fun border(fill: Int = Color.WHITE, width: Int = 2) = GradientDrawable().apply {
        setColor(fill); setStroke(dp(1).coerceAtLeast(width), Color.BLACK); cornerRadius = dp(6).toFloat()
    }

    fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        text = label; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(if (primary) Color.WHITE else Color.BLACK)
        background = border(if (primary) Color.BLACK else Color.WHITE)
        stateListAnimator = null
        minHeight = dp(44); minimumHeight = dp(44); setPadding(dp(16), 0, dp(16), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(6), dp(4), 0, dp(4)) }
        setOnClickListener { onClick() }
    }

    /** A full-width tappable card with a title and a subtitle line. */
    fun card(titleText: String, sub: String, onClick: (() -> Unit)?): Pair<LinearLayout, TextView> {
        val sv = small(sub)
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = border()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(4), 0, dp(4)) }
            addView(text(titleText, 18f, true))
            addView(sv)
            if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
        }
        return c to sv
    }

    fun row(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    fun rule() = View(ctx).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(12), 0, 0) }
    }
}
