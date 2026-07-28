package com.orbis.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.orbis.app.surface.DetectedSurface
import com.orbis.app.surface.Surface
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.AppUsage
import com.orbis.app.usage.DurationFormatter
import com.orbis.app.usage.TargetApp
import com.orbis.app.usage.UsageProfile

@Composable
fun UsageScreen(
    state: UsageUiState,
    detected: DetectedSurface,
    hasDetection: Boolean,
    onGrantUsageAccess: () -> Unit,
    onEnableDetection: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Today's screen time",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        if (!state.hasUsageAccess) {
            PermissionCard(
                title = "ORBIS needs usage access to measure your screen time.",
                body = "This one can't be granted with a normal permission prompt. " +
                    "The Settings list will open — find ORBIS in it and turn usage " +
                    "access on.",
                buttonText = "Open Settings",
                onClick = onGrantUsageAccess,
            )
            return@Column
        }

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.profile.entries.isEmpty() && !state.loading) {
            Text(
                text = "Nothing tracked yet today. Open one of your apps for a " +
                    "moment, then refresh.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            UsageList(profile = state.profile)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
                Text("Refresh")
            }
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        Text(
            text = "Surface detection",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        if (!hasDetection) {
            PermissionCard(
                title = "Turn on ORBIS surface detection to tell Reels from Stories.",
                body = "Accessibility settings will open — pick \"ORBIS surface " +
                    "detection\" and switch it on. It only ever reads the apps " +
                    "ORBIS targets, and nothing leaves your phone.",
                buttonText = "Open Accessibility Settings",
                onClick = onEnableDetection,
            )
        } else {
            DetectionCard(detected)
        }
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
            )
            Text(
                text = if (detected.surface.throttled) {
                    "Would be slowed (Phase 2c)"
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
    Card(colors = CardDefaults.cardColors()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick) { Text(buttonText) }
        }
    }
}

@Composable
private fun UsageList(profile: UsageProfile) {
    Card {
        Column(modifier = Modifier.fillMaxWidth()) {
            profile.entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider()
                UsageRow(entry)
            }
        }
    }
}

@Composable
private fun UsageRow(entry: AppUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = entry.app.displayName, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = DurationFormatter.format(entry.durationMillis),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UsageScreenPreview() {
    OrbisTheme {
        UsageScreen(
            state = UsageUiState(
                hasUsageAccess = true,
                profile = UsageProfile(
                    listOf(
                        AppUsage(TargetApp.INSTAGRAM, 3_720_000L),
                        AppUsage(TargetApp.YOUTUBE, 1_500_000L),
                        AppUsage(TargetApp.WHATSAPP, 240_000L),
                    )
                ),
            ),
            detected = DetectedSurface(Surface.REELS, "com.instagram.android", 0L),
            hasDetection = true,
            onGrantUsageAccess = {},
            onEnableDetection = {},
            onRefresh = {},
        )
    }
}
