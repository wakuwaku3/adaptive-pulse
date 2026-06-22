package io.github.wakuwaku3.adaptivepulse.core

/**
 * IntervalEngine の対外スナップショット。データソースが「Phase」だけでは判別できない
 * 状態 (= warm-up 中か否か) を必要とするため、phase と warm-up フラグを 1 つにまとめて
 * 渡す。例: TieredCadenceLock の discovery 開始は warm-up 終了時点をトリガにする。
 */
data class SessionPhaseSnapshot(
    val phase: Phase,
    val isWarmingUp: Boolean,
)
