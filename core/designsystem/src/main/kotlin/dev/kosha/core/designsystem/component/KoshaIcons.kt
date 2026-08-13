package dev.kosha.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalAtm
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector

/** Resolves the DB icon-token strings (spec G2 seeds) to icon vectors. */
object KoshaIcons {
    fun forToken(token: String?): ImageVector = when (token) {
        "restaurant" -> Icons.Outlined.Restaurant
        "grocery" -> Icons.Outlined.ShoppingCart
        "transport" -> Icons.Outlined.DirectionsBus
        "fuel" -> Icons.Outlined.LocalGasStation
        "shopping" -> Icons.Outlined.ShoppingBag
        "bills" -> Icons.AutoMirrored.Outlined.ReceiptLong
        "home" -> Icons.Outlined.Home
        "emi" -> Icons.Outlined.CreditCard
        "health" -> Icons.Outlined.HealthAndSafety
        "insurance" -> Icons.Outlined.Shield
        "education" -> Icons.Outlined.School
        "entertainment" -> Icons.Outlined.Movie
        "subscriptions" -> Icons.Outlined.Subscriptions
        "travel" -> Icons.Outlined.Flight
        "personalcare" -> Icons.Outlined.Face
        "construction" -> Icons.Outlined.Construction
        "transfer" -> Icons.Outlined.SwapHoriz
        "atm" -> Icons.Outlined.LocalAtm
        "salary" -> Icons.Outlined.Payments
        "business" -> Icons.Outlined.Business
        "interest" -> Icons.Outlined.TrendingUp
        "refund" -> Icons.Outlined.Redeem
        "otherincome" -> Icons.Outlined.AccountBalance
        else -> Icons.Outlined.Category
    }
}
