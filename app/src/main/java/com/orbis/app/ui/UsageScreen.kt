package com.orbis.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.AppUsage
import com.orbis.app.usage.DurationFormatter
import com.orbis.app.usage.TargetApp
import com.orbis.app.usage.UsageProfile

@Composable
fun UsageScreen(
    state: UsageUiState,
    onGrantAccess: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Today's screen time",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        if (!state.hasUsageAccess) {
            UsageAccessPrompt(onGrantAccess = onGrantAccess)
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

        Spacer(Modifier.height(8.dp))

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
    }
}

@Composable
private fun UsageAccessPrompt(onGrantAccess: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "ORBIS needs usage access to measure your screen time.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                // Be honest that this is a two-step flow: the system page opens on
                // a list, not on our toggle.
                text = "This one can't be granted with a normal permission prompt. " +
                    "The Settings list will open — find ORBIS in it and turn " +
                    "usage access on.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onGrantAccess) {
                Text("Open Settings")
            }
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
        Text(
            text = entry.app.displayName,
            style = MaterialTheme.typography.bodyLarge,
        )
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
            onGrantAccess = {},
            onRefresh = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UsageAccessPromptPreview() {
    OrbisTheme {
        UsageScreen(
            state = UsageUiState(hasUsageAccess = false),
            onGrantAccess = {},
            onRefresh = {},
        )
    }
}
