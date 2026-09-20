package com.orbis.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.orbis.app.earn.ClearTime
import com.orbis.app.earn.EarnAction
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.DurationFormatter

/**
 * The earn-back side quest.
 *
 * The framing is the feature. ORBIS never blocks a feed - it makes one slower,
 * and this is where the user buys it back. Restriction on its own reads as
 * something being taken away, and people push back against that harder than they
 * push back against the habit; an earn-back loop keeps the lever in their hands
 * and turns the friction into a price rather than a punishment.
 *
 * So the copy here never scolds and never gates. It says what things cost and
 * what they pay, and lets the user decide.
 */
@Composable
fun EarnScreen(
    state: EarnUiState,
    onStartFocus: () -> Unit,
    onCancelFocus: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // The manifest entry alone is not enough on 13+: the worker's own permission
    // check fails silently and the prompt simply never arrives.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Earn",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        item { BalanceCard(state) }

        state.message?.let { message ->
            item { MessageCard(message = message, onDismiss = onDismissMessage) }
        }

        item {
            if (state.focusRemainingMillis != null) {
                FocusRunningCard(
                    remainingMillis = state.focusRemainingMillis,
                    onCancel = onCancelFocus,
                )
            } else {
                ActionCard(
                    action = EarnAction.FOCUS_SESSION,
                    remainingUses = state.remainingUses[EarnAction.FOCUS_SESSION] ?: 0,
                    buttonText = "Start ${EarnAction.FOCUS_DURATION_MILLIS / 60_000L} minutes",
                    onClick = onStartFocus,
                )
            }
        }

        item { StreakCard(streak = state.streak) }
    }
}

/**
 * The balance, stated as what it buys rather than as a number of points.
 *
 * "18 minutes of clear time" is a thing the user can picture spending. A score is
 * not, and a score is what turns an earn-back loop into a game to be farmed.
 */
@Composable
private fun BalanceCard(state: EarnUiState) {
    Card(colors = CardDefaults.elevatedCardColors()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Clear time left today",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = DurationFormatter.format(state.remainingMillis),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (state.remainingMillis > 0L) {
                    "Reels, Shorts and Spotlight run at full speed until this " +
                        "runs out. It resets tonight, so there is nothing to save up."
                } else {
                    "Short-form feeds are running slowly. Earn some clear time " +
                        "below to bring them back up to speed."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.balance.earnedMillis > 0L) {
                Text(
                    text = "Earned ${DurationFormatter.format(state.balance.earnedMillis)} " +
                        "today, spent ${DurationFormatter.format(state.balance.spentMillis)}. " +
                        "The daily ceiling is " +
                        "${DurationFormatter.format(ClearTime.DAILY_CAP_MILLIS)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    action: EarnAction,
    remainingUses: Int,
    buttonText: String,
    onClick: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = action.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "+${action.rewardMillis / 60_000L} min",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = action.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = if (remainingUses > 0) {
                    "$remainingUses left today"
                } else {
                    "Done for today - back tomorrow"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onClick,
                enabled = remainingUses > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(buttonText)
            }
        }
    }
}

/**
 * A running session, showing what is left rather than what has elapsed - the
 * remaining number is the one that makes finishing feel close.
 */
@Composable
private fun FocusRunningCard(remainingMillis: Long, onCancel: () -> Unit) {
    Card(colors = CardDefaults.elevatedCardColors()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Focus session running",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = DurationFormatter.format(remainingMillis),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            val progress = 1f -
                (remainingMillis.toFloat() / EarnAction.FOCUS_DURATION_MILLIS.toFloat())
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                // The countdown beside it already says this, and twice is noise.
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {},
            )

            Text(
                text = "Put the phone down, or use any other app - it keeps " +
                    "counting, and ORBIS will tell you when it is done. Only " +
                    "opening Reels, Shorts or Spotlight ends it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Stop session")
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun StreakCard(streak: Int) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (streak > 0) "$streak-day streak" else "No streak yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = if (streak > 0) {
                    "Earn something tomorrow to keep it going."
                } else {
                    "Earn clear time on two days in a row to start one."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EarnScreenPreview() {
    OrbisTheme {
        EarnScreen(
            state = EarnUiState(
                remainingMillis = 12 * 60_000L,
                streak = 4,
                remainingUses = mapOf(EarnAction.FOCUS_SESSION to 4),
            ),
            onStartFocus = {},
            onCancelFocus = {},
            onDismissMessage = {},
        )
    }
}
