package com.an0obis.bridge.model.play

import com.an0obis.bridge.model.Suit

/**
 * A quick rule-of-thumb card player: lead winners or the long suit, second hand low, third hand
 * high, win as cheaply as possible last, ruff when the opponents are winning. It uses only what
 * a real player could know (own hand, the cards played, the partner's hand when it is the dummy),
 * so it is safe as the rollout policy for the sampling player.
 */
internal object Heuristic {

    fun choose(s: Sim): Int {
        val legal = s.legal()
        if (Bits.count(legal) == 1) return Bits.low(legal)
        return if (s.trickSize == 0) lead(s) else follow(s, legal)
    }

    /** Of touching cards in [mine], the lowest one (the usual card to play from a sequence). */
    private fun lowestOfSequence(card: Int, mine: Long, rem: Long): Int {
        var c = card
        val sm = Bits.suitMask(Bits.suitOf(card))
        while (true) {
            val below = rem and sm and (Bits.bit(c) - 1)
            if (below == 0L) return c
            val next = Bits.high(below)
            if ((mine and Bits.bit(next)) != 0L) c = next else return c
        }
    }

    private fun lead(s: Sim): Int {
        val seat = s.turn
        val mine = s.hands[seat]
        val rem = s.remaining()
        val trump = s.trump
        val declSide = (seat and 1) == s.declarerSide

        // the declarer's side draws trumps while the opponents still have some
        if (trump != Suit.NO_TRUMP && declSide) {
            val tm = Bits.suitMask(trump)
            val myTrumps = mine and tm
            val partnerTrumps = s.hands[(seat + 2) and 3] and tm
            val outstanding = rem and mine.inv() and partnerTrumps.inv() and tm
            if (myTrumps != 0L && outstanding != 0L) {
                return lowestOfSequence(Bits.high(myTrumps), mine, rem)
            }
        }

        // cash a winner: the top card of a suit that nobody can beat
        var bestWinner = -1
        var bestLen = 0
        for (suit in 0..3) {
            val sm = Bits.suitMask(suit)
            val mySuit = mine and sm
            if (mySuit == 0L) continue
            val top = Bits.high(mySuit)
            if (Bits.high(rem and sm) == top && Bits.count(mySuit) > bestLen) {
                bestWinner = top
                bestLen = Bits.count(mySuit)
            }
        }
        if (bestWinner >= 0) return bestWinner

        // otherwise a card from the longest suit, trumps last
        var suitChoice = -1
        var len = 0
        for (suit in 0..3) {
            val n = Bits.count(mine and Bits.suitMask(suit))
            if (n == 0) continue
            val value = n * 2 + (if (suit == trump) -20 else 0)
            if (suitChoice == -1 || value > len) {
                suitChoice = suit
                len = value
            }
        }
        val sm = Bits.suitMask(suitChoice)
        val mySuit = mine and sm
        val n = Bits.count(mySuit)
        val top = Bits.high(mySuit)
        // fourth best from a long suit, top of a sequence of honors, top of a doubleton, else low
        if (n >= 4) {
            var m = mySuit
            repeat(3) { m = m and Bits.bit(Bits.high(m)).inv() }
            return Bits.high(m)
        }
        val second = if (n >= 2) Bits.high(mySuit and Bits.bit(top).inv()) else -1
        if (n >= 3 && top % 13 >= 9 && second >= 0 && (rem and Bits.between(second, top)) == 0L) return top
        if (n == 2) return top
        return Bits.low(mySuit)
    }

    private fun follow(s: Sim, legal: Long): Int {
        val seat = s.turn
        val mine = s.hands[seat]
        val rem = s.remaining()
        val trump = s.trump
        val led = Bits.suitOf(s.trick[0])
        val pos = s.trickSize

        var best = 0
        for (i in 1 until pos) if (s.beats(s.trick[i], s.trick[best], led)) best = i
        val bestCard = s.trick[best]
        val partnerWinning = (((s.leader + best) and 3) and 1) == (seat and 1)

        var winning = 0L
        var m = legal
        while (m != 0L) {
            val c = Bits.low(m)
            m = m and (m - 1)
            if (s.beats(c, bestCard, led)) winning = winning or Bits.bit(c)
        }

        val canFollow = (mine and Bits.suitMask(led)) != 0L
        if (canFollow) {
            if (partnerWinning) {
                if (pos == 3) return Bits.low(legal)
                // partner led and the fourth hand is still to play: help only if the lead can be beaten
                val outstanding = rem and mine.inv() and Bits.suitMask(led) and s.trickMask().inv()
                val unbeatable = outstanding == 0L || Bits.suitOf(bestCard) != led || Bits.high(outstanding) < bestCard
                if (unbeatable || winning == 0L) return Bits.low(legal)
                return lowestOfSequence(Bits.high(winning), mine, rem)
            }
            return when (pos) {
                3 -> if (winning != 0L) Bits.low(winning) else Bits.low(legal)
                1 -> {
                    // second hand low, but cover an honor with an honor
                    if (winning != 0L && bestCard % 13 >= 9) Bits.low(winning) else Bits.low(legal)
                }
                else -> if (winning != 0L) lowestOfSequence(Bits.high(winning), mine, rem) else Bits.low(legal)
            }
        }

        // void in the suit led: ruff if the opponents are winning, otherwise discard
        if (!partnerWinning && winning != 0L && trump != Suit.NO_TRUMP) {
            return Bits.low(winning)
        }
        return discard(s, legal, mine, rem)
    }

    private fun discard(s: Sim, legal: Long, mine: Long, rem: Long): Int {
        var chosen = -1
        var chosenLen = 0
        for (suit in 0..3) {
            if (suit == s.trump) continue
            val sm = Bits.suitMask(suit)
            val mySuit = legal and sm
            if (mySuit == 0L) continue
            // keep a suit whose top card is a winner
            if (Bits.high(rem and sm) == Bits.high(mine and sm)) continue
            val n = Bits.count(mine and sm)
            if (n > chosenLen) {
                chosen = Bits.low(mySuit)
                chosenLen = n
            }
        }
        if (chosen >= 0) return chosen
        // nothing safe: the lowest card of a non-trump suit, else the lowest card
        val nonTrump = legal and Bits.suitMask(s.trump).inv()
        return Bits.low(if (nonTrump != 0L) nonTrump else legal)
    }
}
