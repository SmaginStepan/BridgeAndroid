package com.an0obis.bridge

import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.Multiplier
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.sayc.AuctionState
import com.an0obis.bridge.model.sayc.HandInfo
import com.an0obis.bridge.model.sayc.BiddingSystem
import com.an0obis.bridge.model.sayc.SaycBot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaycTest {

    /** "AK5 KQ4 QJ52 Q103" = spades, hearts, diamonds, clubs; T or 10 is a ten; "-" is a void. */
    private fun hand(text: String): HandInfo {
        val cards = mutableListOf<Card>()
        val suits = listOf(Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS)
        text.trim().split(Regex("\\s+")).forEachIndexed { i, part ->
            if (part == "-") return@forEachIndexed
            var rest = part.replace("10", "T")
            for (ch in rest) {
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

    /** The call made by the player who acts after [calls] (dealer North unless given). */
    private fun call(hand: String, vararg calls: Call, dealer: Int = Seat.NORTH): Call =
        BiddingSystem.SAYC.choose(AuctionState(dealer, calls.toList()), hand(hand))

    // ----------------------------------------------------------------- openings

    @Test
    fun openingBids() {
        assertEquals(bid(1, Suit.NO_TRUMP), call("AK5 KQ4 QJ52 Q103"))          // 17 balanced
        assertEquals(bid(1, Suit.HEARTS), call("K32 AQJ75 Q64 85"))             // 12, five hearts
        assertEquals(bid(1, Suit.DIAMONDS), call("KT54 AQ62 KJ3 84"))           // 13, 4-4-3-2: longer minor
        assertEquals(bid(1, Suit.CLUBS), call("KQ52 A63 Q74 J85"))             // 12, 4-3-3-3, equal minors: 1C
    }

    @Test
    fun weakHandsPassAndStrongHandsOpenHigh() {
        assertEquals(Call.Pass, call("Q852 J63 K74 J85"))                        // 7 points
        assertEquals(bid(2, Suit.CLUBS), call("AKQ5 AKJ4 AK3 Q4"))               // 26 points: strong 2C
        assertEquals(bid(2, Suit.NO_TRUMP), call("AKJ5 KQ4 AQ52 J3"))            // 20 balanced
    }

    @Test
    fun weakTwoAndPreempt() {
        assertEquals(bid(2, Suit.SPADES), call("KQT852 3 J743 82"))              // 6 points, six good spades
        assertEquals(bid(3, Suit.HEARTS), call("3 KQJ9852 743 82"))              // seven hearts
        assertEquals(Call.Pass, call("Q98765 3 J743 82"))                        // poor suit: no weak two
    }

    @Test
    fun fiveCardMajorsBeatMinors() {
        assertEquals(bid(1, Suit.SPADES), call("AQJ75 K4 Q643 85"))
        // 5-5 in the majors: the higher suit
        assertEquals(bid(1, Suit.SPADES), call("AQJ75 KJ542 Q6 8"))
        // longer hearts than spades: hearts first
        assertEquals(bid(1, Suit.HEARTS), call("AKJ75 KJ5432 6 8"))
    }

    // ---------------------------------------------------------------- responses

    private val oneHeartPassed = arrayOf<Call>(bid(1, Suit.HEARTS), Call.Pass)

    @Test
    fun raisesOfPartnersMajor() {
        assertEquals(bid(2, Suit.HEARTS), call("Q74 J86 K93 T852", *oneHeartPassed))      // 6, three hearts
        assertEquals(bid(3, Suit.HEARTS), call("Q74 A86 K93 J852", *oneHeartPassed))      // 10: limit raise
        assertEquals(bid(4, Suit.HEARTS), call("K74 AQ86 K93 J85", *oneHeartPassed))     // 13: game
    }

    @Test
    fun newSuitsAndNoTrumpResponses() {
        val oneClub = arrayOf<Call>(bid(1, Suit.CLUBS), Call.Pass)
        assertEquals(bid(1, Suit.SPADES), call("KJ63 84 Q72 K1085", *oneClub))            // 4 spades, 9 points
        assertEquals(bid(2, Suit.CLUBS), call("KJ6 84 Q72 KT853", *oneClub))              // 9, five clubs: raise
        assertEquals(bid(1, Suit.NO_TRUMP), call("KJ6 Q84 Q72 J853", *oneClub))            // 9, no fit, no suit
        assertEquals(Call.Pass, call("Q74 J86 93 T8532", *oneClub))                         // 3 points
    }

    @Test
    fun responsesToOneNoTrump() {
        val oneNt = arrayOf<Call>(bid(1, Suit.NO_TRUMP), Call.Pass)
        assertEquals(bid(2, Suit.HEARTS), call("KJ763 54 Q82 J73", *oneNt))               // Jacoby transfer to spades
        assertEquals(bid(2, Suit.CLUBS), call("KJ63 Q854 A82 J7", *oneNt))               // Stayman
        assertEquals(bid(3, Suit.NO_TRUMP), call("K63 Q85 A82 KJ73", *oneNt))             // 12 balanced
        assertEquals(Call.Pass, call("Q63 Q85 982 J873", *oneNt))                         // 5 points
    }

    @Test
    fun openerAnswersStaymanAndTransfers() {
        val stayman = arrayOf<Call>(bid(1, Suit.NO_TRUMP), Call.Pass, bid(2, Suit.CLUBS), Call.Pass)
        assertEquals(bid(2, Suit.SPADES), call("AQ93 K4 KJ85 Q73", *stayman))
        assertEquals(bid(2, Suit.HEARTS), call("A93 KJ84 KJ5 Q73", *stayman))
        assertEquals(bid(2, Suit.DIAMONDS), call("AK3 K42 KJ85 Q73", *stayman))
        val transfer = arrayOf<Call>(bid(1, Suit.NO_TRUMP), Call.Pass, bid(2, Suit.DIAMONDS), Call.Pass)
        assertEquals(bid(2, Suit.HEARTS), call("AQ3 K42 KJ85 Q73", *transfer))
    }

    @Test
    fun openersRebids() {
        // 1H - 1S: with four spades raise; with a minimum and nothing else 1NT
        val start = arrayOf<Call>(bid(1, Suit.HEARTS), Call.Pass, bid(1, Suit.SPADES), Call.Pass)
        assertEquals(bid(2, Suit.SPADES), call("KJ93 AQ752 Q4 85", *start))
        assertEquals(bid(1, Suit.NO_TRUMP), call("K93 AQ752 Q4 J85", *start))
        assertEquals(bid(2, Suit.HEARTS), call("93 AKQ975 Q4 K85", *start))                // 14, six hearts: rebid
        assertEquals(bid(3, Suit.HEARTS), call("93 AKQJ75 AQ4 82", *start))               // 16, six hearts: jump
    }

    // ---------------------------------------------------------- competition

    @Test
    fun overcallsAndTakeoutDoubles() {
        val oneClub = arrayOf<Call>(bid(1, Suit.CLUBS))
        assertEquals(bid(1, Suit.SPADES), call("KQJ75 Q64 83 J74", *oneClub, dealer = Seat.EAST))
        assertEquals(Call.Dbl, call("KJ52 AQ63 Q74 85", *oneClub, dealer = Seat.EAST))
        assertEquals(Call.Pass, call("Q852 J63 K74 J85", *oneClub, dealer = Seat.EAST))
    }

    // ------------------------------------------------------------ simulation

    @Test
    fun printSampleAuctions() {
        for (board in 1..25) {
            val hands = Deck.deal(SeasonRandom.forPurpose("sayc-sample", "deal", board))
            val game = BoardGame(board, hands)
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose("sayc-sample", "bots", board * 4 + it)) }
            while (game.phase == com.an0obis.bridge.model.Phase.AUCTION) {
                game.call(bots[game.controller].chooseCall(game.view()))
            }
            val seats = "NESW"
            val shapes = (0..3).joinToString(" ") { seat ->
                val h = HandInfo(hands[seat])
                "${seats[seat]}:${h.hcp}/${h.len[3]}${h.len[2]}${h.len[1]}${h.len[0]}"
            }
            println("SAMPLE board $board dealer ${seats[game.dealer]} | $shapes | ${game.auction.calls.joinToString(" ")} => ${game.auction.contract}")
        }
    }

    @Test
    fun thousandsOfBoardsBidLegallyAndSensibly() {
        var passedOut = 0
        var contracts = 0
        var partscore = 0
        var games = 0
        var slams = 0
        var doubled = 0
        var made = 0
        var withExplain = 0
        val boards = 2000
        for (board in 1..boards) {
            val hands = Deck.deal(SeasonRandom.forPurpose("sayc-sim", "deal", board))
            val game = BoardGame(board, hands)
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose("sayc-sim", "bots", board * 4 + it)) }
            // only the auction: stop when the play starts
            while (game.phase == com.an0obis.bridge.model.Phase.AUCTION) {
                val view = game.view()
                val call = bots[game.controller].chooseCall(view)
                assertTrue("illegal $call after ${game.auction.calls}", game.auction.isLegal(call))
                game.call(call)
            }
            val auction = game.auction
            for (i in auction.calls.indices) {
                val meanings = BiddingSystem.SAYC.explain(auction.dealer, auction.calls, i)
                assertTrue(meanings.isNotEmpty())
                withExplain++
            }
            val contract = auction.contract
            if (contract == null) passedOut++ else {
                contracts++
                val needed = contract.tricksNeeded
                if (contract.multiplier != Multiplier.NONE) doubled++
                when {
                    contract.level >= 6 -> slams++
                    (contract.denom == Suit.NO_TRUMP && contract.level >= 3) ||
                        ((contract.denom == Suit.HEARTS || contract.denom == Suit.SPADES) && contract.level >= 4) ||
                        contract.level >= 5 -> games++
                    else -> partscore++
                }
                assertTrue(needed in 7..13)
            }
            assertTrue(auction.calls.size < 40)
        }
        println("SAYC SIM boards=$boards passedOut=$passedOut partscore=$partscore games=$games slams=$slams doubled=$doubled")
        assertTrue("too many pass-outs: $passedOut", passedOut < boards * 0.15)
        assertTrue("too few contracts", contracts > boards * 0.85)
        assertTrue("too many slams: $slams", slams < boards * 0.08)
        assertTrue("no games", games > boards * 0.10)
    }
}
