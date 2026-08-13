package dev.kosha.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dashboard widget, 4x2 (spec G11): weather line + Pulse number + top-2
 * budget rings, deep-linking into the app. Refreshed by
 * [WidgetRefreshWorker] on a 30-minute window and immediately after any
 * data change.
 *
 * Privacy mode masks amounts as "₹ ••••" — the widget is the one surface a
 * stranger can read over your shoulder.
 */
class KoshaDashboardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                DashboardContent(context)
            }
        }
    }

    @Composable
    private fun DashboardContent(context: Context) {
        val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
        val masked = prefs[KEY_MASKED] ?: false
        val weather = prefs[KEY_WEATHER] ?: ""
        val pulse = prefs[KEY_PULSE] ?: "—"
        val budget1 = prefs[KEY_BUDGET_1]
        val budget2 = prefs[KEY_BUDGET_2]

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(CHARCOAL)
                .padding(14.dp)
                .clickable(openAppAction(context)),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            if (weather.isNotEmpty()) {
                Text(
                    text = weather,
                    style = TextStyle(color = ColorProvider(OFF_WHITE_MUTED), fontSize = 12.sp),
                )
            }
            Text(
                text = if (masked) MASKED_AMOUNT else pulse,
                style = TextStyle(color = ColorProvider(OFF_WHITE), fontSize = 28.sp),
            )
            Row {
                listOfNotNull(budget1, budget2).forEach { budget ->
                    Text(
                        text = budget,
                        style = TextStyle(color = ColorProvider(OFF_WHITE_MUTED), fontSize = 11.sp),
                        modifier = GlanceModifier.padding(end = 10.dp),
                    )
                }
            }
        }
    }

    companion object {
        val KEY_MASKED = booleanPreferencesKey("widget_masked")
        val KEY_WEATHER = stringPreferencesKey("widget_weather")
        val KEY_PULSE = stringPreferencesKey("widget_pulse")
        val KEY_BUDGET_1 = stringPreferencesKey("widget_budget_1")
        val KEY_BUDGET_2 = stringPreferencesKey("widget_budget_2")

        const val MASKED_AMOUNT = "₹ ••••"
        val CHARCOAL = Color(0xFF0F1114)
        val OFF_WHITE = Color(0xFFF2EFEA)
        val OFF_WHITE_MUTED = Color(0xFFB9B4AC)
    }
}

class KoshaDashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KoshaDashboardWidget()
}
