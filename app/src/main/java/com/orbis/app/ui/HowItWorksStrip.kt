package com.orbis.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The whole app in three pictures: spot, slow, earn back.
 *
 * Drawn rather than shipped as an image. A drawing takes the theme's colours, so
 * it is right in light and dark; it scales with the screen instead of blurring;
 * and the labels are real text, so they grow with the reader's font size. A
 * bitmap would be none of those things.
 *
 * Laid out as a row where there is room and a column where there is not, so it
 * works on a small phone, a split screen and a tablet without a second layout.
 */
@Composable
fun HowItWorksStrip(modifier: Modifier = Modifier) {
    val steps = listOf<Triple<String, String, DrawScope.(Color, Color) -> Unit>>(
        Triple("Spots reels", "It knows Reels from your chats", DrawScope::drawPhone),
        Triple("Slows them", "In bursts, every few seconds", DrawScope::drawBars),
        Triple("You earn it back", "Do something else, get speed", DrawScope::drawClock),
    )

    BoxWithConstraints(modifier.fillMaxWidth().semantics {
        contentDescription = "How ORBIS works: it spots reels, slows them in bursts " +
            "every few seconds, and you earn the speed back by doing something else."
    }) {
        val sideBySide = maxWidth >= 340.dp

        if (sideBySide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                steps.forEach { (title, caption, glyph) ->
                    Step(title, caption, glyph, Modifier.weight(1f))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                steps.forEach { (title, caption, glyph) ->
                    Step(title, caption, glyph, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Step(
    title: String,
    caption: String,
    glyph: DrawScope.(Color, Color) -> Unit,
    modifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The labels beneath say all of this; the strip carries one description.
        Canvas(Modifier.size(64.dp).clearAndSetSemantics {}) { glyph(accent, quiet) }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = quiet,
            textAlign = TextAlign.Center,
        )
    }
}

/** A phone with a video playing, and an eye on it. */
private fun DrawScope.drawPhone(accent: Color, quiet: Color) {
    val w = size.width
    val stroke = Stroke(width = w * 0.055f)
    drawRoundRect(
        color = quiet,
        topLeft = Offset(w * 0.28f, w * 0.26f),
        size = Size(w * 0.44f, w * 0.62f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.09f),
        style = stroke,
    )
    // Play triangle.
    val play = Path().apply {
        moveTo(w * 0.45f, w * 0.47f)
        lineTo(w * 0.61f, w * 0.57f)
        lineTo(w * 0.45f, w * 0.67f)
        close()
    }
    drawPath(play, accent)
    // Eye, looking at it.
    drawArc(
        color = accent,
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(w * 0.18f, w * 0.06f),
        size = Size(w * 0.64f, w * 0.30f),
        style = stroke,
    )
    drawCircle(accent, radius = w * 0.06f, center = Offset(w * 0.5f, w * 0.17f))
}

/** Four short bars and one tall one: the pulse, at a glance. */
private fun DrawScope.drawBars(accent: Color, quiet: Color) {
    val w = size.width
    val bottom = w * 0.82f
    val widthEach = w * 0.10f
    val heights = listOf(0.16f, 0.16f, 0.16f, 0.16f, 0.52f)

    heights.forEachIndexed { index, height ->
        val tall = height > 0.3f
        drawRoundRect(
            color = if (tall) accent else quiet,
            topLeft = Offset(w * 0.14f + index * (widthEach + w * 0.045f), bottom - w * height),
            size = Size(widthEach, w * height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.03f),
        )
    }
}

/** A clock with a plus: minutes coming back. */
private fun DrawScope.drawClock(accent: Color, quiet: Color) {
    val w = size.width
    val stroke = Stroke(width = w * 0.055f)
    drawCircle(quiet, radius = w * 0.30f, center = Offset(w * 0.44f, w * 0.56f), style = stroke)
    drawLine(
        color = quiet,
        start = Offset(w * 0.44f, w * 0.56f),
        end = Offset(w * 0.44f, w * 0.37f),
        strokeWidth = w * 0.055f,
    )
    drawLine(
        color = quiet,
        start = Offset(w * 0.44f, w * 0.56f),
        end = Offset(w * 0.58f, w * 0.60f),
        strokeWidth = w * 0.055f,
    )
    // The plus.
    drawLine(
        color = accent,
        start = Offset(w * 0.78f, w * 0.20f),
        end = Offset(w * 0.78f, w * 0.40f),
        strokeWidth = w * 0.06f,
    )
    drawLine(
        color = accent,
        start = Offset(w * 0.68f, w * 0.30f),
        end = Offset(w * 0.88f, w * 0.30f),
        strokeWidth = w * 0.06f,
    )
}
