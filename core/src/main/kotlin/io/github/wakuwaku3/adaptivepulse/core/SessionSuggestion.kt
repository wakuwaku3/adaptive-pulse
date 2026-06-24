package io.github.wakuwaku3.adaptivepulse.core

import kotlinx.serialization.Serializable

/**
 * セッション中に engine が出す行動提案。engine 自体は何も操作せず、
 * ユーザに対して「ペースを緩めるか中断するか」の判断を促す UI 表示専用 (FB 2026-06-24)。
 *
 * 文言は日本語固定 (UI 全体は英語だが、提案だけは意味の取りやすさを優先する例外)。
 */
@Serializable
data class SessionSuggestion(
    val kind: SuggestionKind,
    val title: String,
    val reason: String,
)

@Serializable
enum class SuggestionKind {
    /** ペースを緩めるよう促す (高強度フェーズが速く上がりすぎている = 出力が落ちつつある可能性) */
    EASE_PACE,

    /** 中断を検討するよう促す (回復に時間がかかっている = 自律神経が追いついていない可能性) */
    CONSIDER_STOP,
}
