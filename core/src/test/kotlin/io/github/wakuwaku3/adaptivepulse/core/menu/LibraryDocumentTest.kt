package io.github.wakuwaku3.adaptivepulse.core.menu

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class LibraryDocumentTest {

    @Test
    fun `initialFrom - 既存の単一設定が hiit メニューとして移行され、初期選択になる`() {
        val config = SessionConfig(
            upperBpm = 159,
            lowerBpm = 136,
            targetCycles = 6,
            targetCadenceHigh = 150,
            targetCadenceRecovery = 80,
        )
        val library = LibraryDocument.initialFrom(config)

        val hiit = library.menu(LibraryDocument.HIIT_MENU_ID)!!
        val kind = hiit.kind as MenuKind.Interval
        assertEquals("hiit", hiit.name)
        assertEquals(159, kind.upperBpm)
        assertEquals(136, kind.lowerBpm)
        assertEquals(6, kind.cycles)
        assertEquals(150, kind.targetCadenceHigh)
        assertEquals(80, kind.targetCadenceRecovery)

        assertEquals(SelectionKind.MENU, library.selection.kind)
        assertEquals(LibraryDocument.HIIT_MENU_ID, library.selection.id)
        // どの端末のどんな実編集にも LWW で負けるよう 0
        assertEquals(0L, library.updatedAtMs)
    }

    @Test
    fun `JSON 往復 - メニュー 2 型とプログラムがそのまま戻る`() {
        val json = Json { ignoreUnknownKeys = true }
        val library = LibraryDocument(
            menus = listOf(
                Menu("m1", "Custom Interval", MenuKind.Interval(160, 130, 3)),
                Menu("m2", "Custom Walk", MenuKind.Timed(130, 110, 25, targetCadence = 100)),
                Menu("m3", "No Floor", MenuKind.Timed(120, null, 10)),
            ),
            programs = listOf(
                Program("p1", "My Program", listOf(ProgramEntry("m1", 2), ProgramEntry("m2"))),
            ),
            selection = LibrarySelection(SelectionKind.PROGRAM, "p1"),
            updatedAtMs = 42L,
            updatedBy = "phone",
        )

        val decoded = json.decodeFromString<LibraryDocument>(json.encodeToString(LibraryDocument.serializer(), library))
        assertEquals(library, decoded)
        assertNull((decoded.menu("m3")!!.kind as MenuKind.Timed).lowerBpm)
    }
}
