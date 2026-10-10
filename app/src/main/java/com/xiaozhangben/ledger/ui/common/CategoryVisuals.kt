package com.xiaozhangben.ledger.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.xiaozhangben.ledger.domain.model.CategoryKeys

/**
 * Turns the stored `iconKey` / `colorHex` strings into drawable/colour values.
 *
 * Icons are data rather than code so custom categories work without a rebuild, but
 * they still need a single mapping table so an unknown key degrades to something
 * recognisable instead of crashing.
 */
object CategoryVisuals {

    fun icon(iconKey: String?): ImageVector = when (iconKey) {
        "restaurant" -> Icons.Outlined.Fastfood
        "directions_bus" -> Icons.Outlined.DirectionsBus
        "shopping_bag" -> Icons.Outlined.ShoppingBag
        "home" -> Icons.Outlined.Home
        "smartphone" -> Icons.Outlined.Smartphone
        "movie" -> Icons.Outlined.Movie
        "medical_services" -> Icons.Outlined.MedicalServices
        "card_giftcard" -> Icons.Outlined.CardGiftcard
        "school" -> Icons.Outlined.School
        "build" -> Icons.Outlined.Build
        "flight" -> Icons.Outlined.Flight
        "subscriptions" -> Icons.Outlined.Subscriptions
        "help_outline" -> Icons.Outlined.Help
        "undo" -> Icons.Outlined.Refresh
        "more_horiz" -> Icons.Outlined.MoreHoriz
        else -> Icons.Outlined.MoreHoriz
    }

    fun color(hex: String?): Color = try {
        Color(android.graphics.Color.parseColor(hex ?: FALLBACK))
    } catch (_: Throwable) {
        Color(android.graphics.Color.parseColor(FALLBACK))
    }

    /** Keys offered when creating a custom category. */
    val ICON_CHOICES = listOf(
        "restaurant", "directions_bus", "shopping_bag", "home", "smartphone", "movie",
        "medical_services", "card_giftcard", "school", "build", "flight", "subscriptions",
        "more_horiz",
    )

    val COLOR_CHOICES = listOf(
        "#E8590C", "#1C7ED6", "#C2255C", "#5F3DC4", "#0B7285", "#D6336C",
        "#2B8A3E", "#E67700", "#1971C2", "#495057", "#0CA678", "#7048E8", "#868E96",
    )

    private const val FALLBACK = "#868E96"

    fun isSystemKey(key: String?): Boolean =
        key == CategoryKeys.UNCLASSIFIED || key == CategoryKeys.REFUND_POOL
}
