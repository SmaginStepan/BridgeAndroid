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

/** Partner opened [call] and nobody else has bid. */
private fun AuctionState.partnerOpened(call: Call): Boolean {
    val o = opening ?: return false
    return o.seat == partner && o.call == call && bids.size == 1 && oppActions.isEmpty()
}

private fun AuctionState.partnerOpened1NT(): Boolean = partnerOpened(bid(1, NT))

/** I opened [call], partner answered and nobody else bid: partner's answer. */
private fun AuctionState.myOpeningAnswered(call: Call): Call.Bid? {
    val o = opening ?: return null
    if (o.seat != seat || o.call != call || bids.size != 2 || oppActions.isNotEmpty()) return null
    return partners.singleOrNull()?.call as? Call.Bid
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
private fun AuctionState.myNtReply(): Call.Bid? = myOpeningAnswered(bid(1, NT))

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
private fun AuctionState.partnerOpened2C(): Boolean = partnerOpened(bid(2, C))

/** I opened 2C and partner answered 2D (waiting), nobody else bid. */
private fun AuctionState.openedStrongAfterWaiting(): Boolean = myOpeningAnswered(bid(2, C)) == bid(2, D)

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

/** Opening with four-card majors (Acol): the longest suit; a four-card major before a four-card minor, hearts before spades. */
private fun chosenOpeningSuit4(h: HandInfo): Int {
    val longest = h.len.max()
    val suits = (C..S).filter { h.len[it] == longest }
    if (longest >= 5) return suits.max()
    return when {
        H in suits -> H
        S in suits -> S
        D in suits && C in suits -> if (h.len[D] >= 4) D else C
        else -> suits.max()
    }
}

/** The longest suit of five or more cards, the higher one on ties. */
private fun longestFiveCardSuit(h: HandInfo, among: IntRange = C..S): Int? =
    among.filter { h.len[it] >= 5 }.maxWithOrNull(compareBy({ h.len[it] }, { it }))

/** The longest suit, majors before minors and the higher suit on ties. */
private fun longestSuit(h: HandInfo): Int = (C..S).maxWith(compareBy({ h.len[it] }, { it >= H }, { it }))

private fun cheapestLevel(suit: Int, opp: Int) = if (suit > opp) 1 else 2

// ---------------------------------------------------------------------------------------------
// The rule table. Order is priority: the first rule that fits the hand is used.
// ---------------------------------------------------------------------------------------------

/**
 * Builds the rule table of a system from its [SystemConfig]. The Standard American preset gives
 * "SAYC-lite": opening bids, common responses, rebids, overcalls and takeout doubles. The other
 * options change the openings and the first rounds of answers; later rounds are left to the
 * points-and-fit logic ([Fallback]).
 */
internal class RuleBuilder(private val c: SystemConfig) {

    private val rules = mutableListOf<Rule>()

    private fun add(
        id: String,
        call: Call,
        hcp: IntRange = 0..40,
        needs: List<Need> = emptyList(),
        balanced: Boolean? = null,
        gameForcing: Boolean = false,
        situation: (AuctionState) -> Boolean,
        extra: (HandInfo, AuctionState) -> Boolean = { _, _ -> true }
    ) {
        if (hcp.isEmpty()) return // the system's ranges leave no room for this call
        rules.add(Rule(id, call, hcp, needs, balanced, situation, extra, gameForcing))
    }

    /** Partner opened one of a suit that is natural in this system (not an artificial 1♣): the suit. */
    private fun AuctionState.partnerOpenedNatural1Suit(): Int? =
        partnerOpened1Suit()?.takeIf { it != C || c.club == ClubStyle.NATURAL }

    /** Like [myRebid], for natural openings only. */
    private fun AuctionState.myNaturalRebid(): Pair<Int, Call.Bid>? =
        myRebid()?.takeIf { it.first != C || c.club == ClubStyle.NATURAL }

    // opener's ranges, derived from the options
    private val minimum = c.openMin..minOf(14, c.oneSuitMax)
    private val medium = 15..minOf(17, c.oneSuitMax)
    private val maximum = 18..c.oneSuitMax
    private val oneSuit = c.openMin..c.oneSuitMax

    /** A balanced opener outside the 1NT range rebids 1NT (just below a strong 1NT, just above a weak one) or 2NT. */
    private val rebid1nt = if (c.weakNoTrump) c.ntMax + 1..c.ntMax + 2 else c.openMin..c.ntMin - 1
    private val rebid2nt = if (c.weakNoTrump) c.ntMax + 3..19 else c.ntMax + 1..c.ntMax + 2

    fun build(): List<Rule> {
        openings()
        strongTwoClubs()
        strongClub()
        polishClub()
        twoLevelConventions()
        responsesToOneSuit()
        responsesToOneNt()
        rebids()
        competition()
        return rules
    }

    // ------------------------------------------------------------------ opening bids
    private fun openings() {
        val firstBid = { s: AuctionState -> s.bids.isEmpty() }
        when (c.club) {
            ClubStyle.NATURAL -> {
                add("open_2c", bid(2, C), c.strongTwoClubsMin..40, gameForcing = true, situation = firstBid)
                add("open_2nt", bid(2, NT), c.twoNtMin..c.twoNtMax, balanced = true, situation = firstBid)
                add("open_1nt", bid(1, NT), c.ntMin..c.ntMax, balanced = true, situation = firstBid)
                oneOfASuit(firstBid)
            }
            ClubStyle.STRONG -> {
                add("open_2nt", bid(2, NT), c.twoNtMin..c.twoNtMax, balanced = true, situation = firstBid)
                add("open_1c_strong", bid(1, C), c.strongClubMin..40, situation = firstBid)
                add("open_1nt", bid(1, NT), c.ntMin..c.ntMax, balanced = true, situation = firstBid)
                oneOfAMajor(firstBid)
                naturalTwoClubs(firstBid, c.oneSuitMax)
                shortDiamonds(firstBid)
                add("open_1d_prec", bid(1, D), oneSuit, situation = firstBid)
            }
            ClubStyle.POLISH -> {
                add("open_2nt", bid(2, NT), c.twoNtMin..c.twoNtMax, balanced = true, situation = firstBid)
                add("open_1c_polish_strong", bid(1, C), c.strongClubMin..40, situation = firstBid)
                add("open_1nt", bid(1, NT), c.ntMin..c.ntMax, balanced = true, situation = firstBid)
                oneOfAMajor(firstBid)
                add("open_1c_polish", bid(1, C), c.openMin..c.ntMin - 1, balanced = true, situation = firstBid)
                add("open_1c_polish", bid(1, C), c.ntMin..c.oneSuitMax, listOf(Need(C, 5)), situation = firstBid,
                    extra = { h, _ -> h.len[C] >= h.len[D] })
                naturalTwoClubs(firstBid, c.ntMin - 1)
                shortDiamonds(firstBid)
                add("open_1d_polish", bid(1, D), oneSuit, listOf(Need(D, 4)), situation = firstBid)
                add("open_1c_polish", bid(1, C), oneSuit, situation = firstBid)
            }
        }
        weakTwos(firstBid)
        for (suit in C..S) {
            add("open_pre3", bid(3, suit), 5..10, listOf(Need(suit, 7)), situation = firstBid,
                extra = { h, _ -> h.hcpIn(suit) >= 3 })
        }
        add("open_pass", Call.Pass, situation = firstBid)
    }

    /** 1♥/1♠ (and with four-card majors also the minors) with the opener's one-suit range. */
    private fun oneOfASuit(firstBid: (AuctionState) -> Boolean) {
        if (c.majorLength == 4) {
            for ((suit, id) in listOf(S to "open_1s4", H to "open_1h4", D to "open_1d4", C to "open_1c4")) {
                add(id, bid(1, suit), oneSuit, listOf(Need(suit, 4)), situation = firstBid,
                    extra = { h, _ -> chosenOpeningSuit4(h) == suit })
            }
            return
        }
        oneOfAMajor(firstBid)
        add("open_1d", bid(1, D), oneSuit, listOf(Need(H, 0, 4), Need(S, 0, 4)), situation = firstBid,
            extra = { h, _ -> h.len[D] > h.len[C] || (h.len[D] == h.len[C] && h.len[D] >= 4) })
        add("open_1c", bid(1, C), oneSuit, listOf(Need(H, 0, 4), Need(S, 0, 4)), situation = firstBid,
            extra = { h, _ -> h.len[C] > h.len[D] || (h.len[C] == h.len[D] && h.len[C] <= 3) })
    }

    private fun oneOfAMajor(firstBid: (AuctionState) -> Boolean) {
        add("open_1s", bid(1, S), oneSuit, listOf(Need(S, 5)), situation = firstBid,
            extra = { h, _ -> h.len[S] >= h.len[H] })
        add("open_1h", bid(1, H), oneSuit, listOf(Need(H, 5)), situation = firstBid,
            extra = { h, _ -> h.len[H] > h.len[S] })
    }

    /** 2♣ with long clubs: six of them, or five and a four-card major (strong-club systems). */
    private fun naturalTwoClubs(firstBid: (AuctionState) -> Boolean, max: Int) {
        add("open_2c_nat", bid(2, C), c.openMin..max, listOf(Need(C, 5), Need(H, 0, 4), Need(S, 0, 4)), situation = firstBid,
            extra = { h, _ -> h.len[C] >= 6 || (h.len[C] == 5 && (h.len[H] == 4 || h.len[S] == 4)) })
    }

    private fun shortDiamonds(firstBid: (AuctionState) -> Boolean) {
        if (c.twoDiamonds != TwoDiamonds.SHORT) return
        add("open_2d_short", bid(2, D), oneSuit, listOf(Need(D, 0, 1), Need(H, 3, 4), Need(S, 3, 4), Need(C, 4, 5)),
            situation = firstBid, extra = { h, _ -> h.len[H] == 4 || h.len[S] == 4 })
    }

    private fun weakTwos(firstBid: (AuctionState) -> Boolean) {
        val weak = 5..c.openMin - 1
        if (c.twoDiamonds == TwoDiamonds.WEAK) {
            add("open_weak2", bid(2, D), weak, listOf(Need(D, 6, 6)), situation = firstBid,
                extra = { h, _ -> h.honors(D) >= 2 })
        }
        if (c.twoDiamonds == TwoDiamonds.MULTI) {
            for (suit in listOf(H, S)) {
                add("open_2d_multi", bid(2, D), 5..10, listOf(Need(suit, 6, 6)), situation = firstBid,
                    extra = { h, _ -> h.honors(suit) >= 2 })
            }
        }
        for (suit in listOf(H, S)) {
            when (c.twoMajors) {
                TwoMajors.WEAK -> add("open_weak2", bid(2, suit), weak, listOf(Need(suit, 6, 6)), situation = firstBid,
                    extra = { h, _ -> h.honors(suit) >= 2 })
                TwoMajors.POLISH -> add("open_2m_polish", bid(2, suit), 5..10, listOf(Need(suit, 5, 5)), situation = firstBid,
                    extra = { h, _ -> (h.len[C] >= 4 || h.len[D] >= 4) && h.len[S + H - suit] <= 3 })
            }
        }
    }

    // ------------------------------------------------------------------ strong 2C
    private fun strongTwoClubs() {
        if (c.club != ClubStyle.NATURAL) return
        val strong = c.strongTwoClubsMin
        add("s2c_pos", bid(2, H), 8..40, listOf(Need(H, 5)), gameForcing = true, situation = { it.partnerOpened2C() },
            extra = { h, _ -> h.honors(H) >= 2 })
        add("s2c_pos", bid(2, S), 8..40, listOf(Need(S, 5)), gameForcing = true, situation = { it.partnerOpened2C() },
            extra = { h, _ -> h.honors(S) >= 2 })
        add("s2c_pos", bid(3, C), 8..40, listOf(Need(C, 5)), gameForcing = true, situation = { it.partnerOpened2C() },
            extra = { h, _ -> h.honors(C) >= 2 })
        add("s2c_pos", bid(3, D), 8..40, listOf(Need(D, 5)), gameForcing = true, situation = { it.partnerOpened2C() },
            extra = { h, _ -> h.honors(D) >= 2 })
        add("s2c_2nt", bid(2, NT), 8..40, balanced = true, gameForcing = true, situation = { it.partnerOpened2C() })
        add("s2c_wait", bid(2, D), gameForcing = true, situation = { it.partnerOpened2C() })
        add("s2c_open2nt", bid(2, NT), strong..strong + 2, balanced = true, gameForcing = true,
            situation = { it.openedStrongAfterWaiting() })
        add("s2c_open3nt", bid(3, NT), strong + 3..strong + 5, balanced = true, gameForcing = true,
            situation = { it.openedStrongAfterWaiting() })
        for (suit in listOf(H, S)) {
            add("s2c_suit", bid(2, suit), strong..40, listOf(Need(suit, 5)), gameForcing = true,
                situation = { it.openedStrongAfterWaiting() },
                extra = { h, _ -> (H..S).filter { x -> h.len[x] >= 5 }.maxWithOrNull(compareBy({ h.len[it] }, { it })) == suit })
        }
        for (suit in listOf(C, D)) {
            add("s2c_suit", bid(3, suit), strong..40, listOf(Need(suit, 5)), gameForcing = true,
                situation = { it.openedStrongAfterWaiting() },
                extra = { h, _ -> (C..S).filter { x -> h.len[x] >= 5 }.maxWithOrNull(compareBy({ h.len[it] }, { it })) == suit })
        }
    }

    // ------------------------------------------------------------------ strong 1♣ (Precision)
    private fun strongClub() {
        if (c.club != ClubStyle.STRONG) return
        val sc = { s: AuctionState -> s.partnerOpened(bid(1, C)) }
        for (m in listOf(S, H)) {
            add("sc_pos_major", bid(1, m), 8..40, listOf(Need(m, 5)), gameForcing = true, situation = sc,
                extra = { h, _ -> longestFiveCardSuit(h) == m })
        }
        for (m in listOf(D, C)) {
            add("sc_pos_minor", bid(2, m), 8..40, listOf(Need(m, 5)), gameForcing = true, situation = sc,
                extra = { h, _ -> longestFiveCardSuit(h) == m })
        }
        add("sc_pos_2nt", bid(2, NT), 14..40, balanced = true, gameForcing = true, situation = sc)
        add("sc_pos_nt", bid(1, NT), 8..40, gameForcing = true, situation = sc)
        add("sc_neg", bid(1, D), 0..7, situation = sc)

        // opener after the negative 1♦
        val neg = { s: AuctionState -> s.myOpeningAnswered(bid(1, C)) == bid(1, D) }
        add("sc_reb_1nt", bid(1, NT), c.strongClubMin..c.strongClubMin + 2, balanced = true, situation = neg)
        add("sc_reb_2nt", bid(2, NT), c.strongClubMin + 3..c.twoNtMin - 1, balanced = true, situation = neg)
        add("sc_reb_3nt", bid(3, NT), c.twoNtMax + 1..40, balanced = true, situation = neg)
        for (suit in C..S) {
            add("sc_reb_suit", bid(if (suit >= H) 1 else 2, suit), c.strongClubMin..40, listOf(Need(suit, 4)), situation = neg,
                extra = { h, _ -> longestSuit(h) == suit })
        }
    }

    // ------------------------------------------------------------------ Polish 1♣
    private fun polishClub() {
        if (c.club != ClubStyle.POLISH) return
        val pc = { s: AuctionState -> s.partnerOpened(bid(1, C)) }
        val noMajor = { h: HandInfo, _: AuctionState -> h.len[H] < 4 && h.len[S] < 4 }
        for (m in listOf(H, S)) {
            add("pc_major", bid(1, m), 7..40, listOf(Need(m, 4)), situation = pc,
                extra = { h, _ -> (H..S).filter { h.len[it] >= 4 }.maxWithOrNull(compareBy({ h.len[it] }, { -it })) == m })
        }
        for (m in listOf(D, C)) {
            add("pc_minor", bid(2, m), 12..40, listOf(Need(m, 5)), gameForcing = true, situation = pc,
                extra = { h, s -> noMajor(h, s) && longestFiveCardSuit(h, C..D) == m })
        }
        add("pc_3nt", bid(3, NT), 13..15, balanced = true, situation = pc, extra = noMajor)
        add("pc_2nt", bid(2, NT), 11..12, balanced = true, situation = pc, extra = noMajor)
        add("pc_1nt", bid(1, NT), 7..12, situation = pc, extra = noMajor)
        add("pc_neg", bid(1, D), 0..6, situation = pc)

        // opener's rebid after 1♦
        val neg = { s: AuctionState -> s.myOpeningAnswered(bid(1, C)) == bid(1, D) }
        add("pcr_strong_nt", bid(2, NT), c.strongClubMin..c.strongClubMin + 1, balanced = true, situation = neg)
        for (suit in C..S) {
            add("pcr_strong_jump", bid(if (suit >= H) 2 else 3, suit), c.strongClubMin..40, listOf(Need(suit, 4)),
                situation = neg, extra = { h, _ -> longestSuit(h) == suit })
        }
        add("pcr_1nt", bid(1, NT), c.openMin..c.ntMin - 1, balanced = true, situation = neg)
        add("pcr_clubs", bid(2, C), c.ntMin..c.oneSuitMax, listOf(Need(C, 5)), situation = neg)
        for (suit in C..S) {
            add("pcr_suit", bid(if (suit >= H) 1 else 2, suit), c.openMin..c.oneSuitMax, listOf(Need(suit, 4)),
                situation = neg, extra = { h, _ -> longestSuit(h) == suit })
        }
        // opener's rebid after a major
        for (m in listOf(H, S)) {
            val major = { s: AuctionState -> s.myOpeningAnswered(bid(1, C)) == bid(1, m) }
            add("pcr_raise4", bid(4, m), c.strongClubMin..40, listOf(Need(m, 4)), situation = major)
            add("pcr_raise3", bid(3, m), c.ntMin..c.oneSuitMax, listOf(Need(m, 4)), situation = major)
            add("pcr_raise2", bid(2, m), c.openMin..c.ntMin - 1, listOf(Need(m, 4)), situation = major)
            add("pcr_1nt", bid(1, NT), c.openMin..c.ntMin - 1, balanced = true, situation = major)
        }
    }

    // ------------------------------------------------------------------ the Multi 2♦
    private fun twoLevelConventions() {
        if (c.twoDiamonds != TwoDiamonds.MULTI) return
        val multi = { s: AuctionState -> s.partnerOpened(bid(2, D)) }
        add("multi_ask", bid(2, NT), 16..40, gameForcing = false, situation = multi)
        add("multi_pc", bid(2, H), 0..15, situation = multi)
        val afterPc = { s: AuctionState -> s.myOpeningAnswered(bid(2, D)) == bid(2, H) }
        add("multi_pass", Call.Pass, needs = listOf(Need(H, 6)), situation = afterPc)
        add("multi_spades", bid(2, S), needs = listOf(Need(S, 6)), situation = afterPc)
        val afterAsk = { s: AuctionState -> s.myOpeningAnswered(bid(2, D)) == bid(2, NT) }
        for (m in listOf(H, S)) {
            add("multi_show", bid(3, m), needs = listOf(Need(m, 6)), situation = afterAsk)
        }
    }

    // ------------------------------------------------------------------ responses to 1 of a suit
    private fun responsesToOneSuit() {
        val raiseLength = if (c.majorLength == 4) 4 else 3
        for (m in listOf(H, S)) {
            val sit = { s: AuctionState -> s.partnerOpenedNatural1Suit() == m }
            if (c.jacoby2nt) {
                add("resp_jacoby", bid(2, NT), 13..40, listOf(Need(m, 4)), gameForcing = true, situation = sit)
            }
            add("resp_raise4", bid(4, m), 13..40, listOf(Need(m, raiseLength)), situation = sit)
            add("resp_raise3", bid(3, m), 10..12, listOf(Need(m, raiseLength)), situation = sit)
            add("resp_raise2", bid(2, m), 6..9, listOf(Need(m, raiseLength)), situation = sit)
        }
        for (p in C..H) for (t in p + 1..S) {
            add("resp_new1", bid(1, t), 6..40, listOf(Need(t, 4)),
                situation = { it.partnerOpenedNatural1Suit() == p },
                extra = { h, _ -> chosenOneLevelSuit(h, p) == t })
        }
        for (m in listOf(C, D)) {
            val sit = { s: AuctionState -> s.partnerOpenedNatural1Suit() == m }
            add("resp_minor3", bid(3, m), 10..12, listOf(Need(m, 5)), situation = sit)
            add("resp_minor2", bid(2, m), 6..9, listOf(Need(m, 5)), situation = sit)
        }
        for (p in D..S) for (t in C until p) {
            val sit = { s: AuctionState -> s.partnerOpenedNatural1Suit() == p }
            val chosen = { h: HandInfo, _: AuctionState -> chosenTwoLevelSuit(h, p) == t }
            val needs = listOf(Need(t, if (t <= D) 4 else 5))
            if (c.twoOverOne) add("resp_2over1", bid(2, t), 13..40, needs, gameForcing = true, situation = sit, extra = chosen)
            else add("resp_new2", bid(2, t), 10..40, needs, situation = sit, extra = chosen)
        }
        val oneSuit = { s: AuctionState -> s.partnerOpenedNatural1Suit() != null }
        // with Jacoby 2NT a natural 2NT is only left over the minors
        val ntSuit = if (c.jacoby2nt) { s: AuctionState -> s.partnerOpenedNatural1Suit()?.let { it <= D } == true } else oneSuit
        when (c.ntResponses) {
            NtResponses.STANDARD -> {
                add("resp_2nt", bid(2, NT), 13..15, balanced = true, situation = ntSuit)
                add("resp_3nt", bid(3, NT), 16..18, balanced = true, situation = oneSuit)
            }
            NtResponses.LIMIT -> {
                add("resp_3nt", bid(3, NT), 13..15, balanced = true, situation = oneSuit)
                add("resp_2nt_limit", bid(2, NT), 10..12, balanced = true, situation = ntSuit)
            }
        }
        val ntMax = if (c.ntResponses == NtResponses.LIMIT) 9 else 12
        if (c.twoOverOne) {
            add("resp_1nt_forcing", bid(1, NT), 6..12, situation = { s -> s.partnerOpenedNatural1Suit()?.let { it >= H } == true })
        }
        add("resp_1nt", bid(1, NT), 6..ntMax, situation = oneSuit)
        add("resp_pass", Call.Pass, 0..5, situation = oneSuit)
    }

    // ------------------------------------------------------------------ responses to 1NT
    private fun responsesToOneNt() {
        val lo = c.ntMin
        val oneNt = { s: AuctionState -> s.partnerOpened1NT() }
        val invite = 23 - lo..24 - lo
        val game = 25 - lo
        add("n_slam", bid(6, NT), 33 - lo..40, situation = oneNt)
        if (c.transfers) {
            add("n_transfer", bid(2, D), 0..40, listOf(Need(H, 5)), situation = oneNt,
                extra = { h, _ -> h.len[H] > h.len[S] })
            add("n_transfer", bid(2, H), 0..40, listOf(Need(S, 5)), situation = oneNt,
                extra = { h, _ -> h.len[S] >= h.len[H] })
        }
        if (c.stayman) {
            add("n_stayman", bid(2, C), invite.first..40, situation = oneNt, extra = { h, _ -> h.len[H] == 4 || h.len[S] == 4 })
        }
        add("n_3nt", bid(3, NT), game..32 - lo, situation = oneNt)
        add("n_2nt", bid(2, NT), invite, situation = oneNt)
        add("n_pass", Call.Pass, 0..invite.first - 1, situation = oneNt)

        // opener answers
        val mid = (c.ntMin + c.ntMax + 1) / 2
        if (c.transfers) {
            add("n_complete", bid(2, H), situation = { it.myNtReply() == bid(2, D) })
            add("n_complete", bid(2, S), situation = { it.myNtReply() == bid(2, H) })
        }
        if (c.stayman) {
            add("n_stayman_h", bid(2, H), needs = listOf(Need(H, 4)), situation = { it.myNtReply() == bid(2, C) })
            add("n_stayman_s", bid(2, S), needs = listOf(Need(S, 4), Need(H, 0, 3)),
                situation = { it.myNtReply() == bid(2, C) })
            add("n_stayman_none", bid(2, D), situation = { it.myNtReply() == bid(2, C) })
        }
        add("n_inv_accept", bid(3, NT), mid..c.ntMax, situation = { it.myNtReply() == bid(2, NT) })
        add("n_inv_decline", Call.Pass, c.ntMin..mid - 1, situation = { it.myNtReply() == bid(2, NT) })

        // responder after a completed transfer
        if (c.transfers) {
            for (m in listOf(H, S)) {
                val sit = { s: AuctionState -> s.transferredMajor() == m }
                add("n_tr_game", bid(4, m), game..40, listOf(Need(m, 6)), situation = sit)
                add("n_tr_game3nt", bid(3, NT), game..40, listOf(Need(m, 5, 5)), situation = sit)
                add("n_tr_inv", bid(3, m), invite, listOf(Need(m, 6)), situation = sit)
                add("n_tr_inv2nt", bid(2, NT), invite, listOf(Need(m, 5, 5)), situation = sit)
                add("n_tr_pass", Call.Pass, 0..invite.first - 1, situation = sit)
            }
        }
    }

    // ------------------------------------------------------------------ opener's rebid after 1 of a suit
    private fun rebids() {
        val newSuitResponse = { s: AuctionState ->
            s.myNaturalRebid()?.let { (_, r) -> r.level == 1 && r.denom < NT } == true
        }
        for (o in C..H) for (r in o + 1..S) {
            val sit = { s: AuctionState -> s.myNaturalRebid()?.let { it.first == o && it.second == bid(1, r) } == true }
            if (r == H || r == S) {
                add("reb_raise4", bid(4, r), maximum, listOf(Need(r, 4)), situation = sit)
                add("reb_raise3", bid(3, r), medium, listOf(Need(r, 4)), situation = sit)
                add("reb_raise2", bid(2, r), minimum, listOf(Need(r, 4)), situation = sit)
            }
            for (t in r + 1..S) {
                add("reb_new1", bid(1, t), oneSuit, listOf(Need(t, 4)), situation = sit,
                    extra = { h, _ -> (r + 1..S).firstOrNull { x -> h.len[x] >= 4 } == t })
            }
        }
        add("reb_2nt", bid(2, NT), rebid2nt, balanced = true, situation = newSuitResponse)
        add("reb_1nt", bid(1, NT), rebid1nt, balanced = true, situation = newSuitResponse)
        for (o in C..S) {
            val sit = { s: AuctionState ->
                s.myNaturalRebid()?.let { (own, r) -> own == o && r.level == 1 && r.denom < NT } == true
            }
            add("reb_rebid3", bid(3, o), 16..c.oneSuitMax, listOf(Need(o, 6)), situation = sit)
            add("reb_rebid2", bid(2, o), c.openMin..15, listOf(Need(o, 6)), situation = sit)
        }
        for (o in C..S) for (r in C..S) for (t in C until r) {
            if (t == o || r == o) continue
            add("reb_new2", bid(2, t), oneSuit, listOf(Need(t, 4)),
                situation = { s -> s.myNaturalRebid()?.let { it.first == o && it.second.level == 1 && it.second.denom == r } == true },
                extra = { h, _ -> chosenRebidSecondSuit(h, o, r) == t })
        }
        for (o in C..S) {
            val sit = { s: AuctionState ->
                s.myNaturalRebid()?.let { (own, r) -> own == o && r.level == 1 && r.denom < NT } == true
            }
            add("reb_rebid2", bid(2, o), oneSuit, listOf(Need(o, 5)), situation = sit)
        }
        add("reb_1nt", bid(1, NT), minimum, situation = newSuitResponse)

        // after partner's 1NT response
        val ntResponse = { s: AuctionState -> s.myNaturalRebid()?.second == bid(1, NT) }
        for (o in C..S) {
            val sit = { s: AuctionState -> s.myNaturalRebid()?.let { it.first == o && it.second == bid(1, NT) } == true }
            add("reb_rebid2", bid(2, o), oneSuit, listOf(Need(o, 6)), situation = sit)
            for (t in C until o) {
                add("reb_new2", bid(2, t), oneSuit, listOf(Need(t, 4)), situation = sit,
                    extra = { h, _ -> (C until o).filter { x -> h.len[x] >= 4 }.maxWithOrNull(compareBy({ h.len[it] }, { -it })) == t })
            }
        }
        add("reb_2nt", bid(2, NT), rebid2nt, balanced = true, situation = ntResponse)
        if (c.twoOverOne) {
            // 1NT over a major is forcing: the opener may not pass, so a minimum bids its longer minor
            val forcing = { s: AuctionState -> s.myNaturalRebid()?.let { it.first >= H && it.second == bid(1, NT) } == true }
            add("reb_nf_minor", bid(2, D), oneSuit, situation = forcing, extra = { h, _ -> h.len[D] > h.len[C] })
            add("reb_nf_minor", bid(2, C), oneSuit, situation = forcing)
        }
        add("reb_pass", Call.Pass, c.openMin..minOf(17, c.oneSuitMax), situation = ntResponse)
    }

    // ------------------------------------------------------------------ competing over an opponent's 1 of a suit
    private fun competition() {
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
    }
}
