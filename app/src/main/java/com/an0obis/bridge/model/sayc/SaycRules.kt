package com.an0obis.bridge.model.sayc

import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit

private const val C = Suit.CLUBS
private const val D = Suit.DIAMONDS
private const val H = Suit.HEARTS
private const val S = Suit.SPADES
private const val NT = Suit.NO_TRUMP

private fun bid(level: Int, denom: Int) = Call.Bid(level, denom)

// ---------------------------------------------------------------------------------------------
// Auction situations. Each returns the facts a rule needs, or null if the situation does not apply.
// ---------------------------------------------------------------------------------------------

/** Partner opened one of a suit and nobody else has bid: the suit. */
private fun AuctionState.partnerOpened1Suit(): Int? {
    val o = opening ?: return null
    val b = o.call as? Call.Bid ?: return null
    return if (o.seat == partner && b.level == 1 && b.denom < NT && bids.size == 1 && oppActions.isEmpty()) b.denom
    else null
}

private fun AuctionState.partnerOpened1NT(): Boolean {
    val o = opening ?: return false
    return o.seat == partner && o.call == bid(1, NT) && bids.size == 1 && oppActions.isEmpty()
}

/** I opened one of a suit, partner responded, nobody else bid: (my suit, partner's response). */
private fun AuctionState.myRebid(): Pair<Int, Call.Bid>? {
    val o = opening ?: return null
    val b = o.call as? Call.Bid ?: return null
    if (o.seat != seat || b.level != 1 || b.denom == NT || bids.size != 2 || oppActions.isNotEmpty()) return null
    val r = partners.singleOrNull()?.call as? Call.Bid ?: return null
    return b.denom to r
}

/** I opened 1NT, partner responded, nobody else bid: partner's response. */
private fun AuctionState.myNtReply(): Call.Bid? {
    val o = opening ?: return null
    if (o.seat != seat || o.call != bid(1, NT) || bids.size != 2 || oppActions.isNotEmpty()) return null
    return partners.singleOrNull()?.call as? Call.Bid
}

/** I responded to 1NT with a transfer and partner completed it: the major. */
private fun AuctionState.transferredMajor(): Int? {
    val o = opening ?: return null
    if (o.seat != partner || o.call != bid(1, NT) || bids.size != 3 || oppActions.isNotEmpty()) return null
    val myCall = mine.singleOrNull()?.call
    val theirs = partners.last().call
    return when {
        myCall == bid(2, D) && theirs == bid(2, H) -> H
        myCall == bid(2, H) && theirs == bid(2, S) -> S
        else -> null
    }
}

/** Partner opened 2C (strong) and nobody else bid. */
private fun AuctionState.partnerOpened2C(): Boolean {
    val o = opening ?: return false
    return o.seat == partner && o.call == bid(2, C) && bids.size == 1 && oppActions.isEmpty()
}

/** I opened 2C and partner answered 2D (waiting), nobody else bid. */
private fun AuctionState.openedStrongAfterWaiting(): Boolean {
    val o = opening ?: return false
    return o.seat == seat && o.call == bid(2, C) && bids.size == 2 &&
        oppActions.isEmpty() && partners.singleOrNull()?.call == bid(2, D)
}

/** An opponent opened one of a suit and I have not acted yet, partner did nothing: the opponent's suit. */
private fun AuctionState.oppOpened1Suit(): Int? {
    val o = opening ?: return null
    val b = o.call as? Call.Bid ?: return null
    if (Seat.sameSide(o.seat, seat) || b.level != 1 || b.denom == NT || bids.size != 1) return null
    if (entries.any { it.seat == partner && it.call !is Call.Pass }) return null
    return b.denom
}

/** An opponent opened one of a suit, partner overcalled and the next opponent passed: (their suit, overcall). */
private fun AuctionState.partnerOvercalled(): Pair<Int, Call.Bid>? {
    val o = opening ?: return null
    val b = o.call as? Call.Bid ?: return null
    if (Seat.sameSide(o.seat, seat) || b.level != 1 || b.denom == NT || bids.size != 2) return null
    if (oppActions.size != 1) return null
    val pb = partners.singleOrNull()?.call as? Call.Bid ?: return null
    return b.denom to pb
}

/** An opponent opened one of a suit and partner doubled (takeout): the opponent's suit. */
private fun AuctionState.partnerDoubledOpening(): Int? {
    val o = opening ?: return null
    val b = o.call as? Call.Bid ?: return null
    if (Seat.sameSide(o.seat, seat) || b.level != 1 || b.denom == NT || bids.size != 1) return null
    val last = entries.lastOrNull { it.call !is Call.Pass } ?: return null
    return if (last.seat == partner && last.call is Call.Dbl) b.denom else null
}

// ---------------------------------------------------------------------------------------------
// Choosing between suits
// ---------------------------------------------------------------------------------------------

/** Responding at the one level over partner's suit [p]: bid the longest suit of 5+, else the cheapest 4-card suit. */
private fun chosenOneLevelSuit(h: HandInfo, p: Int): Int? {
    val candidates = (p + 1..S).filter { h.len[it] >= 4 }
    if (candidates.isEmpty()) return null
    val long = candidates.filter { h.len[it] >= 5 }
    return if (long.isNotEmpty()) long.maxWithOrNull(compareBy({ h.len[it] }, { it })) else candidates.min()
}

/** Responding at the two level (10+ points) below partner's suit [p]: the longest suit, cheapest on ties. */
private fun chosenTwoLevelSuit(h: HandInfo, p: Int): Int? {
    val candidates = (C until p).filter { h.len[it] >= if (it <= D) 4 else 5 }
    return candidates.maxWithOrNull(compareBy({ h.len[it] }, { -it }))
}

/** A second suit for the opener's rebid below the responder's suit (no reverse unless strong). */
private fun chosenRebidSecondSuit(h: HandInfo, own: Int, responder: Int): Int? {
    val candidates = (C until responder).filter { it != own && h.len[it] >= 4 && (it < own || h.hcp >= 17) }
    return candidates.maxWithOrNull(compareBy({ h.len[it] }, { -it }))
}

/** A suit overcall: the longest decent suit of 5+ cards (higher suit on ties), never the opponent's. */
private fun chosenOvercall(h: HandInfo, opp: Int): Int? {
    val candidates = (C..S).filter { it != opp && h.len[it] >= 5 && (h.hcpIn(it) >= 3 || h.len[it] >= 6) }
    return candidates.maxWithOrNull(compareBy({ h.len[it] }, { it }))
}

/** The suit to bid in answer to a takeout double: 5+ cards first (majors preferred), else a 4-card major, else a 4-card minor. */
private fun chosenAdvanceSuit(h: HandInfo, opp: Int): Int? {
    val candidates = (C..S).filter { it != opp && h.len[it] >= 4 }
    if (candidates.isEmpty()) return null
    return candidates.maxWithOrNull(compareBy({ h.len[it] }, { it >= H }, { it }))
}

private fun cheapestLevel(suit: Int, opp: Int) = if (suit > opp) 1 else 2

// ---------------------------------------------------------------------------------------------
// The rule table. Order is priority: the first rule that fits the hand is used.
// ---------------------------------------------------------------------------------------------

/** "SAYC-lite": Standard American opening bids, common responses, rebids, overcalls and takeout doubles. */
object SaycRules {

    val all: List<Rule> = build()

    private fun build(): List<Rule> {
        val rules = mutableListOf<Rule>()

        fun add(
            id: String,
            call: Call,
            hcp: IntRange = 0..40,
            needs: List<Need> = emptyList(),
            balanced: Boolean? = null,
            situation: (AuctionState) -> Boolean,
            extra: (HandInfo, AuctionState) -> Boolean = { _, _ -> true }
        ) {
            rules.add(Rule(id, call, hcp, needs, balanced, situation, extra))
        }

        // ------------------------------------------------------------------ opening bids
        val firstBid = { s: AuctionState -> s.bids.isEmpty() }
        add("open_2c", bid(2, C), 22..40, situation = firstBid)
        add("open_2nt", bid(2, NT), 20..21, balanced = true, situation = firstBid)
        add("open_1nt", bid(1, NT), 15..17, balanced = true, situation = firstBid)
        add("open_1s", bid(1, S), 12..21, listOf(Need(S, 5)), situation = firstBid,
            extra = { h, _ -> h.len[S] >= h.len[H] })
        add("open_1h", bid(1, H), 12..21, listOf(Need(H, 5)), situation = firstBid,
            extra = { h, _ -> h.len[H] > h.len[S] })
        add("open_1d", bid(1, D), 12..21, listOf(Need(H, 0, 4), Need(S, 0, 4)), situation = firstBid,
            extra = { h, _ -> h.len[D] > h.len[C] || (h.len[D] == h.len[C] && h.len[D] >= 4) })
        add("open_1c", bid(1, C), 12..21, listOf(Need(H, 0, 4), Need(S, 0, 4)), situation = firstBid,
            extra = { h, _ -> h.len[C] > h.len[D] || (h.len[C] == h.len[D] && h.len[C] <= 3) })
        for (suit in listOf(D, H, S)) {
            add("open_weak2", bid(2, suit), 5..11, listOf(Need(suit, 6, 6)), situation = firstBid,
                extra = { h, _ -> h.honors(suit) >= 2 })
        }
        for (suit in C..S) {
            add("open_pre3", bid(3, suit), 5..10, listOf(Need(suit, 7)), situation = firstBid,
                extra = { h, _ -> h.hcpIn(suit) >= 3 })
        }
        add("open_pass", Call.Pass, situation = firstBid)

        // ------------------------------------------------------------------ strong 2C
        add("s2c_pos", bid(2, H), 8..40, listOf(Need(H, 5)), situation = { it.partnerOpened2C() },
            extra = { h, _ -> h.honors(H) >= 2 })
        add("s2c_pos", bid(2, S), 8..40, listOf(Need(S, 5)), situation = { it.partnerOpened2C() },
            extra = { h, _ -> h.honors(S) >= 2 })
        add("s2c_pos", bid(3, C), 8..40, listOf(Need(C, 5)), situation = { it.partnerOpened2C() },
            extra = { h, _ -> h.honors(C) >= 2 })
        add("s2c_pos", bid(3, D), 8..40, listOf(Need(D, 5)), situation = { it.partnerOpened2C() },
            extra = { h, _ -> h.honors(D) >= 2 })
        add("s2c_2nt", bid(2, NT), 8..40, balanced = true, situation = { it.partnerOpened2C() })
        add("s2c_wait", bid(2, D), situation = { it.partnerOpened2C() })
        add("s2c_open2nt", bid(2, NT), 22..24, balanced = true, situation = { it.openedStrongAfterWaiting() })
        add("s2c_open3nt", bid(3, NT), 25..27, balanced = true, situation = { it.openedStrongAfterWaiting() })
        for (suit in listOf(H, S)) {
            add("s2c_suit", bid(2, suit), 22..40, listOf(Need(suit, 5)),
                situation = { it.openedStrongAfterWaiting() },
                extra = { h, _ -> (H..S).filter { x -> h.len[x] >= 5 }.maxWithOrNull(compareBy({ h.len[it] }, { it })) == suit })
        }
        for (suit in listOf(C, D)) {
            add("s2c_suit", bid(3, suit), 22..40, listOf(Need(suit, 5)),
                situation = { it.openedStrongAfterWaiting() },
                extra = { h, _ -> (C..S).filter { x -> h.len[x] >= 5 }.maxWithOrNull(compareBy({ h.len[it] }, { it })) == suit })
        }

        // ------------------------------------------------------------------ responses to 1 of a suit
        for (m in listOf(H, S)) {
            val sit = { s: AuctionState -> s.partnerOpened1Suit() == m }
            add("resp_raise4", bid(4, m), 13..40, listOf(Need(m, 3)), situation = sit)
            add("resp_raise3", bid(3, m), 10..12, listOf(Need(m, 3)), situation = sit)
            add("resp_raise2", bid(2, m), 6..9, listOf(Need(m, 3)), situation = sit)
        }
        for (p in C..H) for (t in p + 1..S) {
            add("resp_new1", bid(1, t), 6..40, listOf(Need(t, 4)),
                situation = { it.partnerOpened1Suit() == p },
                extra = { h, _ -> chosenOneLevelSuit(h, p) == t })
        }
        for (m in listOf(C, D)) {
            val sit = { s: AuctionState -> s.partnerOpened1Suit() == m }
            add("resp_minor3", bid(3, m), 10..12, listOf(Need(m, 5)), situation = sit)
            add("resp_minor2", bid(2, m), 6..9, listOf(Need(m, 5)), situation = sit)
        }
        for (p in D..S) for (t in C until p) {
            add("resp_new2", bid(2, t), 10..40, listOf(Need(t, if (t <= D) 4 else 5)),
                situation = { it.partnerOpened1Suit() == p },
                extra = { h, _ -> chosenTwoLevelSuit(h, p) == t })
        }
        val oneSuit = { s: AuctionState -> s.partnerOpened1Suit() != null }
        add("resp_2nt", bid(2, NT), 13..15, balanced = true, situation = oneSuit)
        add("resp_3nt", bid(3, NT), 16..18, balanced = true, situation = oneSuit)
        add("resp_1nt", bid(1, NT), 6..12, situation = oneSuit)
        add("resp_pass", Call.Pass, 0..5, situation = oneSuit)

        // ------------------------------------------------------------------ responses to 1NT
        val oneNt = { s: AuctionState -> s.partnerOpened1NT() }
        add("n_slam", bid(6, NT), 18..40, situation = oneNt)
        add("n_transfer", bid(2, D), 0..40, listOf(Need(H, 5)), situation = oneNt,
            extra = { h, _ -> h.len[H] > h.len[S] })
        add("n_transfer", bid(2, H), 0..40, listOf(Need(S, 5)), situation = oneNt,
            extra = { h, _ -> h.len[S] >= h.len[H] })
        add("n_stayman", bid(2, C), 8..40, situation = oneNt, extra = { h, _ -> h.len[H] == 4 || h.len[S] == 4 })
        add("n_3nt", bid(3, NT), 10..17, situation = oneNt)
        add("n_2nt", bid(2, NT), 8..9, situation = oneNt)
        add("n_pass", Call.Pass, 0..7, situation = oneNt)

        // opener answers
        add("n_complete", bid(2, H), situation = { it.myNtReply() == bid(2, D) })
        add("n_complete", bid(2, S), situation = { it.myNtReply() == bid(2, H) })
        add("n_stayman_h", bid(2, H), needs = listOf(Need(H, 4)), situation = { it.myNtReply() == bid(2, C) })
        add("n_stayman_s", bid(2, S), needs = listOf(Need(S, 4), Need(H, 0, 3)),
            situation = { it.myNtReply() == bid(2, C) })
        add("n_stayman_none", bid(2, D), situation = { it.myNtReply() == bid(2, C) })
        add("n_inv_accept", bid(3, NT), 16..17, situation = { it.myNtReply() == bid(2, NT) })
        add("n_inv_decline", Call.Pass, 15..15, situation = { it.myNtReply() == bid(2, NT) })

        // responder after a completed transfer
        for (m in listOf(H, S)) {
            val sit = { s: AuctionState -> s.transferredMajor() == m }
            add("n_tr_game", bid(4, m), 10..40, listOf(Need(m, 6)), situation = sit)
            add("n_tr_game3nt", bid(3, NT), 10..40, listOf(Need(m, 5, 5)), situation = sit)
            add("n_tr_inv", bid(3, m), 8..9, listOf(Need(m, 6)), situation = sit)
            add("n_tr_inv2nt", bid(2, NT), 8..9, listOf(Need(m, 5, 5)), situation = sit)
            add("n_tr_pass", Call.Pass, 0..7, situation = sit)
        }

        // ------------------------------------------------------------------ opener's rebid after 1 of a suit
        val newSuitResponse = { s: AuctionState ->
            s.myRebid()?.let { (_, r) -> r.level == 1 && r.denom < NT } == true
        }
        for (o in C..H) for (r in o + 1..S) {
            val sit = { s: AuctionState -> s.myRebid()?.let { it.first == o && it.second == bid(1, r) } == true }
            if (r == H || r == S) {
                add("reb_raise4", bid(4, r), 18..21, listOf(Need(r, 4)), situation = sit)
                add("reb_raise3", bid(3, r), 15..17, listOf(Need(r, 4)), situation = sit)
                add("reb_raise2", bid(2, r), 12..14, listOf(Need(r, 4)), situation = sit)
            }
            for (t in r + 1..S) {
                add("reb_new1", bid(1, t), 12..21, listOf(Need(t, 4)), situation = sit,
                    extra = { h, _ -> (r + 1..S).firstOrNull { x -> h.len[x] >= 4 } == t })
            }
        }
        add("reb_2nt", bid(2, NT), 18..19, balanced = true, situation = newSuitResponse)
        add("reb_1nt", bid(1, NT), 12..14, balanced = true, situation = newSuitResponse)
        for (o in C..S) {
            val sit = { s: AuctionState ->
                s.myRebid()?.let { (own, r) -> own == o && r.level == 1 && r.denom < NT } == true
            }
            add("reb_rebid3", bid(3, o), 16..21, listOf(Need(o, 6)), situation = sit)
            add("reb_rebid2", bid(2, o), 12..15, listOf(Need(o, 6)), situation = sit)
        }
        for (o in C..S) for (r in C..S) for (t in C until r) {
            if (t == o || r == o) continue
            add("reb_new2", bid(2, t), 12..21, listOf(Need(t, 4)),
                situation = { s -> s.myRebid()?.let { it.first == o && it.second.level == 1 && it.second.denom == r } == true },
                extra = { h, _ -> chosenRebidSecondSuit(h, o, r) == t })
        }
        for (o in C..S) {
            val sit = { s: AuctionState ->
                s.myRebid()?.let { (own, r) -> own == o && r.level == 1 && r.denom < NT } == true
            }
            add("reb_rebid2", bid(2, o), 12..21, listOf(Need(o, 5)), situation = sit)
        }
        add("reb_1nt", bid(1, NT), 12..14, situation = newSuitResponse)

        // after partner's 1NT response
        val ntResponse = { s: AuctionState -> s.myRebid()?.second == bid(1, NT) }
        for (o in C..S) {
            val sit = { s: AuctionState -> s.myRebid()?.let { it.first == o && it.second == bid(1, NT) } == true }
            add("reb_rebid2", bid(2, o), 12..21, listOf(Need(o, 6)), situation = sit)
            for (t in C until o) {
                add("reb_new2", bid(2, t), 12..21, listOf(Need(t, 4)), situation = sit,
                    extra = { h, _ -> (C until o).filter { x -> h.len[x] >= 4 }.maxWithOrNull(compareBy({ h.len[it] }, { -it })) == t })
            }
        }
        add("reb_2nt", bid(2, NT), 18..19, balanced = true, situation = ntResponse)
        add("reb_pass", Call.Pass, 12..17, situation = ntResponse)

        // ------------------------------------------------------------------ competing over an opponent's 1 of a suit
        val oppSuit = { s: AuctionState -> s.oppOpened1Suit() != null }
        for (o in C..S) {
            add("ov_1nt", bid(1, NT), 15..18, balanced = true,
                situation = { it.oppOpened1Suit() == o }, extra = { h, _ -> h.hasStopper(o) })
            add("ov_takeout_strong", Call.Dbl, 17..40, listOf(Need(o, 0, 3)),
                situation = { it.oppOpened1Suit() == o })
            for (t in C..S) {
                if (t == o) continue
                val level = cheapestLevel(t, o)
                add("ov_suit", bid(level, t), if (level == 1) 8..16 else 11..16, listOf(Need(t, 5)),
                    situation = { it.oppOpened1Suit() == o }, extra = { h, _ -> chosenOvercall(h, o) == t })
            }
            add("ov_takeout", Call.Dbl, 12..16, listOf(Need(o, 0, 2)), situation = { it.oppOpened1Suit() == o },
                extra = { h, _ -> (C..S).filter { it != o }.all { h.len[it] >= 3 } })
        }
        add("ov_pass", Call.Pass, situation = oppSuit)

        // advancing partner's suit overcall
        for (o in C..S) for (p in C..S) {
            if (p == o) continue
            for (level in 1..2) {
                val sit = { s: AuctionState -> s.partnerOvercalled()?.let { it.first == o && it.second == bid(level, p) } == true }
                if (p == H || p == S) add("adv_raise", bid(4, p), 13..40, listOf(Need(p, 3)), situation = sit)
                add("adv_raise", bid(level + 2, p), 10..12, listOf(Need(p, 3)), situation = sit)
                add("adv_raise", bid(level + 1, p), 6..9, listOf(Need(p, 3)), situation = sit)
            }
        }
        for (o in C..S) {
            add("adv_1nt", bid(1, NT), 8..12, balanced = true,
                situation = { it.partnerOvercalled()?.first == o }, extra = { h, _ -> h.hasStopper(o) })
        }
        add("adv_pass", Call.Pass, 0..9, situation = { it.partnerOvercalled() != null })

        // answering partner's takeout double
        for (o in C..S) for (t in C..S) {
            if (t == o) continue
            val sit = { s: AuctionState -> s.partnerDoubledOpening() == o }
            val level = cheapestLevel(t, o)
            val chosen = { h: HandInfo, _: AuctionState -> chosenAdvanceSuit(h, o) == t }
            if (t == H || t == S) {
                add("td_game", bid(4, t), 12..40, listOf(Need(t, 4)), situation = sit, extra = chosen)
            }
        }
        for (o in C..S) {
            add("td_3nt", bid(3, NT), 12..40, situation = { it.partnerDoubledOpening() == o },
                extra = { h, _ -> h.hasStopper(o) && h.balanced })
        }
        for (o in C..S) for (t in C..S) {
            if (t == o) continue
            val sit = { s: AuctionState -> s.partnerDoubledOpening() == o }
            val level = cheapestLevel(t, o)
            val chosen = { h: HandInfo, _: AuctionState -> chosenAdvanceSuit(h, o) == t }
            add("td_jump", bid(level + 1, t), 9..40, listOf(Need(t, 4)), situation = sit, extra = chosen)
            add("td_min", bid(level, t), 0..8, listOf(Need(t, 4)), situation = sit, extra = chosen)
        }
        for (o in C..S) {
            add("td_nt", bid(1, NT), 6..10, situation = { it.partnerDoubledOpening() == o },
                extra = { h, _ -> chosenAdvanceSuit(h, o) == null && h.hasStopper(o) })
            for (t in C..S) {
                if (t == o) continue
                add("td_cheap", bid(cheapestLevel(t, o), t), situation = { it.partnerDoubledOpening() == o },
                    extra = { h, _ ->
                        chosenAdvanceSuit(h, o) == null &&
                            (C..S).filter { x -> x != o }.maxWithOrNull(compareBy({ h.len[it] }, { it })) == t
                    })
            }
        }

        return rules
    }

    /** The rules that describe [call] made at the position after [state]'s calls. */
    fun matching(state: AuctionState, call: Call): List<Rule> =
        all.filter { it.call == call && it.situation(state) }

    /** The first rule that fits [hand] in [state], or null if no rule covers the position. */
    fun firstFitting(state: AuctionState, hand: HandInfo): Rule? =
        all.firstOrNull { it.situation(state) && it.fits(hand, state) }
}
