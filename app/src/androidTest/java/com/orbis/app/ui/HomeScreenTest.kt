package com.orbis.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.orbis.app.dashboard.DayTotal
import com.orbis.app.dashboard.ReclaimedSummary
import com.orbis.app.surface.DetectedSurface
import com.orbis.app.surface.Surface
import com.orbis.app.ui.theme.OrbisTheme
import com.orbis.app.usage.AppUsage
import com.orbis.app.usage.TargetApp
import com.orbis.app.usage.UsageProfile
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * Renders the home screen against synthetic history.
 *
 * The device only has a single day of real data, so the multi-day chart branch
 * would otherwise never execute - and seeding fake rows into the user's actual
 * database to see it draw is not an acceptable way to test.
 */
class HomeScreenTest {

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

    private fun granted(
        autoThrottle: Boolean = true,
        hasVpnConsent: Boolean = true,
        tunnelRunning: Boolean = false,
        detected: DetectedSurface = DetectedSurface(),
    ) = ProtectionUiState(
        hasUsageAccess = true,
        hasDetection = true,
        hasVpnConsent = hasVpnConsent,
        autoThrottle = autoThrottle,
        tunnelRunning = tunnelRunning,
        detected = detected,
    )

    private fun show(
        state: HomeUiState,
        protection: ProtectionUiState = granted(),
    ) {
        compose.setContent {
            OrbisTheme {
                HomeScreen(
                    state = state,
                    protection = protection,
                    today = today,
                    onGrantUsageAccess = {},
                    onEnableDetection = {},
                    onEnableThrottle = {},
                    onOpenEarn = {},
                    onShowSimple = {},
                )
            }
        }
    }

    @Test
    fun withoutUsageAccessItSaysSoRatherThanSpinning() {
        // Reinstalling revokes usage access, and refresh() is then never called -
        // so an indefinite "Working it out…" is a dead end, not a loading state.
        show(
            state = HomeUiState(loading = true, summary = null),
            protection = ProtectionUiState(hasUsageAccess = false),
        )

        compose.onNodeWithText("Usage access is off").assertIsDisplayed()
        compose.onNodeWithText("Working it out…").assertDoesNotExist()
    }

    @Test
    fun fullWeekRendersTheChart() {
        show(HomeUiState(loading = false, summary = summary(days = 7)))

        compose.onNodeWithText("Short-form video, last 7 days").assertIsDisplayed()
        compose.onNodeWithText("Reclaimed today").assertIsDisplayed()
    }

    @Test
    fun singleDayShowsNoChart() {
        show(HomeUiState(loading = false, summary = summary(days = 1)))

        compose.onNodeWithText("Short-form video today").assertIsDisplayed()
        compose.onNodeWithText("Short-form video, last 7 days").assertDoesNotExist()
    }

    @Test
    fun missingBaselineShowsStillLearningRatherThanANumber() {
        show(HomeUiState(loading = false, summary = summary(days = 2, baselineMillis = null)))

        compose.onNodeWithText("Still learning your rhythm").assertIsDisplayed()
        compose.onNodeWithText("Reclaimed today").assertDoesNotExist()
    }

    @Test
    fun twoDaysIsEnoughToDrawTheChart() {
        show(HomeUiState(loading = false, summary = summary(days = 2)))

        compose.onNodeWithText("Short-form video, last 7 days").assertIsDisplayed()
    }

    @Test
    fun whatsAppIsShownAsNeverSlowed() {
        // The invariant is worth stating on screen, so it is worth a test: if the
        // badge ever stops rendering, the home screen quietly implies ORBIS might
        // throttle a messaging app.
        show(
            HomeUiState(
                loading = false,
                summary = summary(days = 7),
                profile = UsageProfile(
                    listOf(
                        AppUsage(TargetApp.INSTAGRAM, 3_600_000L),
                        AppUsage(TargetApp.WHATSAPP, 600_000L),
                    )
                ),
            )
        )

        // The split card sits below the hero and the chart, so on a phone it
        // starts off-screen - assertIsDisplayed alone fails on scrollable content
        // that exists but has not been scrolled to.
        compose.onNodeWithText("WhatsApp").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("never slowed").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anActiveThrottleIsNamedOnTheProtectionCard() {
        show(
            state = HomeUiState(loading = false, summary = summary(days = 7)),
            protection = granted(
                tunnelRunning = true,
                detected = DetectedSurface(Surface.REELS, TargetApp.INSTAGRAM.packageName, 0L),
            ),
        )

        compose.onNodeWithText("Slowing Reels").assertIsDisplayed()
    }

    @Test
    fun setUpButSwitchedOffOffersToTurnItOn() {
        show(
            state = HomeUiState(loading = false, summary = summary(days = 7)),
            protection = granted(autoThrottle = false),
        )

        compose.onNodeWithText("Ready when you are").assertIsDisplayed()
        compose.onNodeWithText("Turn on auto-slowing").assertIsDisplayed()
    }

    @Test
    fun switchedOnWithoutVpnConsentNeverClaimsToBeWorking() {
        // Measured on device: onboarding set the flag without ever asking for VPN
        // consent, and the card said all was well while nothing could be slowed.
        show(
            state = HomeUiState(loading = false, summary = summary(days = 7)),
            protection = granted(autoThrottle = true, hasVpnConsent = false),
        )

        compose.onNodeWithText("VPN permission was lost").assertIsDisplayed()
        compose.onNodeWithText("Turn on auto-slowing").assertIsDisplayed()
        compose.onNodeWithText("Watching").assertDoesNotExist()
    }
}
