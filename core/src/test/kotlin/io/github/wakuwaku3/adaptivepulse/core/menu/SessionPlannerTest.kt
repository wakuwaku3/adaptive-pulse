package io.github.wakuwaku3.adaptivepulse.core.menu

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionPlannerTest {

    private val presetMenus = Presets.menus(ageYears = 39)
    private val library = LibraryDocument.initialFrom(SessionConfig()).let {
        it.copy(
            programs = listOf(
                Program(
                    id = "custom-p",
                    name = "Mine",
                    entries = listOf(
                        ProgramEntry(Presets.MENU_WARMUP),
                        ProgramEntry(LibraryDocument.HIIT_MENU_ID, amountOverride = 3),
                    ),
                ),
                Program(id = "broken-p", name = "Broken", entries = listOf(ProgramEntry("deleted-menu"))),
            ),
        )
    }

    @Test
    fun `メニュー単体の選択は 1 セグメントのプランになる`() {
        val plan = SessionPlanner.resolve(
            LibrarySelection(SelectionKind.MENU, LibraryDocument.HIIT_MENU_ID),
            library,
            presetMenus,
        )!!
        assertEquals("hiit", plan.name)
        assertNull(plan.programId)
        assertEquals(1, plan.segments.size)
        assertEquals(5, plan.segments.first().amount) // SessionConfig デフォルトの targetCycles
    }

    @Test
    fun `プログラムの量上書きが解決される (カスタム → プリセットの順でメニューを探す)`() {
        val plan = SessionPlanner.resolve(
            LibrarySelection(SelectionKind.PROGRAM, "custom-p"),
            library,
            presetMenus,
        )!!
        assertEquals("custom-p", plan.programId)
        assertEquals(listOf(5, 3), plan.segments.map { it.amount }) // warmup 5 分 / hiit 上書き 3 本
    }

    @Test
    fun `参照切れ (削除済みメニュー) は null で呼び出し側フォールバックに委ねる`() {
        assertNull(
            SessionPlanner.resolve(
                LibrarySelection(SelectionKind.PROGRAM, "broken-p"),
                library,
                presetMenus,
            ),
        )
        assertNull(
            SessionPlanner.resolve(
                LibrarySelection(SelectionKind.MENU, "no-such-menu"),
                library,
                presetMenus,
            ),
        )
    }
}
