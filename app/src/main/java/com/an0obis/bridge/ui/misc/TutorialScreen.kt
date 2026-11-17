package com.an0obis.bridge.ui.misc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.an0obis.bridge.R
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.ui.AccentGold
import com.an0obis.bridge.ui.TableGreen
import com.an0obis.bridge.ui.solo.CardFace
import com.an0obis.bridge.ui.solo.suitColor
import com.an0obis.bridge.ui.solo.suitSymbol
import kotlinx.coroutines.launch

private class TutorialPage(
    val title: Int,
    val body: Int,
    val picture: (@Composable () -> Unit)? = null
)

private val PAGES = listOf(
    TutorialPage(R.string.tut1_title, R.string.tut1_body) { Compass() },
    TutorialPage(R.string.tut2_title, R.string.tut2_body),
    TutorialPage(R.string.tut3_title, R.string.tut3_body) { PointCards() },
    TutorialPage(R.string.tut4_title, R.string.tut4_body) { SuitOrder() },
    TutorialPage(R.string.tut5_title, R.string.tut5_body),
    TutorialPage(R.string.tut6_title, R.string.tut6_body),
    TutorialPage(R.string.tut7_title, R.string.tut7_body)
)

/** Four seats around a table; you and your partner are highlighted. */
@Composable
private fun Compass() {
    @Composable
    fun Seat(letter: String, caption: String, ours: Boolean) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (ours) AccentGold else Color(0xFF3A3A3A))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(letter, fontSize = 22.sp, color = if (ours) Color.Black else Color.White)
            Text(caption, fontSize = 12.sp, color = if (ours) Color.Black else Color(0xFFCCCCCC))
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(TableGreen)
            .padding(16.dp)
    ) {
        Seat(stringResource(R.string.seat_n), stringResource(R.string.tut_partner), true)
        Row(
            horizontalArrangement = Arrangement.spacedBy(56.dp),
            modifier = Modifier.padding(vertical = 10.dp)
        ) {
            Seat(stringResource(R.string.seat_w), stringResource(R.string.tut_opponent), false)
            Seat(stringResource(R.string.seat_e), stringResource(R.string.tut_opponent), false)
        }
        Seat(stringResource(R.string.seat_s), stringResource(R.string.tut_you), true)
    }
}

/** A, K, Q, J with their high-card points. */
@Composable
private fun PointCards() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(14, 13, 12, 11).forEach { rank ->
            val card = Card(rank, Suit.SPADES)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CardFace(card, width = 62.dp)
                Text(
                    text = "${card.points}",
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Serif,
                    color = AccentGold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** ♣ < ♦ < ♥ < ♠ < NT */
@Composable
private fun SuitOrder() {
    // separate items in one Row, so the line never wraps on a narrow screen
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF2F2F2))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        listOf(Suit.CLUBS, Suit.DIAMONDS, Suit.HEARTS, Suit.SPADES, Suit.NO_TRUMP).forEachIndexed { i, suit ->
            if (i > 0) Text("<", fontSize = 18.sp, color = Color(0xFF777777))
            Text(suitSymbol(suit), fontSize = 26.sp, color = suitColor(suit))
        }
    }
}

/**
 * The how-to-play pages. [onDone] runs for both Skip and the last page's button; it is where the
 * caller marks the tutorial as seen and decides where to go next.
 */
@Composable
fun TutorialScreen(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { PAGES.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES.size - 1

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!last) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.tut_skip)) }
            }
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
            val page = PAGES[index]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    text = stringResource(page.title),
                    fontSize = 30.sp,
                    fontFamily = FontFamily.Serif,
                    color = AccentGold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                page.picture?.let {
                    Box(modifier = Modifier.padding(bottom = 16.dp)) { it() }
                }
                Text(
                    text = stringResource(page.body),
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(PAGES.size) { i ->
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) AccentGold else Color(0xFF555555))
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pager.currentPage > 0) {
                TextButton(onClick = {
                    scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                }) { Text(stringResource(R.string.learn_prev)) }
            } else {
                Box(modifier = Modifier.size(1.dp))
            }
            Button(onClick = {
                if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }) {
                Text(stringResource(if (last) R.string.tut_start else R.string.learn_next))
            }
        }
    }
}
