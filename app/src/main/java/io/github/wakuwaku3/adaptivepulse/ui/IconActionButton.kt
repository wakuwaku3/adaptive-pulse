package io.github.wakuwaku3.adaptivepulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Text

/** 記号グリフ 1 文字の小円アクションボタン (視覚 32dp、タップ領域 48dp)。.claude/rules/ui.md */
@Composable
fun IconActionButton(
    glyph: String,
    tint: Color,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompactButton(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(backgroundColor = background),
        modifier = modifier,
    ) {
        Text(text = glyph, color = tint, fontSize = 14.sp)
    }
}
