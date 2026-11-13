package com.an0obis.bridge.model.play

import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Hand

/** Cards as bits: index = suit * 13 + (rank - 2), so a higher rank is a higher bit within a suit. */
internal object Bits {
    fun idx(c: Card): Int = c.suit * 13 + (c.rank - 2)
    fun card(i: Int): Card = Card(i % 13 + 2, i / 13)
    fun toHand(m: Long): Hand {
        val cards = ArrayList<Card>(13)
        var x = m
        while (x != 0L) { cards.add(card(low(x))); x = x and (x - 1) }
        return Hand(cards)
    }
    fun suitOf(i: Int): Int = i / 13
    fun suitMask(s: Int): Long = 0x1FFFL shl (13 * s)
    fun bit(i: Int): Long = 1L shl i
    fun high(m: Long): Int = 63 - java.lang.Long.numberOfLeadingZeros(m)
    fun low(m: Long): Int = java.lang.Long.numberOfTrailingZeros(m)
    fun count(m: Long): Int = java.lang.Long.bitCount(m)

    /** Bits strictly between card [a] and a higher card [b]. */
    fun between(a: Int, b: Int): Long = ((1L shl b) - 1) and (-1L shl (a + 1))

    /**
     * Drops cards that are equal for play purposes: in a suit, two of [legal] with no card of [rem]
     * between them. Keeps the lowest of each group.
     */
    fun reduce(legal: Long, rem: Long): Long {
        var out = 0L
        for (suit in 0..3) {
            val sm = suitMask(suit)
            var m = legal and sm
            var prev = -1
            while (m != 0L) {
                val c = low(m)
                m = m and (m - 1)
                if (prev == -1 || (rem and between(prev, c)) != 0L) out = out or bit(c)
                prev = c
            }
        }
        return out
    }
}

/**
 * A fast, copyable picture of a deal in progress with every hand known (a "world" sampled by the
 * player, or a real deal in a test). Seats are 0..3 as in [com.an0obis.bridge.model.Seat].
 */
internal class Sim(val trump: Int, val declarerSide: Int) {
    val hands = LongArray(4)
    var leader = 0
    val trick = IntArray(4)
    var trickSize = 0

    /** Tricks won by North-South so far. */
    var nsTricks = 0
    var tricksDone = 0

    val turn: Int get() = (leader + trickSize) and 3

    val finished: Boolean get() = (hands[0] or hands[1] or hands[2] or hands[3]) == 0L

    fun copy(): Sim {
        val s = Sim(trump, declarerSide)
        System.arraycopy(hands, 0, s.hands, 0, 4)
        s.leader = leader
        System.arraycopy(trick, 0, s.trick, 0, 4)
        s.trickSize = trickSize
        s.nsTricks = nsTricks
        s.tricksDone = tricksDone
        return s
    }

    fun trickMask(): Long {
        var m = 0L
        for (i in 0 until trickSize) m = m or Bits.bit(trick[i])
        return m
    }

    /** Every card not yet played, in a hand or on the table. */
    fun remaining(): Long = hands[0] or hands[1] or hands[2] or hands[3] or trickMask()

    fun legal(): Long {
        val h = hands[turn]
        if (trickSize == 0) return h
        val follow = h and Bits.suitMask(Bits.suitOf(trick[0]))
        return if (follow != 0L) follow else h
    }

    fun beats(c: Int, best: Int, led: Int): Boolean {
        val cs = Bits.suitOf(c)
        val bs = Bits.suitOf(best)
        if (cs == bs) return c > best
        return cs == trump && bs != trump
    }

    fun play(card: Int) {
        val seat = turn
        hands[seat] = hands[seat] and Bits.bit(card).inv()
        trick[trickSize++] = card
        if (trickSize < 4) return
        val led = Bits.suitOf(trick[0])
        var best = 0
        for (i in 1..3) if (beats(trick[i], trick[best], led)) best = i
        val winner = (leader + best) and 3
        if ((winner and 1) == 0) nsTricks++
        tricksDone++
        leader = winner
        trickSize = 0
    }
}

/** Exact play of the rest of a deal when every hand is known, by alpha-beta search. */
internal object DoubleDummy {

    /** The number of tricks North-South will take with best play on both sides. */
    fun solve(s: Sim): Int = search(s, 0, 13)

    private fun search(s: Sim, alpha0: Int, beta0: Int): Int {
        if (s.finished) return s.nsTricks
        var alpha = alpha0
        var beta = beta0
        val legal = Bits.reduce(s.legal(), s.remaining())
        val maximizing = (s.turn and 1) == 0
        var best = if (maximizing) -1 else 99
        var m = legal
        while (m != 0L) {
            val c = Bits.high(m)
            m = m and Bits.bit(c).inv()
            val n = s.copy()
            n.play(c)
            val v = search(n, alpha, beta)
            if (maximizing) {
                if (v > best) best = v
                if (best > alpha) alpha = best
            } else {
                if (v < best) best = v
                if (best < beta) beta = best
            }
            if (alpha >= beta) break
        }
        return best
    }
}
