// file: app/src/main/java/com/negi/survey/screens/IntroScreen.kt
package com.negi.survey.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Intro screen in monotone (pure black/gray/white).
 *
 * Design goals:
 * - Real black behind system bars (status + navigation) with no accidental
 *   light surfaces bleeding through. We explicitly paint pure black rectangles
 *   equal to system bar insets at the very top/bottom.
 * - Subtle animated black→dark-gray background for the content area.
 * - A glass-like centered card with a very thin neutral rim for definition.
 * - Clean typography and accessible structure with semantics + test tags.
 *
 * Implementation notes:
 * - We intentionally do NOT use Scaffold here to avoid any default container
 *   coloring that might show white during insets/layout transitions.
 * - The order of children in the root Box matters: background first, then
 *   content, then the top/bottom black strips to overwrite any edge artifacts.
 */
@Composable
fun IntroScreen(
    onStart: () -> Unit,
) {
    val bgBrush = animatedMonotoneBackground()

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Main content background: animated dark monotone gradient
            .background(bgBrush)
            // Accessibility & testing hooks
            .semantics { contentDescription = "Survey intro screen" }
            .testTag("IntroScreenRoot")
    ) {
        // Top system bar strip: paint exact status bar height in pure black.
//        Box(
//            modifier = Modifier
//                .align(Alignment.TopStart)
//                .windowInsetsTopHeight(WindowInsets.statusBars)
//                .fillMaxSize()
//                .background(Color.Black)
//        )

        // Center content layer: the hero card.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            IntroCardMono(
                title = "Survey Test App",
                subtitle = "A focused, privacy-friendly evaluation flow",
                onStart = onStart
            )
        }

//        // Bottom system bar strip: paint exact navigation bar height in pure black.
//        Box(
//            modifier = Modifier
//                .align(Alignment.BottomStart)
//                .windowInsetsBottomHeight(WindowInsets.navigationBars)
//                .fillMaxSize()
//                .background(Color.Black)
//        )
    }
}

/* ──────────────────────────── Card & Typography ─────────────────────────── */

/**
 * Centered glass card in strict grayscale.
 *
 * Why ElevatedCard?
 * - Provides Material3-consistent elevation/shadows; visually separates from
 *   the animated background without requiring heavy drop shadows.
 *
 * Rim stroke rationale:
 * - We draw a neutral sweep gradient rim (dark→mid→light gray). Alpha is
 *   extremely low and stroke width is 1f to keep it premium and not flashy.
 */
@Composable
private fun IntroCardMono(
    title: String,
    subtitle: String,
    onStart: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val corner = 20.dp

    ElevatedCard(
        shape = RoundedCornerShape(corner),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .drawBehind {
                // Neutral rim (no chroma): subtle definition around the card
                val dark = Color(0xFF1A1A1A).copy(alpha = 0.18f)
                val mid  = Color(0xFF7A7A7A).copy(alpha = 0.14f)
                val light= Color(0xFFE5E5E5).copy(alpha = 0.10f)
                val sweep = Brush.sweepGradient(
                    0f to dark, 0.25f to mid, 0.5f to light, 0.75f to mid, 1f to dark
                )
                drawRoundRect(
                    brush = sweep,
                    style = Stroke(width = 1f),
                    cornerRadius = CornerRadius(corner.toPx(), corner.toPx())
                )
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GradientHeadlineMono(title)

            Spacer(Modifier.height(6.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = lerp(cs.onSurface, Color(0xFF909090), 0.25f),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))

            HorizontalDivider(
                thickness = DividerDefaults.Thickness,
                color = cs.outlineVariant.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onStart,
                shape = CircleShape,
                // Neutral dark gray with white text; avoids accidental color.
                colors = ButtonDefaults.buttonColors(
                    containerColor = lerp(Color(0xFF1F1F1F), cs.surface, 0.25f),
                    contentColor = Color.White
                ),
                modifier = Modifier.testTag("StartButton")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                Spacer(Modifier.height(0.dp)) // keeps baseline; no extra width between icon/text
                Text(text = "Start", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Monotone gradient headline (vertical).
 *
 * Technique:
 * - Uses a vertical gradient brush applied via SpanStyle to the entire text.
 * - Deliberately avoids shadows; hierarchy is achieved by contrast only.
 */
@Composable
private fun GradientHeadlineMono(text: String) {
    val brush = Brush.verticalGradient(
        0f to Color(0xFF909090),
        1f to Color(0xFF090909)
    )
    val label = buildAnnotatedString {
        withStyle(SpanStyle(brush = brush)) { append(text) }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center
    )
}

/* ────────────────────────── Background (monotone) ───────────────────────── */

/**
 * Animated monotone background.
 *
 * Implementation:
 * - Single infinite transition animates a factor `p` used to drift the linear
 *   gradient's end vector. Colors are strict grayscale; no hue change.
 * - Because system bar areas are explicitly painted pure black in IntroScreen,
 *   even if the gradient contains lighter grays near the edges, bars remain black.
 */
@Composable
private fun animatedMonotoneBackground(): Brush {
    val t = rememberInfiniteTransition(label = "mono-bg")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(14_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mono-bg-p"
    )

    // Strict grayscale stops
    val c0 = Color(0xFF0B0B0B)
    val c1 = Color(0xFF141414)
    val c2 = Color(0xFF1E1E1E)
    val c3 = Color(0xFF272727)

    // End vector drifts slowly to create subtle motion (no chroma)
    val endX = 900f + 280f * p
    val endY = 720f - 220f * p

    return Brush.linearGradient(
        colors = listOf(c0, c1, c2, c3),
        start = Offset(0f, 0f),
        end = Offset(endX, endY)
    )
}

/* ───────────────────────────────── Preview ──────────────────────────────── */

@Preview(showBackground = true, name = "Intro — Monotone Chic / Black Bars")
@Composable
private fun IntroScreenPreview() {
    MaterialTheme {
        IntroScreen(onStart = {})
    }
}
