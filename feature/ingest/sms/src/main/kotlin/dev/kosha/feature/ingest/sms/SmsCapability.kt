package dev.kosha.feature.ingest.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Whether this BUILD can capture SMS at all.
 *
 * The `lite` flavor strips the SMS permissions from the manifest so the app
 * installs without Play Protect blocking it. Requesting a permission the
 * manifest does not declare is refused instantly by the OS, so any UI that
 * offers it there is a dead end — every SMS entry point must check this
 * first rather than assuming the permission is merely ungranted.
 */
object SmsCapability {

    /** False in the lite build: the permission is not in the manifest. */
    fun isSupportedByBuild(context: Context): Boolean {
        val declared = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
        }.getOrNull().orEmpty()
        return Manifest.permission.READ_SMS in declared
    }

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** Ready to actually read the inbox. */
    fun isUsable(context: Context): Boolean =
        isSupportedByBuild(context) && isGranted(context)
}
