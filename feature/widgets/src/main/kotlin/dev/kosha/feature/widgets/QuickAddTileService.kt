package dev.kosha.feature.widgets

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick-settings tile (spec G11): opens the amount-first keypad directly.
 * One tap from the shade to a logged expense.
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickAddTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = KoshaDeepLinks.intent(this, KoshaDeepLinks.ACTION_QUICK_ADD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
