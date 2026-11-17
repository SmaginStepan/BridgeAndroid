package com.an0obis.bridge.ui.solo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.BoardResult
import com.an0obis.bridge.model.Bot
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Contract
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.GlobalScores
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.Scoring
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Vulnerability
import com.an0obis.bridge.model.advice.Advice
import com.an0obis.bridge.model.advice.BidAdvisor
import com.an0obis.bridge.model.advice.PlayAdvisor
import com.an0obis.bridge.model.play.Claims
import com.an0obis.bridge.monetization.Engagement
import com.an0obis.bridge.model.sayc.SaycBot
import com.an0obis.bridge.model.session.Baseline
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One seat as the table shows it. [cards] is null for a hidden hand; [count] is how many cards remain. */
data class SeatView(val cards: List<Card>?, val count: Int)

/** A finished board of the session: the result and the matchpoint percentage against the bot field. */
data class BoardRecord(
    val board: Int,
    val contract: Contract?,
    val declarerTricks: Int,
    val northSouthScore: Int,
    val percent: Double
)

/** Everything the table screen needs to draw. */
data class TableState(
    val boardNumber: Int = 1,
    val sessionSize: Int = SoloViewModel.SESSION_SIZE,
    val dealer: Int = Seat.NORTH,
    val vulnerability: Vulnerability = Vulnerability.NONE,
    val phase: Phase = Phase.AUCTION,
    val calls: List<Call> = emptyList(),
    val seats: List<SeatView> = List(4) { SeatView(null, 13) },
    /** Cards on the table: seat to card, in the order played. */
    val trick: List<Pair<Int, Card>> = emptyList(),
    val turnSeat: Int? = null,
    val humanToAct: Boolean = false,
    val legalCalls: Set<Call> = emptySet(),
    val legalCards: Set<Card> = emptySet(),
    val contract: Contract? = null,
    val tricksNorthSouth: Int = 0,
    val tricksEastWest: Int = 0,
    val result: BoardResult? = null,
    /** How the board ended early, if it did. */
    val ending: Ending = Ending.PLAYED,
    /** Matchpoint percentage of the finished board; null while the bot field is still being played. */
    val percent: Double? = null,
    /** The finished boards of this session. */
    val records: List<BoardRecord> = emptyList(),
    val showSummary: Boolean = false,
    val canUndo: Boolean = false,
    val canConcede: Boolean = false,
    /** The session is over and the player has not yet said whether to join the leaderboard. */
    val leaderboardAsk: Boolean = false
) {
    val average: Double? get() = if (records.isEmpty()) null else records.sumOf { it.percent } / records.size
    val isLastBoard: Boolean get() = boardNumber >= sessionSize
}

enum class Ending { PLAYED, CLAIMED, CONCEDED }

/** The advice dialog: closed, being worked out, or ready to show. */
sealed interface AdviceUi {
    object None : AdviceUi
    object Loading : AdviceUi
    class Ready(val advice: Advice) : AdviceUi
}

/**
 * Solo play: the human sits South, the partner and both opponents are bots. A session is 16 boards
 * dealt from one random seed (the same mechanism a season will use with the server's seed); every
 * board is ranked in matchpoints against a field of bot tables playing the same deal.
 */
class SoloViewModel : ViewModel() {

    companion object {
        const val SESSION_SIZE = 16

        /** How long the human can still undo before the bots answer. */
        private const val UNDO_WINDOW_MS = 900L
    }

    private enum class Kind { HUMAN, BOT, AUTO }
    private class Act(val item: Any, val kind: Kind)

    private val human = Seat.SOUTH

    private var seed = newSeed()
    private var boardNumber = 1
    private var dealHands: List<Hand> = dealFor(boardNumber)
    private var game = BoardGame(boardNumber, dealHands)
    private var bots: List<Bot> = newBots(boardNumber)
    private var fieldTables: Deferred<List<Int>> = startField()

    private val records = mutableListOf<BoardRecord>()
    private var percent: Double? = null
    private var ending = Ending.PLAYED
    private var showSummary = false

    /** The leaderboard choice (see [AppSettings]); kept here so drawing never reads a file. */
    private var leaderboardChoice = AppSettings().leaderboardChoice

    /** This session's result has already been queued for the leaderboard. */
    private var posted = false

    /** Every call and card of this board, to be able to take back the last human move. */
    private val actions = mutableListOf<Act>()

    /** The just-finished trick stays on the table for a moment so the player can see it. */
    private var lingering: List<Pair<Int, Card>>? = null
    private var busy = false
    private var job: Job? = null

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<TableState> = _state.asStateFlow()

    private val _advice = MutableStateFlow<AdviceUi>(AdviceUi.None)
    val advice: StateFlow<AdviceUi> = _advice.asStateFlow()

    /** Bumped whenever the advice is closed or becomes out of date, so a late answer is thrown away. */
    private var adviceToken = 0

    init {
        run()
    }

    private fun newSeed() = java.util.UUID.randomUUID().toString().replace("-", "")

    private fun dealFor(n: Int) = Deck.deal(SeasonRandom.forPurpose(seed, "deal", n))

    private fun newBots(n: Int): List<Bot> =
        List(4) { seat -> SaycBot(SeasonRandom.forPurpose(seed, "bots", n * 4 + seat)) }

    /** The bot tables for this board are played in the background while the human plays. */
    private fun startField(): Deferred<List<Int>> {
        val s = seed
        val n = boardNumber
        val hands = dealHands
        return viewModelScope.async(Dispatchers.Default) { Baseline.field(s, n, hands) }
    }

    // ------------------------------------------------------------------ the human's moves

    /**
     * Works out what to bid or play now and why, for the player's own turn. It is shown by the
     * screen; the answer is dropped if the position changes before it is ready.
     */
    fun requestAdvice() {
        if (busy || game.phase == Phase.DONE || game.controller != human) return
        val token = ++adviceToken
        val view = game.view()
        val auction = game.phase == Phase.AUCTION
        _advice.value = AdviceUi.Loading
        viewModelScope.launch {
            val advice = withContext(Dispatchers.Default) {
                if (auction) BidAdvisor.advise(view.auction.dealer, view.auction.calls, view.actingHand)
                else PlayAdvisor.advise(view)
            }
            if (token == adviceToken) _advice.value = AdviceUi.Ready(advice)
        }
    }

    fun closeAdvice() {
        adviceToken++
        _advice.value = AdviceUi.None
    }

    fun onCall(call: Call) {
        if (busy || game.phase != Phase.AUCTION || game.controller != human || !game.auction.isLegal(call)) return
        closeAdvice()
        run({ doCall(call, Kind.HUMAN) })
    }

    fun onCard(card: Card) {
        if (busy || game.phase != Phase.PLAY || game.controller != human) return
        if (card !in game.view().legalCards) return
        closeAdvice()
        run({ playAndShow(card, Kind.HUMAN) })
    }

    /** Gives the remaining tricks to the opponents. */
    fun concede() {
        if (busy || game.phase != Phase.PLAY || game.controller != human) return
        closeAdvice()
        game.giveRest(1 - (human and 1))
        ending = Ending.CONCEDED
        run()
    }

    private fun canUndoNow(): Boolean {
        if (game.phase == Phase.DONE) return false
        val last = actions.indexOfLast { it.kind == Kind.HUMAN }
        return last >= 0 && actions.drop(last + 1).all { it.kind == Kind.AUTO }
    }

    /** Takes back the last human move, if no bot has answered it yet. */
    fun undo() {
        if (!canUndoNow()) return
        closeAdvice()
        viewModelScope.launch {
            job?.cancelAndJoin()
            if (!canUndoNow()) { run(); return@launch }
            val keep = actions.take(actions.indexOfLast { it.kind == Kind.HUMAN })
            game = BoardGame(boardNumber, dealHands)
            actions.clear()
            lingering = null
            for (a in keep) {
                when (val x = a.item) {
                    is Call -> game.call(x)
                    is Card -> game.playCard(x)
                }
                actions.add(a)
            }
            run()
        }
    }

    fun nextBoard() {
        if (busy || game.phase != Phase.DONE) return
        closeAdvice()
        if (boardNumber >= SESSION_SIZE) {
            showSummary = true
            publish()
            return
        }
        boardNumber++
        startBoard()
    }

    fun newSession() {
        closeAdvice()
        job?.cancel()
        fieldTables.cancel()
        seed = newSeed()
        boardNumber = 1
        records.clear()
        showSummary = false
        posted = false
        busy = false
        startBoard()
    }

    fun dismissSummary() {
        showSummary = false
        publish()
    }

    /** The player agreed to appear on the leaderboard under [name]. */
    fun joinLeaderboard(name: String) {
        val settings = AppSettings()
        settings.playerName = name.trim()
        settings.leaderboardChoice = AppSettings.POST
        leaderboardChoice = AppSettings.POST
        postSessionIfDue()
        publish()
    }

    fun declineLeaderboard() {
        AppSettings().leaderboardChoice = AppSettings.DECLINED
        leaderboardChoice = AppSettings.DECLINED
        publish()
    }

    /** Queues the finished session's average for the leaderboard, once, if the player agreed. */
    private fun postSessionIfDue() {
        if (posted || records.size < SESSION_SIZE || leaderboardChoice != AppSettings.POST) return
        val average = records.sumOf { it.percent } / records.size
        posted = true
        GlobalScores.enqueue(AppSettings().playerName, average)
        viewModelScope.launch(Dispatchers.IO) { GlobalScores.flushPending() }
    }

    private fun startBoard() {
        dealHands = dealFor(boardNumber)
        game = BoardGame(boardNumber, dealHands)
        bots = newBots(boardNumber)
        fieldTables = startField()
        percent = null
        ending = Ending.PLAYED
        actions.clear()
        lingering = null
        run()
    }

    // ------------------------------------------------------------------ the game loop

    private fun doCall(call: Call, kind: Kind) {
        game.call(call)
        actions.add(Act(call, kind))
        publish()
    }

    private suspend fun playAndShow(card: Card, kind: Kind) {
        val before = game.play!!.tricks.size
        game.playCard(card)
        actions.add(Act(card, kind))
        val play = game.play!!
        if (play.tricks.size > before) {
            val trick = play.tricks.last()
            lingering = trick.cards.mapIndexed { i, c -> (trick.leader + i) % 4 to c }
            publish()
            delay(1000L)
            lingering = null
        }
        publish()
    }

    /** If the rest of the deal is decided, ends it. */
    private suspend fun claimIfSure(): Boolean {
        val play = game.play ?: return false
        if (play.currentTrick.isNotEmpty()) return false
        val winner = withContext(Dispatchers.Default) { Claims.sureWinner(play) } ?: return false
        game.giveRest(winner)
        ending = Ending.CLAIMED
        return true
    }

    /** Runs the optional first action, then lets the bots move until the human has to act. */
    private fun run(first: (suspend () -> Unit)? = null) {
        job = viewModelScope.launch {
            busy = true
            publish()
            try {
                first?.invoke()
                // after a human move there is a short pause in which it can be taken back
                var undoWindow = first != null
                while (game.phase != Phase.DONE) {
                    if (game.phase == Phase.PLAY && claimIfSure()) break
                    if (game.controller == human) {
                        // a forced card (the only legal one) is played for the human
                        val forced = if (game.phase == Phase.PLAY) game.view().legalCards.singleOrNull() else null
                        if (forced == null) break
                        publish()
                        delay(450L)
                        playAndShow(forced, Kind.AUTO)
                        continue
                    }
                    publish()
                    delay(if (undoWindow) UNDO_WINDOW_MS else if (game.phase == Phase.AUCTION) 500L else 600L)
                    undoWindow = false
                    val bot = bots[game.controller]
                    val view = game.view()
                    if (game.phase == Phase.AUCTION) {
                        doCall(bot.chooseCall(view), Kind.BOT)
                    } else {
                        // the card search is heavy: keep it off the main thread
                        val card = withContext(Dispatchers.Default) { bot.chooseCard(view) }
                        playAndShow(card, Kind.BOT)
                    }
                }
            } finally {
                busy = false
                publish()
            }
            if (game.phase == Phase.DONE) settleBoard()
        }
    }

    /** Ranks the finished board against the bot field (computed in the background). */
    private fun settleBoard() {
        val result = game.result ?: return
        val n = boardNumber
        val s = seed
        val tables = fieldTables
        viewModelScope.launch {
            val pct = Scoring.matchpointPercent(result.northSouthScore, tables.await())
            if (s != seed || records.any { it.board == n }) return@launch
            records.add(BoardRecord(n, result.contract, result.declarerTricks, result.northSouthScore, pct))
            Engagement.dealFinished() // counts towards the ad-free first boards and the rate reminder
            if (n == boardNumber) percent = pct
            postSessionIfDue()
            publish()
        }
    }

    // ------------------------------------------------------------------ what the screen shows

    private fun publish() {
        _state.value = buildState()
    }

    private fun buildState(): TableState {
        val g = game
        val play = g.play
        val done = g.phase == Phase.DONE
        val contract = play?.contract

        val seats = List(4) { seat ->
            val cards = when {
                done -> g.startingHand(seat).cards
                play == null -> g.startingHand(seat).cards
                else -> Hand(play.cardsOf(seat)).cards
            }
            val faceUp = done || seat == human || (play != null && play.dummyVisible && seat == contract?.dummy)
            SeatView(if (faceUp) cards else null, cards.size)
        }

        val trick = lingering ?: play?.let { p ->
            p.currentTrick.mapIndexed { i, c -> (p.trickLeader + i) % 4 to c }
        } ?: emptyList()

        val humanToAct = !busy && !done && g.controller == human
        // a human forced card is played automatically, so the human is never really "to act" for it
        val forcedCard = humanToAct && g.phase == Phase.PLAY && g.view().legalCards.size == 1
        val acting = humanToAct && !forcedCard
        return TableState(
            boardNumber = boardNumber,
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
            showSummary = showSummary,
            canUndo = canUndoNow(),
            canConcede = acting && g.phase == Phase.PLAY,
            leaderboardAsk = showSummary && leaderboardChoice == AppSettings.ASK
        )
    }
}
