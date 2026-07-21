package io.github.wakuwaku3.adaptivepulse.core

import kotlin.time.Duration

/**
 * セッション中の心拍を 1 秒グリッドに集める収集器。
 *
 * 心拍の全量時系列は容量のため Firestore に保存しない方針の中で、セッション中だけは
 * フェーズ応答・回復速度の分析に使える生値を `SessionRecord` に同梱する
 * (`.claude/rules/health-connect-sync.md` の時系列除外の例外)。
 *
 * 1 秒グリッドにするのは JSON サイズを見積り可能にするため (センサーは約 1Hz なので
 * 情報損失は実質ない)。同一秒に複数サンプルが来たら最後の値で上書きする。
 */
class HrSampleSeries {

    private val bySecond = mutableMapOf<Int, Int>()

    fun record(elapsed: Duration, bpm: Int) {
        bySecond[elapsed.inWholeSeconds.toInt()] = bpm
    }

    /**
     * index = セッション開始からの経過秒、値 = その秒の bpm。サンプルの無い秒
     * (ウォームアップ前・センサー欠落) は null。1 サンプルも無ければ空リスト。
     */
    fun toBpmBySecond(): List<Int?> {
        val last = bySecond.keys.maxOrNull() ?: return emptyList()
        return (0..last).map { bySecond[it] }
    }
}
