package com.an0obis.bridge

import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.DefaultRandom
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.sayc.AuctionState
import com.an0obis.bridge.model.sayc.BiddingSystem
import com.an0obis.bridge.model.sayc.ClubStyle
import com.an0obis.bridge.model.sayc.HandInfo
import com.an0obis.bridge.model.sayc.NtResponses
import com.an0obis.bridge.model.sayc.SaycBot
import com.an0obis.bridge.model.sayc.Seating
import com.an0obis.bridge.model.sayc.SystemConfig
import com.an0obis.bridge.model.sayc.TwoDiamonds
import com.an0obis.bridge.model.sayc.TwoMajors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Every preset and any convention card a player can build must bid legally and sensibly. */
class SystemsTest {

    /** "AK5 KQ4 QJ52 Q103" = spades, hearts, diamonds, clubs; T or 10 is a ten; "-" is a void. */
    private fun hand(text: String): HandInfo {
        val cards = mutableListOf<Card>()
        val suits = listOf(Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS)
        text.trim().split(Regex("\\s+")).forEachIndexed { i, part ->
            if (part == "-") return@forEachIndexed
            for (ch in part.replace("10", "T")) {
                val rank = when (ch) {
                    'A' -> 14; 'K' -> 13; 'Q' -> 12; 'J' -> 11; 'T' -> 10
                    else -> ch.digitToInt()
                }
                cards.add(Card(rank, suits[i]))
            }
        }
        require(cards.size == 13) { "hand has ${cards.size} cards: $text" }
        return HandInfo(Hand(cards))
    }

    private fun bid(level: Int, denom: Int) = Call.Bid(level, denom)
    private val c = Suit.CLUBS
    private val d = Suit.DIAMONDS
    private val h = Suit.HEARTS
    private val s = Suit.SPADES
    private val nt = Suit.NO_TRUMP

    private fun call(config: SystemConfig, hand: String, vararg calls: Call): Call =
        BiddingSystem.of(config).choose(AuctionState(Seat.NORTH, calls.toList()), hand(hand))

    private class Stats(val boards: Int) {
        var passedOut = 0
        var partscore = 0
        var games = 0
        var slams = 0
        override fun toString() = "boards=$boards passedOut=$passedOut partscore=$partscore games=$games slams=$slams"
    }

    /** Bids [boards] deals with bots of [seating]; checks every call is legal and can be explained. */
    private fun simulate(seating: Seating, tag: String, boards: Int, print: Int = 0): Stats {
        val stats = Stats(boards)
        for (board in 1..boards) {
            val hands = Deck.deal(SeasonRandom.forPurpose("systems-$tag", "deal", board))
            val game = BoardGame(board, hands)
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose("systems-$tag", "bots", board * 4 + it), 0, seating = seating) }
            while (game.phase == Phase.AUCTION) {
                val call = bots[game.controller].chooseCall(game.view())
                assertTrue("$tag: illegal $call after ${game.auction.calls}", game.auction.isLegal(call))
                game.call(call)
                assertTrue("$tag: endless auction", game.auction.calls.size < 40)
            }
            val auction = game.auction
            for (i in auction.calls.indices) assertTrue(seating.explain(auction.dealer, auction.calls, i).isNotEmpty())
            if (board <= print) {
                val shapes = (0..3).joinToString(" ") { seat ->
                    val info = HandInfo(hands[seat])
                    "${"NESW"[seat]}:${info.hcp}/${info.len[3]}${info.len[2]}${info.len[1]}${info.len[0]}"
                }
                println("$tag board $board dealer ${"NESW"[game.dealer]} | $shapes | ${auction.calls.joinToString(" ")} => ${auction.contract}")
            }
            val contract = auction.contract
            when {
                contract == null -> stats.passedOut++
                contract.level >= 6 -> stats.slams++
                (contract.denom == nt && contract.level >= 3) || (contract.denom >= h && contract.denom < nt && contract.level >= 4) ||
                    contract.level >= 5 -> stats.games++
                else -> stats.partscore++
            }
        }
        println("SYSTEM SIM $tag $stats")
        return stats
    }

    private fun assertSensible(stats: Stats, tag: String) {
        val n = stats.boards
        assertTrue("$tag: too many pass-outs ${stats.passedOut}", stats.passedOut < n * 0.15)
        assertTrue("$tag: too many slams ${stats.slams}", stats.slams < n * 0.08)
        assertTrue("$tag: too few games ${stats.games}", stats.games > n * 0.10)
        assertTrue("$tag: too few part-scores ${stats.partscore}", stats.partscore > n * 0.25)
    }

    @Test
    fun everyPresetBidsLegallyAndSensibly() {
        for (preset in SystemConfig.PRESETS) {
            val seating = Seating.of(preset, preset)
            assertSensible(simulate(seating, preset.id, 1500, print = 12), preset.id)
        }
    }

    @Test
    fun differentSystemsAtOneTable() {
        val pairs = listOf(
            SystemConfig.PRECISION to SystemConfig.ACOL,
            SystemConfig.POLISH to SystemConfig.TWO_OVER_ONE,
            SystemConfig.SAYC to SystemConfig.PRECISION,
        )
        for ((ns, ew) in pairs) {
            val tag = "${ns.id}-vs-${ew.id}"
            assertSensible(simulate(Seating.of(ns, ew), tag, 800), tag)
        }
    }

    @Test
    fun everyHandHasAnOpeningCallInEverySystem() {
        for (preset in SystemConfig.PRESETS) {
            val system = BiddingSystem.of(preset)
            repeat(20000) {
                val cards = Deck.deal(SeasonRandom.forPurpose("openings-${preset.id}", "deal", it))[0]
                val info = HandInfo(cards)
                val rule = system.firstFitting(AuctionState(Seat.NORTH, emptyList()), info)
                assertNotNull("${preset.id}: no opening rule for ${info.hcp} points ${info.len.toList()}", rule)
            }
        }
    }

    @Test
    fun presetsAreAlreadyConsistent() {
        for (preset in SystemConfig.PRESETS) assertEquals(preset.id, preset, preset.normalized())
    }

    @Test
    fun anyConventionCardTheBuilderCanMakeBidsLegally() {
        val rnd = Random(11)
        repeat(30) { i ->
            val config = SystemConfig(
                id = "custom-test-$i",
                club = ClubStyle.entries[rnd.nextInt(ClubStyle.entries.size)],
                majorLength = if (rnd.nextBoolean()) 4 else 5,
                openMin = 10 + rnd.nextInt(4),
                ntMin = 12 + rnd.nextInt(5),
                ntMax = 14 + rnd.nextInt(5),
                twoNtMin = 19 + rnd.nextInt(4),
                twoNtMax = 20 + rnd.nextInt(4),
                twoDiamonds = TwoDiamonds.entries[rnd.nextInt(TwoDiamonds.entries.size)],
                twoMajors = TwoMajors.entries[rnd.nextInt(TwoMajors.entries.size)],
                stayman = rnd.nextBoolean(),
                transfers = rnd.nextBoolean(),
                twoOverOne = rnd.nextBoolean(),
                jacoby2nt = rnd.nextBoolean(),
                ntResponses = NtResponses.entries[rnd.nextInt(NtResponses.entries.size)],
            ).normalized()
            assertEquals("normalized twice", config, config.normalized())
            val system = BiddingSystem.of(config)
            repeat(2000) {
                val hand = HandInfo(Deck.deal(DefaultRandom)[0])
                assertNotNull("$config: no opening for ${hand.hcp} ${hand.len.toList()}",
                    system.firstFitting(AuctionState(Seat.NORTH, emptyList()), hand))
            }
            simulate(Seating.of(config, SystemConfig.SAYC), "random$i", 150)
        }
    }

    // ----------------------------------------------------------------- what each preset does

    @Test
    fun precisionOpensAStrongClub() {
        val p = SystemConfig.PRECISION
        assertEquals(bid(1, c), call(p, "AK5 KQ4 QJ52 Q103"))          // 17 balanced
        assertEquals(bid(1, nt), call(p, "K105 KQ4 QJ52 Q103"))        // 14 balanced
        assertEquals(bid(1, s), call(p, "AQ1093 K4 Q52 J103"))         // 12, five spades
        assertEquals(bid(2, c), call(p, "K5 Q4 A52 KJ10983"))          // 13, six clubs
        assertEquals(bid(2, d), call(p, "KJ53 AQ104 4 Q1053"))         // 4-4-1-4
        assertEquals(bid(1, nt), call(p, "K5 Q42 AJ1052 K103"))        // 13, 5-3-3-2 counts as balanced
        assertEquals(bid(1, d), call(p, "K5 Q4 AJ1052 K1032"))         // 13, the catch-all diamond
        assertEquals(bid(1, d), call(p, "J852 J63 K74 J85", bid(1, c), Call.Pass)) // 6 points: negative
        assertEquals(bid(1, h), call(p, "J8 AQ863 K74 J85", bid(1, c), Call.Pass)) // 10 points, five hearts
    }

    @Test
    fun acolOpensAWeakNoTrumpAndFourCardMajors() {
        val a = SystemConfig.ACOL
        assertEquals(bid(1, nt), call(a, "K105 KQ4 QJ52 Q103"))        // 14 balanced
        assertEquals(bid(1, h), call(a, "AK5 KQ74 Q52 Q103"))          // 16 balanced, four hearts
        assertEquals(bid(1, s), call(a, "AK54 K74 Q52 K103"))          // 15, four spades
        assertEquals(bid(2, nt), call(a, "AK5 AQ4 KQ52 KJ3"))          // 22 balanced
        // the 1NT rebid shows 15-16
        assertEquals(bid(1, nt), call(a, "A105 KQ74 QJ2 K103", bid(1, h), Call.Pass, bid(1, s), Call.Pass))
    }

    @Test
    fun polishClubShowsAWeakBalancedHand() {
        val p = SystemConfig.POLISH
        assertEquals(bid(1, c), call(p, "K105 KQ4 QJ52 Q103"))         // 14 balanced
        assertEquals(bid(2, nt), call(p, "AK5 AQ4 KQ52 Q103"))         // 20 balanced
        assertEquals(bid(1, c), call(p, "AKQ93 AQ43 KQ5 2"))           // 20 unbalanced: the strong club
        assertEquals(bid(1, nt), call(p, "AK5 KQ4 QJ52 Q103"))         // 17 balanced
        assertEquals(bid(1, d), call(p, "K5 Q4 AJ1052 KJ103"))         // 14, diamonds
        assertEquals(bid(2, d), call(p, "Q5 KQ10843 52 J103"))         // Multi: weak two in hearts
    }

    @Test
    fun twoOverOneForcesToGame() {
        val t = SystemConfig.TWO_OVER_ONE
        assertEquals(bid(2, c), call(t, "Q5 K4 A52 KJ10983", bid(1, s), Call.Pass))        // 13, clubs: 2/1
        assertEquals(bid(2, nt), call(t, "KJ54 A4 K52 Q1083", bid(1, s), Call.Pass))       // 13, four spades: Jacoby
        assertEquals(bid(1, nt), call(t, "Q5 K4 Q52 KJ10983", bid(1, s), Call.Pass))       // 11: forcing 1NT
        // the opener may not pass the forcing 1NT
        val rebid = call(t, "AQ1093 K4 Q52 J103", bid(1, s), Call.Pass, bid(1, nt), Call.Pass)
        assertTrue("opener passed a forcing 1NT", rebid != Call.Pass)
    }
}
