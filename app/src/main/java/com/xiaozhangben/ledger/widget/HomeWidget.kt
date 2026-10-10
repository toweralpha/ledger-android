package com.xiaozhangben.ledger.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.di.appContainer
import com.xiaozhangben.ledger.domain.budget.BudgetSnapshot

/**
 * Home-screen widget: "how much may I spend today", without opening the app.
 *
 * `updatePeriodMillis` is 0 in the widget metadata and the widget is refreshed
 * explicitly instead — a periodic wake-up purely to redraw a number is exactly the
 * kind of background cost this project is trying not to pay.
 */
class HomeWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetData.load(context)
        provideContent { WidgetContent(state) }
    }
}

private data class WidgetData(
    val totalText: String,
    val spentText: String,
    val dailyText: String,
    val pockets: List<Pair<String, String>>,
    val overBudget: Boolean,
) {
    companion object {
        suspend fun load(context: Context): WidgetData = try {
            val snapshot: BudgetSnapshot = context.appContainer.snapshot.load()
            from(snapshot)
        } catch (_: Throwable) {
            WidgetData("—", "—", "—", emptyList(), false)
        }

        fun from(snapshot: BudgetSnapshot): WidgetData = WidgetData(
            totalText = Money.formatCompact(snapshot.remainingCents),
            spentText = Money.formatCompact(snapshot.spentCents),
            dailyText = Money.formatCompact(snapshot.dailyRemainingCents.coerceAtLeast(0L)),
            pockets = snapshot.pockets.take(4).map { p ->
                p.name to Money.formatCompact(p.remainingTodayCents)
            },
            overBudget = snapshot.remainingCents < 0L,
        )
    }
}

@Composable
private fun WidgetContent(data: WidgetData) {
    val bg = ColorProvider(Color(0xFF101412))
    val fg = ColorProvider(Color(0xFFE6EAE8))
    val muted = ColorProvider(Color(0xFF9AA5A1))
    val accent = ColorProvider(if (data.overBudget) Color(0xFFDC2626) else Color(0xFF4FBF9F))

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bg)
            .cornerRadius(20.dp)
            .padding(14.dp),
        verticalAlignment = Alignment.Vertical.Top,
    ) {
        Text(
            text = "今日还能花",
            style = TextStyle(color = muted, fontSize = 12.sp),
        )
        Spacer(GlanceModifier.height(2.dp))
        Text(
            text = data.dailyText,
            style = TextStyle(color = accent, fontSize = 26.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.height(6.dp))
        Text(
            text = "本月可用 ${data.totalText} · 已用 ${data.spentText}",
            style = TextStyle(color = muted, fontSize = 11.sp),
        )

        if (data.pockets.isNotEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            data.pockets.forEach { (name, left) ->
                Text(
                    text = "$name  $left",
                    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp),
                    style = TextStyle(color = fg, fontSize = 12.sp),
                )
            }
        }
    }
}
