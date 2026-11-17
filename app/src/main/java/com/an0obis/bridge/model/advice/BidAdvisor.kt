package com.an0obis.bridge.model.advice

import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.sayc.AuctionState
import com.an0obis.bridge.model.sayc.Fallback
import com.an0obis.bridge.model.sayc.HandInfo
import com.an0obis.bridge.model.sayc.SaycRules
import com.an0obis.bridge.model.sayc.suitSymbolText

/**
 * What to bid and why. It makes exactly the call the bidding system makes (the rule table first,
 * the points-and-fit logic where the table is silent) and explains it from the same facts.
 */
object BidAdvisor {

    fun advise(dealer: Int, calls: List<Call>, hand: Hand): Advice {
        val state = AuctionState(dealer, calls)
        val info = HandInfo(hand)
        val reasons = mutableListOf<Reason>()
        reasons += Reason("hand", listOf(info.hcp.toString(), shape(info)))

        val rule = SaycRules.firstFitting(state, info)
        if (rule != null) {
            val call = if (state.auction.isLegal(rule.call)) rule.call else Call.Pass
            return Advice(call, null, reasons, if (call == rule.call) rule.meaning() else null)
        }

        val partner = Fallback.partnerInfo(state)
        if (partner.minHcp > 0) {
            reasons += if (partner.maxHcp < 40)
                Reason("partner_range", listOf(partner.minHcp.toString(), partner.maxHcp.toString()))
            else
                Reason("partner_min", listOf(partner.minHcp.toString()))
        }
        val decision = Fallback.decideWithWhy(state, info, partner)
        val call = if (state.auction.isLegal(decision.call)) decision.call else Call.Pass
        reasons += Reason(decision.key, decision.args)
        return Advice(call, null, reasons)
    }

    /** "5♠ 3♥ 3♦ 2♣": the suit lengths, longest rank first, empty suits left out. */
    internal fun shape(info: HandInfo): String =
        (Suit.SPADES downTo Suit.CLUBS).filter { info.len[it] > 0 }
            .joinToString(" ") { "${info.len[it]}${suitSymbolText(it)}" }
}
