package com.an0obis.bridge.model.sayc

import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Hand

/**
 * What an auction tells about the hands: a hand is *plausible* for a seat if the bidding system
 * would have made exactly the calls that seat made. This reuses the system itself (the rule
 * table and the fallback), so "West opened 1NT" excludes hands that are not 15-17 balanced,
 * and "East passed" excludes hands strong enough to bid.
 *
 * A call made in a position the rule table knows nothing about gives no information.
 * Built once per auction, then asked about many sampled hands.
 */
class BidInference(dealer: Int, calls: List<Call>) {

    private class Step(val call: Call, val state: AuctionState, val rules: List<Rule>) {
        val partner: PartnerInfo by lazy { Fallback.partnerInfo(state) }
    }

    private val steps: Array<MutableList<Step>> = Array(4) { mutableListOf() }

    init {
        for ((i, call) in calls.withIndex()) {
            val state = AuctionState(dealer, calls.take(i))
            if (state.auction.isFinished) break
            val rules = SaycRules.all.filter { it.situation(state) }
            if (rules.isEmpty()) continue
            steps[state.seat].add(Step(call, state, rules))
        }
    }

    /** True if the auction says something about the hand of [seat]. */
    fun informs(seat: Int): Boolean = steps[seat].isNotEmpty()

    /** Would [seat], holding [hand], have made all the calls it made? */
    fun plausible(seat: Int, hand: Hand): Boolean {
        val list = steps[seat]
        if (list.isEmpty()) return true
        val info = HandInfo(hand)
        for (step in list) {
            val rule = step.rules.firstOrNull { it.fits(info, step.state) }
            val made = rule?.call ?: Fallback.decide(step.state, info, step.partner)
            val legal = if (step.state.auction.isLegal(made)) made else Call.Pass
            if (legal != step.call) return false
        }
        return true
    }
}
