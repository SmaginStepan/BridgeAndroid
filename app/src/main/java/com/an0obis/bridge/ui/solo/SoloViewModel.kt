package com.an0obis.bridge.ui.solo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.an0obis.bridge.model.BasicBot
import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.BoardResult
import com.an0obis.bridge.model.Bot
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Contract
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Vulnerability
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One seat as the table shows it. [cards] is null for a hidden hand; [count] is how many cards remain. */
data class SeatView(val cards: List<Card>?, val count: Int)

/** Everything the table screen needs to draw. */
data class TableState(
    val boardNumber: Int = 1,
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
    val result: BoardResult? = null
)

/**
 * Solo play: the human sits South, the partner and both opponents are bots. Boards are dealt
 * from a random seed (the same mechanism a season will use with the server's seed).
 */
class SoloViewModel : ViewModel() {

    private val human = Seat.SOUTH
    private val seed = java.util.UUID.randomUUID().toString().replace("-", "")

    private var boardNumber = 1
    private var game: BoardGame = newGame(boardNumber)
    private var bots: List<Bot> = newBots(boardNumber)

    /** The just-finished trick stays on the table for a moment so the player can see it. */
    private var lingering: List<Pair<Int, Card>>? = null
    private var busy = false

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<TableState> = _state.asStateFlow()

    init {
        run()
    }

    private fun newGame(n: Int) = BoardGame(n, Deck.deal(SeasonRandom.forPurpose(seed, "deal", n)))

    private fun newBots(n: Int): List<Bot> =
        List(4) { seat -> BasicBot(SeasonRandom.forPurpose(seed, "bots", n * 4 + seat)) }

    fun onCall(call: Call) {
        if (busy || game.phase != Phase.AUCTION || game.controller != human || !game.auction.isLegal(call)) return
        run {
            game.call(call)
            publish()
        }
    }

    fun onCard(card: Card) {
        if (busy || game.phase != Phase.PLAY || game.controller != human) return
        if (card !in game.view().legalCards) return
        run { playAndShow(card) }
    }

    fun nextBoard() {
        if (busy || game.phase != Phase.DONE) return
        boardNumber++
        game = newGame(boardNumber)
        bots = newBots(boardNumber)
        lingering = null
        run()
    }

    /** Runs the optional first action, then lets the bots move until the human has to act. */
    private fun run(first: (suspend () -> Unit)? = null) {
        viewModelScope.launch {
            busy = true
            publish()
            try {
                first?.invoke()
                while (game.phase != Phase.DONE && game.controller != human) {
                    delay(if (game.phase == Phase.AUCTION) 500L else 600L)
                    val bot = bots[game.controller]
                    val view = game.view()
                    if (game.phase == Phase.AUCTION) {
                        game.call(bot.chooseCall(view))
                        publish()
                    } else {
                        playAndShow(bot.chooseCard(view))
                    }
                }
            } finally {
                busy = false
                publish()
            }
        }
    }

    private suspend fun playAndShow(card: Card) {
        val before = game.play!!.tricks.size
        game.playCard(card)
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
        return TableState(
            boardNumber = g.boardNumber,
            dealer = g.dealer,
            vulnerability = g.vulnerability,
            phase = g.phase,
            calls = g.auction.calls,
            seats = seats,
            trick = trick,
            turnSeat = if (done) null else g.actingSeat,
            humanToAct = humanToAct,
            legalCalls = if (humanToAct && g.phase == Phase.AUCTION) g.auction.legalCalls().toSet() else emptySet(),
            legalCards = if (humanToAct && g.phase == Phase.PLAY) g.view().legalCards.toSet() else emptySet(),
            contract = g.auction.contract,
            tricksNorthSouth = play?.tricksWon(Seat.NORTH) ?: 0,
            tricksEastWest = play?.tricksWon(Seat.EAST) ?: 0,
            result = g.result
        )
    }
}
