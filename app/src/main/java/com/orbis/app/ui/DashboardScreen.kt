package com.orbis.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.orbis.app.dashboard.DayTotal
import com.orbis.app.dashboard.ReclaimedSummary
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.DurationFormatter
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    modifier: Modifier = Modifier,
) {
    val summary = state.summary

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Your time back",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        if (summary == null) {
            Text(
                text = if (state.loading) "Working it out…" else "No data yet.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        if (summary.baselineMillis == null) {
            StillLearningCard(daysSoFar = summary.week.size)
        } else {
            ReclaimedHero(summary)
        }

        // One point is not a trend. A lone bar fills the plot and reads as a solid
        // block, so below two days the chart is omitted entirely rather than drawn
        // misleadingly - the card above already explains why there is no history.
        if (summary.week.size >= MIN_CHART_DAYS) {
            WeekChartCard(summary)
        } else if (summary.todayMillis > 0L) {
            TodayOnlyCard(summary.todayMillis)
        }
    }
}

/** Below this a bar chart has nothing to show a reader that a number does not. */
private const val MIN_CHART_DAYS = 2

/** Keeps two or three bars looking like bars instead of stretching into slabs. */
private val MAX_BAR_WIDTH = 56.dp

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

/**
 * The headline is a single number, so it is a stat tile rather than a chart -
 * plotting one value would add nothing to read.
 */
@Composable
private fun ReclaimedHero(summary: ReclaimedSummary) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Reclaimed today",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = DurationFormatter.format(summary.reclaimedTodayMillis),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = encouragement(summary),
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

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
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            WeekBars(
                days = summary.week,
                baselineMillis = summary.baselineMillis,
            )
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

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
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
                    start = androidx.compose.ui.geometry.Offset(0f, y),
                    end = androidx.compose.ui.geometry.Offset(size.width, y),
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

@Preview(showBackground = true)
@Composable
private fun DashboardPreview() {
    val today = LocalDate.of(2026, 7, 29)
    OrbisTheme {
        DashboardScreen(
            state = DashboardUiState(
                loading = false,
                summary = ReclaimedSummary(
                    todayMillis = 22 * 60_000L,
                    baselineMillis = 61 * 60_000L,
                    reclaimedTodayMillis = 39 * 60_000L,
                    reclaimedWeekMillis = 96 * 60_000L,
                    week = (6 downTo 0).map {
                        DayTotal(today.minusDays(it.toLong()), (30L + it * 9) * 60_000L)
                    },
                ),
            ),
        )
    }
}
