package com.an0obis.bridge.model

/** A call in the auction: pass, a bid, double or redouble. */
sealed class Call {
    object Pass : Call() {
        override fun toString() = "Pass"
    }

    /** [level] 1..7 (the side promises to win level + 6 tricks), [denom] is a [Suit] value including NO_TRUMP. */
    data class Bid(val level: Int, val denom: Int) : Call() {
        init {
            require(level in 1..7) { "level $level" }
            require(denom in Suit.CLUBS..Suit.NO_TRUMP) { "denomination $denom" }
        }

        fun isHigherThan(other: Bid) = level > other.level || (level == other.level && denom > other.denom)

        override fun toString() = level.toString() + when (denom) {
            Suit.CLUBS -> "♣"
            Suit.DIAMONDS -> "♦"
            Suit.HEARTS -> "♥"
            Suit.SPADES -> "♠"
            else -> "NT"
        }
    }

    object Dbl : Call() {
        override fun toString() = "X"
    }

    object Redbl : Call() {
        override fun toString() = "XX"
    }
}

enum class Multiplier { NONE, DOUBLED, REDOUBLED }

/** The result of a finished auction that contained at least one bid. */
data class Contract(
    val level: Int,
    val denom: Int,
    val multiplier: Multiplier,
    val declarer: Int
) {
    val dummy: Int get() = Seat.partner(declarer)

    /** The player who makes the opening lead (left of declarer). */
    val openingLeader: Int get() = Seat.next(declarer)

    /** Tricks the declaring side must win to make the contract. */
    val tricksNeeded: Int get() = level + 6

    override fun toString() = Call.Bid(level, denom).toString() + when (multiplier) {
        Multiplier.NONE -> ""
        Multiplier.DOUBLED -> "X"
        Multiplier.REDOUBLED -> "XX"
    }
}

/**
 * The bidding. Immutable: [add] returns a new auction. Calls go clockwise starting with [dealer].
 */
class Auction(val dealer: Int, val calls: List<Call> = emptyList()) {

    /** The seat whose turn it is (meaningless once [isFinished]). */
    val currentSeat: Int get() = (dealer + calls.size) % 4

    private fun seatOf(index: Int) = (dealer + index) % 4

    private val lastBidIndex: Int = calls.indexOfLast { it is Call.Bid }

    val lastBid: Call.Bid? get() = if (lastBidIndex >= 0) calls[lastBidIndex] as Call.Bid else null

    /** The seat that made the last bid, or null if nobody has bid yet. */
    val lastBidder: Int? get() = if (lastBidIndex >= 0) seatOf(lastBidIndex) else null

    private val afterLastBid: List<Call> get() = if (lastBidIndex >= 0) calls.drop(lastBidIndex + 1) else emptyList()

    private val isDoubled: Boolean get() = afterLastBid.any { it is Call.Dbl }
    private val isRedoubled: Boolean get() = afterLastBid.any { it is Call.Redbl }

    /** Three passes after a bid, or four passes with no bid at all (a passed-out board). */
    val isFinished: Boolean
        get() = if (lastBidIndex < 0) calls.size >= 4
        else calls.size >= 4 && calls.takeLast(3).all { it is Call.Pass }

    val isPassedOut: Boolean get() = isFinished && lastBidIndex < 0

    fun isLegal(call: Call): Boolean {
        if (isFinished) return false
        val seat = currentSeat
        return when (call) {
            is Call.Pass -> true
            is Call.Bid -> lastBid?.let { call.isHigherThan(it) } ?: true
            is Call.Dbl -> lastBidIndex >= 0 &&
                !Seat.sameSide(seatOf(lastBidIndex), seat) &&
                !isDoubled && !isRedoubled
            is Call.Redbl -> lastBidIndex >= 0 &&
                Seat.sameSide(seatOf(lastBidIndex), seat) &&
                isDoubled && !isRedoubled
        }
    }

    /** Every call the player in turn may make, lowest first. */
    fun legalCalls(): List<Call> {
        if (isFinished) return emptyList()
        val result = mutableListOf<Call>(Call.Pass)
        for (level in 1..7) for (denom in Suit.CLUBS..Suit.NO_TRUMP) {
            val bid = Call.Bid(level, denom)
            if (isLegal(bid)) result.add(bid)
        }
        if (isLegal(Call.Dbl)) result.add(Call.Dbl)
        if (isLegal(Call.Redbl)) result.add(Call.Redbl)
        return result
    }

    fun add(call: Call): Auction {
        require(isLegal(call)) { "illegal call $call by seat $currentSeat after $calls" }
        return Auction(dealer, calls + call)
    }

    /** The contract, or null while the auction runs or if the board was passed out. */
    val contract: Contract?
        get() {
            if (!isFinished || lastBidIndex < 0) return null
            val bid = calls[lastBidIndex] as Call.Bid
            val side = seatOf(lastBidIndex) % 2
            // declarer: the first player of the winning side who named this denomination
            val declarer = calls.indices.first { i ->
                val c = calls[i]
                c is Call.Bid && c.denom == bid.denom && seatOf(i) % 2 == side
            }.let { seatOf(it) }
            val multiplier = when {
                isRedoubled -> Multiplier.REDOUBLED
                isDoubled -> Multiplier.DOUBLED
                else -> Multiplier.NONE
            }
            return Contract(bid.level, bid.denom, multiplier, declarer)
        }
}
