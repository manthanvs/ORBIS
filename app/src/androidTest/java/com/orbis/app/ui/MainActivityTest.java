package com.orbis.app.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.orbis.app.MainActivity;
import com.orbis.app.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The View UI, end to end on a device.
 *
 * <p>Deliberately about what the user can reach rather than about numbers: the
 * arithmetic behind every figure on these screens is covered purely in
 * {@code src/test}, and the thing that can only break here is navigation and
 * whether the promises the app makes are actually on screen.
 */
@RunWith(AndroidJUnit4.class)
public class MainActivityTest {

    @Rule
    public ActivityScenarioRule<MainActivity> activity =
            new ActivityScenarioRule<>(MainActivity.class);

    @Test
    public void homeIsTheStartingTab() {
        onView(withId(R.id.status_title)).check(matches(isDisplayed()));
    }

    @Test
    public void everyTabOpens() {
        onView(withId(R.id.nav_earn)).perform(click());
        onView(withId(R.id.balance_value)).check(matches(isDisplayed()));

        onView(withId(R.id.nav_controls)).perform(click());
        onView(withId(R.id.auto_switch)).check(matches(isDisplayed()));

        onView(withId(R.id.nav_about)).perform(click());
        onView(withText(R.string.about_short_title)).check(matches(isDisplayed()));

        onView(withId(R.id.nav_home)).perform(click());
        onView(withId(R.id.status_title)).check(matches(isDisplayed()));
    }

    @Test
    public void aboutStatesThatWhatsappIsNeverSlowed() {
        // The hard invariant, as the user is told it. If this copy ever goes
        // missing the app is making a promise it no longer explains.
        onView(withId(R.id.nav_about)).perform(click());
        onView(withText(R.string.about_whatsapp_title))
                .perform(scrollTo())
                .check(matches(isDisplayed()));
    }

    @Test
    public void controlsStartWithTheCountersCollapsed() {
        // The screen leads with plain English; the packet counters are behind a tap.
        onView(withId(R.id.nav_controls)).perform(click());
        onView(withId(R.id.seeing_value)).check(matches(isDisplayed()));
        onView(withId(R.id.details_header)).perform(scrollTo(), click());
        onView(withId(R.id.details_body)).check(matches(isDisplayed()));
    }
}
