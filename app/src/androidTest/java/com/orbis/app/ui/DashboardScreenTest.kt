package com.orbis.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.orbis.app.dashboard.DayTotal
import com.orbis.app.dashboard.ReclaimedSummary
import com.orbis.app.ui.theme.OrbisTheme
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * Renders the dashboard against synthetic history.
 *
 * The device only has a single day of real data, so the multi-day chart branch
 * would otherwise never execute - and seeding fake rows into the user's actual
 * database to see it draw is not an acceptable way to test.
 */
class DashboardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val today: LocalDate = LocalDate.of(2026, 7, 29)

    private fun summary(days: Int, baselineMillis: Long? = 60 * 60_000L) = ReclaimedSummary(
        todayMillis = 20 * 60_000L,
        baselineMillis = baselineMillis,
        reclaimedTodayMillis = 40 * 60_000L,
        reclaimedWeekMillis = 90 * 60_000L,
        week = (days - 1 downTo 0).map {
            DayTotal(today.minusDays(it.toLong()), (25L + it * 7) * 60_000L)
        },
    )

    private fun show(state: DashboardUiState, hasUsageAccess: Boolean = true) {
        compose.setContent {
            OrbisTheme {
                DashboardScreen(
                    state = state,
                    hasUsageAccess = hasUsageAccess,
                    onGrantUsageAccess = {},
                )
            }
        }
    }

    @Test
    fun withoutUsageAccessItSaysSoRatherThanSpinning() {
        // Reinstalling revokes usage access, and refresh() is then never called -
        // so an indefinite "Working it out…" is a dead end, not a loading state.
        show(DashboardUiState(loading = true, summary = null), hasUsageAccess = false)

        compose.onNodeWithText("Usage access is off").assertIsDisplayed()
        compose.onNodeWithText("Working it out…").assertDoesNotExist()
    }

    @Test
    fun fullWeekRendersTheChart() {
        show(DashboardUiState(loading = false, summary = summary(days = 7)))

        compose.onNodeWithText("Short-form video, last 7 days").assertIsDisplayed()
        compose.onNodeWithText("Reclaimed today").assertIsDisplayed()
    }

    @Test
    fun singleDayShowsNoChart() {
        show(DashboardUiState(loading = false, summary = summary(days = 1)))

        compose.onNodeWithText("Short-form video today").assertIsDisplayed()
        compose.onNodeWithText("Short-form video, last 7 days").assertDoesNotExist()
    }

    @Test
    fun missingBaselineShowsStillLearningRatherThanANumber() {
        show(DashboardUiState(loading = false, summary = summary(days = 2, baselineMillis = null)))

        compose.onNodeWithText("Still learning your rhythm").assertIsDisplayed()
        compose.onNodeWithText("Reclaimed today").assertDoesNotExist()
    }

    @Test
    fun twoDaysIsEnoughToDrawTheChart() {
        show(DashboardUiState(loading = false, summary = summary(days = 2)))

        compose.onNodeWithText("Short-form video, last 7 days").assertIsDisplayed()
    }
}
