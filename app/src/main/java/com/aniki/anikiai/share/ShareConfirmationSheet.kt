package com.aniki.anikiai.share

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniki.anikiai.data.db.ItemType
import com.aniki.anikiai.ui.theme.Ink
import com.aniki.anikiai.ui.theme.InkLine
import com.aniki.anikiai.ui.theme.Kon
import com.aniki.anikiai.ui.theme.Matcha
import com.aniki.anikiai.ui.theme.Muted
import com.aniki.anikiai.ui.theme.Paper
import com.aniki.anikiai.ui.theme.Paper2
import com.aniki.anikiai.ui.theme.Seal
import com.aniki.anikiai.ui.theme.SealDark
import com.aniki.anikiai.ui.theme.SealMark
import com.aniki.anikiai.ui.theme.weightedShadow
import kotlinx.coroutines.delay

/**
 * The "stamp moment" (mockup plate 02): confirmation sheet shown over the host
 * app after a share is saved. Pure presentation — the save/enqueue behavior in
 * ShareReceiverActivity is unchanged; this replaces the old Toast.
 */
@Composable
fun ShareConfirmationSheet(
    itemType: String,
    itemTitle: String,
    onDone: () -> Unit
) {
    // Auto-dismiss like the Toast it replaces; tap anywhere to dismiss sooner.
    LaunchedEffect(Unit) {
        delay(2600)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF060910).copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDone
            )
    ) {
        val sheetShape = RoundedCornerShape(22.dp)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp)
                .padding(bottom = 14.dp)
                .fillMaxWidth()
                .weightedShadow(sheetShape, ambient = 24.dp, contact = 8.dp)
                .clip(sheetShape)
                .background(Paper)
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp)
        ) {
            // Grip
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(InkLine)
            )
            Spacer(Modifier.height(18.dp))

            // Stamp + heading
            Row(verticalAlignment = Alignment.CenterVertically) {
                StampedSeal()
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(
                        "Saved to Aniki",
                        style = MaterialTheme.typography.titleLarge,
                        color = Ink
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "No forms. Filed automatically.",
                        style = MaterialTheme.typography.labelMedium,
                        color = Muted
                    )
                }
            }
            Spacer(Modifier.height(18.dp))

            // Detected item card
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Paper2)
                    .padding(horizontal = 13.dp, vertical = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(thumbBrush(itemType))
                )
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = itemTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.5.sp),
                        color = Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = typeLabel(itemType),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                        color = Kon,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Kon.copy(alpha = 0.10f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // "Aniki is reading this…" + progress
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot()
                Spacer(Modifier.width(9.dp))
                Text(
                    "Aniki is reading this…",
                    style = MaterialTheme.typography.labelMedium,
                    color = Seal
                )
            }
            Spacer(Modifier.height(8.dp))
            ReadingBar()
        }
    }
}

/** The 52dp stamp, pressed on with the mockup's overshoot animation. */
@Composable
private fun StampedSeal() {
    val scale = remember { Animatable(1.7f) }
    val rotation = remember { Animatable(-14f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(150))
    }
    LaunchedEffect(Unit) {
        scale.animateTo(
            1f,
            animationSpec = tween(durationMillis = 500, easing = { overshoot(it) })
        )
    }
    LaunchedEffect(Unit) {
        rotation.animateTo(
            -8f,
            animationSpec = tween(durationMillis = 500, easing = { overshoot(it) })
        )
    }
    Box(
        modifier = Modifier
            .scale(scale.value)
            .alpha(alpha.value)
    ) {
        SealMark(size = 52.dp, filled = true, rotation = rotation.value)
    }
}

/** Approximation of the mockup's cubic-bezier(.2,1.4,.4,1) stamp curve. */
private fun overshoot(t: Float): Float {
    val tension = 1.3f
    val x = t - 1f
    return x * x * ((tension + 1) * x + tension) + 1f
}

@Composable
private fun PulseDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulsePhase"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .scale(0.8f + 0.35f * phase)
            .alpha(0.3f + 0.7f * phase)
            .clip(CircleShape)
            .background(Seal)
    )
}

@Composable
private fun ReadingBar() {
    val transition = rememberInfiniteTransition(label = "reading")
    val progress by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "readingProgress"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Paper2)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .clip(RoundedCornerShape(3.dp))
                .background(Seal)
        )
    }
}

private fun typeLabel(type: String): String = when (type) {
    ItemType.YOUTUBE_VIDEO -> "▶ YOUTUBE VIDEO · detected"
    ItemType.NOTE -> "✎ NOTE"
    else -> "◈ WEB ARTICLE · detected"
}

/** Somber token-blend placeholders standing in for real thumbnails, by type. */
private fun thumbBrush(type: String): Brush = when (type) {
    ItemType.YOUTUBE_VIDEO -> Brush.linearGradient(listOf(Kon, SealDark))
    ItemType.NOTE -> Brush.linearGradient(listOf(Matcha, Kon))
    else -> Brush.linearGradient(listOf(Kon, Matcha))
}
