package io.github.wakuwaku3.adaptivepulse.core.menu

import kotlinx.serialization.Serializable

/**
 * メニューを順に並べたトレーニング全体の定義「プログラム」。
 * program は「やる予定の定義」で、実行した記録である session と対になる。
 */
@Serializable
data class Program(
    val id: String,
    val name: String,
    val entries: List<ProgramEntry>,
) {
    init {
        require(id.isNotBlank()) { "program id は空にしない" }
        require(name.isNotBlank()) { "program name は空にしない" }
        require(entries.isNotEmpty()) { "program は 1 つ以上のメニューを含むこと" }
    }
}

/** プログラム内のメニュー配置。量 (本数 or 分数) を配置ごとに上書きできる */
@Serializable
data class ProgramEntry(
    val menuId: String,
    /** 心拍トリガー型は本数、時間制は分数。null = メニューのデフォルト値 */
    val amountOverride: Int? = null,
) {
    init {
        require(amountOverride == null || amountOverride >= 1) { "量の上書きは 1 以上" }
    }
}
