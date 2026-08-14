package dev.kosha.app.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.kosha.app.R
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaChip
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType

/**
 * Every optional permission in one place, with its real current state and a
 * way to change it.
 *
 * Kosha asks for nothing at launch and works without all of these (spec G9),
 * so this screen's job is to make the trade explicit: what each permission
 * unlocks, what still works without it, and where to turn it off again. Spec
 * C8 requires exactly this for notifications — "a quiet note with a deep link
 * to system settings" — and the same courtesy applies to the rest.
 */
private data class PermissionRow(
    val titleRes: Int,
    val bodyRes: Int,
    val withoutRes: Int,
    /** Runtime permissions to request; empty when the build omits them. */
    val permissions: List<String>,
    val declaredInBuild: Boolean,
    val granted: Boolean,
)

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-read on resume so returning from system Settings shows the truth.
    var refreshKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshKey++ }

    val rows = remember(refreshKey) { buildRows(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KoshaSpacing.xs, vertical = KoshaSpacing.s),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    tint = KoshaColors.OffWhiteMuted,
                )
            }
            Text(
                text = stringResource(R.string.permissions_title),
                style = KoshaType.Title,
                color = KoshaColors.OffWhite,
            )
        }

        Column(
            Modifier.padding(horizontal = KoshaSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
        ) {
            Text(
                text = stringResource(R.string.permissions_intro),
                style = KoshaType.InsightSerif,
                color = KoshaColors.OffWhiteMuted,
            )

            rows.forEach { row ->
                KoshaCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(row.titleRes),
                            style = KoshaType.Body,
                            color = KoshaColors.OffWhite,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = when {
                                !row.declaredInBuild -> stringResource(R.string.permissions_state_absent)
                                row.granted -> stringResource(R.string.permissions_state_on)
                                else -> stringResource(R.string.permissions_state_off)
                            },
                            style = KoshaType.Label,
                            color = when {
                                !row.declaredInBuild -> KoshaColors.OffWhiteFaint
                                row.granted -> KoshaColors.AccentTeal
                                else -> KoshaColors.OffWhiteMuted
                            },
                        )
                    }
                    Spacer(Modifier.height(KoshaSpacing.xxs))
                    Text(
                        text = stringResource(row.bodyRes),
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteMuted,
                    )
                    Spacer(Modifier.height(KoshaSpacing.xxs))
                    Text(
                        text = stringResource(row.withoutRes),
                        style = KoshaType.Caption,
                        color = KoshaColors.OffWhiteFaint,
                    )

                    if (row.declaredInBuild) {
                        Spacer(Modifier.height(KoshaSpacing.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(KoshaSpacing.xs)) {
                            if (!row.granted && row.permissions.isNotEmpty()) {
                                KoshaChip(
                                    label = stringResource(R.string.permissions_turn_on),
                                    onClick = { requestLauncher.launch(row.permissions.toTypedArray()) },
                                    accent = KoshaColors.AccentTeal,
                                )
                            }
                            // Revoking is only possible in system settings.
                            KoshaChip(
                                label = stringResource(R.string.permissions_open_settings),
                                onClick = { context.openAppInfo() },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(KoshaSpacing.s))
            KoshaCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.permissions_restricted_title),
                    style = KoshaType.Body,
                    color = KoshaColors.OffWhite,
                )
                Spacer(Modifier.height(KoshaSpacing.xxs))
                Text(
                    text = stringResource(R.string.permissions_restricted_body),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteMuted,
                )
                Spacer(Modifier.height(KoshaSpacing.xs))
                KoshaChip(
                    label = stringResource(R.string.permissions_open_settings),
                    onClick = { context.openAppInfo() },
                )
            }

            Spacer(Modifier.height(KoshaSpacing.s))
            Text(
                text = stringResource(R.string.permissions_no_internet),
                style = KoshaType.Caption,
                color = KoshaColors.OffWhiteFaint,
            )
            Spacer(Modifier.height(KoshaSpacing.xxl))
        }
    }
}

private fun buildRows(context: Context): List<PermissionRow> {
    val declared = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
    }.getOrNull().orEmpty().toSet()

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val rows = mutableListOf<PermissionRow>()

    rows += PermissionRow(
        titleRes = R.string.permissions_sms_title,
        bodyRes = R.string.permissions_sms_body,
        withoutRes = R.string.permissions_sms_without,
        permissions = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
        declaredInBuild = Manifest.permission.READ_SMS in declared,
        granted = Manifest.permission.READ_SMS in declared && granted(Manifest.permission.READ_SMS),
    )

    rows += PermissionRow(
        titleRes = R.string.permissions_camera_title,
        bodyRes = R.string.permissions_camera_body,
        withoutRes = R.string.permissions_camera_without,
        permissions = listOf(Manifest.permission.CAMERA),
        declaredInBuild = Manifest.permission.CAMERA in declared,
        granted = granted(Manifest.permission.CAMERA),
    )

    // POST_NOTIFICATIONS is only a runtime permission from Android 13.
    val notificationsDeclared = Build.VERSION.SDK_INT < 33 ||
        Manifest.permission.POST_NOTIFICATIONS in declared
    rows += PermissionRow(
        titleRes = R.string.permissions_notifications_title,
        bodyRes = R.string.permissions_notifications_body,
        withoutRes = R.string.permissions_notifications_without,
        permissions = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        },
        declaredInBuild = notificationsDeclared,
        granted = if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        },
    )

    return rows
}

private fun Context.openAppInfo() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
