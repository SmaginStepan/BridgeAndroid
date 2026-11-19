package com.an0obis.bridge.model.advice

import com.an0obis.bridge.model.BotView
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.DealRandom
import com.an0obis.bridge.model.DefaultRandom
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.sayc.SaycBot
import com.an0obis.bridge.model.sayc.Seating
import com.an0obis.bridge.model.sayc.suitSymbolText

/**
 * What card to play and why. The card is the one the bots would choose (a search over the hands
 * the player cannot see); the reasons are the plain rules of thumb that card follows, worked out
 * from the cards on the table only, so the advice never uses a hand the player could not see.
 */
object PlayAdvisor {

    fun advise(view: BotView, rnd: DealRandom = DefaultRandom, worlds: Int = 100, seating: Seating = Seating.SAYC): Advice {
        val card = SaycBot(rnd, worlds, seating = seating).chooseCard(view)
        return explain(view, card)
    }

    /** The reasons for playing [card] in the position of [view]. */
    fun explain(view: BotView, card: Card): Advice {
        val contract = view.contract ?: return Advice(null, card, listOf(Reason("play_plain")))
        val reasons = mutableListOf<Reason>()
        val seat = view.actingSeat
        val mySide = seat and 1
        val declarerSide = contract.declarer and 1
        val trump = contract.denom
        val hand = view.actingHand.cards

        // ---- where the deal stands
        if (view.viewer != seat) reasons += Reason("from_dummy", listOf("@seat:$seat"))
        val won = view.tricks.count { (it.winner and 1) == declarerSide }
        val lost = view.tricks.size - won
        if (mySide == declarerSide) {
            val need = contract.tricksNeeded - won
            reasons += if (need > 0)
                Reason("plan_declare", listOf(contract.toString(), need.toString(), (13 - view.tricks.size).toString()))
            else Reason("plan_made", listOf(contract.toString()))
        } else {
            val need = (14 - contract.tricksNeeded) - lost
            reasons += if (need > 0) Reason("plan_defend", listOf(contract.toString(), need.toString()))
            else Reason("plan_defeated", listOf(contract.toString()))
        }

        // ---- the cards that could still beat ours: unseen ones, and the opponents' visible cards
        val played = view.tricks.flatMap { it.cards } + view.currentTrick
        val visible = view.hands.values.flatMap { it.cards }
        val everything = (Suit.CLUBS..Suit.SPADES).flatMap { s -> (2..14).map { Card(it, s) } }
        val unseen = everything - played.toSet() - visible.toSet()
        val opposing = unseen + view.hands.filter { (s, _) -> (s and 1) != mySide }.values.flatMap { it.cards }
        val gone = played.toSet() + hand.toSet()

        val sym = suitSymbolText(card.suit)
        if (view.currentTrick.isEmpty()) {
            reasons += leadReason(view, card, hand, trump, mySide == declarerSide, opposing, gone, sym)
        } else {
            reasons += followReason(view, card, hand, trump, mySide, opposing)
        }
        return Advice(null, card, reasons)
    }

    // ------------------------------------------------------------------ leading

    private fun leadReason(
        view: BotView, card: Card, hand: List<Card>, trump: Int, declaring: Boolean,
        opposing: List<Card>, gone: Set<Card>, sym: String
    ): List<Reason> {
        val out = mutableListOf<Reason>()
        if (view.tricks.isEmpty()) out += Reason("lead_opening")
        val suit = hand.filter { it.suit == card.suit }.sortedByDescending { it.rank }
        val higherOut = opposing.any { it.suit == card.suit && it.rank > card.rank }
        val opponentsHaveTrumps = trump != Suit.NO_TRUMP && opposing.any { it.suit == trump }
        val next = sequenceNext(card, suit, gone)
        out += when {
            declaring && trump != Suit.NO_TRUMP && card.suit == trump && opponentsHaveTrumps -> Reason("lead_draw_trumps")
            !higherOut -> Reason("lead_winner", listOf(card.toString()))
            card.rank >= 10 && next != null -> Reason("lead_sequence", listOf(card.toString(), next.toString()))
            suit.size >= 4 && suit.indexOf(card) == 3 -> Reason("lead_fourth", listOf(sym))
            suit.size == 2 && suit.first() == card -> Reason("lead_doubleton")
            suit.size == 1 -> Reason("lead_single")
            else -> Reason("lead_low", listOf(sym))
        }
        return out
    }

    /** The next lower card of the same suit in the hand, if every card between is held or already played. */
    private fun sequenceNext(card: Card, suit: List<Card>, gone: Set<Card>): Card? {
        val lower = suit.filter { it.rank < card.rank }.maxByOrNull { it.rank } ?: return null
        for (r in lower.rank + 1 until card.rank) if (Card(r, card.suit) !in gone) return null
        return lower
    }

    // ------------------------------------------------------------------ following

    private fun followReason(
        view: BotView, card: Card, hand: List<Card>, trump: Int, mySide: Int, opposing: List<Card>
    ): List<Reason> {
        val trick = view.currentTrick
        val led = trick[0].suit
        val position = trick.size // 1 = second hand, 2 = third hand, 3 = fourth hand
        var bestIndex = 0
        for (i in 1 until trick.size) if (trick[i].beats(trick[bestIndex], trump, led)) bestIndex = i
        val best = trick[bestIndex]
        val partnerWinning = (((view.trickLeader + bestIndex) and 3) and 1) == mySide
        val wins = card.beats(best, trump, led)

        if (hand.any { it.suit == led }) {
            return listOf(
                when {
                    partnerWinning && !wins -> Reason("follow_partner_wins", listOf(best.toString()))
                    partnerWinning -> Reason("follow_support")
                    wins && position == 3 -> Reason("follow_win_cheap")
                    wins && position == 2 -> Reason("follow_third_high")
                    wins && position == 1 && best.rank >= 10 -> Reason("follow_cover", listOf(best.toString()))
                    wins -> Reason("follow_win")
                    position == 1 -> Reason("follow_second_low")
                    else -> Reason("follow_cannot_win")
                }
            )
        }
        // no card of the suit led: trump to win, or throw something away
        if (trump != Suit.NO_TRUMP && card.suit == trump && wins) {
            return listOf(
                if (best.suit == trump) Reason("ruff_over") else Reason("ruff", listOf(suitSymbolText(led)))
            )
        }
        return listOf(if (partnerWinning) Reason("discard_partner") else Reason("discard"))
    }
}
