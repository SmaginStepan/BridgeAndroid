package com.an0obis.bridge.model

enum class Phase { AUCTION, PLAY, DONE }

/** The outcome of one board. [contract] is null for a passed-out board. */
data class BoardResult(
    val contract: Contract?,
    val declarerTricks: Int,
    /** Score from North-South's point of view. */
    val northSouthScore: Int
)

/**
 * What one player is allowed to know when choosing a call or a card. Built by [BoardGame.view]:
 * it never contains a hand the viewer could not legally see.
 */
class BotView(
    /** The player who chooses (the declarer when the dummy's card is to be played). */
    val viewer: Int,
    /** The seat in turn. Differs from [viewer] when the declarer plays the dummy. */
    val actingSeat: Int,
    /** Hands visible to the viewer: its own, plus the dummy once it is exposed. */
    val hands: Map<Int, Hand>,
    val auction: Auction,
    val vulnerability: Vulnerability,
    val contract: Contract?,
    val currentTrick: List<Card>,
    val trickLeader: Int,
    val tricks: List<Trick>,
    val legalCalls: List<Call>,
    val legalCards: List<Card>
) {
    /** The hand of the seat in turn (the one the choice is made from). */
    val actingHand: Hand get() = hands.getValue(actingSeat)
}

/** A player, human-like or machine. Bots are asked only about their own turn. */
interface Bot {
    fun chooseCall(view: BotView): Call
    fun chooseCard(view: BotView): Card
}

/**
 * One board from the deal to the score: the auction, then the play, then the result.
 * Mutable; drive it with [call] and [playCard], or let bots drive it with [runBots].
 */
class BoardGame(val boardNumber: Int, hands: List<Hand>) {

    private val deal: List<Hand> = hands.toList()

    val dealer: Int = Boards.dealer(boardNumber)
    val vulnerability: Vulnerability = Boards.vulnerability(boardNumber)

    var auction: Auction = Auction(dealer)
        private set

    var play: Play? = null
        private set

    var result: BoardResult? = null
        private set

    val phase: Phase
        get() = when {
            result != null -> Phase.DONE
            play != null -> Phase.PLAY
            else -> Phase.AUCTION
        }

    /** A hand as dealt, for showing the board after it is finished (not for bots). */
    fun startingHand(seat: Int): Hand = deal[seat]

    /** The seat whose turn it is. Only meaningful while [phase] is not DONE. */
    val actingSeat: Int
        get() = if (phase == Phase.PLAY) play!!.currentSeat else auction.currentSeat

    /** Who chooses: the seat in turn, except the declarer chooses for the dummy. */
    val controller: Int
        get() = if (phase == Phase.PLAY) play!!.controller else auction.currentSeat

    fun call(call: Call) {
        check(phase == Phase.AUCTION) { "not in the auction" }
        auction = auction.add(call)
        if (auction.isFinished) {
            val contract = auction.contract
            if (contract == null) {
                result = BoardResult(null, 0, 0)
            } else {
                play = Play(contract, deal)
            }
        }
    }

    fun playCard(card: Card) {
        check(phase == Phase.PLAY) { "not in the play" }
        val p = play!!
        p.playCard(card)
        if (p.isFinished) {
            val contract = p.contract
            val tricks = p.declarerTricks
            result = BoardResult(contract, tricks, Scoring.northSouthScore(contract, vulnerability, tricks))
        }
    }

    /**
     * Ends the play by giving every trick still to be played to the side of [winnerSide]
     * (0 = North-South, 1 = East-West): a claim, or a concession by the other side.
     */
    fun giveRest(winnerSide: Int) {
        check(phase == Phase.PLAY) { "not in the play" }
        val p = play!!
        val remaining = 13 - p.tricks.size
        val contract = p.contract
        val tricks = p.declarerTricks + if (winnerSide == contract.declarer % 2) remaining else 0
        result = BoardResult(contract, tricks, Scoring.northSouthScore(contract, vulnerability, tricks))
    }

    /** What the current controller may see. */
    fun view(): BotView {
        check(phase != Phase.DONE) { "board is finished" }
        val viewer = controller
        val p = play
        val hands = mutableMapOf<Int, Hand>()
        if (p == null) {
            hands[viewer] = deal[viewer]
        } else {
            hands[viewer] = Hand(p.cardsOf(viewer))
            if (p.dummyVisible) hands[p.contract.dummy] = Hand(p.cardsOf(p.contract.dummy))
        }
        return BotView(
            viewer = viewer,
            actingSeat = actingSeat,
            hands = hands,
            auction = auction,
            vulnerability = vulnerability,
            contract = auction.contract,
            currentTrick = p?.currentTrick?.toList() ?: emptyList(),
            trickLeader = p?.trickLeader ?: dealer,
            tricks = p?.tricks?.toList() ?: emptyList(),
            legalCalls = if (p == null) auction.legalCalls() else emptyList(),
            legalCards = p?.legalCards() ?: emptyList()
        )
    }

    /**
     * Lets bots act until the board is finished or [botAt] returns null for the controller
     * (a human has to choose). Returns true if the board is finished.
     */
    fun runBots(botAt: (seat: Int) -> Bot?): Boolean {
        while (phase != Phase.DONE) {
            val bot = botAt(controller) ?: return false
            val v = view()
            if (phase == Phase.AUCTION) call(bot.chooseCall(v)) else playCard(bot.chooseCard(v))
        }
        return true
    }
}
