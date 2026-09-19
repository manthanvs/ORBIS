package com.orbis.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orbis.app.surface.DetectedSurface
import com.orbis.app.surface.Surface
import com.orbis.app.throttle.TcpFallback
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.TargetApp
import com.orbis.app.vpn.TunnelStats

/**
 * The switches and the diagnostics.
 *
 * Deliberately not a second dashboard: what the user's time went on is on the
 * home screen, and duplicating it here meant two screens re-querying the same
 * day and disagreeing with each other whenever one refreshed first.
 */
@Composable
fun ControlsScreen(
    protection: ProtectionUiState,
    tunnel: TunnelStats,
    onEnableDetection: () -> Unit,
    onToggleTunnel: () -> Unit,
    onAutoThrottleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Controls",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        SectionTitle("Surface detection")

        if (!protection.hasDetection) {
            PermissionCard(
                title = "Turn on ORBIS surface detection to tell Reels from Stories.",
                body = "Accessibility settings will open — pick \"ORBIS surface " +
                    "detection\" and switch it on. It only ever reads the apps " +
                    "ORBIS targets, and nothing leaves your phone.",
                buttonText = "Open Accessibility Settings",
                onClick = onEnableDetection,
            )
        } else {
            DetectionCard(protection.detected)
        }

        HorizontalDivider()

        SectionTitle("Tunnel")
        TunnelCard(
            tunnel = tunnel,
            // Off when Android has withdrawn the VPN, so the switch never claims a
            // state ORBIS cannot act on - and tapping it asks for consent again.
            autoThrottle = protection.autoThrottle && protection.canSlow,
            onToggle = onToggleTunnel,
            onAutoThrottleChange = onAutoThrottleChange,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun TunnelCard(
    tunnel: TunnelStats,
    autoThrottle: Boolean,
    onToggle: () -> Unit,
    onAutoThrottleChange: (Boolean) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Status", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (tunnel.running) "running" else "stopped",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (tunnel.running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Text(
                text = "Only the one app whose short-form feed is on screen is " +
                    "routed, so slowing Reels leaves YouTube and your browser " +
                    "alone. WhatsApp is never routed at all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 12.dp)) {
                    Text(
                        text = "Slow Reels/Shorts automatically",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "ORBIS raises the tunnel only while a short-form feed " +
                            "is on screen, and drops it again afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoThrottle, onCheckedChange = onAutoThrottleChange)
            }

            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                Text(if (tunnel.running) "Stop tunnel" else "Run a 30-second test")
            }

            // The manual path routes every app ORBIS will ever route, not just the
            // one on screen, so it says so rather than looking like the real thing.
            Text(
                text = "The test routes all target apps at once for 30 seconds, then " +
                    "stops itself. It is for checking the relay, not for everyday " +
                    "use - leave the switch above to do that.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Diagnostics(tunnel)
        }
    }
}

/**
 * Surfaced in the UI rather than logged: ColorOS silently drops this app's
 * logcat output, and a tunnel that is blackholing traffic looks exactly like one
 * that is working until you can see whether anything is being forwarded.
 */
@Composable
private fun Diagnostics(tunnel: TunnelStats) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DiagnosticRow("read", tunnel.packetsRead.toString())
        DiagnosticRow("UDP forwarded", tunnel.udpForwarded.toString())
        DiagnosticRow("TCP dropped", tunnel.tcpDropped.toString())
        DiagnosticRow("shed (back-pressure)", tunnel.packetsDropped.toString())
        DiagnosticRow("open flows", tunnel.activeFlows.toString())
        DiagnosticRow(
            "pulse",
            if (tunnel.pulsePeriodMillis > 0L) {
                "${tunnel.squeezeMillis}ms of every ${tunnel.pulsePeriodMillis}ms"
            } else {
                "off"
            },
        )
        DiagnosticRow("downloaded", "${tunnel.bytesIn / 1024} KB")
        DiagnosticRow("held back by squeeze", tunnel.packetsPoliced.toString())
        DiagnosticRow("delay (while squeezed)", "${tunnel.delayMillis}ms")
        DiagnosticRow("routing", routedLabel(tunnel.routed))
        StoodDownRows()
        Text(
            text = tunnel.status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Apps ORBIS has stood down for because they moved to TCP, with the minutes
 * left. Said plainly, because "why is Morphe not slowed?" deserves an answer.
 */
@Composable
private fun StoodDownRows() {
    val standingDown by TcpFallback.standingDown.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    standingDown.filterValues { it > now }.forEach { (packageName, until) ->
        DiagnosticRow(
            "left alone (on TCP)",
            "${routedLabel(listOf(packageName))} · ${(until - now) / 60_000L + 1}m",
        )
    }
}

/**
 * What the tunnel is carrying, named so the per-app routing is verifiable from
 * the screen rather than only from `dumpsys connectivity`.
 */
private fun routedLabel(routed: List<String>): String = when {
    routed.isEmpty() -> "nothing"
    else -> routed.joinToString {
        TargetApp.fromPackage(it)?.displayName ?: it.substringAfterLast('.')
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DetectionCard(detected: DetectedSurface) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Current surface", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = detected.surface.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = detected.packageName ?: "waiting for a tracked app…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (detected.surface.throttled) {
                    "Slowed while it is on screen"
                } else {
                    "Left at full speed"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onClick) { Text(buttonText) }
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun ControlsPreview() {
    OrbisTheme {
        ControlsScreen(
            protection = ProtectionUiState(
                hasUsageAccess = true,
                hasDetection = true,
                autoThrottle = true,
                tunnelRunning = true,
                detected = DetectedSurface(Surface.REELS, TargetApp.INSTAGRAM.packageName, 0L),
            ),
            tunnel = TunnelStats(
                running = true,
                packetsRead = 1_138,
                udpForwarded = 1_045,
                tcpDropped = 77,
                activeFlows = 12,
                delayMillis = 340,
                status = "relay running, delay 340ms",
            ),
            onEnableDetection = {},
            onToggleTunnel = {},
            onAutoThrottleChange = {},
        )
    }
}
