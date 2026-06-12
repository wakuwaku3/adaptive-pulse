package io.github.wakuwaku3.adaptivepulse.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** watch と同じトーン (黒 + コーラル/ミント/ゴールド)。.claude/rules/ui.md */
object MobileColors {
    val High = Color(0xFFFF4E5E)
    val Recover = Color(0xFF2CE8B5)
    val Done = Color(0xFFFFC857)
    val TextDim = Color(0xFF8A93A0)
    val Surface = Color(0xFF15181D)
}

private val DarkColors = darkColorScheme(
    primary = MobileColors.High,
    onPrimary = Color.Black,
    secondary = MobileColors.Recover,
    onSecondary = Color.Black,
    tertiary = MobileColors.Done,
    background = Color.Black,
    surface = MobileColors.Surface,
)

@Composable
fun AdaptivePulseMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
