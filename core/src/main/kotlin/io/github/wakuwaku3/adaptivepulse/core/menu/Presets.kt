package io.github.wakuwaku3.adaptivepulse.core.menu

import io.github.wakuwaku3.adaptivepulse.core.HeartRateZones

/**
 * アプリ提供のプリセットメニュー/プログラム (docs/stock/requirements.md「プリセット」)。
 *
 * 強度は文献の表現 (%HRmax) で定義し、bpm はプロファイル (Tanaka 式 HRmax) から
 * 生成時に導出する (`.claude/rules/design-basis.md`: 個人実測から導出しない)。
 * 保存はせず毎回生成するため、年齢を変えると bpm も追従する。
 * プリセットは編集不可で、カスタム化は「複製 = 生成時点の bpm を初期値にした新規カスタム」で行う。
 */
object Presets {

    const val MENU_4X4 = "preset-4x4"
    const val MENU_MODERATE = "preset-moderate"
    const val MENU_WALKING = "preset-walking"
    const val MENU_WARMUP = "preset-warmup"

    const val PROGRAM_STANDARD = "preset-standard"
    const val PROGRAM_PYRAMID = "preset-pyramid"
    const val PROGRAM_HYBRID = "preset-hybrid"

    private const val PRESET_ID_PREFIX = "preset-"

    fun isPreset(id: String): Boolean = id.startsWith(PRESET_ID_PREFIX)

    fun menus(ageYears: Int): List<Menu> {
        fun bpm(pctOfMax: Double) = HeartRateZones.percentOfMax(ageYears, pctOfMax)
        return listOf(
            // ノルウェー式 4×4 (Helgerud et al. 2007): 4 分 @90-95% HRmax + 3 分 @70% HRmax ×4 の閾値駆動版
            Menu(
                id = MENU_4X4,
                name = "4x4",
                kind = MenuKind.Interval(
                    upperBpm = bpm(0.90),
                    lowerBpm = bpm(0.70),
                    cycles = 4,
                ),
            ),
            // ACSM の vigorous 下限 (77% HRmax) を挟んで上下する帯。刺激を保ちつつ関節負荷が軽い
            Menu(
                id = MENU_MODERATE,
                name = "Moderate",
                kind = MenuKind.Interval(
                    upperBpm = bpm(0.80),
                    lowerBpm = bpm(0.70),
                    cycles = 5,
                ),
            ),
            // WHO/ACSM の中強度 (64-76% HRmax) 週 150 分推奨帯。cadence 105 は
            // 「100 steps/min 以上 = moderate」の実測基準 (Tudor-Locke et al. 2018)
            Menu(
                id = MENU_WALKING,
                name = "Walking",
                kind = MenuKind.Timed(
                    upperBpm = bpm(0.75),
                    lowerBpm = bpm(0.64),
                    minutes = 30,
                    targetCadence = 105,
                ),
            ),
            // ACSM 推奨のウォームアップ 5-10 分 (moderate 未満の軽い有酸素)。下限なし
            Menu(
                id = MENU_WARMUP,
                name = "Warm-up",
                kind = MenuKind.Timed(
                    upperBpm = bpm(0.64),
                    lowerBpm = null,
                    minutes = 5,
                    targetCadence = 90,
                ),
            ),
        )
    }

    /** プリセットプログラム。hiit はライブラリ移行で必ず存在するメニューを参照する */
    fun programs(): List<Program> = listOf(
        Program(
            id = PROGRAM_STANDARD,
            name = "Standard",
            entries = listOf(
                ProgramEntry(MENU_WARMUP),
                ProgramEntry(LibraryDocument.HIIT_MENU_ID),
            ),
        ),
        Program(
            id = PROGRAM_PYRAMID,
            name = "Pyramid",
            entries = listOf(
                ProgramEntry(MENU_WARMUP),
                ProgramEntry(MENU_MODERATE, amountOverride = 2),
                ProgramEntry(MENU_4X4, amountOverride = 2),
                ProgramEntry(MENU_MODERATE, amountOverride = 2),
            ),
        ),
        Program(
            id = PROGRAM_HYBRID,
            name = "Hybrid",
            entries = listOf(
                ProgramEntry(MENU_WARMUP),
                ProgramEntry(LibraryDocument.HIIT_MENU_ID),
                ProgramEntry(MENU_WALKING, amountOverride = 20),
            ),
        ),
    )
}
