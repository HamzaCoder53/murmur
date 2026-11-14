package com.bitchat.watch.glance

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.MainActivity
import com.bitchat.watch.link.LinkMode
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.WearChatState
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What the tile and complication show, read from the running app. */
data class GlanceState(val unread: Int, val nearby: Int, val link: String) {
    companion object {
        fun current(): GlanceState {
            val mesh = WearMeshService.peek()
            val me = mesh?.myPeerID
            return GlanceState(
                unread = WearChatState.unreadDms.value.values.sum(),
                nearby = AppStateStore.peers.value.count { it != me },
                link = when {
                    mesh == null || !mesh.isRunning() -> "Off"
                    mesh.linkMode.value == LinkMode.TETHERED -> "Phone"
                    else -> "Own radio"
                }
            )
        }
    }

    val unreadLabel: String get() = when (unread) {
        0 -> "No new messages"
        1 -> "1 new message"
        else -> "${if (unread > 99) "99+" else unread} new messages"
    }
}

private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

/** Tile: unread count, people nearby and link, one tap from the watch face. */
class MurmurTileService : TileService() {
    private companion object {
        const val RESOURCES_VERSION = "1"
        const val GREEN = 0xFF32D74B.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFFAAAAAA.toInt()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            val state = GlanceState.current()
            completer.set(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES_VERSION)
                    // Pushed when something changes (see GlanceUpdater); this is only a fallback.
                    .setFreshnessIntervalMillis(15 * 60_000L)
                    .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(state)))
                    .build()
            )
            "murmur-tile"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = CallbackToFutureAdapter.getFuture { completer ->
        completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
        "murmur-tile-resources"
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(sizeSp))
                    .setColor(argb(color))
                    .setWeight(
                        if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                    )
                    .build()
            )
            .build()

    private fun spacer(heightDp: Float) =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(heightDp)).build()

    private fun layout(state: GlanceState): LayoutElementBuilders.LayoutElement {
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("Skein", 14f, GREEN, bold = true))
            .addContent(spacer(6f))
            .addContent(text(if (state.unread > 99) "99+" else state.unread.toString(), 40f, WHITE, bold = true))
            .addContent(text(if (state.unread == 1) "new message" else "new messages", 13f, GREY))
            .addContent(spacer(8f))
            .addContent(text("${state.nearby} nearby · ${state.link}", 13f, GREY))
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(column)
            .build()
    }
}

/** Watch-face complication: number of unread private messages. */
class MurmurComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        if (request.complicationType == ComplicationType.SHORT_TEXT) build(GlanceState.current()) else null

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) build(GlanceState(unread = 2, nearby = 3, link = "Phone")) else null

    private fun build(state: GlanceState): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(if (state.unread > 99) "99+" else state.unread.toString()).build(),
            contentDescription = PlainComplicationText.Builder("Skein: ${state.unreadLabel}").build()
        )
            .setTitle(PlainComplicationText.Builder("Skein").build())
            .setTapAction(openAppIntent(this))
            .build()
}

/** Pushes fresh data to the tile and complication whenever what they show changes. */
object GlanceUpdater {
    private var job: Job? = null

    @OptIn(FlowPreview::class)
    fun start(context: Context, scope: CoroutineScope, mesh: WearMeshService) {
        if (job != null) return
        val app = context.applicationContext
        job = scope.launch {
            combine(WearChatState.unreadDms, AppStateStore.peers, mesh.linkMode) { _, _, _ -> GlanceState.current() }
                .distinctUntilChanged()
                .debounce(1_500)
                .collect { push(app) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun push(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(MurmurTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, MurmurComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
