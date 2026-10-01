package dev.onyxbox.ferry

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import dev.onyxbox.ferry.core.TrustDecision

/** The "first time from this device" question, as a small e-ink friendly dialog. */
class TrustActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val id = intent.getIntExtra(FerryService.EXTRA_REQ, -1)
        val ask = FerryService.asks[id]
        if (ask == null) { finish(); return }
        val ui = Ui(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(16)) }
        col.addView(ui.title("${ask.peer.name} wants to send ${ask.count} file${if (ask.count == 1) "" else "s"}"))
        col.addView(ui.body("${fmtSize(ask.total)} · ${ask.peer.os} · first time from this device.\n\n" +
            "“Always” trusts it from now on, so future transfers arrive without asking."))
        val row = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, ui.dp(16), 0, 0) }
        fun btn(label: String, d: TrustDecision) = row.addView(ui.button(label, d == TrustDecision.ALWAYS) {
            FerryService.answer(this, id, d); finish()
        })
        btn("Decline", TrustDecision.DECLINE); btn("Once", TrustDecision.ONCE); btn("Always", TrustDecision.ALWAYS)
        col.addView(row)
        setContentView(col)
    }
}
