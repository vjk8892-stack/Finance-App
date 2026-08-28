package dev.kosha.feature.ingest.ocr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kosha.core.database.model.WarrantyItemEntity
import dev.kosha.core.designsystem.component.KoshaCard
import dev.kosha.core.designsystem.component.KoshaLocalImage
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaSpacing
import dev.kosha.core.designsystem.token.KoshaType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Warranty capture (the OCR bill-scan prompt) had no matching screen to view
 * what was saved — design review finding. This is that screen: soonest-
 * expiring first, a receipt thumbnail when one exists, delete per item.
 */
@Composable
fun WarrantyScreen(
    onBack: () -> Unit,
    viewModel: WarrantyViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val today = remember { LocalDate.now() }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KoshaSpacing.xs, vertical = KoshaSpacing.s),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = KoshaColors.OffWhiteMuted)
            }
            Text(stringResource(R.string.warranty_title), style = KoshaType.Title, color = KoshaColors.OffWhite)
        }

        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.warranty_empty),
                style = KoshaType.InsightSerif,
                color = KoshaColors.OffWhiteMuted,
                modifier = Modifier.padding(KoshaSpacing.screenPadding),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(KoshaSpacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(KoshaSpacing.s),
            ) {
                items(items.size) { i ->
                    WarrantyCard(items[i], today, onDelete = { viewModel.delete(items[i]) })
                }
            }
        }
    }
}

@Composable
private fun WarrantyCard(item: WarrantyItemEntity, today: LocalDate, onDelete: () -> Unit) {
    val expiry = remember(item.expiryDateMillis) {
        Instant.ofEpochMilli(item.expiryDateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val daysLeft = ChronoUnit.DAYS.between(today, expiry).toInt()
    val soon = daysLeft <= 7

    KoshaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            item.receiptPhotoUri?.let { uri ->
                KoshaLocalImage(
                    uri = uri,
                    contentDescription = null,
                    targetSize = 48.dp,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(KoshaSpacing.chipRadius)),
                )
                Spacer(Modifier.width(KoshaSpacing.s))
            }
            Column(Modifier.weight(1f)) {
                Text(item.itemName, style = KoshaType.Body, color = KoshaColors.OffWhite)
                Text(
                    text = when {
                        daysLeft < 0 -> stringResource(R.string.warranty_expired_days, -daysLeft)
                        daysLeft == 0 -> stringResource(R.string.warranty_expires_today)
                        else -> stringResource(R.string.warranty_expires_in_days, daysLeft)
                    },
                    style = KoshaType.Caption,
                    color = if (soon) KoshaColors.Amber else KoshaColors.OffWhiteFaint,
                )
                Text(
                    text = stringResource(R.string.warranty_expiry_date, DATE_FORMAT.format(expiry)),
                    style = KoshaType.Caption,
                    color = KoshaColors.OffWhiteFaint,
                )
            }
            TextButton(onClick = onDelete) {
                Text("×", style = KoshaType.Title, color = KoshaColors.OffWhiteFaint)
            }
        }
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
