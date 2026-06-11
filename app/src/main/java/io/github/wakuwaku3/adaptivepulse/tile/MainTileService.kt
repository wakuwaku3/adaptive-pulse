package io.github.wakuwaku3.adaptivepulse.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.wakuwaku3.adaptivepulse.MainActivity

private const val RESOURCES_VERSION = "1"

/**
 * ウォッチフェイスから 1 スワイプ → 1 タップでセッションを開始するタイル。
 * 毎朝のジムで起動操作の摩擦を最小にする。タップは MainActivity を
 * start_session extra 付きで起動し、許可済みなら即計測が始まる。
 */
class MainTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val launchAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(
                        MainActivity.EXTRA_START_SESSION,
                        ActionBuilders.booleanExtra(true),
                    )
                    .build(),
            )
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("start")
                            .setOnClick(launchAction)
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        Text.Builder(this, "ADAPTIVE PULSE")
                            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                            .setColor(argb(0xFF7C8694.toInt()))
                            .build(),
                    )
                    .addContent(
                        Text.Builder(this, "▶ START")
                            .setTypography(Typography.TYPOGRAPHY_TITLE1)
                            .setColor(argb(Colors.DEFAULT.primary))
                            .build(),
                    )
                    .build(),
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder().setRoot(root).build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build(),
        )
}
