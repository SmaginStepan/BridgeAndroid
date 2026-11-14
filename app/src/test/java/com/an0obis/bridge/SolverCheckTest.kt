package com.an0obis.bridge

import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.play.Bits
import com.an0obis.bridge.model.play.DoubleDummy
import com.an0obis.bridge.model.play.Sim
import org.junit.Assert.assertEquals
import org.junit.Test

/** Compares the pruned solver with a plain minimax that tries every legal card. */
class SolverCheckTest {

    private fun brute(s: Sim): Int {
        if (s.finished) return s.nsTricks
        var m = s.legal()
        val max = (s.turn and 1) == 0
        var best = if (max) -1 else 99
        while (m != 0L) {
            val c = Bits.low(m)
            m = m and (m - 1)
            val n = s.copy()
            n.play(c)
            val v = brute(n)
            best = if (max) maxOf(best, v) else minOf(best, v)
        }
        return best
    }

    @Test
    fun prunedSolverAgreesWithBruteForce() {
        for (n in 1..400) {
            val rnd = SeasonRandom.forPurpose("solver-check", "deal", n)
            val cards = (0 until 52).toMutableList()
            for (i in cards.size - 1 downTo 1) { val j = rnd.nextInt(i + 1); val t = cards[i]; cards[i] = cards[j]; cards[j] = t }
            val per = 3 + n % 2          // 3 or 4 cards each keeps the brute force fast
            val trump = n % 5
            val s = Sim(trump, 0)
            for (seat in 0..3) for (k in 0 until per) s.hands[seat] = s.hands[seat] or Bits.bit(cards[seat * 13 + k])
            s.leader = n % 4
            assertEquals("deal $n trump $trump", brute(s), DoubleDummy.solve(s))
        }
    }
}

