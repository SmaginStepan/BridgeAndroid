package com.an0obis.bridge.model.sayc

import com.an0obis.bridge.model.Auction
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.Seat

/** What a bidder knows about their own hand: points, suit lengths, shape, honours. */
class HandInfo(hand: Hand) {
    val cards: List<Card> = hand.cards

    /** High-card points: A=4, K=3, Q=2, J=1. */
    val hcp: Int = hand.points

    /** Number of cards in each suit, indexed by [com.an0obis.bridge.model.Suit]. */
    val len: IntArray = IntArray(4) { suit -> cards.count { it.suit == suit } }

    /** 4-3-3-3, 4-4-3-2 or 5-3-3-2. */
    val balanced: Boolean = run {
        val shape = len.sortedDescending()
        shape == listOf(4, 3, 3, 3) || shape == listOf(4, 4, 3, 2) || shape == listOf(5, 3, 3, 2)
    }

    fun hcpIn(suit: Int): Int = cards.filter { it.suit == suit }.sumOf { it.points }

    /** Number of A, K, Q in the suit. */
    fun honors(suit: Int): Int = cards.count { it.suit == suit && it.rank >= 12 }

    /** A, Kx, Qxx or Jxxx. */
    fun hasStopper(suit: Int): Boolean {
        val ranks = cards.filter { it.suit == suit }.map { it.rank }
        return 14 in ranks ||
            (13 in ranks && ranks.size >= 2) ||
            (12 in ranks && ranks.size >= 3) ||
            (11 in ranks && ranks.size >= 4)
    }
}

class Entry(val seat: Int, val call: Call)

/**
 * The auction as seen by the player about to call: who bid what, who opened, and so on.
 * Rules use it to decide whether they apply. Built from the calls made so far.
 */
class AuctionState(val dealer: Int, val calls: List<Call>) {
    val auction = Auction(dealer, calls)
    val seat: Int = auction.currentSeat
    val partner: Int = Seat.partner(seat)

    val entries: List<Entry> = calls.mapIndexed { i, c -> Entry((dealer + i) % 4, c) }
    val bids: List<Entry> = entries.filter { it.call is Call.Bid }
    val opening: Entry? = bids.firstOrNull()

    val mine: List<Entry> = bids.filter { it.seat == seat }
    val partners: List<Entry> = bids.filter { it.seat == partner }

    /** Everything the opponents did except passing (bids, doubles, redoubles). */
    val oppActions: List<Entry> = entries.filter { !Seat.sameSide(it.seat, seat) && it.call !is Call.Pass }

    val openingBid: Call.Bid? get() = opening?.call as? Call.Bid
    val weOpened: Boolean get() = opening?.let { Seat.sameSide(it.seat, seat) } == true
}

/** A requirement on the number of cards in a suit. */
data class Need(val suit: Int, val min: Int = 0, val max: Int = 13)

/**
 * One line of the bidding system: in [situation], a hand with [hcp] points, the [needs] suit lengths
 * and (optionally) a balanced or unbalanced shape makes [call]. [extra] holds conditions that are
 * about choosing between rules (for example "spades at least as long as hearts").
 */
class Rule(
    val id: String,
    val call: Call,
    val hcp: IntRange,
    val needs: List<Need>,
    val balanced: Boolean?,
    val situation: (AuctionState) -> Boolean,
    val extra: (HandInfo, AuctionState) -> Boolean
) {
    fun fits(h: HandInfo, s: AuctionState): Boolean =
        s.auction.isLegal(call) &&
            h.hcp in hcp &&
            needs.all { h.len[it.suit] in it.min..it.max } &&
            (balanced == null || h.balanced == balanced) &&
            extra(h, s)

    fun meaning() = Meaning(id, hcp, needs, balanced)
}

/** What a call promises, for the "what does this bid mean?" popup. [ruleId] selects the text. */
data class Meaning(val ruleId: String, val hcp: IntRange?, val needs: List<Need>, val balanced: Boolean?)
