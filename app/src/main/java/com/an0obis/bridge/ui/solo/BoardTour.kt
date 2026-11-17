package com.an0obis.bridge.ui.solo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.an0obis.bridge.R
import com.an0obis.bridge.ui.AccentGold

/** The parts of the table screen the tour can point at. */
enum class TourTarget { INFO, AUCTION, HAND, BID_GRID, BID_SPECIAL, STATUS, CONCEDE, TRICK, SEAT_N, SEAT_W, SEAT_E, ADVICE }

/** Where each target is on screen right now; a target that is not drawn has no entry. */
class TourBounds {
    val map = mutableStateMapOf<TourTarget, Rect>()
}

fun Modifier.tourTarget(bounds: TourBounds, key: TourTarget): Modifier = composed {
    DisposableEffect(key) { onDispose { bounds.map.remove(key) } }
    onGloballyPositioned { bounds.map[key] = it.boundsInRoot() }
}

/** One stop of the tour; a null [target] is a plain message in the middle of the screen. */
class TourStep(val target: TourTarget?, val title: Int, val body: Int)

/** A group of steps with one bit in the saved "seen" mask. The mask lets each part be shown once. */
class TourPiece(val bit: Int, val steps: List<TourStep>)

object Tour {
    const val AUCTION = 1
    const val PLAY = 2
    const val DUMMY = 4
    const val ADVICE = 8
    const val ALL = AUCTION or PLAY or DUMMY or ADVICE

    /** One stop for the Advice button, shown once on the player's first turn after it was added. */
    val adviceSteps = listOf(TourStep(TourTarget.ADVICE, R.string.tour_advice_t, R.string.tour_advice_b))

    val auctionSteps = listOf(
        TourStep(TourTarget.INFO, R.string.tour_info_t, R.string.tour_info_b),
        TourStep(TourTarget.AUCTION, R.string.tour_auction_t, R.string.tour_auction_b),
        TourStep(TourTarget.HAND, R.string.tour_hand_t, R.string.tour_hand_b),
        TourStep(TourTarget.BID_GRID, R.string.tour_bidbox_t, R.string.tour_bidbox_b),
        TourStep(TourTarget.BID_SPECIAL, R.string.tour_special_t, R.string.tour_special_b)
    )

    /** [hiddenSeat] is a seat whose cards cannot be seen, to explain the card counts. */
    fun playSteps(hiddenSeat: TourTarget?): List<TourStep> = listOfNotNull(
        TourStep(TourTarget.STATUS, R.string.tour_status_t, R.string.tour_status_b),
        TourStep(TourTarget.HAND, R.string.tour_cards_t, R.string.tour_cards_b),
        TourStep(TourTarget.TRICK, R.string.tour_trick_t, R.string.tour_trick_b),
        hiddenSeat?.let { TourStep(it, R.string.tour_hidden_t, R.string.tour_hidden_b) },
        TourStep(TourTarget.INFO, R.string.tour_score_t, R.string.tour_score_b),
        TourStep(TourTarget.CONCEDE, R.string.tour_concede_t, R.string.tour_concede_b),
        TourStep(null, R.string.tour_undo_t, R.string.tour_undo_b)
    )

    fun dummySteps(dummySeat: TourTarget) =
        listOf(TourStep(dummySeat, R.string.tour_dummy_t, R.string.tour_dummy_b))
}

/**
 * A dimmed screen with a hole around the control being explained and a card with the explanation
 * next to it. Taps outside the card do nothing, so the table underneath stays untouched.
 */
@Composable
fun BoardTourOverlay(
    step: TourStep,
    number: Int,
    total: Int,
    target: Rect?,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val gap = 14.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot(); size = it.size }
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color(0xCC000000))
            if (target != null) {
                val pad = 6.dp.toPx()
                val topLeft = Offset(target.left - origin.x - pad, target.top - origin.y - pad)
                val holeSize = Size(target.width + 2 * pad, target.height + 2 * pad)
                val corner = CornerRadius(10.dp.toPx())
                drawRoundRect(Color.Black, topLeft, holeSize, corner, blendMode = BlendMode.Clear)
                drawRoundRect(AccentGold, topLeft, holeSize, corner, style = Stroke(3.dp.toPx()))
            }
        }

        // the card goes on the side of the target that has more room
        val top = if (target != null) target.top - origin.y else 0f
        val bottom = if (target != null) target.bottom - origin.y else 0f
        val above = target != null && top > size.height - bottom
        val cardModifier = when {
            target == null -> Modifier.align(Alignment.Center).padding(16.dp)
            above -> Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp, end = 16.dp, top = 12.dp,
                    bottom = with(density) { (size.height - top).coerceAtLeast(0f).toDp() } + gap
                )
            else -> Modifier
                .align(Alignment.TopCenter)
                .padding(
                    start = 16.dp, end = 16.dp, bottom = 12.dp,
                    top = with(density) { bottom.coerceAtLeast(0f).toDp() } + gap
                )
        }
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = cardModifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0xFF1E1E1E))
                .border(1.dp, AccentGold, shape)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(step.title),
                fontSize = 22.sp,
                fontFamily = FontFamily.Serif,
                color = AccentGold
            )
            Text(
                text = stringResource(step.body),
                fontSize = 16.sp,
                lineHeight = 23.sp,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.tour_count, number, total),
                    color = Color(0xFFAAAAAA),
                    fontSize = 14.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (number < total) {
                        TextButton(onClick = onSkip) { Text(stringResource(R.string.tut_skip)) }
                    }
                    Button(onClick = onNext) {
                        Text(stringResource(if (number < total) R.string.learn_next else R.string.tour_done))
                    }
                }
            }
        }
    }
}
