package io.github.wakuwaku3.adaptivepulse.core.menu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetsTest {

    // 39 歳: Tanaka HRmax = 208 - 0.7*39 ≈ 181
    private val menus = Presets.menus(ageYears = 39)

    private fun menu(id: String) = menus.first { it.id == id }

    @Test
    fun `4x4 - 90%~70% HRmax を 4 本 (Helgerud 2007 の閾値駆動版)`() {
        val kind = menu(Presets.MENU_4X4).kind as MenuKind.Interval
        assertEquals(163, kind.upperBpm) // 0.90 * 181
        assertEquals(127, kind.lowerBpm) // 0.70 * 181
        assertEquals(4, kind.cycles)
    }

    @Test
    fun `Moderate - 80%~70% HRmax を 5 本`() {
        val kind = menu(Presets.MENU_MODERATE).kind as MenuKind.Interval
        assertEquals(145, kind.upperBpm)
        assertEquals(127, kind.lowerBpm)
        assertEquals(5, kind.cycles)
    }

    @Test
    fun `Walking - 64-75% HRmax の帯で 30 分の時間制`() {
        val kind = menu(Presets.MENU_WALKING).kind as MenuKind.Timed
        assertEquals(136, kind.upperBpm)
        assertEquals(116, kind.lowerBpm)
        assertEquals(30, kind.minutes)
    }

    @Test
    fun `Warm-up - 64% HRmax 上限のみ (下限なし) で 5 分`() {
        val kind = menu(Presets.MENU_WARMUP).kind as MenuKind.Timed
        assertEquals(116, kind.upperBpm)
        assertNull(kind.lowerBpm)
        assertEquals(5, kind.minutes)
    }

    @Test
    fun `年齢を変えると bpm が追従する (保存でなく生成)`() {
        val younger = Presets.menus(ageYears = 25) // HRmax ≈ 191 (round(190.5))
        val kind = younger.first { it.id == Presets.MENU_4X4 }.kind as MenuKind.Interval
        assertEquals(172, kind.upperBpm) // 0.90 * 191 = 171.9
    }

    @Test
    fun `プリセットプログラムの参照は hiit 移行後のライブラリで全部解決できる`() {
        val library = LibraryDocument.initialFrom(
            io.github.wakuwaku3.adaptivepulse.core.SessionConfig(),
        )
        Presets.programs().forEach { program ->
            val plan = SessionPlanner.resolve(
                LibrarySelection(SelectionKind.PROGRAM, program.id),
                library,
                presetMenus = menus,
            )
            assertNotNull(plan, "program ${program.id} が解決できること")
            assertEquals(program.entries.size, plan.segments.size)
        }
    }

    @Test
    fun `Pyramid は量の上書きで 6 本 (2+2+2) になる`() {
        val library = LibraryDocument.initialFrom(io.github.wakuwaku3.adaptivepulse.core.SessionConfig())
        val plan = SessionPlanner.resolve(
            LibrarySelection(SelectionKind.PROGRAM, Presets.PROGRAM_PYRAMID),
            library,
            presetMenus = menus,
        )!!
        val intervalAmounts = plan.segments.filter { it.menu.kind is MenuKind.Interval }.map { it.amount }
        assertEquals(listOf(2, 2, 2), intervalAmounts)
    }

    @Test
    fun `preset id 判定`() {
        assertTrue(Presets.isPreset(Presets.MENU_4X4))
        assertTrue(Presets.isPreset(Presets.PROGRAM_STANDARD))
        kotlin.test.assertFalse(Presets.isPreset(LibraryDocument.HIIT_MENU_ID))
    }
}
