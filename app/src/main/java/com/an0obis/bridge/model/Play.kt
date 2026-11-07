package com.an0obis.bridge.model

/** One completed trick. */
data class Trick(val leader: Int, val cards: List<Card>, val winner: Int)

/**
 * The play of one deal. Mutable. [hands] are the four starting hands indexed by [Seat].
 *
 * The seat in turn is [currentSeat]; who actually chooses the card is [controller]:
 * the declarer plays both his own hand and the dummy's.
 */
class Play(val contract: Contract, hands: List<Hand>) {

    private val remaining: List<MutableList<Card>> = hands.map { it.cards.toMutableList() }

    private val _tricks = mutableListOf<Trick>()
    val tricks: List<Trick> get() = _tricks

    private val current = mutableListOf<Card>()
    private var leader: Int = contract.openingLeader

    /** Cards on the table in the unfinished trick, in the order played. */
    val currentTrick: List<Card> get() = current

    val currentSeat: Int get() = (leader + current.size) % 4

    /** The seat that led the current (unfinished) trick. */
    val trickLeader: Int get() = leader

    /** The player who chooses the card for [currentSeat]. */
    val controller: Int get() = if (currentSeat == contract.dummy) contract.declarer else currentSeat

    val isFinished: Boolean get() = remaining.all { it.isEmpty() }

    /** The dummy is exposed once the opening lead has been made. */
    val dummyVisible: Boolean get() = _tricks.isNotEmpty() || current.isNotEmpty()

    fun cardsOf(seat: Int): List<Card> = remaining[seat].toList()

    /** Tricks won so far by the side of [seat]. */
    fun tricksWon(seat: Int): Int = _tricks.count { Seat.sameSide(it.winner, seat) }

    val declarerTricks: Int get() = tricksWon(contract.declarer)

    private val ledSuit: Int? get() = current.firstOrNull()?.suit

    /** The cards [currentSeat] may play: must follow the led suit if able. */
    fun legalCards(): List<Card> {
        if (isFinished) return emptyList()
        val hand = remaining[currentSeat]
        val led = ledSuit ?: return hand.toList()
        val follow = hand.filter { it.suit == led }
        return if (follow.isNotEmpty()) follow else hand.toList()
    }

    /** Plays [card] from [currentSeat]. Returns the trick if it was completed by this card. */
    fun playCard(card: Card): Trick? {
        require(card in legalCards()) { "illegal card $card for seat $currentSeat" }
        remaining[currentSeat].remove(card)
        current.add(card)
        if (current.size < 4) return null

        var winnerIndex = 0
        for (i in 1 until 4) {
            if (current[i].beats(current[winnerIndex], contract.denom, current[0].suit)) winnerIndex = i
        }
        val trick = Trick(leader, current.toList(), (leader + winnerIndex) % 4)
        _tricks.add(trick)
        current.clear()
        leader = trick.winner
        return trick
    }
}
