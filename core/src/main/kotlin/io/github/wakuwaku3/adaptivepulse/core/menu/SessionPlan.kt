package io.github.wakuwaku3.adaptivepulse.core.menu

/**
 * 選択 (メニュー単体 or プログラム) を実行可能な形に解決したもの。
 * メニュー単体は 1 セグメントのプランとして同じ経路で実行する。
 */
data class SessionPlan(
    val name: String,
    /** null = メニュー単体の実行 */
    val programId: String?,
    val segments: List<PlannedSegment>,
) {
    init {
        require(segments.isNotEmpty()) { "plan は 1 セグメント以上" }
    }
}

/** プラン内の 1 メニュー実行分。amount は上書き解決済みの量 (本数 or 分数) */
data class PlannedSegment(
    val menu: Menu,
    val amount: Int,
)

object SessionPlanner {

    /**
     * 選択をプランに解決する。参照先メニューはカスタム (library) → プリセットの順で探す。
     * 参照切れ (削除済み等) は null を返し、呼び出し側が hiit へフォールバックする。
     */
    fun resolve(
        selection: LibrarySelection,
        library: LibraryDocument,
        presetMenus: List<Menu>,
        presetPrograms: List<Program> = Presets.programs(),
    ): SessionPlan? {
        fun findMenu(id: String): Menu? = library.menu(id) ?: presetMenus.firstOrNull { it.id == id }

        return when (selection.kind) {
            SelectionKind.MENU -> {
                val menu = findMenu(selection.id) ?: return null
                SessionPlan(
                    name = menu.name,
                    programId = null,
                    segments = listOf(PlannedSegment(menu, menu.kind.defaultAmount)),
                )
            }
            SelectionKind.PROGRAM -> {
                val program = library.program(selection.id)
                    ?: presetPrograms.firstOrNull { it.id == selection.id }
                    ?: return null
                val segments = program.entries.map { entry ->
                    val menu = findMenu(entry.menuId) ?: return null
                    PlannedSegment(menu, entry.amountOverride ?: menu.kind.defaultAmount)
                }
                SessionPlan(name = program.name, programId = program.id, segments = segments)
            }
        }
    }
}
