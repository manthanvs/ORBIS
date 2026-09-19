package com.orbis.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbis.app.dashboard.ReclaimedSummary
import com.orbis.app.surface.DetectedSurface
import com.orbis.app.surface.Surface
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.AppUsage
import com.orbis.app.usage.DurationFormatter
import com.orbis.app.usage.TargetApp
import com.orbis.app.usage.UsageProfile

/**
 * The plain-English home screen, and the one a first-time user lands on.
 *
 * The detailed screen is a dashboard: it assumes you already know what ORBIS is
 * doing and want the numbers. This one assumes nothing. It answers, in order, the
 * four questions somebody opening the app for the first time actually has: what
 * is this doing right now, what have I got out of it, how does it work, and what
 * is it *not* touching.
 *
 * Copy rules for anything added here:
 *
 * - No internal vocabulary. Not "tunnel", "throttle", "surface", "baseline",
 *   "accessibility service", "VPN". Those words are all correct and all useless
 *   to the person reading them; they live on Controls and About instead.
 * - One idea per card, and the card's first line says it.
 * - Setup is one step at a time with its position stated ("Step 1 of 3"), because
 *   two of the three permissions can only be granted out in Settings, and a user
 *   who does not know how many trips that takes assumes something is broken.
 * - Still never shame-based, exactly as on the detailed screen. See CLAUDE.md.
 */
@Composable
fun SimpleHomeScreen(
    state: HomeUiState,
    protection: ProtectionUiState,
    onGrantUsageAccess: () -> Unit,
    onEnableDetection: () -> Unit,
    onEnableThrottle: () -> Unit,
    onOpenDeeds: () -> Unit,
    onOpenAbout: () -> Unit,
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OrbisHomeHeader(simple = true, onModeChange = { simple -> if (!simple) onShowDetails() })

        BigStatusCard(
            protection = protection,
            onGrantUsageAccess = onGrantUsageAccess,
            onEnableDetection = onEnableDetection,
            onEnableThrottle = onEnableThrottle,
        )

        // Without usage access there is no number to show and never will be, so
        // the screen skips straight to explaining itself rather than displaying a
        // permanent zero.
        if (protection.hasUsageAccess) {
            TimeBackCard(summary = state.summary, loading = state.loading)
            WhereItWentCard(state.profile)
        }

        HowItWorksCard()
        SlowedOrNotCard()

        SimpleEarnCard(
            streak = state.streak,
            earnedToday = state.earnedToday,
            clearTimeMillis = state.clearTimeMillis,
            onOpenEarn = onOpenDeeds,
        )

        OutlinedButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) {
            Text("Read the full explainer")
        }

        Spacer(Modifier.height(8.dp))
    }
}

// -------------------------------------------------------------------- header

/**
 * Shared by both home screens, so the view switch sits in the same place on each
 * and reads as one control rather than two different ones.
 */
@Composable
internal fun OrbisHomeHeader(
    simple: Boolean,
    onModeChange: (Boolean) -> Unit,
    trailingLabel: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "ORBIS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            trailingLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ViewModeSwitch(simple = simple, onModeChange = onModeChange)
    }
}

/**
 * A two-option segmented control rather than a Material `Switch`.
 *
 * A switch labelled "Simple" is ambiguous when off: off could mean detailed, or
 * mean the app has been simplified away. Naming both states removes the guess.
 */
@Composable
private fun ViewModeSwitch(simple: Boolean, onModeChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ViewModeOption("Simple", selected = simple) { onModeChange(true) }
        ViewModeOption("Details", selected = !simple) { onModeChange(false) }
    }
}

@Composable
private fun ViewModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        label = "viewModeBackground",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "viewModeContent",
    )

    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

// -------------------------------------------------------------------- status

private enum class SimpleTone { WORKING, READY, SETUP }

private data class SimpleStatus(
    val headline: String,
    val body: String,
    val tone: SimpleTone,
    val stepLabel: String?,
    val buttonLabel: String?,
    val action: (() -> Unit)?,
)

/**
 * The single biggest thing on the screen: is ORBIS doing anything for me right now?
 *
 * The three setup branches each carry a step counter, so the trips out to Settings
 * and back have a visible end.
 */
@Composable
private fun BigStatusCard(
    protection: ProtectionUiState,
    onGrantUsageAccess: () -> Unit,
    onEnableDetection: () -> Unit,
    onEnableThrottle: () -> Unit,
) {
    val status = remember(protection, onGrantUsageAccess, onEnableDetection, onEnableThrottle) {
        protection.toSimpleStatus(onGrantUsageAccess, onEnableDetection, onEnableThrottle)
    }

    val container = when (status.tone) {
        SimpleTone.WORKING -> MaterialTheme.colorScheme.primaryContainer
        SimpleTone.READY -> MaterialTheme.colorScheme.secondaryContainer
        SimpleTone.SETUP -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val onContainer = when (status.tone) {
        SimpleTone.WORKING -> MaterialTheme.colorScheme.onPrimaryContainer
        SimpleTone.READY -> MaterialTheme.colorScheme.onSecondaryContainer
        SimpleTone.SETUP -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = onContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            status.stepLabel?.let { step ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // The status dot means "live". During setup nothing is live yet,
                // and the step counter carries the meaning instead.
                if (status.stepLabel == null) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(onContainer)
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = status.headline,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(text = status.body, style = MaterialTheme.typography.bodyLarge)

            val action = status.action
            if (status.buttonLabel != null && action != null) {
                Button(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text(status.buttonLabel)
                }
            }
        }
    }
}

private fun ProtectionUiState.toSimpleStatus(
    onGrantUsageAccess: () -> Unit,
    onEnableDetection: () -> Unit,
    onEnableThrottle: () -> Unit,
): SimpleStatus = when {
    !hasUsageAccess -> SimpleStatus(
        headline = "Let ORBIS see your screen time",
        body = "It needs to know how long you spend in Instagram, YouTube, Snapchat " +
            "and WhatsApp. Android only lets you allow this from Settings — the " +
            "button opens the right list, then find ORBIS in it and switch it on.",
        tone = SimpleTone.SETUP,
        stepLabel = "Step 1 of 3",
        buttonLabel = "Open Settings",
        action = onGrantUsageAccess,
    )

    !hasDetection -> SimpleStatus(
        headline = "Let ORBIS tell your screens apart",
        body = "This is how ORBIS knows Reels from a DM, so it slows the endless " +
            "scroll and never your conversations. Nothing it reads leaves your phone. " +
            "In the list that opens, look under Downloaded apps or Installed apps " +
            "for ORBIS surface detection.",
        tone = SimpleTone.SETUP,
        stepLabel = "Step 2 of 3",
        buttonLabel = "Open Settings",
        action = onEnableDetection,
    )

    !autoThrottle -> SimpleStatus(
        headline = "One switch to go",
        body = "Turn this on and Reels, Shorts and Spotlight will stall for a " +
            "moment every few seconds — on purpose. Everything else stays exactly as " +
            "fast as it is now. Android will ask whether ORBIS may set up a VPN: " +
            "that is how it adds the drag, and it never leaves your phone.",
        tone = SimpleTone.SETUP,
        stepLabel = "Step 3 of 3",
        buttonLabel = "Turn it on",
        action = onEnableThrottle,
    )

    // On, but Android no longer lets it act - usually because another VPN app
    // was switched on since, which quietly takes the slot. Saying "all set"
    // here would be the one lie this screen must never tell.
    !canSlow -> SimpleStatus(
        headline = "ORBIS needs its VPN back",
        body = "Android only lets one app use a VPN at a time, and ORBIS has lost " +
            "its turn — often because another VPN app was switched on. Until you " +
            "allow it again, nothing is being slowed.",
        tone = SimpleTone.SETUP,
        stepLabel = null,
        buttonLabel = "Allow again",
        action = onEnableThrottle,
    )

    tunnelRunning && detected.surface.throttled -> SimpleStatus(
        headline = "Slowing this down right now",
        body = "You are in ${detected.surface.friendlyName()}, so every few seconds " +
            "ORBIS makes it stall on purpose. Leave the feed and everything speeds " +
            "straight back up.",
        tone = SimpleTone.WORKING,
        stepLabel = null,
        buttonLabel = null,
        action = null,
    )

    else -> SimpleStatus(
        headline = "You are all set",
        body = "Everything is running at full speed. ORBIS only steps in when an " +
            "endless-scroll feed opens, and steps straight back out when you leave.",
        tone = SimpleTone.READY,
        stepLabel = null,
        buttonLabel = null,
        action = null,
    )
}

private fun Surface.friendlyName(): String = when (this) {
    Surface.REELS -> "Instagram Reels"
    Surface.SHORTS -> "YouTube Shorts"
    Surface.SPOTLIGHT -> "Snapchat Spotlight"
    Surface.BROWSER_SHORT_VIDEO -> "a short-video feed in your browser"
    Surface.NORMAL -> "a normal screen"
}

// ----------------------------------------------------------------- time back

/**
 * One number, and one sentence saying what it means.
 *
 * "Reclaimed" becomes "time you got back", and the comparison is spelled out in
 * words: a number with no stated comparison invites the reader to invent one,
 * usually a worse one.
 */
@Composable
private fun TimeBackCard(summary: ReclaimedSummary?, loading: Boolean) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                summary == null && loading ->
                    Text("Adding today up…", style = MaterialTheme.typography.bodyLarge)

                summary == null || summary.baselineMillis == null -> {
                    Text(
                        text = "Getting to know your week",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "ORBIS compares today with your own normal — not " +
                            "with anyone else's. Give it a few days of using your " +
                            "phone exactly as you already do, and a number appears here.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    summary?.todayMillis?.takeIf { it > 0L }?.let { today ->
                        Text(
                            // The whole app, DMs included - ORBIS measures time in
                            // Instagram, not time in Reels, so it must not claim to.
                            text = "Instagram, YouTube and Snapchat today: " +
                                DurationFormatter.format(today),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    Text(
                        text = "Time you got back today",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = DurationFormatter.format(summary.reclaimedTodayMillis),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = simpleEncouragement(summary),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/** Encouraging in every branch, including the heavier day. See CLAUDE.md. */
private fun simpleEncouragement(summary: ReclaimedSummary): String {
    val usual = DurationFormatter.format(summary.baselineMillis ?: 0L)
    val today = DurationFormatter.format(summary.todayMillis)
    return when {
        summary.reclaimedTodayMillis >= 30 * 60_000L ->
            "You normally spend about $usual a day on short videos. Today it is " +
                "$today. That is a real chunk of your day back."

        summary.reclaimedTodayMillis > 0L ->
            "Your usual day is about $usual of short videos. Today it is $today " +
                "— a bit less, and it adds up."

        else ->
            "A bigger day than your usual $usual. No big deal — tomorrow " +
                "starts fresh."
    }
}

// ------------------------------------------------------------- where it went

/**
 * The per-app split, without the chart.
 *
 * The detailed screen draws bars scaled against the heaviest app. Here it is a
 * plain list, because the job of this card in simple mode is to show that
 * WhatsApp is on the list and is never slowed, not to compare sizes.
 */
@Composable
private fun WhereItWentCard(profile: UsageProfile) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Where today went",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            if (profile.entries.isEmpty()) {
                Text(
                    text = "Nothing yet today. Open Instagram or YouTube for a minute " +
                        "and it turns up here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            profile.entries.forEach { entry -> SimpleUsageRow(entry) }
        }
    }
}

@Composable
private fun SimpleUsageRow(entry: AppUsage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = entry.app.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (entry.app.throttled) {
                    "only the short-video part is slowed"
                } else {
                    "never slowed"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = DurationFormatter.format(entry.durationMillis),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// -------------------------------------------------------------- how it works

@Composable
private fun HowItWorksCard() {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "How ORBIS works",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            HowStep(
                number = 1,
                title = "It times you, it does not watch you",
                body = "ORBIS counts how long each app is open. It never sees what " +
                    "you post, type or send.",
            )
            HowStep(
                number = 2,
                title = "It makes endless feeds stutter",
                body = "While Reels, Shorts or Spotlight is on screen, every five " +
                    "seconds the feed is squeezed for a moment, then let go. Enough " +
                    "to break the spell, never enough to break the app.",
            )
            HowStep(
                number = 3,
                title = "The more you scroll, the longer it stalls",
                body = "Each squeeze starts at a second and a half and grows to three " +
                    "as your short-video time adds up across every app - switching " +
                    "apps does not reset it. It never blocks anything.",
            )
            HowStep(
                number = 4,
                title = "It hands the time back to you",
                body = "The minutes you did not spend scrolling show up on this " +
                    "screen. And you can buy a feed back to full speed: a focus " +
                    "session or a good deed earns clear time, and clear time turns " +
                    "the drag off until it is spent.",
            )
        }
    }
}

@Composable
private fun HowStep(number: Int, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------ slowed or not

/**
 * The two lists, one under the other.
 *
 * This card exists because "an app that slows my phone down" is the fear people
 * arrive with, and the honest answer is a short, specific list of four things.
 */
@Composable
private fun SlowedOrNotCard() {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "What actually gets slowed",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            ListBlock(
                heading = "Slowed a little",
                items = listOf(
                    "Instagram Reels",
                    "YouTube Shorts",
                    "Snapchat Spotlight",
                    "Those same feeds opened in a browser",
                ),
                accent = MaterialTheme.colorScheme.primary,
            )

            ListBlock(
                heading = "Never touched",
                items = listOf(
                    "WhatsApp — all of it, always full speed",
                    "DMs, chats and calls in any app",
                    "Instagram Stories and your normal feed",
                    "Normal-length YouTube videos and music",
                    "Games, school work, and every other app on your phone",
                ),
                accent = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun ListBlock(heading: String, items: List<String>, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Text(text = item, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// -------------------------------------------------------------------- streak

/**
 * The side quest, as the home screen shows it: what clear time is left, and
 * the way to earn more.
 */
@Composable
private fun SimpleEarnCard(
    streak: Int,
    earnedToday: Boolean,
    clearTimeMillis: Long,
    onOpenEarn: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    clearTimeMillis > 0L ->
                        "${DurationFormatter.format(clearTimeMillis)} of clear time left"
                    streak <= 0 -> "Earn some clear time"
                    streak == 1 -> "1 day of earning"
                    else -> "$streak days of earning in a row"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when {
                    clearTimeMillis > 0L ->
                        "Your feeds run at full speed until it is spent. It resets tonight."
                    earnedToday -> "Earned and spent today. Streak safe."
                    else -> "Fifteen minutes without a feed, or one small kind thing, " +
                        "buys your feeds back to full speed for a while."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            FilledTonalButton(onClick = onOpenEarn, modifier = Modifier.fillMaxWidth()) {
                Text(if (earnedToday) "Earn more" else "Start earning")
            }
        }
    }
}

// ------------------------------------------------------------------ previews

@Preview(showBackground = true, heightDp = 2000)
@Composable
private fun SimpleHomePreview() {
    OrbisTheme {
        SimpleHomeScreen(
            state = HomeUiState(
                loading = false,
                streak = 3,
                summary = ReclaimedSummary(
                    todayMillis = 22 * 60_000L,
                    baselineMillis = 61 * 60_000L,
                    reclaimedTodayMillis = 39 * 60_000L,
                    reclaimedWeekMillis = 96 * 60_000L,
                    week = emptyList(),
                ),
                profile = UsageProfile(
                    listOf(
                        AppUsage(TargetApp.INSTAGRAM, 3_720_000L),
                        AppUsage(TargetApp.WHATSAPP, 240_000L),
                    )
                ),
            ),
            protection = ProtectionUiState(
                hasUsageAccess = true,
                hasDetection = true,
                autoThrottle = true,
                tunnelRunning = true,
                delayMillis = 340L,
                detected = DetectedSurface(Surface.REELS, TargetApp.INSTAGRAM.packageName, 0L),
            ),
            onGrantUsageAccess = {},
            onEnableDetection = {},
            onEnableThrottle = {},
            onOpenDeeds = {},
            onOpenAbout = {},
            onShowDetails = {},
        )
    }
}
