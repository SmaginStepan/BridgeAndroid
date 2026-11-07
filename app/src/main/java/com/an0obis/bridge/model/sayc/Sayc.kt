package com.an0obis.bridge.model.sayc

import com.an0obis.bridge.model.Bot
import com.an0obis.bridge.model.BotView
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.DealRandom
import com.an0obis.bridge.model.DefaultRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit

/** What the partner's calls tell us so far. */
class PartnerInfo(val minHcp: Int, val maxHcp: Int, val minLen: IntArray) {
    /** A middle-of-the-road guess of partner's points. */
    val expectedHcp: Int get() = (minHcp + minOf(maxHcp, minHcp + 5)) / 2
}

/**
 * Bidding by combined strength and fit, for every position the rule table does not cover:
 * game with about 25 points, an invitation with 23-24, and a pass or a cheap competitive
 * bid below that.
 */
object Fallback {

    fun partnerInfo(state: AuctionState): PartnerInfo {
        var min = 0
        var max = 40
        val lens = IntArray(4)
        for ((i, e) in state.entries.withIndex()) {
            if (e.seat != state.partner || e.call is Call.Pass) continue
            val prefix = AuctionState(state.dealer, state.calls.take(i))
            val rules = SaycRules.matching(prefix, e.call)
            if (rules.isNotEmpty()) {
                min = maxOf(min, rules.minOf { it.hcp.first })
                max = minOf(max, rules.maxOf { it.hcp.last })
                for (suit in Suit.CLUBS..Suit.SPADES) {
                    val m = rules.minOf { r -> r.needs.filter { it.suit == suit }.maxOfOrNull { it.min } ?: 0 }
                    lens[suit] = maxOf(lens[suit], m)
                }
            } else if (e.call is Call.Bid) {
                // an unexplained bid: natural, and a little extra strength
                if (e.call.denom < Suit.NO_TRUMP) lens[e.call.denom] = maxOf(lens[e.call.denom], 4)
                min = minOf(min + 3, 20)
            }
        }
        if (max < min) max = min
        return PartnerInfo(min, max, lens)
    }

    private fun isGame(bid: Call.Bid): Boolean = when (bid.denom) {
        Suit.NO_TRUMP -> bid.level >= 3
        Suit.HEARTS, Suit.SPADES -> bid.level >= 4
        else -> bid.level >= 5
    }

    fun decide(state: AuctionState, hand: HandInfo): Call {
        val auction = state.auction
        val last = auction.lastBid ?: return Call.Pass
        val lastBidder = auction.lastBidder ?: return Call.Pass
        val lastIsOurs = Seat.sameSide(lastBidder, state.seat)

        val info = partnerInfo(state)
        val estimate = hand.hcp + info.expectedHcp

        val gameForced = state.weOpened && state.openingBid == Call.Bid(2, Suit.CLUBS) &&
            state.bids.none { Seat.sameSide(it.seat, state.seat) && isGame(it.call as Call.Bid) }

        // the best fit: a suit where we hold at least eight cards together, majors first
        var fit: Int? = null
        var fitTotal = 0
        for (suit in listOf(Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS)) {
            if (info.minLen[suit] < 3) continue
            val total = hand.len[suit] + info.minLen[suit]
            if (total >= 8 && (fit == null || total > fitTotal || (total == fitTotal && suit >= Suit.HEARTS && fit < Suit.HEARTS))) {
                fit = suit
                fitTotal = total
            }
        }

        val target: Call.Bid? = when {
            estimate >= 25 || gameForced -> when {
                fit != null && fit >= Suit.HEARTS -> Call.Bid(4, fit)
                fit != null && estimate >= 28 -> Call.Bid(5, fit)
                else -> Call.Bid(3, Suit.NO_TRUMP)
            }
            estimate >= 23 -> when {
                fit != null && fit >= Suit.HEARTS -> Call.Bid(3, fit)
                fit == null && hand.balanced -> Call.Bid(2, Suit.NO_TRUMP)
                else -> null
            }
            // competing for the part-score with a known eight-card fit
            fit != null && !lastIsOurs && estimate >= 19 -> {
                val level = if (fitTotal >= 9) 3 else 2
                Call.Bid(level, fit)
            }
            else -> null
        }

        if (target == null) {
            // the opponents have taken the bidding away: compete in a long suit of our own, up to the three level
            val ownSuit = state.mine.map { it.call as Call.Bid }
                .filter { it.denom < Suit.NO_TRUMP && hand.len[it.denom] >= 5 }
                .maxByOrNull { hand.len[it.denom] }?.denom
            if (!lastIsOurs && ownSuit != null && estimate >= 20 && (hand.len[ownSuit] >= 6 || hand.hcp >= 15)) {
                for (level in 1..3) {
                    val bid = Call.Bid(level, ownSuit)
                    if (auction.isLegal(bid)) return bid
                }
            }
            return Call.Pass
        }
        // we are already at the target or beyond it
        if (lastIsOurs && (last == target || last.isHigherThan(target))) return Call.Pass
        if (target.level > 5) return Call.Pass
        return if (auction.isLegal(target)) target else Call.Pass
    }
}

object Sayc {

    /** The call the system makes with [hand] in [state]. Always legal. */
    fun choose(state: AuctionState, hand: HandInfo): Call {
        val call = SaycRules.firstFitting(state, hand)?.call ?: Fallback.decide(state, hand)
        return if (state.auction.isLegal(call)) call else Call.Pass
    }

    /** The meanings of the call at [index] in [calls]: the rules that describe it, or a natural-bid meaning. */
    fun explain(dealer: Int, calls: List<Call>, index: Int): List<Meaning> {
        val prefix = AuctionState(dealer, calls.take(index))
        val call = calls[index]
        val rules = SaycRules.matching(prefix, call)
        if (rules.isNotEmpty()) return rules.map { it.meaning() }
        return listOf(
            Meaning(
                when (call) {
                    is Call.Pass -> "gen_pass"
                    is Call.Bid -> "natural"
                    else -> "gen_double"
                },
                null, emptyList(), null
            )
        )
    }
}

/** A bot that bids by the SAYC-lite rule table. It plays random legal cards for now. */
class SaycBot(private val rnd: DealRandom = DefaultRandom) : Bot {

    override fun chooseCall(view: BotView): Call {
        val state = AuctionState(view.auction.dealer, view.auction.calls)
        val call = Sayc.choose(state, HandInfo(view.actingHand))
        return if (call in view.legalCalls) call else Call.Pass
    }

    override fun chooseCard(view: BotView): Card = view.legalCards[rnd.nextInt(view.legalCards.size)]
}
