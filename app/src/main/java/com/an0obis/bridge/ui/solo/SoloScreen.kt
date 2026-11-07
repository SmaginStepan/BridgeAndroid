package com.an0obis.bridge.ui.solo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.an0obis.bridge.R
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.Vulnerability
import com.an0obis.bridge.ui.AccentGold

private val Felt = Color(0xFF0B5D2E)
private val LabelColor = Color(0xFFE8F5E9)

@Composable
private fun seatLetter(seat: Int): String = stringResource(
    when (seat) {
        Seat.NORTH -> R.string.seat_n
        Seat.EAST -> R.string.seat_e
        Seat.SOUTH -> R.string.seat_s
        else -> R.string.seat_w
    }
)

@Composable
private fun vulnerabilityText(v: Vulnerability): String = stringResource(
    when (v) {
        Vulnerability.NONE -> R.string.vul_none
        Vulnerability.NS -> R.string.vul_ns
        Vulnerability.EW -> R.string.vul_ew
        Vulnerability.BOTH -> R.string.vul_both
    }
)

@Composable
private fun callText(call: Call): AnnotatedString = when (call) {
    is Call.Pass -> AnnotatedString(stringResource(R.string.bid_pass))
    is Call.Dbl -> AnnotatedString("X")
    is Call.Redbl -> AnnotatedString("XX")
    is Call.Bid -> denominationText(call.level.toString(), call.denom, onTable = true)
}

private fun denominationText(prefix: String, denom: Int, onTable: Boolean): AnnotatedString =
    buildAnnotatedString {
        append(prefix)
        val color = if (onTable) suitColorOnTable(denom) else suitColor(denom)
        withStyle(SpanStyle(color = if (denom == Suit.NO_TRUMP) Color.Unspecified else color)) {
            append(suitSymbol(denom))
        }
    }

@Composable
fun SoloScreen(onBack: () -> Unit, vm: SoloViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    var showResult by remember(s.boardNumber) { mutableStateOf(true) }

    val context = LocalContext.current.applicationContext
    val cloth = remember { BitmapPainter(CardArt.cloth(context)) }
    Column(modifier = Modifier.fillMaxSize().background(Felt).paint(cloth, contentScale = ContentScale.Crop)) {
        InfoBar(s)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp)) {
                NorthSeat(s, vm::onCard)
            }
            Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp)) {
                SideSeat(s, Seat.WEST)
            }
            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) {
                SideSeat(s, Seat.EAST)
            }
            Box(modifier = Modifier.align(Alignment.Center)) {
                if (s.phase == Phase.AUCTION) AuctionGrid(s) else TrickArea(s.trick)
            }
        }

        // South: the human's own hand
        SeatLabel(s, Seat.SOUTH, modifier = Modifier.align(Alignment.CenterHorizontally))
        val south = s.seats[Seat.SOUTH].cards ?: emptyList()
        val southActive = s.humanToAct && s.phase == Phase.PLAY && s.turnSeat == Seat.SOUTH
        HandGrid(
            cards = south,
            legal = if (southActive) s.legalCards else null,
            onCard = vm::onCard,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            when (s.phase) {
                Phase.AUCTION -> BiddingBox(s.legalCalls, s.humanToAct, vm::onCall)
                Phase.PLAY -> StatusLine(s)
                Phase.DONE -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = vm::nextBoard, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.solo_next))
                    }
                    TextButton(onClick = onBack) { Text(stringResource(R.string.solo_menu), color = Color.White) }
                }
            }
        }
    }

    val result = s.result
    if (s.phase == Phase.DONE && result != null && showResult) {
        AlertDialog(
            onDismissRequest = { showResult = false },
            title = { Text(stringResource(R.string.solo_result_title)) },
            text = {
                Column {
                    val contract = result.contract
                    if (contract == null) {
                        Text(stringResource(R.string.solo_passed_out))
                    } else {
                        Text(
                            stringResource(R.string.solo_contract, contract.toString(), seatLetter(contract.declarer)),
                            fontSize = 18.sp
                        )
                        val diff = result.declarerTricks - contract.tricksNeeded
                        Text(
                            when {
                                diff > 0 -> stringResource(R.string.solo_made_plus, diff)
                                diff == 0 -> stringResource(R.string.solo_made)
                                else -> stringResource(R.string.solo_down, -diff)
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    val signed = if (result.northSouthScore > 0) "+${result.northSouthScore}" else "${result.northSouthScore}"
                    Text(
                        stringResource(R.string.solo_score_ns, signed),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = vm::nextBoard) { Text(stringResource(R.string.solo_next)) }
            },
            dismissButton = {
                TextButton(onClick = { showResult = false }) { Text(stringResource(R.string.solo_view_table)) }
            }
        )
    }
}

@Composable
private fun InfoBar(s: TableState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            text = listOf(
                stringResource(R.string.solo_board, s.boardNumber),
                stringResource(R.string.solo_dealer, seatLetter(s.dealer)),
                stringResource(R.string.solo_vul, vulnerabilityText(s.vulnerability))
            ).joinToString("  •  "),
            color = LabelColor,
            fontSize = 14.sp
        )
        val contract = s.contract
        if (contract != null) {
            Text(
                text = stringResource(R.string.solo_contract, contract.toString(), seatLetter(contract.declarer)) +
                    "   " + stringResource(R.string.solo_tricks, s.tricksNorthSouth, s.tricksEastWest),
                color = AccentGold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun SeatLabel(s: TableState, seat: Int, modifier: Modifier = Modifier) {
    val active = s.turnSeat == seat
    Text(
        text = seatLetter(seat) + if (s.seats[seat].cards == null) "  ${s.seats[seat].count}" else "",
        color = if (active) AccentGold else LabelColor,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        fontSize = 15.sp,
        modifier = modifier
    )
}

@Composable
private fun NorthSeat(s: TableState, onCard: (Card) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SeatLabel(s, Seat.NORTH)
        val cards = s.seats[Seat.NORTH].cards
        if (cards != null) {
            val active = s.humanToAct && s.phase == Phase.PLAY && s.turnSeat == Seat.NORTH
            HandGrid(
                cards = cards,
                legal = if (active) s.legalCards else null,
                onCard = onCard,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        } else {
            CardBack(30.dp)
        }
    }
}

@Composable
private fun SideSeat(s: TableState, seat: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SeatLabel(s, seat)
        val cards = s.seats[seat].cards
        if (cards != null) SuitLines(cards) else CardBack(30.dp)
    }
}

/** The cards played to the current trick, laid out like a compass. */
@Composable
private fun TrickArea(trick: List<Pair<Int, Card>>) {
    Box(modifier = Modifier.size(176.dp, 150.dp)) {
        for ((seat, card) in trick) {
            val (x, y) = when (seat) {
                Seat.NORTH -> 65.dp to 0.dp
                Seat.WEST -> 0.dp to 42.dp
                Seat.EAST -> 130.dp to 42.dp
                else -> 65.dp to 84.dp
            }
            CardFace(card, 52.dp, modifier = Modifier.offset(x, y))
        }
    }
}

@Composable
private fun AuctionGrid(s: TableState) {
    val cellWidth = 52.dp
    val cells: List<Call?> = List<Call?>(s.dealer) { null } + s.calls
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x33000000))
            .padding(8.dp)
    ) {
        Row {
            for (seat in Seat.NORTH..Seat.WEST) {
                Text(
                    text = seatLetter(seat),
                    color = if (seat == s.turnSeat) AccentGold else LabelColor,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(cellWidth)
                )
            }
        }
        cells.chunked(4).forEach { row ->
            Row {
                for (i in 0 until 4) {
                    val call = row.getOrNull(i)
                    Text(
                        text = if (call == null) AnnotatedString("") else callText(call),
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(cellWidth)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(s: TableState) {
    val text = when {
        !s.humanToAct -> stringResource(R.string.solo_waiting)
        s.turnSeat == Seat.SOUTH -> stringResource(R.string.solo_your_turn)
        else -> stringResource(R.string.solo_play_dummy, seatLetter(s.turnSeat ?: Seat.NORTH))
    }
    Text(
        text = text,
        color = if (s.humanToAct) AccentGold else LabelColor,
        fontSize = 18.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
    )
}

@Composable
private fun BidKey(
    label: AnnotatedString,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(if (enabled) Color.White else Color(0x44FFFFFF))
            .border(if (selected) 3.dp else 1.dp, if (selected) AccentGold else Color(0xFF888888), shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    }
}

/** Pick a level, then a denomination; or pass / double / redouble. Only legal calls are enabled. */
@Composable
private fun BiddingBox(legal: Set<Call>, enabled: Boolean, onCall: (Call) -> Unit) {
    var level by remember(legal) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (l in 1..7) {
                BidKey(
                    label = AnnotatedString(l.toString()),
                    enabled = enabled && legal.any { it is Call.Bid && it.level == l },
                    selected = level == l,
                    modifier = Modifier.weight(1f)
                ) { level = l }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in Suit.CLUBS..Suit.NO_TRUMP) {
                val lv = level
                BidKey(
                    label = denominationText("", d, onTable = false),
                    enabled = enabled && lv != null && Call.Bid(lv, d) in legal,
                    modifier = Modifier.weight(1f)
                ) {
                    if (lv != null) onCall(Call.Bid(lv, d))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BidKey(
                label = AnnotatedString(stringResource(R.string.bid_pass)),
                enabled = enabled && Call.Pass in legal,
                modifier = Modifier.weight(2f)
            ) { onCall(Call.Pass) }
            BidKey(
                label = AnnotatedString("X"),
                enabled = enabled && Call.Dbl in legal,
                modifier = Modifier.weight(1f)
            ) { onCall(Call.Dbl) }
            BidKey(
                label = AnnotatedString("XX"),
                enabled = enabled && Call.Redbl in legal,
                modifier = Modifier.weight(1f)
            ) { onCall(Call.Redbl) }
        }
    }
}
