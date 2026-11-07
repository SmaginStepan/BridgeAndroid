package com.an0obis.bridge.model

/** Suits in bridge rank order: clubs < diamonds < hearts < spades. [NO_TRUMP] ranks above all. */
object Suit {
    const val CLUBS = 0
    const val DIAMONDS = 1
    const val HEARTS = 2
    const val SPADES = 3
    const val NO_TRUMP = 4
}

/** A playing card: [rank] 2..14 (11 = J, 12 = Q, 13 = K, 14 = A), [suit] from [Suit]. */
data class Card(val rank: Int, val suit: Int) {
    /** High-card points: A=4, K=3, Q=2, J=1. */
    val points: Int get() = if (rank >= 11) rank - 10 else 0

    /** True if this card beats [other] in a trick led in [ledSuit] with [trump] (or [Suit.NO_TRUMP]). */
    fun beats(other: Card, trump: Int, ledSuit: Int): Boolean {
        if (suit == other.suit) return rank > other.rank
        if (suit == trump) return true
        if (other.suit == trump) return false
        return suit == ledSuit
    }

    override fun toString(): String {
        val r = when (rank) { 14 -> "A"; 13 -> "K"; 12 -> "Q"; 11 -> "J"; else -> rank.toString() }
        val s = when (suit) { Suit.CLUBS -> "♣"; Suit.DIAMONDS -> "♦"; Suit.HEARTS -> "♥"; else -> "♠" }
        return r + s
    }
}

/** A hand of cards, kept sorted by suit (spades first) then rank (high first). */
class Hand(cards: List<Card> = emptyList()) {
    val cards: List<Card> = cards.sortedWith(compareByDescending<Card> { it.suit }.thenByDescending { it.rank })
    val points: Int get() = cards.sumOf { it.points }
    fun suitCards(suit: Int): List<Card> = cards.filter { it.suit == suit }
    fun hasSuit(suit: Int): Boolean = cards.any { it.suit == suit }
}

/** The four seats, clockwise. North-South and East-West are the two partnerships. */
object Seat {
    const val NORTH = 0
    const val EAST = 1
    const val SOUTH = 2
    const val WEST = 3
    fun partner(seat: Int) = (seat + 2) % 4
    fun next(seat: Int) = (seat + 1) % 4
    fun sameSide(a: Int, b: Int) = a % 2 == b % 2
}

object Deck {
    /** Deals four hands of 13 cards, indexed by [Seat]. The same [rnd] stream gives the same deal. */
    fun deal(rnd: DealRandom = DefaultRandom): List<Hand> {
        val pool = ArrayList<Card>(52)
        for (suit in Suit.CLUBS..Suit.SPADES) for (rank in 2..14) pool.add(Card(rank, suit))
        // Fisher-Yates driven by the shared generator (the draw order is part of the season contract)
        for (i in pool.size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = pool[i]; pool[i] = pool[j]; pool[j] = t
        }
        return List(4) { seat -> Hand(pool.subList(seat * 13, seat * 13 + 13)) }
    }
}
