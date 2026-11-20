package com.an0obis.bridge.ui.online

import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.Bot
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.Scoring
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.play.Claims
import com.an0obis.bridge.model.sayc.SaycBot
import com.an0obis.bridge.model.session.Baseline
import com.an0obis.bridge.ui.solo.BoardRecord
import com.an0obis.bridge.ui.solo.Ending
import com.an0obis.bridge.ui.solo.PlayerTag
import com.an0obis.bridge.ui.solo.SeatView
import com.an0obis.bridge.ui.solo.TableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Who plays a room seat: the host on this phone, a bot run here, or a guest over the network. */
enum class SeatKind { LOCAL, BOT, REMOTE }

/** The table as the room was when the host started the game. Lists are by room seat (0 = host). */
class HostSetup(val names: List<String>, val kinds: List<SeatKind>, val rules: RoomRules)

/** Timings of the host's game loop (shorter in tests). */
class HostTiming(
    val botCallMs: Long = 500,
    val botCardMs: Long = 600,
    val forcedCardMs: Long = 450,
    /** a finished trick stays on the table this long (nobody can take it early online) */
    val trickPauseMs: Long = 1300,
    val collectMs: Long = 200,
    val fieldTables: Int = Baseline.TABLES
)

/**
 * The online board, run on the host's phone: the same flow as solo play (bots, claims, the bot field
 * for matchpoints), but a seat may be a guest whose moves arrive over the network. Every change is
 * sent to each guest as that guest's own view of the table (see [turnedFor]).
 *
 * Seats here are compass seats; the host sits South. A session is 16 boards; after each board every
 * player who is online presses Next board (or the room's auto-continue does it), and after the last
 * one the session result is shown until the host starts a new session.
 */
class OnlineHost(
    private val scope: CoroutineScope,
    private val setup: HostSetup,
    /** sends [NetMsg] to a guest's room seat */
    private val send: (roomSeat: Int, msg: NetMsg) -> Unit,
    private val timing: HostTiming = HostTiming()
) {
    companion object {
        const val SESSION_SIZE = 16

        /** What [move] gets when a player presses Next board. */
        val NEXT = Any()
    }

    private val seating = setup.rules.seating
    private fun kind(seat: Int) = setup.kinds[roomSeatOf(seat)]
    private fun name(seat: Int) = setup.names[roomSeatOf(seat)]
    private fun human(seat: Int) = kind(seat) != SeatKind.BOT

    /** Compass seats of guests who are connected right now (the host is always there). */
    private val online = (0..3).filter { kind(it) == SeatKind.REMOTE }.toMutableSet()

    private var seed = newSeed()
    private var boardNumber = 1
    private var hands: List<Hand> = dealFor(1)
    private var game = BoardGame(1, hands)
    private var bots: List<Bot> = newBots(1)
    private var field: Deferred<List<Int>>? = null
    private val records = mutableListOf<BoardRecord>()
    private var percent: Double? = null
    private var ending = Ending.PLAYED

    /** The seat whose move the loop is waiting for. */
    private var awaiting: Int? = null
    private var lingering: List<Pair<Int, Card>>? = null
    private var collectTo: Int? = null

    /** After a board: the seats that pressed Next board. Null while the board is being played. */
    private var confirmed: MutableSet<Int>? = null
    private var sessionOver = false
    private val summaryClosed = mutableSetOf<Int>()

    /** A move of [seat] made on [step] (see [TableState.step]); seat -1 only wakes the loop up. */
    private class Move(val seat: Int, val item: Any, val step: Int)

    private val moves = Channel<Move>(Channel.UNLIMITED)

    /** Goes up with every call, card and new board; moves made on an older step are void. */
    private var step = 0
    private var job: Job? = null

    private val _hostState = MutableStateFlow(buildState(Seat.SOUTH))

    /** The host's own view of the table. */
    val hostState: StateFlow<TableState> = _hostState.asStateFlow()

    private fun newSeed() = java.util.UUID.randomUUID().toString().replace("-", "")
    private fun dealFor(n: Int) = Deck.deal(SeasonRandom.forPurpose(seed, "deal", n))
    private fun newBots(n: Int): List<Bot> =
        List(4) { seat -> SaycBot(SeasonRandom.forPurpose(seed, "bots", n * 4 + seat), seating = seating) }

    fun start() {
        startBoard()
    }

    fun stop() {
        job?.cancel()
        field?.cancel()
    }

    /**
     * A move of the player at compass [seat] made on [atStep]: a [Call], a [Card] or [NEXT]. Wrong,
     * late or repeated moves are ignored.
     */
    fun move(seat: Int, item: Any, atStep: Int) {
        if (sessionOver) {
            // between sessions Next board (labelled "Session result") opens the result again
            if (item === NEXT) {
                summaryClosed.remove(seat)
                if (seat == Seat.SOUTH) publish() else sendTo(seat)
            }
            return
        }
        moves.trySend(Move(seat, item, atStep))
    }

    /** A message from the guest at [roomSeat]. */
    fun onGuest(roomSeat: Int, act: NetMsg.Act) {
        val seat = compassOf(roomSeat)
        if (kind(seat) != SeatKind.REMOTE) return
        if (act.sync == true) {
            sendTo(seat)
            return
        }
        val at = act.step ?: return
        when {
            act.next == true -> move(seat, NEXT, at)
            act.call != null -> runCatching { com.an0obis.bridge.model.session.Seasons.decode(act.call) }
                .getOrNull()?.takeIf { it is Call }?.let { move(seat, it, at) }
            act.card != null -> runCatching { com.an0obis.bridge.model.session.Seasons.decode(act.card) }
                .getOrNull()?.takeIf { it is Card }?.let { move(seat, it, at) }
        }
    }

    /** A guest went offline or came back; one who is back gets the table again. */
    fun setOnline(roomSeat: Int, connected: Boolean) {
        val seat = compassOf(roomSeat)
        if (kind(seat) != SeatKind.REMOTE) return
        val changed = if (connected) online.add(seat) else online.remove(seat)
        if (changed) {
            // an offline player is not waited for at Next board
            moves.trySend(Move(-1, Unit, -1))
            publish()
        }
    }

    /** The host starts the next 16 boards (after a finished session). */
    fun newSession() {
        if (!sessionOver) return
        seed = newSeed()
        boardNumber = 1
        records.clear()
        sessionOver = false
        summaryClosed.clear()
        startBoard()
    }

    /** The player at [seat] closed the session result. */
    fun closeSummary(seat: Int) {
        summaryClosed.add(seat)
        if (seat == Seat.SOUTH) publish() else sendTo(seat)
    }

    // ------------------------------------------------------------------ the game loop

    private fun startBoard() {
        job?.cancel()
        hands = dealFor(boardNumber)
        game = BoardGame(boardNumber, hands)
        bots = newBots(boardNumber)
        val s = seed
        val n = boardNumber
        val h = hands
        field?.cancel()
        field = scope.async(Dispatchers.Default) { Baseline.field(s, n, h, seating, timing.fieldTables) }
        percent = null
        ending = Ending.PLAYED
        lingering = null
        collectTo = null
        confirmed = null
        awaiting = null
        while (moves.tryReceive().isSuccess) { /* moves of the last board are void */ }
        step++
        job = scope.launch { playBoard() }
    }

    private suspend fun playBoard() {
        while (game.phase != Phase.DONE) {
            if (game.phase == Phase.PLAY && claimIfSure()) break
            val c = game.controller
            if (human(c)) {
                val forced = if (game.phase == Phase.PLAY) game.view().legalCards.singleOrNull() else null
                if (forced != null) {
                    publish()
                    delay(timing.forcedCardMs)
                    playAndShow(forced)
                    continue
                }
                awaiting = c
                publish()
                val item = awaitMove(c)
                awaiting = null
                if (item is Call) {
                    game.call(item)
                    step++
                    publish()
                } else playAndShow(item as Card)
            } else {
                publish()
                delay(if (game.phase == Phase.AUCTION) timing.botCallMs else timing.botCardMs)
                val view = game.view()
                if (game.phase == Phase.AUCTION) {
                    game.call(bots[c].chooseCall(view))
                    step++
                    publish()
                } else {
                    playAndShow(withContext(Dispatchers.Default) { bots[c].chooseCard(view) })
                }
            }
        }
        val result = game.result!!
        step++ // Next board belongs to the finished board
        percent = Scoring.matchpointPercent(result.northSouthScore, field?.await() ?: emptyList())
        records.add(BoardRecord(boardNumber, result.contract, result.declarerTricks, result.northSouthScore, percent!!))
        waitForNext()
        if (boardNumber >= SESSION_SIZE) {
            sessionOver = true
            confirmed = null
            publish()
        } else {
            boardNumber++
            startBoard()
        }
    }

    /** The next legal move of [seat]; anything else in the queue is dropped. */
    private suspend fun awaitMove(seat: Int): Any {
        while (true) {
            val m = moves.receive()
            val item = m.item
            if (m.seat != seat || m.step != step) continue
            when {
                item is Call && game.phase == Phase.AUCTION && game.auction.isLegal(item) -> return item
                item is Card && game.phase == Phase.PLAY && item in game.view().legalCards -> return item
            }
        }
    }

    /** Everybody online presses Next board; the room's auto-continue presses it for them. */
    private suspend fun waitForNext() {
        val ready = mutableSetOf<Int>()
        confirmed = ready
        fun missing() = (0..3).filter { human(it) && (kind(it) == SeatKind.LOCAL || it in online) && it !in ready }
        publish()
        val auto = setup.rules.autoNextSec
        val all = withTimeoutOrNull(if (auto > 0) auto * 1000L else Long.MAX_VALUE) {
            while (missing().isNotEmpty()) {
                val m = moves.receive()
                if (m.item === NEXT && m.step == step && human(m.seat)) {
                    ready.add(m.seat)
                    publish()
                }
            }
        }
        if (all == null) publish()
    }

    private suspend fun playAndShow(card: Card) {
        val play = game.play!!
        val before = play.tricks.size
        game.playCard(card)
        step++
        if (play.tricks.size > before) {
            val trick = play.tricks.last()
            lingering = trick.cards.mapIndexed { i, c -> (trick.leader + i) % 4 to c }
            publish()
            delay(timing.trickPauseMs)
            collectTo = trick.winner
            publish()
            delay(timing.collectMs)
            collectTo = null
            lingering = null
        }
        publish()
    }

    private suspend fun claimIfSure(): Boolean {
        val play = game.play ?: return false
        if (play.currentTrick.isNotEmpty()) return false
        val winner = withContext(Dispatchers.Default) { Claims.sureWinner(play) } ?: return false
        game.giveRest(winner)
        step++
        ending = Ending.CLAIMED
        return true
    }

    // ------------------------------------------------------------------ what each player sees

    private fun sendTo(seat: Int) {
        if (kind(seat) != SeatKind.REMOTE) return
        send(roomSeatOf(seat), NetMsg.State(buildState(seat).turnedFor(seat).toWire()))
    }

    private fun publish() {
        _hostState.value = buildState(Seat.SOUTH)
        for (seat in 0..3) sendTo(seat)
    }

    /** The table as the player at compass [viewer] may see it (not yet turned). */
    private fun buildState(viewer: Int): TableState {
        val g = game
        val play = g.play
        val done = g.phase == Phase.DONE
        val contract = play?.contract
        val seats = List(4) { seat ->
            val cards = if (play == null || done) g.startingHand(seat).cards else Hand(play.cardsOf(seat)).cards
            val faceUp = done || seat == viewer || (play != null && play.dummyVisible && seat == contract?.dummy)
            SeatView(if (faceUp) cards else null, cards.size)
        }
        val trick = lingering ?: play?.let { p -> p.currentTrick.mapIndexed { i, c -> (p.trickLeader + i) % 4 to c } }
            ?: emptyList()
        val acting = !done && awaiting == viewer
        val ready = confirmed
        return TableState(
            boardNumber = boardNumber,
            sessionSize = SESSION_SIZE,
            dealer = g.dealer,
            vulnerability = g.vulnerability,
            phase = g.phase,
            calls = g.auction.calls,
            seats = seats,
            trick = trick,
            turnSeat = if (done) null else g.actingSeat,
            humanToAct = acting,
            legalCalls = if (acting && g.phase == Phase.AUCTION) g.auction.legalCalls().toSet() else emptySet(),
            legalCards = if (acting && g.phase == Phase.PLAY) g.view().legalCards.toSet() else emptySet(),
            contract = g.auction.contract,
            tricksNorthSouth = play?.tricksWon(Seat.NORTH) ?: 0,
            tricksEastWest = play?.tricksWon(Seat.EAST) ?: 0,
            result = g.result,
            ending = ending,
            percent = percent,
            records = records.toList(),
            showSummary = sessionOver && viewer !in summaryClosed,
            trickDone = lingering != null,
            collectTo = collectTo,
            players = List(4) { PlayerTag(name(it), kind(it) == SeatKind.BOT) },
            waitingFor = if (ready == null) emptyList()
            else (0..3).filter { human(it) && (kind(it) == SeatKind.LOCAL || it in online) && it !in ready }.map { name(it) },
            confirmed = ready != null && viewer in ready,
            step = step,
            canNewSession = viewer == Seat.SOUTH
        )
    }
}
