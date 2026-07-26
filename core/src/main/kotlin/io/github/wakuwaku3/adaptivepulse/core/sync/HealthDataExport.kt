package io.github.wakuwaku3.adaptivepulse.core.sync

import io.github.wakuwaku3.adaptivepulse.core.analysis.HealthAnalysis
import io.github.wakuwaku3.adaptivepulse.core.strength.StrengthCatalog
import io.github.wakuwaku3.adaptivepulse.core.strength.WorkoutRecord
import kotlinx.serialization.Serializable

/**
 * エクスポート 1 ファイルの top-level シェイプ (schema 2)。
 *
 * 「ファイルを AI に食わせて『分析して』だけで /analyze 相当の評価が返る」ことを狙い、
 * 自己完結にする: 生データ ([raw]) と集計済みデータ ([analysis]) の両方を持ち、先頭の
 * [documentation] に「このファイルが何か・各データの意味・誤読を防ぐ解釈ルール」を同梱する。
 */
@Serializable
data class HealthDataExport(
    val schema: Int = 2,
    /** UNIX epoch millis でのエクスポート時刻 */
    val exportedAtMs: Long,
    /** 含まれる日付の範囲 (inclusive) */
    val fromDate: String,
    val toDate: String,
    val documentation: ExportDocumentation,
    val raw: RawData,
    val analysis: HealthAnalysis,
)

/** 生データ一式。集計 ([HealthAnalysis]) はここから算術ミスなく導ける */
@Serializable
data class RawData(
    val dailyMetrics: List<DailyHealthRecord>,
    /** HIIT セッション。容量のためセッション中 1 秒 HR 列 (hrBpmBySecond) は落として同梱する */
    val sessions: List<SessionRecord>,
    val workouts: List<WorkoutRecord>,
    val settings: SettingsDocument? = null,
    val strengthCatalog: StrengthCatalog? = null,
)

/** ファイル自体の説明と解釈ルール。AI が前提知識なしで正しく読めるようにする */
@Serializable
data class ExportDocumentation(
    val aboutThisFile: String,
    /** データセット名 → その中身が何を表すか */
    val datasets: Map<String, String>,
    val analysisIntent: String,
    /** 数値を誤読しないためのルール (カロリー収支・brake・null の扱い等) */
    val interpretationRules: List<String>,
) {
    companion object {
        fun default(): ExportDocumentation = ExportDocumentation(
            aboutThisFile =
                "adaptive-pulse (Wear OS のアダプティブ HIIT アプリ) が端末から書き出す健康・" +
                    "トレーニングデータ。生データ (raw) と集計済み (analysis) を両方含む。" +
                    "このファイルだけを読んで、下記 analysisIntent と interpretationRules に沿って" +
                    "現状評価と行動提案を返してほしい。",
            datasets = mapOf(
                "raw.dailyMetrics" to
                    "Health Connect 由来の 1 日 1 行の指標 (安静時心拍/心拍の揺らぎ/睡眠/歩数/" +
                    "カロリー/体重/体脂肪/摂取と PFC 等)。tdeeKcal と exerciseExtraKcal は本アプリの再計算値。",
                "raw.sessions" to
                    "HIIT セッション履歴。cycles=完了本数, plannedCycles=予定本数, fatigueBrake=疲労ブレーキ発動, " +
                    "highDurationsSec/recoveryDurationsSec=サイクルごとの高強度/回復の所要秒, config=実行時設定。",
                "raw.workouts" to
                    "筋トレ記録 (ジム 1 回 = 1 レコード)。entries[].sets[] に weightKg/reps。skipped=その日やらない明示。",
                "raw.settings" to "現行のセッション設定と身体プロファイル (年齢/安静時心拍/身長/体重/目標体重)。",
                "raw.strengthCatalog" to "ジムと種目の台帳 (種目名の順路・直近実績)。",
                "analysis.bodyComposition" to "期間の体重・体脂肪率の最初→最後と変化量。",
                "analysis.nutrition" to
                    "摂取と TDEE の収支、タンパク質/脂質の下限割れ日。下限の根拠は体重・目標体重ベース。",
                "analysis.recovery" to "安静時心拍の中央値と心拍の揺らぎの平均 (回復の基準値)。",
                "analysis.hiit" to "HIIT の本数・完走数・ブレーキ発動数・高強度ゾーン滞在の合計分。",
                "analysis.strengthProgression" to "種目別の日別トップセットと総量 (漸進的過負荷の判定材料)。",
            ),
            analysisIntent =
                "デフォルトは「トレーニング (HIIT + 筋トレ) / 睡眠 / 食事 / 体組成」の 4 軸で評価する。" +
                    "体組成の変化を起点に、それを他 3 軸 (トレーニング量・睡眠・食事収支) で説明し、" +
                    "最後に具体的な行動提案を出す。筋トレは種目別の推移から漸進的過負荷が効いているかを必ず見る。",
            interpretationRules = listOf(
                "値が null / 欠損なのは「まだ取得していない/連携待ち」であって不具合ではない。「未連携バグ」と書かない。",
                "カロリー収支のマイナス (摂取 - TDEE < 0) は減量目的の意図的なもの。「カロリー不足」とネガティブに書かない。" +
                    "問題化するのは栄養素が下限を割っているとき (proteinBelowFloorDates / fatBelowFloorDates) に限る。",
                "session の fatigueBrake=疲労、と短絡しない。既定は「設定を探索中で本数/心拍帯が合っていないだけ」。" +
                    "疲労と判断するには同一設定で複数日ブレーキが再現し、かつ安静時心拍↑・心拍の揺らぎ↓が同時に出ていること。",
                "安静時心拍は 14 日中央値 +5bpm 超で疲労の警告、心拍の揺らぎは 7 日平均の -20% 以下で過剰トレーニング徴候。",
                "筋トレは同一種目で重量・回数が伸びていれば漸進的過負荷が効いている。横ばい・低下なら回復不足 (睡眠・食事) か種目のマンネリを疑う。",
                "体脂肪率の 0 は欠損なので実測として扱わない。",
                "当日 (toDate) は記録が途中でありうる (食事や HC の当日分が未確定)。",
            ),
        )
    }
}
