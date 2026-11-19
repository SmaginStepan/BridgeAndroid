package com.an0obis.bridge

import com.an0obis.bridge.model.Auction
import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.BotView
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Contract
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.Multiplier
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.Vulnerability
import com.an0obis.bridge.model.advice.AdviceKeys
import com.an0obis.bridge.model.advice.BidAdvisor
import com.an0obis.bridge.model.advice.PlayAdvisor
import com.an0obis.bridge.model.sayc.AuctionState
import com.an0obis.bridge.model.sayc.HandInfo
import com.an0obis.bridge.model.sayc.BiddingSystem
import com.an0obis.bridge.model.sayc.SaycBot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdviceTest {

    /** "AK5 KQ4 QJ52 Q103" = spades, hearts, diamonds, clubs. */
    private fun hand(text: String): Hand {
        val cards = mutableListOf<Card>()
        val suits = listOf(Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS)
        text.trim().split(Regex("\\s+")).forEachIndexed { i, part ->
            if (part == "-") return@forEachIndexed
            for (ch in part.replace("10", "T")) {
                val rank = when (ch) { 'A' -> 14; 'K' -> 13; 'Q' -> 12; 'J' -> 11; 'T' -> 10; else -> ch.digitToInt() }
                cards.add(Card(rank, suits[i]))
            }
        }
        return Hand(cards)
    }

    // ------------------------------------------------------------------ bidding

    @Test
    fun anOpeningBidComesFromTheRuleTableAndShowsWhatItMeans() {
        val advice = BidAdvisor.advise(Seat.NORTH, emptyList(), hand("AK5 KQ4 QJ52 Q103"))
        assertEquals(Call.Bid(1, Suit.NO_TRUMP), advice.call)
        assertNull(advice.card)
        assertEquals("open_1nt", advice.meaning?.ruleId)
        val first = advice.reasons.first()
        assertEquals("hand", first.key)
        assertEquals("17", first.args[0])                      // 17 points
        assertEquals("3♠ 3♥ 4♦ 3♣", first.args[1]) // the shape, longest suit rank first
    }

    @Test
    fun aWeakHandPassesAndTheTableSaysWhat() {
        val advice = BidAdvisor.advise(Seat.NORTH, emptyList(), hand("Q852 J63 K74 J85"))
        assertEquals(Call.Pass, advice.call)
        assertEquals("open_pass", advice.meaning?.ruleId)
        assertEquals("7", advice.reasons.first().args[0]) // 7 points
    }

    @Test
    fun whereTheTableIsSilentTheReasonComesFromPointsAndFit() {
        // look through real auctions for the positions the rule table does not cover
        var found = 0
        for (board in 1..80) {
            val seed = "advice-fallback"
            val game = BoardGame(board, Deck.deal(SeasonRandom.forPurpose(seed, "deal", board)))
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose(seed, "bots", board * 4 + it), worlds = 0) }
            while (game.phase == Phase.AUCTION) {
                val view = game.view()
                val advice = BidAdvisor.advise(view.auction.dealer, view.auction.calls, view.actingHand)
                if (advice.meaning == null) {
                    found++
                    val last = advice.reasons.last()
                    assertTrue(last.key.startsWith("fb_"))
                    assertTrue(last.key in AdviceKeys.bidding)
                }
                game.call(bots[game.controller].chooseCall(view))
            }
        }
        assertTrue("the fallback never decided in 80 boards", found > 0)
    }

    @Test
    fun bidAdviceIsAlwaysTheSystemsOwnLegalCall() {
        var steps = 0
        val keysSeen = mutableSetOf<String>()
        for (board in 1..60) {
            val seed = "advice-bid"
            val game = BoardGame(board, Deck.deal(SeasonRandom.forPurpose(seed, "deal", board)))
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose(seed, "bots", board * 4 + it), worlds = 0) }
            while (game.phase == Phase.AUCTION) {
                val view = game.view()
                val advice = BidAdvisor.advise(view.auction.dealer, view.auction.calls, view.actingHand)
                val system = BiddingSystem.SAYC.choose(AuctionState(view.auction.dealer, view.auction.calls), HandInfo(view.actingHand))
                assertEquals("board $board, calls ${view.auction.calls}", system, advice.call)
                assertTrue(advice.call in view.legalCalls)
                assertTrue(advice.reasons.isNotEmpty())
                for (r in advice.reasons) { assertTrue("unknown key ${r.key}", r.key in AdviceKeys.bidding); keysSeen += r.key }
                steps++
                game.call(bots[game.controller].chooseCall(view))
            }
        }
        assertTrue("only $steps auction steps checked", steps > 200)
        assertTrue("reasons seen: $keysSeen", keysSeen.size >= 5)
    }

    // ------------------------------------------------------------------ play

    private fun view(
        viewer: Int, acting: Int, hands: Map<Int, Hand>, contract: Contract,
        trick: List<Card> = emptyList(), leader: Int = acting
    ) = BotView(
        viewer = viewer, actingSeat = acting, hands = hands, auction = Auction(Seat.NORTH),
        vulnerability = Vulnerability.NONE, contract = contract, currentTrick = trick, trickLeader = leader,
        tricks = emptyList(), legalCalls = emptyList(), legalCards = hands.getValue(acting).cards
    )

    @Test
    fun theOpeningLeadFromASequenceIsExplained() {
        val contract = Contract(3, Suit.NO_TRUMP, Multiplier.NONE, Seat.SOUTH) // West leads
        val west = hand("KQJ63 842 975 J6")
        val v = view(Seat.WEST, Seat.WEST, mapOf(Seat.WEST to west), contract)
        val advice = PlayAdvisor.explain(v, Card(13, Suit.SPADES))
        val keys = advice.reasons.map { it.key }
        assertEquals(listOf("plan_defend", "lead_opening", "lead_sequence"), keys)
        assertEquals("3NT", advice.reasons[0].args[0])
        assertEquals("5", advice.reasons[0].args[1]) // the defenders need 5 tricks to beat 3NT
        assertEquals(Card(13, Suit.SPADES), advice.card)
    }

    @Test
    fun partnerIsWinningSoPlayLow() {
        // South declares 4 hearts; West led the ace of spades, the dummy (North) played a low spade
        val contract = Contract(4, Suit.HEARTS, Multiplier.NONE, Seat.SOUTH)
        val east = hand("72 Q94 KJ86 8742")
        val dummy = hand("J53 AKJ75 Q3 AQ5")
        val v = view(
            Seat.EAST, Seat.EAST, mapOf(Seat.EAST to east, Seat.NORTH to dummy), contract,
            trick = listOf(Card(14, Suit.SPADES), Card(3, Suit.SPADES)), leader = Seat.WEST
        )
        val advice = PlayAdvisor.explain(v, Card(2, Suit.SPADES))
        val last = advice.reasons.last()
        assertEquals("follow_partner_wins", last.key)
        assertEquals(listOf("A♠"), last.args)
    }

    @Test
    fun trumpingAnOpponentsWinnerIsExplained() {
        // 4 spades by South; West led a club, North (dummy) took it high, East has no clubs and trumps
        val contract = Contract(4, Suit.SPADES, Multiplier.NONE, Seat.SOUTH)
        val east = hand("QJ4 K86 AJ95 -")
        val dummy = hand("A53 A74 K32 AQ5")
        val v = view(
            Seat.EAST, Seat.EAST, mapOf(Seat.EAST to east, Seat.NORTH to dummy), contract,
            trick = listOf(Card(10, Suit.CLUBS), Card(14, Suit.CLUBS)), leader = Seat.WEST
        )
        val advice = PlayAdvisor.explain(v, Card(4, Suit.SPADES))
        assertEquals("ruff", advice.reasons.last().key)
    }

    @Test
    fun playAdviceIsALegalCardWithReasonsInEveryPosition() {
        val keysSeen = mutableSetOf<String>()
        var positions = 0
        for (board in 1..4) {
            val seed = "advice-play"
            val game = BoardGame(board, Deck.deal(SeasonRandom.forPurpose(seed, "deal", board)))
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose(seed, "bots", board * 4 + it), worlds = 4) }
            while (game.phase == Phase.AUCTION) game.call(bots[game.controller].chooseCall(game.view()))
            while (game.phase == Phase.PLAY) {
                val view = game.view()
                if (view.legalCards.size > 1) {
                    val advice = PlayAdvisor.advise(view, SeasonRandom.forPurpose(seed, "advice", board * 100 + positions), worlds = 4)
                    assertNotNull(advice.card)
                    assertTrue("illegal advised card ${advice.card}", advice.card in view.legalCards)
                    assertTrue(advice.reasons.size >= 2)
                    for (r in advice.reasons) { assertTrue("unknown key ${r.key}", r.key in AdviceKeys.play); keysSeen += r.key }
                    positions++
                }
                game.playCard(bots[game.controller].chooseCard(view))
            }
        }
        assertTrue("only $positions positions", positions > 40)
        assertTrue("reasons seen: $keysSeen", keysSeen.size >= 8)
    }

    // ------------------------------------------------------------------ texts

    @Test
    fun everyReasonHasATextInEveryLanguage() {
        val base = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
        for (dir in listOf("values", "values-es", "values-ru")) {
            val xml = File(base, "$dir/strings.xml").readText()
            for (key in AdviceKeys.all) {
                assertTrue("$dir has no adv_$key", xml.contains("name=\"adv_$key\""))
            }
        }
    }
}
