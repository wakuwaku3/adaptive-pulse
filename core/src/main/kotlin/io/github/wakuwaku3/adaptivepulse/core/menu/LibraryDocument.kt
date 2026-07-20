package io.github.wakuwaku3.adaptivepulse.core.menu

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import kotlinx.serialization.Serializable

/** 開始画面の初期表示 = 最後に使ったメニューまたはプログラム */
@Serializable
data class LibrarySelection(
    val kind: SelectionKind,
    val id: String,
)

@Serializable
enum class SelectionKind { MENU, PROGRAM }

/**
 * カスタムメニュー/プログラムと選択状態の同期ペイロード (watch ⇄ phone ⇄ server)。
 * settings と同じく文書全体を updatedAtMs の最終更新者勝ち (LWW) で解決する
 * (docs/stock/sync.md)。プリセットは端末側で生成するためここには含めない。
 */
@Serializable
data class LibraryDocument(
    val schema: Int = 1,
    val menus: List<Menu>,
    val programs: List<Program> = emptyList(),
    val selection: LibrarySelection,
    val updatedAtMs: Long,
    val updatedBy: String,
) {
    fun menu(id: String): Menu? = menus.firstOrNull { it.id == id }

    fun program(id: String): Program? = programs.firstOrNull { it.id == id }

    companion object {
        /** 既存の単一設定から移行した「hiit」メニューの固定 id (プリセットプログラムが参照する) */
        const val HIIT_MENU_ID = "hiit"

        /**
         * 既存ユーザの単一設定から初期ライブラリを生成する (hiit 移行)。
         * updatedAtMs = 0 にして、どの端末のどんな実編集にも LWW で負けるようにする
         * (watch / phone が独立に生成しても実質同値で、同期後は編集した側が正になる)。
         */
        fun initialFrom(config: SessionConfig) = LibraryDocument(
            menus = listOf(
                Menu(
                    id = HIIT_MENU_ID,
                    name = "hiit",
                    kind = MenuKind.Interval(
                        upperBpm = config.upperBpm,
                        lowerBpm = config.lowerBpm,
                        cycles = config.targetCycles,
                        targetCadenceHigh = config.targetCadenceHigh,
                        targetCadenceRecovery = config.targetCadenceRecovery,
                    ),
                ),
            ),
            selection = LibrarySelection(SelectionKind.MENU, HIIT_MENU_ID),
            updatedAtMs = 0L,
            updatedBy = "initial",
        )
    }
}
