package com.orbis.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.orbis.app.dashboard.DayTotal
import com.orbis.app.dashboard.ReclaimedSummary
import com.orbis.app.surface.DetectedSurface
import com.orbis.app.surface.Surface
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.AppUsage
import com.orbis.app.usage.DurationFormatter
import com.orbis.app.usage.TargetApp
import com.orbis.app.usage.UsageProfile
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val HEADER_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** Below this a bar chart has nothing to show a reader that a number does not. */
private const val MIN_CHART_DAYS = 2

/** Keeps two or three bars looking like bars instead of stretching into slabs. */
private val MAX_BAR_WIDTH = 56.dp

/**
 * The home screen.
 *
 * Ordered by what the user came to find out: whether ORBIS is working right now,
 * then what it has won back, then where the time actually went, then the streak.
 *
 * Everything on it is read-only apart from the permission prompts. Turning the
 * tunnel on and off, and the packet counters that go with it, live on Controls -
 * a home screen that changes several times a second is a home screen nobody can
 * read.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    protection: ProtectionUiState,
    today: LocalDate,
    onGrantUsageAccess: () -> Unit,
    onEnableDetection: () -> Unit,
    onEnableThrottle: () -> Unit,
    onOpenDeeds: () -> Unit,
    onShowSimple: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Same header component as simple mode, so the switch back is in the
        // same place on both screens.
        OrbisHomeHeader(
            simple = false,
            onModeChange = { simple -> if (simple) onShowSimple() },
            trailingLabel = HEADER_DATE.format(today),
        )

        ProtectionCard(
            protection = protection,
            onGrantUsageAccess = onGrantUsageAccess,
            onEnableDetection = onEnableDetection,
            onEnableThrottle = onEnableThrottle,
        )

        // Without usage access nothing below will ever load, so the screen stops
        // here rather than spinning indefinitely. Reinstalling the app revokes
        // this permission, which is exactly how that dead end gets reached.
        if (!protection.hasUsageAccess) return@Column

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val summary = state.summary
        when {
            summary == null && state.loading -> LoadingCard()
            summary == null -> Text(
                text = "No data yet.",
                style = MaterialTheme.typography.bodyMedium,
            )

            summary.baselineMillis == null -> StillLearningCard(daysSoFar = summary.week.size)
            else -> ReclaimedHero(summary)
        }

        // One point is not a trend. A lone bar fills the plot and reads as a solid
        // block, so below two days the chart is omitted entirely rather than drawn
        // misleadingly - the card above already explains why there is no history.
        if (summary != null) {
            if (summary.week.size >= MIN_CHART_DAYS) {
                WeekChartCard(summary)
            } else if (summary.todayMillis > 0L) {
                TodayOnlyCard(summary.todayMillis)
            }
        }

        TodaySplitCard(state.profile)

        StreakTeaser(
            streak = state.streak,
            earnedToday = state.earnedToday,
            clearTimeMillis = state.clearTimeMillis,
            onOpenEarn = onOpenDeeds,
        )

        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------ protection

/**
 * What ORBIS is doing right now, and the one thing to fix if it is not doing it.
 *
 * Deliberately shows a single next step rather than a checklist of three: the
 * permissions have to be granted in order anyway, and three simultaneous prompts
 * on the home screen reads as nagging.
 */
@Composable
private fun ProtectionCard(
    protection: ProtectionUiState,
    onGrantUsageAccess: () -> Unit,
    onEnableDetection: () -> Unit,
    onEnableThrottle: () -> Unit,
) {
    val status = remember(protection) { protection.toStatus() }
    val dotColor by animateColorAsState(
        targetValue = when (status.tone) {
            Tone.ACTIVE -> MaterialTheme.colorScheme.primary
            Tone.READY -> MaterialTheme.colorScheme.secondary
            Tone.SETUP -> MaterialTheme.colorScheme.tertiary
        },
        label = "protectionDot",
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = status.headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = status.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            status.action?.let { action ->
                Button(
                    onClick = when (action) {
                        Action.USAGE_ACCESS -> onGrantUsageAccess
                        Action.DETECTION -> onEnableDetection
                        Action.THROTTLE -> onEnableThrottle
                    }
                ) {
                    Text(action.label)
                }
            }
        }
    }
}

private enum class Tone { ACTIVE, READY, SETUP }

private enum class Action(val label: String) {
    USAGE_ACCESS("Open Settings"),
    DETECTION("Open Accessibility Settings"),
    THROTTLE("Turn on auto-slowing"),
}

private data class ProtectionStatus(
    val headline: String,
    val detail: String,
    val tone: Tone,
    val action: Action?,
)

/**
 * Copy stays encouraging in every branch, including the ones where something is
 * switched off - see the dashboard invariant in CLAUDE.md. Nothing here scolds.
 */
private fun ProtectionUiState.toStatus(): ProtectionStatus = when {
    !hasUsageAccess -> ProtectionStatus(
        headline = "Usage access is off",
        detail = "ORBIS measures your screen time against your own recent average. " +
            "This one can't be granted with a normal prompt — the Settings list " +
            "opens, and you pick ORBIS from it.",
        tone = Tone.SETUP,
        action = Action.USAGE_ACCESS,
    )

    !hasDetection -> ProtectionStatus(
        headline = "Detection is off",
        detail = "Turn on surface detection so ORBIS can tell Reels from Stories. " +
            "It only ever reads the apps ORBIS targets, and nothing leaves your phone. " +
            "Look under Downloaded apps or Installed apps in the list that opens.",
        tone = Tone.SETUP,
        action = Action.DETECTION,
    )

    !autoThrottle -> ProtectionStatus(
        headline = "Ready when you are",
        detail = "Everything's set up. Switch on auto-slowing and ORBIS will add a " +
            "stutter to Reels, Shorts and Spotlight — and nothing else. " +
            "Android will ask to allow a VPN; it stays on your phone.",
        tone = Tone.SETUP,
        action = Action.THROTTLE,
    )

    !canSlow -> ProtectionStatus(
        headline = "VPN permission was lost",
        detail = "Android lets one app use a VPN at a time, and another one has " +
            "taken ORBIS's turn. Nothing is being slowed until you allow it again.",
        tone = Tone.SETUP,
        action = Action.THROTTLE,
    )

    tunnelRunning && detected.surface.throttled -> ProtectionStatus(
        headline = "Slowing ${detected.surface.label()}",
        detail = buildString {
            append(detected.packageName?.let(::appLabel) ?: "Short-form video")
            if (pulsePeriodMillis > 0L) {
                append(" · stalls ${seconds(squeezeMillis)}s of every ${seconds(pulsePeriodMillis)}s")
            }
        },
        tone = Tone.ACTIVE,
        action = null,
    )

    else -> ProtectionStatus(
        headline = "Watching",
        detail = detected.packageName?.let { "${appLabel(it)} · full speed" }
            ?: "Nothing to slow right now. Everything runs at full speed until a " +
            "short-form feed appears.",
        tone = Tone.READY,
        action = null,
    )
}

/** 2400 -> "2.4", 5000 -> "5". */
private fun seconds(millis: Long): String {
    val tenths = (millis + 50L) / 100L
    return if (tenths % 10L == 0L) "${tenths / 10L}" else "${tenths / 10L}.${tenths % 10L}"
}

private fun Surface.label(): String = when (this) {
    Surface.REELS -> "Reels"
    Surface.SHORTS -> "Shorts"
    Surface.SPOTLIGHT -> "Spotlight"
    Surface.BROWSER_SHORT_VIDEO -> "short video"
    Surface.NORMAL -> "nothing"
}

private fun appLabel(packageName: String): String =
    TargetApp.fromPackage(packageName)?.displayName ?: packageName.substringAfterLast('.')

// ----------------------------------------------------------------- reclaimed

@Composable
private fun LoadingCard() {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Working it out…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The headline is a single number, so it is a stat tile rather than a chart -
 * plotting one value would add nothing to read.
 */
@Composable
private fun ReclaimedHero(summary: ReclaimedSummary) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "Reclaimed today", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = DurationFormatter.format(summary.reclaimedTodayMillis),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(text = encouragement(summary), style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile("This week", DurationFormatter.format(summary.reclaimedWeekMillis))
                StatTile("Your usual day", DurationFormatter.format(summary.baselineMillis ?: 0L))
                StatTile("Today", DurationFormatter.format(summary.todayMillis))
            }
        }
    }
}

/**
 * Copy is encouraging in every branch and never scolds - a heavier day is framed as
 * a fresh start, not a failure. See the dashboard invariant in CLAUDE.md.
 */
private fun encouragement(summary: ReclaimedSummary): String = when {
    summary.reclaimedTodayMillis >= 30 * 60_000L ->
        "That's a real chunk of your day back. Nicely done."

    summary.reclaimedTodayMillis > 0L ->
        "Less short-form than your usual day. That adds up."

    else ->
        "A heavier day than usual — tomorrow starts fresh."
}

@Composable
private fun StatTile(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StillLearningCard(daysSoFar: Int) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Still learning your rhythm",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                // Better to say there is no number yet than to invent one from a
                // day or two of data.
                text = "ORBIS compares today against your own recent average. " +
                    "After a few days of tracking it can show what you've won back. " +
                    "$daysSoFar day${if (daysSoFar == 1) "" else "s"} recorded so far.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TodayOnlyCard(todayMillis: Long) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Short-form video today",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = DurationFormatter.format(todayMillis),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "A chart appears once there are a few days to compare.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------------- chart

@Composable
private fun WeekChartCard(summary: ReclaimedSummary) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                // A single series, so the title names it and no legend is needed.
                text = "Short-form video, last 7 days",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            WeekBars(days = summary.week, baselineMillis = summary.baselineMillis)
        }
    }
}

@Composable
private fun WeekBars(
    days: List<DayTotal>,
    baselineMillis: Long?,
    modifier: Modifier = Modifier,
) {
    val peak = maxOf(days.maxOfOrNull { it.millis } ?: 0L, baselineMillis ?: 0L, 1L)
    val barColor = MaterialTheme.colorScheme.primary
    val mutedBar = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val today = days.lastOrNull()?.date

    // The canvas is decoration for a number stated in words below it, so it is
    // hidden from screen readers rather than announced as an unlabelled graphic.
    val chartDescription = "Short-form video over the last ${days.size} days, " +
        "peaking at ${DurationFormatter.format(peak)}"

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .semantics { contentDescription = chartDescription }
        ) {
            val gap = 2.dp.toPx()
            val slot = size.width / days.size
            // Cap the width so a short history renders as bars rather than slabs.
            val barWidth = (slot - gap).coerceIn(1f, MAX_BAR_WIDTH.toPx())
            val slotPadding = (slot - gap - barWidth).coerceAtLeast(0f) / 2f
            val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())

            // Recessive baseline guide: shows what a usual day looks like without
            // competing with the data.
            baselineMillis?.let { baseline ->
                val y = size.height - (baseline.toFloat() / peak) * size.height
                drawLine(
                    color = guideColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx(),
                )
            }

            days.forEachIndexed { index, day ->
                val height = (day.millis.toFloat() / peak) * size.height
                val left = index * slot + gap / 2f + slotPadding
                val top = size.height - height

                // Rounded only at the data end; the bar stays anchored to the
                // baseline rather than floating as a pill.
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(left, top, left + barWidth, size.height),
                            topLeft = radius,
                            topRight = radius,
                            bottomRight = CornerRadius.Zero,
                            bottomLeft = CornerRadius.Zero,
                        )
                    )
                }
                drawPath(path, color = if (day.date == today) barColor else mutedBar)
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            days.forEach { day ->
                Text(
                    text = day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Selective direct label: today only, rather than a number on every bar.
        days.lastOrNull()?.let { latest ->
            Text(
                text = "Today: ${DurationFormatter.format(latest.millis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----------------------------------------------------------------- app split

/**
 * Where today's time actually went.
 *
 * WhatsApp is listed and labelled as protected. It is measured but never
 * throttled, and saying so on the home screen is the clearest way to show that
 * ORBIS distinguishes talking to people from passive scrolling.
 */
@Composable
private fun TodaySplitCard(profile: UsageProfile) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "Today",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = DurationFormatter.format(profile.totalMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (profile.entries.isEmpty()) {
                Text(
                    text = "Nothing tracked yet today. Open one of your apps for a " +
                        "moment and this fills in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val peak = profile.entries.maxOf { it.durationMillis }.coerceAtLeast(1L)
            profile.entries.forEach { entry ->
                AppUsageRow(entry = entry, peakMillis = peak)
            }
        }
    }
}

@Composable
private fun AppUsageRow(entry: AppUsage, peakMillis: Long) {
    val protected = !entry.app.throttled
    val barColor =
        if (protected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.app.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (protected) {
                    Spacer(Modifier.width(8.dp))
                    ProtectedBadge()
                }
            }
            Text(
                text = DurationFormatter.format(entry.durationMillis),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }

        // Scaled against the heaviest app rather than the total, so a quiet day
        // still reads as a comparison instead of four identical slivers.
        UsageBar(
            fraction = entry.durationMillis.toFloat() / peakMillis,
            color = barColor,
            track = track,
        )
    }
}

@Composable
private fun UsageBar(fraction: Float, color: Color, track: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(track)
            // The number beside it already says this; a second announcement of
            // the same fact is noise.
            .clearAndSetSemantics { }
    ) {
        // fillMaxWidth rejects a zero fraction, and an app with no time today has
        // nothing to draw anyway.
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun ProtectedBadge() {
    Text(
        text = "never slowed",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// -------------------------------------------------------------------- streak

@Composable
private fun StreakTeaser(
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = when {
                        clearTimeMillis > 0L ->
                            "${DurationFormatter.format(clearTimeMillis)} clear time left"
                        streak <= 0 -> "Earn some clear time"
                        streak == 1 -> "1 day of earning"
                        else -> "$streak days of earning"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (earnedToday) {
                        "Earned today. Streak safe."
                    } else {
                        "A focus session or a good deed keeps it going."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // TextButton defaults to `primary` and ignores the Card's
            // contentColor, which puts a teal label on the amber card.
            TextButton(
                onClick = onOpenEarn,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Text(if (earnedToday) "More" else "Earn")
            }
        }
    }
}

// ------------------------------------------------------------------ previews

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun HomeScreenPreview() {
    val today = LocalDate.of(2026, 8, 14)
    OrbisTheme {
        HomeScreen(
            today = today,
            state = HomeUiState(
                loading = false,
                streak = 3,
                summary = ReclaimedSummary(
                    todayMillis = 22 * 60_000L,
                    baselineMillis = 61 * 60_000L,
                    reclaimedTodayMillis = 39 * 60_000L,
                    reclaimedWeekMillis = 96 * 60_000L,
                    week = (6 downTo 0).map {
                        DayTotal(today.minusDays(it.toLong()), (30L + it * 9) * 60_000L)
                    },
                ),
                profile = UsageProfile(
                    listOf(
                        AppUsage(TargetApp.INSTAGRAM, 3_720_000L),
                        AppUsage(TargetApp.YOUTUBE, 1_500_000L),
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
            onShowSimple = {},
        )
    }
}
