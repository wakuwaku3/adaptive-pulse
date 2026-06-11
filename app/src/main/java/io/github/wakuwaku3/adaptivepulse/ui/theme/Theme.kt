package io.github.wakuwaku3.adaptivepulse.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography
import io.github.wakuwaku3.adaptivepulse.R

/**
 * デザイントーン (.claude/rules/ui.md): 黒背景 + フェーズの直感色分け。
 * 運動中の一瞥で読めることを最優先し、色とタイポはここに集約する。
 */
object APColors {
    /** 高強度フェーズ: コーラル (押せ) */
    val High = Color(0xFFFF4E5E)

    /** 回復フェーズ: ミント (緩めろ) */
    val Recover = Color(0xFF2CE8B5)

    /** 完了: ゴールド */
    val Done = Color(0xFFFFC857)

    val Text = Color(0xFFE8ECF1)
    val TextDim = Color(0xFF7C8694)
    val RingTrack = Color(0xFF20242B)
    val StopChip = Color(0xFF2A2F37)
}

val Rajdhani = FontFamily(
    Font(R.font.rajdhani_regular, FontWeight.Normal),
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
)

private val AppTypography = Typography(
    // 心拍数字: 画面の主役。Rajdhani は数字が等幅寄りでチラつかない。
    // 丸画面の内接領域に収めるため、サイズは控えめにする (ユーザ FB 2026-06-11)
    display1 = Typography().display1.copy(
        fontFamily = Rajdhani,
        fontWeight = FontWeight.Bold,
        fontSize = 54.sp,
    ),
    title1 = Typography().title1.copy(
        fontFamily = Rajdhani,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 2.sp,
    ),
    // フェーズラベル: 大文字 + 広め letterSpacing で標識らしく
    title3 = Typography().title3.copy(
        fontFamily = Rajdhani,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 3.sp,
    ),
    body1 = Typography().body1.copy(
        fontFamily = Rajdhani,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        letterSpacing = 1.sp,
    ),
    caption1 = Typography().caption1.copy(
        fontFamily = Rajdhani,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 1.5.sp,
    ),
    // CompactChip (高さ 32dp) のラベルにも収まるサイズに抑える
    button = Typography().button.copy(
        fontFamily = Rajdhani,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
    ),
)

private val AppColors = Colors(
    primary = APColors.High,
    secondary = APColors.Recover,
    background = Color.Black,
    onBackground = APColors.Text,
    surface = APColors.StopChip,
    onSurface = APColors.Text,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
)

@Composable
fun AdaptivePulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = AppColors,
        typography = AppTypography,
        content = content,
    )
}
