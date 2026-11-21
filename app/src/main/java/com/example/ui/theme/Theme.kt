package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val PanoDarkColorScheme = darkColorScheme(
    primary = SignalCyan,
    onPrimary = Obsidian,
    primaryContainer = DeepCyanContainer,
    onPrimaryContainer = LightCyanTint,
    secondary = MintEmerald,
    onSecondary = Obsidian,
    secondaryContainer = DeepMintContainer,
    onSecondaryContainer = OffWhiteText,
    tertiary = AmberCaution,
    onTertiary = Obsidian,
    tertiaryContainer = DeepAmberContainer,
    onTertiaryContainer = OffWhiteText,
    error = CoralAlert,
    onError = Obsidian,
    errorContainer = DeepCoralContainer,
    onErrorContainer = OffWhiteText,
    background = Obsidian,
    onBackground = OffWhiteText,
    surface = CharcoalSurface,
    onSurface = OffWhiteText,
    surfaceVariant = ElevatedSlate,
    onSurfaceVariant = MutedSlateText,
    outline = SlateOutline
)

private val PanoLightColorScheme = lightColorScheme(
    primary = OceanCyanPrimary,
    onPrimary = CrispWhiteSurface,
    primaryContainer = OceanCyanContainer,
    onPrimaryContainer = DeepCyanContainer,
    secondary = EmeraldSecondary,
    onSecondary = CrispWhiteSurface,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = DeepMintContainer,
    tertiary = AmberCaution,
    onTertiary = DeepNavyText,
    error = CoralAlert,
    onError = CrispWhiteSurface,
    background = MistBackground,
    onBackground = DeepNavyText,
    surface = CrispWhiteSurface,
    onSurface = DeepNavyText,
    surfaceVariant = SoftSlateSurfaceVariant,
    onSurfaceVariant = SlateSecondaryText,
    outline = ColorTokens.LightOutline
)

private object ColorTokens {
    val LightOutline = androidx.compose.ui.graphics.Color(0xFFCBD5E1)
}

val PanoShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun PanoGuideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) PanoDarkColorScheme else PanoLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PanoTypography,
        shapes = PanoShapes,
        content = content
    )
}
