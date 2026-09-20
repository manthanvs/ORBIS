package com.orbis.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbis.app.throttle.ThrottleEngine
import com.orbis.app.ui.theme.OrbisTheme

/**
 * The whole app, explained.
 *
 * Simple mode says what ORBIS does; this says how and why, and it is the only
 * screen allowed to be long. It exists because every other screen is deliberately
 * short, and a digital-wellbeing app that quietly routes your traffic and reads
 * your screens owes the reader a complete answer somewhere.
 *
 * The ordering follows what people actually want to know, not the order the code
 * runs in: what it is, then what it will and will not slow, then what it can see
 * about them, then the mechanics, then how to switch it all off.
 *
 * Two rules for editing anything here:
 *
 * - Numbers come from the code, not from memory. The delay range is read off
 *   [ThrottleEngine] so this page cannot quietly drift out of date.
 * - Say the limits out loud. The detection-breaks-on-redesign section is not a
 *   disclaimer to bury; a user who knows why it stopped working can fix it, and
 *   one who does not just decides the app is broken.
 */
@Composable
fun AboutScreen(
    versionName: String,
    hasUsageAccess: Boolean,
    hasDetection: Boolean,
    autoThrottle: Boolean,
    onGrantUsageAccess: () -> Unit,
    onEnableDetection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "About ORBIS",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        InShortCard()

        HowItWorksStrip(Modifier.padding(vertical = 4.dp))

        CollapsibleCard(
            title = "What the name means",
            initiallyExpanded = false,
        ) {
            Paragraph(
                "ORBIS stands for Optimized Responsible Browsing & Intervention " +
                    "System. In plain English: it works out which apps are eating " +
                    "your time, makes the endless-scroll parts of them slightly " +
                    "harder to binge, and shows you what you got back."
            )
            Paragraph(
                "The word \"intervention\" is doing a lot of work there, so here is " +
                    "the honest version: ORBIS never blocks anything, never locks " +
                    "you out, and never tells you off. It adds a small amount of " +
                    "friction and then gets out of the way. If you want to keep " +
                    "scrolling, you can — you will just notice yourself doing it."
            )
        }

        CollapsibleCard(
            title = "What gets slowed, and what never does",
            initiallyExpanded = true,
        ) {
            Paragraph(
                "Only four things are ever slowed, and all four are short-form " +
                    "video feeds — the swipe-up-forever kind:"
            )
            Bullets(
                listOf(
                    "Instagram Reels",
                    "YouTube Shorts",
                    "Snapchat Spotlight",
                    "Those same feeds opened in a browser, like a youtube.com/shorts link",
                )
            )
            Paragraph("Everything else runs at full speed, including:")
            Bullets(
                listOf(
                    "Instagram Stories, your normal feed, and every DM",
                    "Normal-length YouTube videos, and YouTube Music",
                    "Snapchat chats and Stories",
                    "Every app ORBIS does not track at all",
                )
            )
            Callout(
                title = "WhatsApp is never slowed. Not any part of it.",
                body = "WhatsApp is how you talk to people, not something you scroll " +
                    "at, so it is excluded at the deepest level ORBIS has: while the " +
                    "slow-down is active, WhatsApp's traffic is not routed through " +
                    "ORBIS at all. It is not a setting that could be flipped by " +
                    "accident — the phone's own network layer enforces it. ORBIS " +
                    "still counts your WhatsApp time so it can show up on your " +
                    "dashboard, and that is all it does with it."
            )
            Paragraph(
                "If ORBIS is not sure what screen you are on, it treats it as normal " +
                    "and leaves it alone. Missing a Reel is a much smaller problem " +
                    "than slowing something you wanted fast."
            )
        }

        CollapsibleCard(
            title = "What ORBIS can see about you",
            initiallyExpanded = true,
        ) {
            Paragraph(
                "Everything stays on this phone. There is no ORBIS account, no " +
                    "server, no cloud backup and no sync. Nothing is uploaded " +
                    "anywhere, because there is nowhere for it to be uploaded to."
            )
            Paragraph("What is stored, all of it in the app's private storage:")
            Bullets(
                listOf(
                    "How many minutes a day you spent in Instagram, YouTube, " +
                        "Snapchat and WhatsApp — kept for a rolling window, then deleted",
                    "Which of those four screens you are on right now, held in " +
                        "memory only and never written down",
                    "Your good-deed log: the photo you took, an optional note, and " +
                        "the date",
                )
            )
            Paragraph("What ORBIS never sees:")
            Bullets(
                listOf(
                    "What you post, type, send or read",
                    "Who you talk to",
                    "Any screen in any app other than the four it tracks",
                    "The contents of your traffic — it can tell that data is moving " +
                        "and briefly hold it back, not what is in it",
                )
            )
            Paragraph(
                "Uninstalling ORBIS deletes all of it, including the photos, since " +
                    "they live in the app's own folder rather than your gallery."
            )
        }

        CollapsibleCard(
            title = "The three permissions, and why each one",
            initiallyExpanded = false,
        ) {
            PermissionEntry(
                name = "Usage access",
                granted = hasUsageAccess,
                why = "Lets ORBIS ask Android how long each app was open. This is " +
                    "the measuring tape — without it there are no numbers and no " +
                    "dashboard. It reports durations only, never content.",
                note = "Android will not show a normal pop-up for this one. It has " +
                    "to be switched on from a list in Settings.",
                buttonLabel = "Open usage access settings",
                onClick = onGrantUsageAccess,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            PermissionEntry(
                name = "Surface detection (an accessibility service)",
                granted = hasDetection,
                why = "This is what tells Reels apart from Stories. A network filter " +
                    "on its own genuinely cannot do it — modern traffic is " +
                    "encrypted, and Reels and Stories arrive from the same servers, " +
                    "so from the outside they look identical. Reading which view is " +
                    "on screen is the only way to know, and it is the reason ORBIS " +
                    "can slow Reels without touching your DMs.",
                note = "It is restricted to Instagram, YouTube, Snapchat and " +
                    "browsers, and it looks for a handful of specific screen " +
                    "elements. It does not read text you write, and nothing it " +
                    "sees leaves the phone.",
                buttonLabel = "Open accessibility settings",
                onClick = onEnableDetection,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            PermissionEntry(
                name = "VPN permission",
                granted = autoThrottle,
                why = "The slow-down works by sending the tracked apps' traffic " +
                    "through ORBIS first, and Android only allows that behind the " +
                    "VPN prompt — which is why your phone shows a key icon while " +
                    "it is running.",
                note = "It is a local VPN: traffic goes through ORBIS on this " +
                    "device and straight back out. There is no remote server, and " +
                    "your traffic is not sent through anyone else's machine. ORBIS " +
                    "turns it on only while a short-video feed is actually on " +
                    "screen and drops it a few seconds after you leave.",
                buttonLabel = null,
                onClick = null,
            )
            Paragraph(
                "Two more, both ordinary: notifications, for the once-a-day " +
                    "good-deed nudge, and the camera, used only when you tap to " +
                    "photograph a deed."
            )
        }

        CollapsibleCard(
            title = "How the slow-down actually works",
            initiallyExpanded = false,
        ) {
            NumberedStep(
                1,
                "Detection spots the feed",
                "ORBIS checks which app is in front and which of its screens is " +
                    "showing. Instagram Reels, YouTube Shorts and Snapchat " +
                    "Spotlight each have their own fingerprint. In a browser it " +
                    "reads the address bar instead."
            )
            NumberedStep(
                2,
                "The local VPN comes up",
                "Only then, and only for Instagram, YouTube, Snapchat and " +
                    "browsers. Every other app on the phone, WhatsApp included, is " +
                    "left off it entirely."
            )
            NumberedStep(
                3,
                "The feed is squeezed, then let go",
                "Every five seconds the app's downloads are held to a trickle for " +
                    "a moment, then released. Videos stall or drop in quality, " +
                    "recover, and stall again - and that stutter is exactly the " +
                    "moment you notice you have been scrolling. Nothing you send or " +
                    "receive is read or altered."
            )
            NumberedStep(
                4,
                "It comes back down",
                "Leave the feed and the VPN drops within a few seconds, whether " +
                    "you switch screens, switch apps or close the app. It does not " +
                    "linger in the background slowing your phone."
            )
            Callout(
                title = "How much slower, exactly?",
                body = "Each squeeze lasts ${ThrottleEngine.MIN_SQUEEZE_MILLIS / 100 / 10.0} " +
                    "seconds on a light day, rising to " +
                    "${ThrottleEngine.MAX_SQUEEZE_MILLIS / 1000} of every " +
                    "${ThrottleEngine.PULSE_PERIOD_MILLIS / 1000} as your short-video " +
                    "time for the day adds up across all your apps. In between, the " +
                    "feed runs close to normal on a light day and noticeably slower " +
                    "on a heavy one. It is never cut off completely: a " +
                    "feed that feels broken just gets the app uninstalled, which " +
                    "helps nobody."
            )
        }

        CollapsibleCard(
            title = "Where \"time you got back\" comes from",
            initiallyExpanded = false,
        ) {
            Paragraph(
                "ORBIS records your short-video minutes each day and works out your " +
                    "own recent average. Today's total is compared against that " +
                    "average, and the difference is the time you got back."
            )
            Paragraph(
                "So it is measured against you, not against a target somebody else " +
                    "picked. There is no recommended daily limit in this app and no " +
                    "score to lose. That also means it needs a few days of history " +
                    "before it can say anything at all — until then it says it is " +
                    "still learning, rather than inventing a number."
            )
            Paragraph(
                "On a heavier-than-usual day the number simply goes to zero. It " +
                    "will not go negative and the app will not scold you for it."
            )
        }

        CollapsibleCard(
            title = "The good-deed challenge",
            initiallyExpanded = false,
        ) {
            Paragraph(
                "Taking time away from something works far better when there is " +
                    "somewhere for it to go. Once a day ORBIS nudges you to do one " +
                    "small good thing off your phone and log it with a photo."
            )
            Paragraph(
                "The bar is meant to be low: washing up, helping a sibling with " +
                    "homework, texting a friend who is having a bad week, picking " +
                    "up litter on the way home. Log it and your streak grows. The " +
                    "photos are for you — they are saved in the app's private " +
                    "folder, are not shared anywhere, and never touch your gallery."
            )
        }

        CollapsibleCard(
            title = "Limits, and switching it off",
            initiallyExpanded = false,
        ) {
            Paragraph(
                "Detection relies on recognising specific parts of Instagram, " +
                    "YouTube and Snapchat's screens. When those apps get " +
                    "redesigned — and they do, often — ORBIS can stop recognising " +
                    "a feed until it is updated. The failure is a safe one: it " +
                    "stops slowing things, it never starts slowing the wrong thing."
            )
            Paragraph(
                "It also measures usage the same way Android's own Digital " +
                    "Wellbeing does — by how long an app is in front of you — so " +
                    "leaving Instagram open in your pocket counts, and watching " +
                    "over someone's shoulder does not."
            )
            Paragraph(
                "You are never locked in. The Controls tab has one switch that " +
                    "stops the slow-down completely, and every permission can be " +
                    "revoked in Android's own Settings at any time. Turning it off " +
                    "does not delete your history."
            )
        }

        Text(
            text = "ORBIS $versionName · everything on this page happens on this " +
                "phone and nowhere else",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(8.dp))
    }
}

/** The whole app in three lines, for a reader who will not open a single section. */
@Composable
private fun InShortCard() {
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "In short",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "ORBIS makes endless-scroll video feeds load slightly slower, " +
                    "so the scroll that was going to swallow an hour gets " +
                    "interrupted by you noticing it.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "It only ever slows Reels, Shorts and Spotlight. It never " +
                    "slows WhatsApp, your DMs, your Stories or anything else. " +
                    "Everything it knows about you stays on this phone.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

// ------------------------------------------------------------------ sections

/**
 * A collapsible section.
 *
 * Collapsed by default apart from the two that answer the questions people
 * actually arrive with — what will this slow, and what can it see. The full text
 * is long on purpose, and a wall of it is a page nobody reads; the headings let
 * someone find their one question instead.
 */
@Composable
internal fun CollapsibleCard(
    title: String,
    initiallyExpanded: Boolean,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "aboutChevron",
    )

    Card {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 12.dp),
                )
                // A glyph rather than a Material icon: material-icons is not a
                // dependency of this module and one chevron does not justify it.
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotation),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Bullets(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** For the one or two facts in a section that must not be skimmed past. */
@Composable
private fun Callout(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NumberedStep(number: Int, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
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

/**
 * One permission, with its live state.
 *
 * The status is shown here as well as on Home because this is the page somebody
 * reads when they are deciding whether to grant it, and "you have already given
 * me this one" is part of the answer.
 */
@Composable
private fun PermissionEntry(
    name: String,
    granted: Boolean,
    why: String,
    note: String,
    buttonLabel: String?,
    onClick: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (granted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = if (granted) "On" else "Off",
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 0.5.sp,
            color = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = why,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (buttonLabel != null && onClick != null && !granted) {
            OutlinedButton(onClick = onClick) { Text(buttonLabel) }
        }
    }
}

// ------------------------------------------------------------------ previews

@Preview(showBackground = true, heightDp = 1600)
@Composable
private fun AboutPreview() {
    OrbisTheme {
        AboutScreen(
            versionName = "1.0",
            hasUsageAccess = true,
            hasDetection = false,
            autoThrottle = false,
            onGrantUsageAccess = {},
            onEnableDetection = {},
        )
    }
}
