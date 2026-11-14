package com.an0obis.bridge.model.play

import com.an0obis.bridge.model.Play
import kotlin.random.Random

/**
 * Claims: when the rest of a deal is decided, there is no need to play it out.
 *
 * A claim is accepted only when it does not depend on knowing where the opponents' cards are
 * (otherwise the app would give away a lucky finesse). So the deal is solved exactly in the real
 * layout and again in other layouts of the opponents' remaining cards; every one must give the
 * claiming side all the remaining tricks.
 */
object Claims {

    /** The solver is exact and fast only with a few cards left in each hand. */
    const val MAX_CARDS = 6

    /** Other layouts of the opponents' cards that must also work. */
    private const val LAYOUTS = 16

    /** A [Sim] of the position in [play]: all four hands, the trick in progress, the tricks won so far. */
    internal fun simOf(play: Play): Sim {
        val sim = Sim(play.contract.denom, play.contract.declarer and 1)
        for (seat in 0..3) {
            sim.hands[seat] = play.cardsOf(seat).fold(0L) { m, c -> m or Bits.bit(Bits.idx(c)) }
        }
        sim.leader = play.trickLeader
        for ((i, c) in play.currentTrick.withIndex()) sim.trick[i] = Bits.idx(c)
        sim.trickSize = play.currentTrick.size
        sim.nsTricks = play.tricks.count { (it.winner and 1) == 0 }
        sim.tricksDone = play.tricks.size
        return sim
    }

    /** The North-South tricks still to be won by the solver's best play on both sides. */
    private fun nsRest(sim: Sim): Int = DoubleDummy.solve(sim) - sim.nsTricks

    /**
     * The side (0 = North-South, 1 = East-West) that wins every remaining trick whatever the
     * opponents play and however their cards are placed, or null if that is not certain (or it is
     * too early to check). Only at the start of a trick, with at least two tricks to go.
     */
    fun sureWinner(play: Play): Int? {
        if (play.currentTrick.isNotEmpty()) return null
        val left = 13 - play.tricks.size
        if (left < 2 || left > MAX_CARDS) return null
        val sim = simOf(play)
        val rest = nsRest(sim)
        val side = when (rest) {
            left -> 0
            0 -> 1
            else -> return null
        }

        // the opponents' cards, as they could be laid out: same hand sizes, no suit they showed out of
        val a = (1 - side) // an opposing seat is a, a+2
        val seatA = a
        val seatB = a + 2
        val void = Array(4) { BooleanArray(4) }
        for (t in play.tricks) {
            val led = t.cards[0].suit
            for ((i, c) in t.cards.withIndex()) if (c.suit != led) void[(t.leader + i) and 3][led] = true
        }
        val pool = ArrayList<Int>()
        run { var m = sim.hands[seatA] or sim.hands[seatB]; while (m != 0L) { pool.add(Bits.low(m)); m = m and (m - 1) } }
        val sizeA = Bits.count(sim.hands[seatA])
        val rnd = Random(play.tricks.size * 7919 + 17)
        var tried = 0
        var attempts = 0
        while (tried < LAYOUTS && attempts < LAYOUTS * 6) {
            attempts++
            pool.shuffle(rnd)
            var handA = 0L
            var handB = 0L
            var ok = true
            for ((i, c) in pool.withIndex()) {
                val suit = Bits.suitOf(c)
                if (i < sizeA) { if (void[seatA][suit]) ok = false; handA = handA or Bits.bit(c) }
                else { if (void[seatB][suit]) ok = false; handB = handB or Bits.bit(c) }
            }
            if (!ok) continue
            val other = sim.copy()
            other.hands[seatA] = handA
            other.hands[seatB] = handB
            val r = nsRest(other)
            if (side == 0 && r != left) return null
            if (side == 1 && r != 0) return null
            tried++
        }
        return side
    }
}
