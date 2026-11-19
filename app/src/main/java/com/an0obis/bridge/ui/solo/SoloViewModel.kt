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
import com.an0obis.bridge.model.sayc.Meaning
import com.an0obis.bridge.model.sayc.SaycBot
import com.an0obis.bridge.model.sayc.SystemCatalog
import com.an0obis.bridge.model.session.Baseline
import com.an0obis.bridge.model.session.SeasonBoard
import com.an0obis.bridge.model.session.SeasonState
import com.an0obis.bridge.model.session.Seasons
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    /** The four cards of a finished trick are on the table, waiting to be taken (a tap takes them at once). */
    val trickDone: Boolean = false,
    /** While the finished trick is swept away: the seat of the side that won it. */
    val collectTo: Int? = null,
    /** The session is over and the player has not yet said whether to join the leaderboard. */
    val leaderboardAsk: Boolean = false,
    /** In a season game: the number of the season session being played (null in solo play). */
    val seasonSession: Int? = null,
    /** The season ended: no new board can start. */
    val seasonOver: Boolean = false
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

/** Solo play (a fresh random session each time) or the season run (the shared deal sequence). */
sealed interface PlayMode {
    object Solo : PlayMode
    class Season(val state: SeasonState) : PlayMode
}

/**
 * Solo play: the human sits South, the partner and both opponents are bots. A session is 16 boards
 * dealt from one random seed (the same mechanism a season will use with the server's seed); every
 * board is ranked in matchpoints against a field of bot tables playing the same deal.
 */
class SoloViewModel(mode: PlayMode = PlayMode.Solo) : ViewModel() {

    companion object {
        const val SESSION_SIZE = 16

        /** Builds the view model of a season game on [state]. */
        fun seasonFactory(state: SeasonState) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SoloViewModel(PlayMode.Season(state)) as T
        }

        /** How long the human can still undo before the bots answer. */
        private const val UNDO_WINDOW_MS = 900L

        /** How long a finished trick stays on the table unless the player taps. */
        private const val TRICK_PAUSE_MS = 1000L

        /** The sweep of the finished trick to its winner. */
        private const val COLLECT_MS = 200L
    }

    private enum class Kind { HUMAN, BOT, AUTO }
    private class Act(val item: Any, val kind: Kind)

    private val human = Seat.SOUTH

    /**
     * The season run being played, or null in solo play. A season game uses the season's seed and
     * deal numbers, the player's system against the season's opponents, and no advice or undo; every
     * move is saved, so a board continues where it was left and can never be started again.
     */
    private val season: SeasonState? = (mode as? PlayMode.Season)?.state

    /** The bidding systems: the player's side and the opponents (from the settings, or the season's). */
    private var seating = season?.let { Seasons.seating(it) } ?: SystemCatalog.seating()

    private var seed = season?.seed ?: newSeed()

    /** The board within the 16-board session (1..16). */
    private var boardNumber = season?.let { it.boardsPlayed % SESSION_SIZE + 1 } ?: 1

    /** The deal number of the session's first board (1 in solo play; in a season it runs on). */
    private var sessionStart = season?.let { it.boardsPlayed - it.boardsPlayed % SESSION_SIZE + 1 } ?: 1

    private var seasonOver = false

    /** The deal number of the session's board [n]: the index into the deal sequence. */
    private fun dealIndex(n: Int = boardNumber) = sessionStart + n - 1

    private var dealHands: List<Hand> = dealFor(boardNumber)
    private var game = BoardGame(dealIndex(), dealHands)
    private var bots: List<Bot> = newBots(boardNumber)
    private var fieldTables: Deferred<List<Int>> = startField()

    private val records = mutableListOf<BoardRecord>().apply {
        // a season continues the session in progress
        val run = season
        if (run != null && run.boardsPlayed % SESSION_SIZE != 0) addAll(run.session.map { it.toRecord() })
    }
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

    /** Set for a moment while the lingering trick is swept to its winner. */
    private var collectTo: Int? = null

    /** A tap on the table: the lingering trick is taken without waiting out the pause. */
    private val takeSignal = Channel<Unit>(Channel.CONFLATED)
    private var busy = false
    private var job: Job? = null

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<TableState> = _state.asStateFlow()

    private val _advice = MutableStateFlow<AdviceUi>(AdviceUi.None)
    val advice: StateFlow<AdviceUi> = _advice.asStateFlow()

    /** Bumped whenever the advice is closed or becomes out of date, so a late answer is thrown away. */
    private var adviceToken = 0

    init {
        val run = season
        if (run != null) {
            // continue a board that was left unfinished
            val moves = Seasons.movesOf(run, dealIndex())
            Seasons.replay(game, moves, human)
            for (m in moves) actions.add(Act(Seasons.decode(m), Kind.AUTO))
            if (Seasons.CONCEDE in moves) ending = Ending.CONCEDED
            seasonOver = Seasons.isOver(run) && moves.isEmpty()
        }
        if (!seasonOver) run()
    }

    private fun SeasonBoard.toRecord(): BoardRecord {
        val contract = if (level == 0) null
        else com.an0obis.bridge.model.Contract(level, denom, com.an0obis.bridge.model.Multiplier.entries[multiplier], declarer)
        return BoardRecord((deal - 1) % SESSION_SIZE + 1, contract, declarerTricks, northSouthScore, percent)
    }

    /** Saves the moves of the board in progress (season games only). */
    private fun saveMoves() {
        val run = season ?: return
        Seasons.saveMoves(run, dealIndex(), actions.map { Seasons.encode(it.item) })
    }

    private fun newSeed() = java.util.UUID.randomUUID().toString().replace("-", "")

    private fun dealFor(n: Int) = Deck.deal(SeasonRandom.forPurpose(seed, "deal", dealIndex(n)))

    private fun newBots(n: Int): List<Bot> =
        List(4) { seat -> SaycBot(SeasonRandom.forPurpose(seed, "bots", dealIndex(n) * 4 + seat), seating = seating) }

    /** The bot tables for this board are played in the background while the human plays. */
    private fun startField(): Deferred<List<Int>> {
        val s = seed
        val n = dealIndex()
        val hands = dealHands
        val systems = seating
        return viewModelScope.async(Dispatchers.Default) { Baseline.field(s, n, hands, systems) }
    }

    // ------------------------------------------------------------------ the human's moves

    /**
     * Works out what to bid or play now and why, for the player's own turn. It is shown by the
     * screen; the answer is dropped if the position changes before it is ready.
     */
    fun requestAdvice() {
        if (season != null) return // no advice in a season game
        if (busy || game.phase == Phase.DONE || game.controller != human) return
        val token = ++adviceToken
        val view = game.view()
        val auction = game.phase == Phase.AUCTION
        _advice.value = AdviceUi.Loading
        viewModelScope.launch {
            val advice = withContext(Dispatchers.Default) {
                if (auction) BidAdvisor.advise(view.auction.dealer, view.auction.calls, view.actingHand, seating)
                else PlayAdvisor.advise(view, seating = seating)
            }
            if (token == adviceToken) _advice.value = AdviceUi.Ready(advice)
        }
    }

    /** The system of the player and partner in this session (the guide explains it). */
    val ourSystem: com.an0obis.bridge.model.sayc.SystemConfig get() = seating.ns.config

    /** The meanings of the call at [index] of this board's auction, in the system of the side that made it. */
    fun explain(index: Int): List<Meaning> {
        val auction = game.auction
        return seating.explain(auction.dealer, auction.calls, index)
    }

    /** The player tapped the table: the finished trick goes to its winner now. */
    fun takeTrick() {
        if (lingering != null) takeSignal.trySend(Unit)
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
        if (season != null) {
            actions.add(Act(Seasons.CONCEDE, Kind.HUMAN))
            saveMoves()
        }
        run()
    }

    private fun canUndoNow(): Boolean {
        if (season != null || game.phase == Phase.DONE) return false
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
            game = BoardGame(dealIndex(), dealHands)
            actions.clear()
            lingering = null
            collectTo = null
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
        if (season?.let { Seasons.isOver(it) } == true) {
            seasonOver = true
            publish()
            return
        }
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
        val run = season
        if (run != null) {
            // the next season session continues the deal sequence after the last finished board
            if (Seasons.isOver(run)) {
                seasonOver = true
                publish()
                return
            }
            sessionStart = run.boardsPlayed + 1
        } else {
            seed = newSeed()
            seating = SystemCatalog.seating() // a new session picks up a system changed in the settings
            sessionStart = 1
        }
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
        GlobalScores.setLeaderboardChoice(AppSettings.POST)
        leaderboardChoice = AppSettings.POST
        postSessionIfDue()
        publish()
    }

    fun declineLeaderboard() {
        GlobalScores.setLeaderboardChoice(AppSettings.DECLINED)
        leaderboardChoice = AppSettings.DECLINED
        publish()
    }

    /** Queues the finished session's average for the leaderboard, once, if the player agreed. */
    private fun postSessionIfDue() {
        if (season != null) return // a season has its own board
        if (posted || records.size < SESSION_SIZE || leaderboardChoice != AppSettings.POST) return
        val average = records.sumOf { it.percent } / records.size
        posted = true
        GlobalScores.enqueue(AppSettings().playerName, average)
        viewModelScope.launch(Dispatchers.IO) { GlobalScores.flushPending() }
    }

    private fun startBoard() {
        dealHands = dealFor(boardNumber)
        game = BoardGame(dealIndex(), dealHands)
        bots = newBots(boardNumber)
        fieldTables = startField()
        percent = null
        ending = Ending.PLAYED
        actions.clear()
        lingering = null
        collectTo = null
        run()
    }

    // ------------------------------------------------------------------ the game loop

    private fun doCall(call: Call, kind: Kind) {
        game.call(call)
        actions.add(Act(call, kind))
        saveMoves()
        publish()
    }

    private suspend fun playAndShow(card: Card, kind: Kind) {
        val before = game.play!!.tricks.size
        game.playCard(card)
        actions.add(Act(card, kind))
        saveMoves()
        val play = game.play!!
        if (play.tricks.size > before) {
            val trick = play.tricks.last()
            lingering = trick.cards.mapIndexed { i, c -> (trick.leader + i) % 4 to c }
            publish()
            takeSignal.tryReceive() // forget an old tap
            withTimeoutOrNull(TRICK_PAUSE_MS) { takeSignal.receive() }
            collectTo = trick.winner
            publish()
            delay(COLLECT_MS)
            collectTo = null
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
                var undoWindow = first != null && season == null
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
        val deal = dealIndex()
        val s = seed
        val tables = fieldTables
        viewModelScope.launch {
            val pct = Scoring.matchpointPercent(result.northSouthScore, tables.await())
            if (s != seed || deal != dealIndex(n) || records.any { it.board == n }) return@launch
            records.add(BoardRecord(n, result.contract, result.declarerTricks, result.northSouthScore, pct))
            season?.let { run ->
                val c = result.contract
                Seasons.boardFinished(
                    run,
                    SeasonBoard(
                        deal, c?.level ?: 0, c?.denom ?: 0, c?.multiplier?.ordinal ?: 0, c?.declarer ?: 0,
                        result.declarerTricks, result.northSouthScore, pct
                    )
                )
                launch(Dispatchers.IO) { Seasons.submitPending(run) }
            }
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
            trickDone = lingering != null,
            collectTo = collectTo,
            leaderboardAsk = showSummary && season == null && leaderboardChoice == AppSettings.ASK,
            seasonSession = season?.let { (sessionStart - 1) / SESSION_SIZE + 1 },
            seasonOver = seasonOver
        )
    }
}
