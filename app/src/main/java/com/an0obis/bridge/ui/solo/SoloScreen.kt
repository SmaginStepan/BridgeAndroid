package com.an0obis.bridge.ui.solo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.an0obis.bridge.model.advice.BiddingGuide
import com.an0obis.bridge.ui.system.BiddingGuideDialog
import com.an0obis.bridge.ui.system.coloredSuits
import com.an0obis.bridge.ui.system.meaningText
import com.an0obis.bridge.ui.system.rangesText
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.Vulnerability
import com.an0obis.bridge.model.advice.Advice
import com.an0obis.bridge.model.advice.Reason
import com.an0obis.bridge.model.sayc.Meaning
import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.monetization.AdsManager
import com.an0obis.bridge.monetization.Engagement
import com.an0obis.bridge.ui.AccentGold
import com.an0obis.bridge.ui.misc.RateBanner
import com.an0obis.bridge.ui.misc.findActivity
import com.an0obis.bridge.ui.misc.openStorePage

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

private fun seatTarget(seat: Int): TourTarget = when (seat) {
    Seat.NORTH -> TourTarget.SEAT_N
    Seat.EAST -> TourTarget.SEAT_E
    else -> TourTarget.SEAT_W
}

/** The parts of the tour that apply right now: only on the player's own turn, and only if not seen yet. */
private fun tourPieces(s: TableState, seen: Int, adviceAllowed: Boolean): List<TourPiece> {
    if (s.showSummary || !s.humanToAct) return emptyList()
    val pieces = mutableListOf<TourPiece>()
    when (s.phase) {
        Phase.AUCTION -> if (seen and Tour.AUCTION == 0) pieces += TourPiece(Tour.AUCTION, Tour.auctionSteps)
        Phase.PLAY -> {
            if (seen and Tour.PLAY == 0) {
                val hidden = listOf(Seat.WEST, Seat.EAST, Seat.NORTH).firstOrNull { s.seats[it].cards == null }
                pieces += TourPiece(Tour.PLAY, Tour.playSteps(hidden?.let { seatTarget(it) }))
            }
            // the dummy is on the table only after the opening lead
            val dummy = s.contract?.dummy
            if (seen and Tour.DUMMY == 0 && dummy != null && dummy != Seat.SOUTH && s.seats[dummy].cards != null) {
                pieces += TourPiece(Tour.DUMMY, Tour.dummySteps(seatTarget(dummy)))
            }
        }
        Phase.DONE -> {}
    }
    if (adviceAllowed && seen and Tour.ADVICE == 0) pieces += TourPiece(Tour.ADVICE, Tour.adviceSteps)
    return pieces
}

/**
 * The table. [adviceAllowed] switches the Advice button off: the Season game, where every player
 * gets the same deals and the results are compared, must call this with false.
 */
@Composable
fun SoloScreen(onBack: () -> Unit, adviceAllowed: Boolean = true, vm: TableController = viewModel<SoloViewModel>()) {
    val s by vm.state.collectAsState()
    var showResult by remember(s.boardNumber) { mutableStateOf(true) }
    var explainIndex by remember(s.boardNumber) { mutableStateOf<Int?>(null) }
    var showConcede by remember(s.boardNumber) { mutableStateOf(false) }

    val context = LocalContext.current.applicationContext
    val activity = LocalContext.current.findActivity()
    // between a finished board and the next one: an ad, when the rules say so
    val onNext: () -> Unit = {
        if (activity != null && vm.showsAds) AdsManager.showIfDue(activity, Engagement.state().dealsFinished) { vm.nextBoard() }
        else vm.nextBoard()
    }
    var rateLater by remember { mutableStateOf(false) }
    val cloth = remember { BitmapPainter(CardArt.cloth(context)) }

    // the on-table tour: parts the player has not seen yet are shown when it is their turn
    val settings = remember { AppSettings() }
    val bounds = remember { TourBounds() }
    var tourSeen by remember { mutableIntStateOf(settings.boardTourSeen) }
    var tourIndex by remember { mutableIntStateOf(0) }
    val pieces = tourPieces(s, tourSeen, adviceAllowed)
    val tourSteps = pieces.flatMap { it.steps }.filter { it.target == null || it.target in bounds.map }
    val tourStep = tourSteps.getOrNull(tourIndex.coerceAtMost(tourSteps.lastIndex))
    val endTour = { mask: Int ->
        tourSeen = tourSeen or mask
        settings.boardTourSeen = tourSeen
        tourIndex = 0
    }
    val finishTour = { endTour(pieces.fold(0) { acc, p -> acc or p.bit }) }
    BackHandler(enabled = tourStep != null) { endTour(Tour.ALL) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(Felt).paint(cloth, contentScale = ContentScale.Crop)) {
            Box(modifier = Modifier.tourTarget(bounds, TourTarget.INFO)) {
                InfoBar(s, bounds, if (adviceAllowed) vm::requestAdvice else null)
            }

            if (s.phase == Phase.AUCTION) {
                // the auction: a scrollable list of the calls made so far fills the middle
                AuctionList(
                    s,
                    onCallClick = { explainIndex = it },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        .tourTarget(bounds, TourTarget.AUCTION)
                )
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp).tourTarget(bounds, TourTarget.SEAT_N)) {
                        NorthSeat(s, vm::onCard)
                    }
                    Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp).tourTarget(bounds, TourTarget.SEAT_W)) {
                        SideSeat(s, Seat.WEST)
                    }
                    Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).tourTarget(bounds, TourTarget.SEAT_E)) {
                        SideSeat(s, Seat.EAST)
                    }
                    Box(modifier = Modifier.align(Alignment.Center).tourTarget(bounds, TourTarget.TRICK)) {
                        TrickArea(s.trick, s.collectTo)
                    }
                }
            }

            // South: the human's own hand
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().tourTarget(bounds, TourTarget.HAND)
            ) {
                SeatLabel(s, Seat.SOUTH)
                val south = s.seats[Seat.SOUTH].cards ?: emptyList()
                val southActive = s.humanToAct && s.phase == Phase.PLAY && s.turnSeat == Seat.SOUTH
                HandGrid(
                    cards = south,
                    legal = if (southActive) s.legalCards else null,
                    onCard = vm::onCard,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                when (s.phase) {
                    // the bidding grid appears only on the player's turn
                    Phase.AUCTION -> if (s.humanToAct) BidGrid(s.legalCalls, bounds, vm::onCall) else StatusRow(s, bounds, vm::undo) { showConcede = true }
                    Phase.PLAY -> StatusRow(s, bounds, vm::undo) { showConcede = true }
                    Phase.DONE -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (s.confirmed) {
                            // online: this player is ready, the others have not pressed Next yet
                            WaitingText(s, Modifier.weight(1f))
                        } else {
                            Button(onClick = onNext, modifier = Modifier.weight(1f)) {
                                Text(stringResource(if (s.isLastBoard) R.string.solo_session_result else R.string.solo_next))
                            }
                        }
                        TextButton(onClick = onBack) {
                            Text(stringResource(if (s.online) R.string.mp_leave else R.string.solo_menu), color = Color.White)
                        }
                    }
                }
            }
        }

        // a finished trick lies on the table for a moment: a tap anywhere takes it at once
        if (s.trickDone && s.collectTo == null) {
            Box(
                modifier = Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = vm::takeTrick
                )
            )
        }

        if (tourStep != null) {
            val index = tourSteps.indexOf(tourStep)
            BoardTourOverlay(
                step = tourStep,
                number = index + 1,
                total = tourSteps.size,
                target = tourStep.target?.let { bounds.map[it] },
                onNext = { if (index < tourSteps.lastIndex) tourIndex = index + 1 else finishTour() },
                onSkip = { endTour(Tour.ALL) }
            )
        }
    }

    val explain = explainIndex
    if (explain != null && explain in s.calls.indices) {
        MeaningDialog(
            call = s.calls[explain],
            meanings = vm.explain(explain),
            onDismiss = { explainIndex = null }
        )
    }

    val adviceUi by vm.advice.collectAsState()
    var guideSection by remember { mutableStateOf<String?>(null) }
    AdviceDialog(
        state = adviceUi,
        onDo = { a -> a.call?.let { vm.onCall(it) }; a.card?.let { vm.onCard(it) } },
        onClose = vm::closeAdvice,
        onMore = { guideSection = it }
    )
    guideSection?.let { section -> BiddingGuideDialog(initial = section, config = vm.ourSystem, onClose = { guideSection = null }) }

    if (showConcede && s.canConcede) {
        AlertDialog(
            onDismissRequest = { showConcede = false },
            title = { Text(stringResource(R.string.solo_concede_title)) },
            text = { Text(stringResource(R.string.solo_concede_text)) },
            confirmButton = {
                TextButton(onClick = { showConcede = false; vm.concede() }) { Text(stringResource(R.string.solo_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showConcede = false }) { Text(stringResource(R.string.solo_no)) }
            }
        )
    }

    if (s.seasonOver) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.menu_season)) },
            text = { Text(stringResource(R.string.season_over)) },
            confirmButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.solo_menu)) } }
        )
    }

    if (s.showSummary) {
        SessionDialog(s, onNew = vm::newSession, onClose = vm::dismissSummary, onMenu = onBack)
    }
    if (s.leaderboardAsk) {
        LeaderboardJoinDialog(onJoin = vm::joinLeaderboard, onDecline = vm::declineLeaderboard)
    }

    val result = s.result
    if (s.phase == Phase.DONE && result != null && showResult && !s.showSummary) {
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
                    when (s.ending) {
                        Ending.CLAIMED -> Text(stringResource(R.string.solo_claimed), fontSize = 13.sp)
                        Ending.CONCEDED -> Text(stringResource(R.string.solo_conceded), fontSize = 13.sp)
                        Ending.PLAYED -> {}
                    }
                    Text(
                        text = s.percent?.let { stringResource(R.string.solo_percent, percentText(it)) }
                            ?: stringResource(R.string.solo_percent_wait),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (s.confirmed) WaitingText(s, Modifier.padding(top = 8.dp))
                    if (!s.online && !rateLater && Engagement.rateBannerDue()) {
                        RateBanner(
                            onRate = { openStorePage(context); Engagement.markRated(); rateLater = true },
                            onLater = { rateLater = true },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                if (!s.confirmed) {
                    Button(onClick = onNext) {
                        Text(stringResource(if (s.isLastBoard) R.string.solo_session_result else R.string.solo_next))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showResult = false }) { Text(stringResource(R.string.solo_view_table)) }
            }
        )
    }
}

private fun percentText(p: Double): String = "%.0f%%".format(p)

/** Asked once, after the first finished session: does the player want to appear on the Top players list? */
@Composable
internal fun LeaderboardJoinDialog(onJoin: (String) -> Unit, onDecline: () -> Unit) {
    var name by remember {
        val saved = AppSettings()
        mutableStateOf(if (saved.isDefaultPlayerName) "" else saved.playerName)
    }
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.top_join_title)) },
        text = {
            Column {
                Text(stringResource(R.string.top_join_text), fontSize = 15.sp)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.top_your_name)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onJoin(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.top_join_yes))
            }
        },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.top_join_no)) } }
    )
}

@Composable
private fun InfoBar(s: TableState, bounds: TourBounds, onAdvice: (() -> Unit)?) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = listOf(
                    // a season game names its session: the deal sequence runs on across sessions
                    (s.seasonSession?.let { stringResource(R.string.season_session_fmt, it) + "  •  " } ?: "") +
                        stringResource(R.string.solo_board_of, s.boardNumber, s.sessionSize),
                    stringResource(R.string.solo_dealer, seatLetter(s.dealer)),
                    stringResource(R.string.solo_vul, vulnerabilityText(s.vulnerability))
                ).joinToString("  •  "),
                color = LabelColor,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            s.average?.let {
                Text(stringResource(R.string.solo_average, percentText(it)), color = AccentGold, fontSize = 14.sp)
            }
            // on the player's own turn: what to bid or play, and why (not in the Season game)
            if (s.humanToAct && onAdvice != null) {
                TextButton(onClick = onAdvice, modifier = Modifier.tourTarget(bounds, TourTarget.ADVICE)) {
                    Text(stringResource(R.string.advice_btn), color = AccentGold, fontWeight = FontWeight.Bold)
                }
            }
        }
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

/** The result of a finished session: the average and every board with its matchpoint percentage. */
@Composable
private fun SessionDialog(s: TableState, onNew: () -> Unit, onClose: () -> Unit, onMenu: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.solo_session_result)) },
        text = {
            Column {
                s.average?.let {
                    Text(
                        stringResource(R.string.solo_session_average, percentText(it)),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    for (r in s.records.sortedBy { it.board }) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            val contract = r.contract
                            val diff = if (contract == null) 0 else r.declarerTricks - contract.tricksNeeded
                            Text("${r.board}", modifier = Modifier.weight(0.12f))
                            Text(
                                if (contract == null) "—" else contract.toString() + seatLetter(contract.declarer),
                                modifier = Modifier.weight(0.32f)
                            )
                            Text(
                                when { contract == null -> ""; diff == 0 -> "="; diff > 0 -> "+$diff"; else -> "$diff" },
                                modifier = Modifier.weight(0.14f)
                            )
                            Text(
                                if (r.northSouthScore > 0) "+${r.northSouthScore}" else "${r.northSouthScore}",
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(0.22f)
                            )
                            Text(
                                percentText(r.percent),
                                textAlign = TextAlign.End,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(0.2f)
                            )
                        }
                    }
                    if (s.records.size < s.sessionSize) {
                        Text(stringResource(R.string.solo_session_wait), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (!s.canNewSession) {
                        Text(stringResource(R.string.mp_waiting_session), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (s.canNewSession) {
                Button(onClick = onNew) {
                    Text(stringResource(if (s.seasonSession != null) R.string.season_next_session else R.string.solo_session_new))
                }
            } else {
                // online guests wait for the host to start the next session
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onMenu) { Text(stringResource(if (s.online) R.string.mp_leave else R.string.solo_menu)) }
        }
    )
}

/** Online: who still has to press Next board. */
@Composable
private fun WaitingText(s: TableState, modifier: Modifier = Modifier) {
    Text(
        text = if (s.waitingFor.isEmpty()) stringResource(R.string.solo_waiting_next)
        else stringResource(R.string.mp_waiting_confirm, s.waitingFor.joinToString(", ")),
        color = AccentGold,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(vertical = 10.dp)
    )
}

@Composable
private fun SeatLabel(s: TableState, seat: Int, modifier: Modifier = Modifier) {
    val active = s.turnSeat == seat
    // online: the player's name next to the seat letter
    val name = s.players?.getOrNull(seat)?.name?.let { "  $it" } ?: ""
    Text(
        text = seatLetter(seat) + name + if (s.seats[seat].cards == null) "  ${s.seats[seat].count}" else "",
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
private fun TrickArea(trick: List<Pair<Int, Card>>, collectTo: Int?) {
    Box(modifier = Modifier.size(176.dp, 150.dp)) {
        for ((seat, card) in trick) {
            key(card) { TrickCard(seat, card, collectTo) }
        }
    }
}

/** Where a seat's card lies on the table. */
private fun trickSpot(seat: Int): Pair<Dp, Dp> = when (seat) {
    Seat.NORTH -> 65.dp to 0.dp
    Seat.WEST -> 0.dp to 42.dp
    Seat.EAST -> 130.dp to 42.dp
    else -> 65.dp to 84.dp
}

/** The direction (towards the player's seat) a card comes from, or is swept to. */
private fun towards(seat: Int): Pair<Dp, Dp> = when (seat) {
    Seat.NORTH -> 0.dp to (-90).dp
    Seat.WEST -> (-110).dp to 0.dp
    Seat.EAST -> 110.dp to 0.dp
    else -> 0.dp to 110.dp
}

/** A card slides in from its player when played, and is swept to the winner when the trick is taken. */
@Composable
private fun TrickCard(seat: Int, card: Card, collectTo: Int?) {
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arrived = true }
    val (restX, restY) = trickSpot(seat)
    val (dx, dy) = if (collectTo != null) towards(collectTo) else if (arrived) 0.dp to 0.dp else towards(seat)
    val spec = tween<Dp>(if (collectTo != null) 200 else 160, easing = FastOutSlowInEasing)
    val x by animateDpAsState(restX + dx, spec, label = "card x")
    val y by animateDpAsState(restY + dy, spec, label = "card y")
    val alpha by animateFloatAsState(
        if (collectTo == null && arrived) 1f else 0f,
        tween(if (collectTo != null) 200 else 120),
        label = "card alpha"
    )
    CardFace(card, 52.dp, modifier = Modifier.offset(x, y).graphicsLayer { this.alpha = alpha })
}

// ---------------------------------------------------------------------------------------------
// Advice
// ---------------------------------------------------------------------------------------------

/** "Bid @@" with the call written in suit colours instead of the @@. */
private fun withSymbols(template: String, inner: AnnotatedString): AnnotatedString = buildAnnotatedString {
    val i = template.indexOf("@@")
    if (i < 0) {
        append(template)
    } else {
        append(template.substring(0, i))
        append(inner)
        append(template.substring(i + 2))
    }
}

// the advice dialog is dark, so its suit symbols use the light colours made for the green table
private fun cardLabel(card: Card): AnnotatedString = buildAnnotatedString {
    append(rankText(card.rank))
    withStyle(SpanStyle(color = suitColorOnTable(card.suit))) { append(suitSymbol(card.suit)) }
}

/** The one-line answer: "Bid 1♠", "Pass", "Play ♠K" with the suit symbols coloured. */
@Composable
private fun adviceHeadline(advice: Advice): AnnotatedString {
    val call = advice.call
    val card = advice.card
    return when {
        call is Call.Bid -> withSymbols(
            stringResource(R.string.advice_headline_bid, "@@"),
            denominationText(call.level.toString(), call.denom, onTable = true)
        )
        call is Call.Pass -> AnnotatedString(stringResource(R.string.advice_headline_pass))
        call is Call.Dbl -> AnnotatedString(stringResource(R.string.advice_headline_double))
        call is Call.Redbl -> AnnotatedString(stringResource(R.string.advice_headline_redouble))
        card != null -> withSymbols(stringResource(R.string.advice_headline_play, "@@"), cardLabel(card))
        else -> AnnotatedString("")
    }
}

/** One reason in the player's language: `adv_<key>` filled with its arguments, hearts and diamonds in red. */
@Composable
private fun reasonText(reason: Reason): AnnotatedString {
    val context = LocalContext.current
    val id = context.resources.getIdentifier("adv_${reason.key}", "string", context.packageName)
    if (id == 0) return AnnotatedString(reason.key)
    val args = reason.args.map { a ->
        if (a.startsWith("@seat:")) seatLetter(a.removePrefix("@seat:").toInt()) else a
    }
    return coloredSuits(context.getString(id, *args.toTypedArray()))
}

/** What to bid or play, and why: closed, being worked out, or the answer with "Do it" and "Close". */
@Composable
private fun AdviceDialog(state: AdviceUi, onDo: (Advice) -> Unit, onClose: () -> Unit, onMore: (String) -> Unit) {
    when (state) {
        is AdviceUi.None -> {}
        is AdviceUi.Loading -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.advice_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(stringResource(R.string.advice_thinking), modifier = Modifier.padding(start = 14.dp))
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.advice_close)) } }
        )
        is AdviceUi.Ready -> {
            val advice = state.advice
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(stringResource(R.string.advice_title)) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Text(adviceHeadline(advice), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.advice_why), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF666666))
                        for (reason in advice.reasons) {
                            Text(reasonText(reason), fontSize = 15.sp)
                        }
                        val meaning = advice.meaning
                        if (meaning != null) {
                            Column {
                                Text(stringResource(R.string.advice_means), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF666666))
                                Text(meaningText(meaning.ruleId), fontSize = 15.sp)
                                val ranges = rangesText(meaning)
                                if (ranges.isNotEmpty()) {
                                    Text(ranges, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                        // for a bid: the guide to bidding and to the bidding system, opened at the matching section
                        if (advice.call != null) {
                            TextButton(
                                onClick = { onMore(BiddingGuide.sectionFor(meaning?.ruleId, advice.reasons)) },
                                modifier = Modifier.align(Alignment.Start)
                            ) { Text(stringResource(R.string.guide_btn)) }
                        }
                    }
                },
                confirmButton = { Button(onClick = { onDo(advice) }) { Text(stringResource(R.string.advice_do_it)) } },
                dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.advice_close)) } }
            )
        }
    }
}

/** What a call means under the bidding system, tapped from the auction list. */
@Composable
private fun MeaningDialog(call: Call, meanings: List<Meaning>, onDismiss: () -> Unit) {
    val callLabel = if (call is Call.Pass) stringResource(R.string.bid_pass) else call.toString()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$callLabel — " + stringResource(R.string.sayc_title), fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (m in meanings) {
                    Column {
                        Text(meaningText(m.ruleId))
                        val ranges = rangesText(m)
                        if (ranges.isNotEmpty()) {
                            Text(ranges, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sayc_close)) } }
    )
}

/** The calls made so far, one column per seat; the header stays put and the rows scroll. */
@Composable
private fun AuctionList(s: TableState, onCallClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cells: List<Call?> = List<Call?>(s.dealer) { null } + s.calls
    val scroll = rememberScrollState()
    // keep the newest call in view
    LaunchedEffect(s.calls.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x33000000))
            .padding(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (seat in Seat.NORTH..Seat.WEST) {
                Text(
                    text = seatLetter(seat),
                    color = if (seat == s.turnSeat) AccentGold else LabelColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            cells.chunked(4).forEachIndexed { rowIndex, row ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    for (i in 0 until 4) {
                        val call = row.getOrNull(i)
                        val callIndex = rowIndex * 4 + i - s.dealer
                        Text(
                            text = if (call == null) AnnotatedString("") else callText(call),
                            color = Color.White,
                            fontSize = 22.sp,
                            textAlign = TextAlign.Center,
                            // tap a call to see what it means
                            modifier = Modifier.weight(1f).then(
                                if (call != null) Modifier.clickable { onCallClick(callIndex) } else Modifier
                            )
                        )
                    }
                }
            }
        }
    }
}

/** The status text with the Undo and Concede buttons next to it when they apply. */
@Composable
private fun StatusRow(s: TableState, bounds: TourBounds, onUndo: () -> Unit, onConcede: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f).tourTarget(bounds, TourTarget.STATUS)) { StatusLine(s) }
        if (s.canUndo) {
            TextButton(onClick = onUndo) { Text(stringResource(R.string.solo_undo), color = AccentGold) }
        }
        if (s.canConcede) {
            TextButton(
                onClick = onConcede,
                modifier = Modifier.tourTarget(bounds, TourTarget.CONCEDE)
            ) { Text(stringResource(R.string.solo_concede), color = LabelColor) }
        }
    }
}

@Composable
private fun StatusLine(s: TableState) {
    // online: who is to choose now (the declarer chooses for the dummy)
    val chooser = s.turnSeat?.let { t ->
        val contract = s.contract
        if (s.phase == Phase.PLAY && contract != null && t == contract.dummy) contract.declarer else t
    }
    val other = chooser?.takeIf { it != Seat.SOUTH }?.let { s.players?.getOrNull(it) }?.takeIf { !it.bot }
    val text = when {
        !s.humanToAct && other != null -> stringResource(R.string.mp_waiting_for, other.name)
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
            .height(44.dp)
            .clip(shape)
            .background(if (enabled) Color.White else Color(0x44FFFFFF))
            .border(if (selected) 3.dp else 1.dp, if (selected) AccentGold else Color(0xFF888888), shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    }
}

/**
 * The one-tap bidding grid: a row per level that still has a legal bid, a column per denomination.
 * Bids that are not legal in a shown row are dimmed. Pass, double and redouble sit underneath.
 * Shown only on the player's turn, so [legal] is never empty.
 */
@Composable
private fun BidGrid(legal: Set<Call>, bounds: TourBounds, onCall: (Call) -> Unit) {
    val levels = (1..7).filter { l -> legal.any { it is Call.Bid && it.level == l } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.tourTarget(bounds, TourTarget.BID_GRID)
        ) {
            for (level in levels) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (denom in Suit.CLUBS..Suit.NO_TRUMP) {
                        val bid = Call.Bid(level, denom)
                        BidKey(
                            label = denominationText(level.toString(), denom, onTable = false),
                            enabled = bid in legal,
                            modifier = Modifier.weight(1f)
                        ) { onCall(bid) }
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.tourTarget(bounds, TourTarget.BID_SPECIAL)
        ) {
            BidKey(
                label = AnnotatedString(stringResource(R.string.bid_pass)),
                enabled = Call.Pass in legal,
                modifier = Modifier.weight(2f)
            ) { onCall(Call.Pass) }
            BidKey(label = AnnotatedString("X"), enabled = Call.Dbl in legal, modifier = Modifier.weight(1f)) {
                onCall(Call.Dbl)
            }
            BidKey(label = AnnotatedString("XX"), enabled = Call.Redbl in legal, modifier = Modifier.weight(1f)) {
                onCall(Call.Redbl)
            }
        }
    }
}
