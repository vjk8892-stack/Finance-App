package dev.kosha.feature.widgets

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.action.Action
// The Intent-taking overload lives in the appwidget action package; the base
// androidx.glance.action one only accepts a ComponentName or Activity class.
import androidx.glance.appwidget.action.actionStartActivity

/** Deep links used by widgets, the QS tile and app-icon shortcuts (G11). */
object KoshaDeepLinks {
    const val ACTION_OPEN = "dev.kosha.action.OPEN"
    const val ACTION_QUICK_ADD = "dev.kosha.action.QUICK_ADD"
    const val ACTION_SCAN = "dev.kosha.action.SCAN"
    const val ACTION_VAULT = "dev.kosha.action.VAULT"

    const val EXTRA_DESTINATION = "kosha_destination"

    fun intent(context: Context, action: String): Intent =
        Intent(action).apply {
            component = ComponentName(context, MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_DESTINATION, action)
        }

    private const val MAIN_ACTIVITY = "dev.kosha.app.MainActivity"
}

internal fun openAppAction(context: Context): Action =
    actionStartActivity(KoshaDeepLinks.intent(context, KoshaDeepLinks.ACTION_OPEN))

internal fun quickAddAction(context: Context): Action =
    actionStartActivity(KoshaDeepLinks.intent(context, KoshaDeepLinks.ACTION_QUICK_ADD))
