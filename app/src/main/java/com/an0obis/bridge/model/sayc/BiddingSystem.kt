package com.an0obis.bridge.model.sayc

import com.an0obis.bridge.model.Call
import java.util.concurrent.ConcurrentHashMap

/**
 * A bidding system: the rule table built from a [SystemConfig], plus the points-and-fit logic
 * ([Fallback]) for every position the table does not cover. The same object chooses the bots'
 * calls, explains calls and gives advice, so these can never disagree.
 */
class BiddingSystem private constructor(val config: SystemConfig) {

    /** The rules in priority order: the first rule that fits a hand is the call. */
    val rules: List<Rule> = RuleBuilder(config).build()

    /** The rules that describe [call] made at the position after [state]'s calls. */
    fun matching(state: AuctionState, call: Call): List<Rule> =
        rules.filter { it.call == call && it.situation(state) }

    /** The first rule that fits [hand] in [state], or null if no rule covers the position. */
    fun firstFitting(state: AuctionState, hand: HandInfo): Rule? =
        rules.firstOrNull { it.situation(state) && it.fits(hand, state) }

    /** The call the system makes with [hand] in [state]. Always legal. */
    fun choose(state: AuctionState, hand: HandInfo): Call {
        val call = firstFitting(state, hand)?.call ?: Fallback.decide(state, hand, this)
        return if (state.auction.isLegal(call)) call else Call.Pass
    }

    /**
     * The meanings of the call at [index] in [calls] in this system: the rules that describe it
     * (one per id, the ranges joined), or a natural-bid meaning.
     */
    fun explain(dealer: Int, calls: List<Call>, index: Int): List<Meaning> {
        val prefix = AuctionState(dealer, calls.take(index))
        val call = calls[index]
        val rules = matching(prefix, call)
        if (rules.isNotEmpty()) {
            return rules.groupBy { it.id }.values.map { same ->
                if (same.size == 1) same[0].meaning()
                else Meaning(
                    same[0].id,
                    same.minOf { it.hcp.first }..same.maxOf { it.hcp.last },
                    same[0].needs.filter { n -> same.all { r -> n in r.needs } },
                    same.map { it.balanced }.distinct().singleOrNull()
                )
            }
        }
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

    companion object {
        private val cache = ConcurrentHashMap<SystemConfig, BiddingSystem>()

        /** The system for [config] (built once, then shared: the rule table never changes). */
        fun of(config: SystemConfig): BiddingSystem = cache.getOrPut(config) { BiddingSystem(config) }

        val SAYC: BiddingSystem get() = of(SystemConfig.SAYC)
    }
}

/**
 * The systems at a table: North-South play [ns], East-West play [ew]. Each call is made and
 * explained in the system of the side that made it.
 */
data class Seating(val ns: BiddingSystem, val ew: BiddingSystem) {

    fun of(seat: Int): BiddingSystem = if (seat % 2 == 0) ns else ew

    /** The meanings of the call at [index], in the system of the player who made it. */
    fun explain(dealer: Int, calls: List<Call>, index: Int): List<Meaning> =
        of((dealer + index) % 4).explain(dealer, calls, index)

    companion object {
        val SAYC: Seating get() = Seating(BiddingSystem.SAYC, BiddingSystem.SAYC)

        fun of(ns: SystemConfig, ew: SystemConfig) = Seating(BiddingSystem.of(ns), BiddingSystem.of(ew))
    }
}
