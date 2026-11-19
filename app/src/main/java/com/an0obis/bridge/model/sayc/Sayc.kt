package com.an0obis.bridge.model.sayc

import com.an0obis.bridge.model.Bot
import com.an0obis.bridge.model.BotView
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.DealRandom
import com.an0obis.bridge.model.DefaultRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.play.CardPlayer

/** The symbol of a suit (or "NT"), for texts. */
internal fun suitSymbolText(suit: Int): String = when (suit) {
    Suit.CLUBS -> "♣"
    Suit.DIAMONDS -> "♦"
    Suit.HEARTS -> "♥"
    Suit.SPADES -> "♠"
    else -> "NT"
}

/**
 * What the partner's calls tell us so far. [gameForcing]: a call of our side promised to reach game.
 */
class PartnerInfo(val minHcp: Int, val maxHcp: Int, val minLen: IntArray, val gameForcing: Boolean = false) {
    /** A middle-of-the-road guess of partner's points. */
    val expectedHcp: Int get() = (minHcp + minOf(maxHcp, minHcp + 5)) / 2
}

/**
 * Bidding by combined strength and fit, for every position the rule table does not cover:
 * game with about 25 points, an invitation with 23-24, and a pass or a cheap competitive
 * bid below that.
 */
object Fallback {

    fun partnerInfo(state: AuctionState, system: BiddingSystem): PartnerInfo {
        var min = 0
        var max = 40
        val lens = IntArray(4)
        for ((i, e) in state.entries.withIndex()) {
            if (e.seat != state.partner || e.call is Call.Pass) continue
            val prefix = AuctionState(state.dealer, state.calls.take(i))
            val rules = system.matching(prefix, e.call)
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
        return PartnerInfo(min, max, lens, gameForcingCall(state, system))
    }

    private fun isGame(bid: Call.Bid): Boolean = when (bid.denom) {
        Suit.NO_TRUMP -> bid.level >= 3
        Suit.HEARTS, Suit.SPADES -> bid.level >= 4
        else -> bid.level >= 5
    }

    /** A call chosen by the fallback logic, with the reason ([key] and [args] fill an advice text). */
    class Decision(val call: Call, val key: String, val args: List<String> = emptyList())

    /** [info] can be passed in when the same position is judged for many hands. */
    fun decide(
        state: AuctionState,
        hand: HandInfo,
        system: BiddingSystem,
        info: PartnerInfo = partnerInfo(state, system)
    ): Call = decideWithWhy(state, hand, system, info).call

    /** True if a call of our side so far promised to reach game (a game-forcing rule of [system]). */
    private fun gameForcingCall(state: AuctionState, system: BiddingSystem): Boolean =
        state.entries.withIndex().any { (i, e) ->
            Seat.sameSide(e.seat, state.seat) && e.call !is Call.Pass &&
                system.matching(AuctionState(state.dealer, state.calls.take(i)), e.call).any { it.gameForcing }
        }

    /** The same choice as [decide], together with the reason for it. */
    fun decideWithWhy(
        state: AuctionState,
        hand: HandInfo,
        system: BiddingSystem,
        info: PartnerInfo = partnerInfo(state, system)
    ): Decision {
        val auction = state.auction
        val last = auction.lastBid ?: return Decision(Call.Pass, "fb_pass_open")
        val lastBidder = auction.lastBidder ?: return Decision(Call.Pass, "fb_pass_open")
        val lastIsOurs = Seat.sameSide(lastBidder, state.seat)

        val estimate = hand.hcp + info.expectedHcp
        val est = estimate.toString()

        val gameForced = info.gameForcing &&
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

        var key = "fb_pass_weak"
        var args = listOf(est)
        val target: Call.Bid? = when {
            estimate >= 25 || gameForced -> when {
                fit != null && fit >= Suit.HEARTS -> {
                    key = "fb_game_fit"; args = listOf(est, suitSymbolText(fit), fitTotal.toString())
                    Call.Bid(4, fit)
                }
                fit != null && estimate >= 28 -> {
                    key = "fb_game_fit"; args = listOf(est, suitSymbolText(fit), fitTotal.toString())
                    Call.Bid(5, fit)
                }
                else -> {
                    key = "fb_game_nt"; args = listOf(est)
                    Call.Bid(3, Suit.NO_TRUMP)
                }
            }
            estimate >= 23 -> when {
                fit != null && fit >= Suit.HEARTS -> {
                    key = "fb_invite_fit"; args = listOf(est, suitSymbolText(fit), fitTotal.toString())
                    Call.Bid(3, fit)
                }
                fit == null && hand.balanced -> {
                    key = "fb_invite_nt"; args = listOf(est)
                    Call.Bid(2, Suit.NO_TRUMP)
                }
                else -> null
            }
            // competing for the part-score with a known eight-card fit
            fit != null && !lastIsOurs && estimate >= 19 -> {
                val level = if (fitTotal >= 9) 3 else 2
                key = "fb_compete_fit"; args = listOf(est, suitSymbolText(fit), fitTotal.toString())
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
                    if (auction.isLegal(bid)) return Decision(bid, "fb_compete_own", listOf(suitSymbolText(ownSuit)))
                }
            }
            return Decision(Call.Pass, "fb_pass_weak", listOf(est))
        }
        // we are already at the target or beyond it
        if (lastIsOurs && (last == target || last.isHigherThan(target))) return Decision(Call.Pass, "fb_pass_enough")
        if (target.level > 5) return Decision(Call.Pass, "fb_pass_enough")
        return if (auction.isLegal(target)) Decision(target, key, args) else Decision(Call.Pass, "fb_pass_blocked")
    }
}

/**
 * A bot that bids by the system of its side in [seating] and plays by sampling the hidden hands
 * ([CardPlayer]). [worlds] is how many sampled deals it weighs per card; 0 or less plays at
 * random (fast, for tests).
 */
class SaycBot(
    private val rnd: DealRandom = DefaultRandom,
    private val worlds: Int = 100,
    /** Whether the sampled hands must fit what the bidding showed. */
    private val readBidding: Boolean = true,
    /** Added to the point count when bidding: > 0 bids more boldly, < 0 more carefully. */
    private val hcpShift: Int = 0,
    /** The systems of both sides: the bot bids in its own side's and reads the bidding with both. */
    private val seating: Seating = Seating.SAYC
) : Bot {

    private var inferenceCalls: List<Call>? = null
    private var inference: BidInference? = null

    private fun inferenceFor(view: BotView): BidInference {
        val calls = view.auction.calls
        if (inferenceCalls != calls || inference == null) {
            inference = BidInference(view.auction.dealer, calls, seating)
            inferenceCalls = calls
        }
        return inference!!
    }

    override fun chooseCall(view: BotView): Call {
        val state = AuctionState(view.auction.dealer, view.auction.calls)
        val call = seating.of(state.seat).choose(state, HandInfo(view.actingHand, hcpShift))
        return if (call in view.legalCalls) call else Call.Pass
    }

    override fun chooseCard(view: BotView): Card =
        if (worlds <= 0) view.legalCards[rnd.nextInt(view.legalCards.size)]
        else CardPlayer.choose(
            view, rnd, worlds,
            plausible = if (readBidding) inferenceFor(view).let { inf -> { seat, hand -> inf.plausible(seat, hand) } } else null
        )
}
