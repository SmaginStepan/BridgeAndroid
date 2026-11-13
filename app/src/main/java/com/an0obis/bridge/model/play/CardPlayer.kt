package com.an0obis.bridge.model.play

import com.an0obis.bridge.model.BotView
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.DealRandom
import com.an0obis.bridge.model.DefaultRandom
import com.an0obis.bridge.model.Hand

/**
 * Chooses a card by sampling: it deals the cards it cannot see to the hidden hands (respecting
 * the voids the play has already shown), plays each candidate card in every sampled deal, and
 * picks the card that takes the most tricks on average. Late in the play the rest of each sampled
 * deal is solved exactly; earlier it is finished with the quick [Heuristic] policy.
 *
 * It only reads a [BotView], so it never sees a hand the player could not legally see.
 */
object CardPlayer {

    /** With this many cards or fewer left in each hand the rest of the deal is solved exactly. */
    private const val EXACT_CARDS = 5

    /** Counters for tests and tuning: how many sampled worlds fitted the bidding, and how many did not. */
    object Stats {
        @Volatile var matchedWorlds = 0L
        @Volatile var unmatchedWorlds = 0L
    }

    /** Deals tried per sampled world to find hands that fit the bidding. */
    private const val BIDDING_TRIES = 150

    fun choose(
        view: BotView,
        rnd: DealRandom = DefaultRandom,
        worlds: Int = 100,
        timeLimitMs: Long = 600,
        /** Whether a hand is plausible for a seat given the bidding (null: no such knowledge). */
        plausible: ((seat: Int, hand: Hand) -> Boolean)? = null
    ): Card {
        val legalCards = view.legalCards
        if (legalCards.size == 1) return legalCards[0]
        val contract = view.contract ?: return legalCards[rnd.nextInt(legalCards.size)]
        val acting = view.actingSeat
        val viewerSide = view.viewer and 1

        // ---- what is known
        val known = BooleanArray(4)
        val mask = LongArray(4)
        for ((seat, hand) in view.hands) {
            known[seat] = true
            for (c in hand.cards) mask[seat] = mask[seat] or Bits.bit(Bits.idx(c))
        }
        var seen = 0L
        val void = Array(4) { BooleanArray(4) }
        val handSize = IntArray(4) { 13 - view.tricks.size }

        fun record(leader: Int, cards: List<Card>) {
            val led = cards.firstOrNull()?.suit ?: return
            for ((i, c) in cards.withIndex()) {
                seen = seen or Bits.bit(Bits.idx(c))
                if (c.suit != led) void[(leader + i) and 3][led] = true
            }
        }
        for (t in view.tricks) record(t.leader, t.cards)
        record(view.trickLeader, view.currentTrick)
        for (i in view.currentTrick.indices) handSize[(view.trickLeader + i) and 3]--

        var visibleCards = 0L
        for (seat in 0..3) if (known[seat]) visibleCards = visibleCards or mask[seat]
        val allCards = (1L shl 52) - 1
        val unseen = allCards and seen.inv() and visibleCards.inv()
        val unknownSeats = (0..3).filter { !known[it] }
        val unseenList = ArrayList<Int>()
        run { var m = unseen; while (m != 0L) { unseenList.add(Bits.low(m)); m = m and (m - 1) } }
        if (unknownSeats.sumOf { handSize[it] } != unseenList.size) {
            return legalCards[rnd.nextInt(legalCards.size)]
        }

        // ---- the candidate cards (equal cards counted once)
        var legalMask = 0L
        for (c in legalCards) legalMask = legalMask or Bits.bit(Bits.idx(c))
        var trickMask = 0L
        for (c in view.currentTrick) trickMask = trickMask or Bits.bit(Bits.idx(c))
        val rem = visibleCards or unseen or trickMask
        val reduced = Bits.reduce(legalMask, rem)
        val cands = ArrayList<Int>()
        run { var m = reduced; while (m != 0L) { cands.add(Bits.low(m)); m = m and (m - 1) } }
        if (cands.size == 1) return lead(cands[0], view, mask[acting], rem)

        // ---- sample worlds and score every candidate in each
        val sum = DoubleArray(cands.size)
        val deadline = System.nanoTime() + timeLimitMs * 1_000_000L
        var done = 0
        val minWorlds = minOf(8, worlds)
        val ns = view.tricks.count { (it.winner and 1) == 0 }

        // if the bidding cannot be matched at all (say a human bid off-system), stop trying
        var useBidding = plausible != null
        var matched = 0
        var unmatched = 0
        val check: ((Int, Long) -> Boolean)? = plausible?.let { f -> { seat, m -> f(seat, Bits.toHand(m)) } }

        for (w in 0 until maxOf(1, worlds)) {
            if (w >= minWorlds && System.nanoTime() > deadline) break
            val dealt = deal(unknownSeats, handSize, unseenList, void, rnd, if (useBidding) check else null)
            val hands = dealt.hands
            if (useBidding) {
                if (dealt.matchedBidding) matched++ else unmatched++
                if (matched == 0 && unmatched >= 3) useBidding = false
                Stats.matchedWorlds += if (dealt.matchedBidding) 1 else 0
                Stats.unmatchedWorlds += if (dealt.matchedBidding) 0 else 1
            }
            val base = Sim(contract.denom, contract.declarer and 1)
            for (seat in 0..3) base.hands[seat] = if (known[seat]) mask[seat] else hands[seat]
            base.leader = view.trickLeader
            for ((i, c) in view.currentTrick.withIndex()) base.trick[i] = Bits.idx(c)
            base.trickSize = view.currentTrick.size
            base.nsTricks = ns
            base.tricksDone = view.tricks.size

            val habit = Heuristic.choose(base)
            for ((k, card) in cands.withIndex()) {
                val s = base.copy()
                s.play(card)
                val nsFinal = if (maxCards(s) <= EXACT_CARDS) DoubleDummy.solve(s) else rollout(s)
                val mine = if (viewerSide == 0) nsFinal else 13 - nsFinal
                sum[k] += mine + (if (card == habit) 0.02 else 0.0)
            }
            done++
        }

        var bestK = 0
        for (k in 1 until cands.size) if (sum[k] > sum[bestK]) bestK = k
        return lead(cands[bestK], view, mask[acting], rem)
    }

    /** Of equal cards, the top one when leading (the usual lead), else the lowest as chosen. */
    private fun lead(card: Int, view: BotView, hand: Long, rem: Long): Card {
        var c = card
        if (view.currentTrick.isEmpty()) {
            val sm = Bits.suitMask(Bits.suitOf(c))
            while (true) {
                val above = hand and sm and (-1L shl (c + 1))
                if (above == 0L) break
                val next = Bits.low(above)
                if ((rem and Bits.between(c, next)) == 0L) c = next else break
            }
        }
        return Bits.card(c)
    }

    private fun maxCards(s: Sim): Int = maxOf(maxOf(Bits.count(s.hands[0]), Bits.count(s.hands[1])), maxOf(Bits.count(s.hands[2]), Bits.count(s.hands[3])))

    private fun rollout(s: Sim): Int {
        while (!s.finished) s.play(Heuristic.choose(s))
        return s.nsTricks
    }

    private class Dealt(val hands: LongArray, val matchedBidding: Boolean)

    /**
     * Deals [pool] to [seats] (sizes in [size]) without giving anyone a suit they showed out of.
     * With [plausible] it keeps dealing until every hand fits the bidding (up to [BIDDING_TRIES]
     * deals); if none does, it returns a deal that only respects the voids.
     */
    private fun deal(
        seats: List<Int>,
        size: IntArray,
        pool: List<Int>,
        void: Array<BooleanArray>,
        rnd: DealRandom,
        plausible: ((Int, Long) -> Boolean)?
    ): Dealt {
        if (seats.isEmpty()) return Dealt(LongArray(4), true)
        var voidOnly: LongArray? = null
        val tries = if (plausible == null) 1 else BIDDING_TRIES
        repeat(tries) {
            val one = dealRespectingVoids(seats, size, pool, void, rnd) ?: return@repeat
            if (voidOnly == null) voidOnly = one
            if (plausible == null || seats.all { plausible(it, one[it]) }) return Dealt(one, plausible != null)
        }
        return Dealt(voidOnly ?: dealIgnoringVoids(seats, size, pool), false)
    }

    private fun dealIgnoringVoids(seats: List<Int>, size: IntArray, pool: List<Int>): LongArray {
        val out = LongArray(4)
        val room = IntArray(4) { size[it] }
        for (c in pool) {
            val seat = seats.first { room[it] > 0 }
            room[seat]--
            out[seat] = out[seat] or Bits.bit(c)
        }
        return out
    }

    /** One random deal that gives nobody a suit they showed out of, or null if the voids clash. */
    private fun dealRespectingVoids(
        seats: List<Int>,
        size: IntArray,
        pool: List<Int>,
        void: Array<BooleanArray>,
        rnd: DealRandom
    ): LongArray? {
        repeat(40) {
            val cards = pool.toMutableList()
            for (i in cards.size - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val t = cards[i]; cards[i] = cards[j]; cards[j] = t
            }
            // the most restricted cards first, so that they find a place
            val order = cards.sortedBy { c -> seats.count { !void[it][Bits.suitOf(c)] } }
            val room = IntArray(4) { size[it] }
            val result = LongArray(4)
            var ok = true
            for (c in order) {
                val open = seats.filter { room[it] > 0 && !void[it][Bits.suitOf(c)] }
                if (open.isEmpty()) { ok = false; break }
                val seat = open[rnd.nextInt(open.size)]
                room[seat]--
                result[seat] = result[seat] or Bits.bit(c)
            }
            if (ok) return result
        }
        return null
    }
}
