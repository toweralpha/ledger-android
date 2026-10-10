package com.xiaozhangben.ledger.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.data.repository.ThemeMode
import com.xiaozhangben.ledger.di.appContainer

/**
 * Motion tokens.
 *
 * The brief is "short but fluid page transitions", so everything is capped well
 * below 300ms. The same spring is reused everywhere: mixing curves is what makes an
 * app feel jumpy even when each individual animation is fine.
 */
object Motion {
    const val SHORT = 180
    const val MEDIUM = 220
    const val LONG = 260

    val spring: androidx.compose.animation.core.SpringSpec<Float> =
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

    val springDp: androidx.compose.animation.core.SpringSpec<androidx.compose.ui.unit.Dp> =
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

    fun <T> quick() = tween<T>(durationMillis = MEDIUM)

    fun <T> short() = tween<T>(durationMillis = SHORT)
}

/**
 * Respects the system "remove animations" accessibility setting.
 * When it is on, screens must snap to their final state rather than animate.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale != 0f
    }
}

/** Amount typography must use tabular figures, otherwise numbers jitter as they tick. */
private fun tabular(size: Int, weight: FontWeight, letterSpacing: Double = 0.0) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    fontWeight = weight,
    fontFeatureSettings = "tnum",
    letterSpacing = letterSpacing.sp,
)

object LedgerType {
    /** The hero number on the home screen. */
    val heroAmount = tabular(44, FontWeight.Medium, -0.5)
    val largeAmount = tabular(28, FontWeight.Medium, -0.2)
    val rowAmount = tabular(16, FontWeight.Medium)
    val captionAmount = tabular(13, FontWeight.Normal)

    val screenTitle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    val sectionTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Normal)
}

private val LedgerTypography = Typography(
    headlineLarge = LedgerType.heroAmount,
    headlineMedium = LedgerType.largeAmount,
    titleLarge = LedgerType.screenTitle,
    titleMedium = LedgerType.sectionTitle,
    bodyLarge = LedgerType.body,
    labelSmall = LedgerType.caption,
)

private val LightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = DangerRed,
)

private val DarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = DangerRed,
)

object Spacing {
    val pageHorizontal = 20.dp
    val cardPadding = 18.dp
    val gapXs = 4.dp
    val gapS = 8.dp
    val gapM = 12.dp
    val gapL = 16.dp
    val gapXl = 24.dp
}

object Radius {
    val s = 12.dp
    val m = 16.dp
    val l = 20.dp
    val xl = 28.dp
}

@Composable
fun LedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = LedgerTypography,
        content = content,
    )
}

/**
 * Applies the user's pinned theme, falling back to the system setting.
 *
 * Reading the preference here rather than in the Activity means switching the theme in
 * settings takes effect immediately, without recreating the Activity (which would lose
 * the current screen).
 */
@Composable
fun LedgerThemeFromSettings(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mode by context.appContainer.settings.themeMode
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    LedgerTheme(darkTheme = dark, content = content)
}
