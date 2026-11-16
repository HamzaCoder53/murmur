package com.bitchat.watch.ui

import com.bitchat.watch.R
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.bitchat.watch.link.LinkMode
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.rendezvous.RendezvousEngine
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the watch's radio is doing right now, as label/value rows. Release builds have no debug
 * hooks, so this is how the wearer (and anyone they report to) can see whether the watch is
 * tethered, when it will next scan, and whether the OS is letting it keep time.
 */
object LinkDiagnostics {

    data class Line(val label: String, val value: String)

    /** [text] resolves a string resource, so the rows follow the watch's language. */
    fun lines(
        mode: LinkMode,
        status: RendezvousEngine.Status,
        clockOffsetMs: Long,
        text: (Int) -> String,
        formatTime: (Long) -> String
    ): List<Line> {
        fun line(label: Int, value: String) = Line(text(label), value)
        if (mode == LinkMode.TETHERED) {
            return listOf(
                line(R.string.diag_link, text(R.string.diag_phone)),
                line(R.string.diag_scan, text(R.string.diag_scan_off))
            )
        }
        val lines = ArrayList<Line>()
        lines += line(R.string.diag_link, text(R.string.diag_own_radio))
        lines += line(R.string.diag_scan, text(scanLabel(status)))
        status.nextWindowWallMs?.let { lines += line(R.string.diag_next_scan, formatTime(it)) }
        if (status.phase != RendezvousEngine.Phase.STOPPED &&
            status.phase != RendezvousEngine.Phase.FOREGROUND
        ) {
            lines += line(
                R.string.diag_timing,
                text(if (status.preciseTiming) R.string.diag_precise else R.string.diag_approximate)
            )
        }
        status.lastWindowSawPeer?.let {
            lines += line(R.string.diag_last_scan, text(if (it) R.string.diag_peer_seen else R.string.diag_no_one))
        }
        if (status.windowsCompleted > 0) {
            lines += line(R.string.diag_scans_done, String.format(Locale.getDefault(), "%d", status.windowsCompleted))
        }
        lines += line(R.string.diag_clock_offset, formatOffset(clockOffsetMs))
        return lines
    }

    private fun scanLabel(status: RendezvousEngine.Status): Int = when (status.phase) {
        RendezvousEngine.Phase.STOPPED -> R.string.diag_bluetooth_off
        RendezvousEngine.Phase.FOREGROUND -> R.string.diag_active_app_open
        RendezvousEngine.Phase.WINDOW_OPEN -> R.string.diag_scanning_now
        RendezvousEngine.Phase.WAITING -> R.string.diag_synced
    }

    fun formatOffset(offsetMs: Long): String {
        val sign = if (offsetMs < 0) "-" else "+"
        val abs = kotlin.math.abs(offsetMs)
        return "$sign${abs / 1000}.${(abs % 1000) / 100} s"
    }

    /** Whether the wearer should be offered the system screen that grants exact alarms. */
    fun shouldOfferPreciseTiming(mode: LinkMode, status: RendezvousEngine.Status): Boolean =
        mode == LinkMode.STANDALONE &&
            status.phase == RendezvousEngine.Phase.WAITING &&
            !status.preciseTiming

    /**
     * The system screen that grants exact alarms. Deliberately not pre-checked with
     * resolveActivity: package visibility rules would hide the settings app from that query and
     * the offer would never appear. Callers launch it and tolerate it being absent.
     */
    fun preciseTimingIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${context.packageName}"))
}

/** Appends the link status rows to a watch list. */
fun ScalingLazyListScope.linkStatusSection(mesh: WearMeshService?) {
    if (mesh == null) return
    item(key = "link-status-header") {
        ListHeader {
            Text(
                text = stringResource(R.string.diag_link),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
    item(key = "link-status") { LinkStatusRows(mesh) }
}

@Composable
private fun LinkStatusRows(mesh: WearMeshService) {
    val mode by mesh.linkMode.collectAsState()
    val status by mesh.rendezvousStatus.collectAsState()
    val palette = LocalBitchatPalette.current
    val context = LocalContext.current
    val timeFormat = androidx.compose.runtime.remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val resources = context.resources
    val lines = LinkDiagnostics.lines(mode, status, mesh.meshClock.offsetMs, resources::getString) {
        timeFormat.format(Date(it))
    }

    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
        lines.forEach { line ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = line.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
                Text(
                    text = line.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End
                )
            }
        }
        if (LinkDiagnostics.shouldOfferPreciseTiming(mode, status)) {
            var unavailable by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(false)
            }
            Text(
                // Some watches ship no screen for this; say so instead of failing silently.
                text = stringResource(if (unavailable) R.string.diag_precise_unavailable else R.string.diag_allow_precise),
                style = MaterialTheme.typography.bodySmall,
                color = if (unavailable) palette.textTertiary else MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !unavailable) {
                        try {
                            context.startActivity(LinkDiagnostics.preciseTimingIntent(context))
                        } catch (_: ActivityNotFoundException) {
                            unavailable = true
                        } catch (_: SecurityException) {
                            unavailable = true
                        }
                    }
                    .padding(vertical = 8.dp)
            )
        }
    }
}
