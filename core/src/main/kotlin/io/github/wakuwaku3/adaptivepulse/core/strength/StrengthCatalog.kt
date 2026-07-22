package io.github.wakuwaku3.adaptivepulse.core.strength

import kotlinx.serialization.Serializable

/**
 * ジムと筋トレ種目 (トレーニング) の台帳。phone ローカルが正で、Firestore
 * (`strengthCatalog/current`) へは分析用バックアップとして書き込み専用で送る
 * (docs/notes/20260722__strength-logging)。
 */
@Serializable
data class StrengthCatalog(
    val schema: Int = 1,
    val gyms: List<Gym> = emptyList(),
    /** 前回使用ジム。画面を開いたときの初期選択に使う */
    val lastGymId: String? = null,
    val updatedAtMs: Long,
    val updatedBy: String,
)

@Serializable
data class Gym(
    val id: String,
    val name: String,
    /** 並び順は登録順を保持する (ジムの順路どおりに登録すればそのまま順路リストになる) */
    val trainings: List<Training> = emptyList(),
)

/** 1 マシンの種目 or ストレッチ。名前はジム内で一意 (trim + 大文字小文字無視) */
@Serializable
data class Training(
    val id: String,
    val name: String,
    /** しばらく実施しない種目を一覧から外す。再表示可能なので削除は提供しない */
    val hidden: Boolean = false,
    /** 直近実績。次回 workout のセット自動記入に使う。null = 負荷なし種目 or 未実施 */
    val lastWeightKg: Double? = null,
    val lastReps: Int? = null,
    val lastPerformedAtMs: Long? = null,
)
