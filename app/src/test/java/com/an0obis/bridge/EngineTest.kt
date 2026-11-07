package com.an0obis.bridge

import com.an0obis.bridge.model.Auction
import com.an0obis.bridge.model.Boards
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Contract
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.Multiplier
import com.an0obis.bridge.model.Play
import com.an0obis.bridge.model.Scoring
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.Vulnerability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    private fun bid(level: Int, denom: Int) = Call.Bid(level, denom)

    private fun auction(dealer: Int, vararg calls: Call): Auction =
        calls.fold(Auction(dealer)) { a, c -> a.add(c) }

    // ---------------------------------------------------------------- auction

    @Test
    fun bidsMustBeHigherThanTheLastBid() {
        val a = auction(Seat.NORTH, bid(1, Suit.HEARTS))
        assertFalse(a.isLegal(bid(1, Suit.HEARTS)))
        assertFalse(a.isLegal(bid(1, Suit.CLUBS)))
        assertTrue(a.isLegal(bid(1, Suit.SPADES)))
        assertTrue(a.isLegal(bid(1, Suit.NO_TRUMP)))
        assertTrue(a.isLegal(bid(2, Suit.CLUBS)))
    }

    @Test
    fun doubleAndRedoubleLegality() {
        val start = Auction(Seat.NORTH)
        assertFalse(start.isLegal(Call.Dbl)) // nothing to double
        val a = start.add(bid(1, Suit.HEARTS)) // N bids; E to call
        assertTrue(a.isLegal(Call.Dbl)) // E may double an opponent's bid
        assertFalse(a.isLegal(Call.Redbl))
        val b = a.add(Call.Pass) // S to call: cannot double his partner
        assertFalse(b.isLegal(Call.Dbl))
        val c = a.add(Call.Dbl) // S: may redouble
        assertTrue(c.isLegal(Call.Redbl))
        assertFalse(c.isLegal(Call.Dbl))
        val d = c.add(Call.Pass) // W: nothing to redouble for the defenders
        assertFalse(d.isLegal(Call.Redbl))
        assertFalse(d.isLegal(Call.Dbl))
        // a new bid clears the double
        val e = auction(Seat.NORTH, bid(1, Suit.HEARTS), Call.Dbl, bid(2, Suit.HEARTS))
        assertFalse(e.isLegal(Call.Redbl))
        assertTrue(e.isLegal(Call.Dbl))
    }

    @Test
    fun passedOutBoardNeedsFourPasses() {
        var a = Auction(Seat.WEST)
        repeat(3) { a = a.add(Call.Pass) }
        assertFalse(a.isFinished)
        a = a.add(Call.Pass)
        assertTrue(a.isFinished)
        assertTrue(a.isPassedOut)
        assertNull(a.contract)
        assertTrue(a.legalCalls().isEmpty())
    }

    @Test
    fun threePassesAfterABidEndTheAuction() {
        val a = auction(Seat.NORTH, bid(1, Suit.HEARTS), Call.Pass, bid(2, Suit.HEARTS), Call.Pass, Call.Pass)
        assertFalse(a.isFinished)
        val done = a.add(Call.Pass)
        assertTrue(done.isFinished)
        val c = done.contract!!
        assertEquals(2, c.level)
        assertEquals(Suit.HEARTS, c.denom)
        assertEquals(Seat.NORTH, c.declarer) // North named hearts first
        assertEquals(Seat.EAST, c.openingLeader)
        assertEquals(Seat.SOUTH, c.dummy)
        assertEquals(Multiplier.NONE, c.multiplier)
    }

    @Test
    fun declarerIsTheFirstOfTheSideToNameTheSuit() {
        // N 1C, E pass, S 1H, W pass, N 2H, all pass: hearts first named by South
        val a = auction(
            Seat.NORTH, bid(1, Suit.CLUBS), Call.Pass, bid(1, Suit.HEARTS), Call.Pass,
            bid(2, Suit.HEARTS), Call.Pass, Call.Pass, Call.Pass
        )
        assertEquals(Seat.SOUTH, a.contract!!.declarer)
        // an opponent naming the suit earlier does not make a player declarer:
        // North bids spades first, but the contract 3S is West's (the first of East-West to name spades)
        val b = auction(
            Seat.NORTH, bid(1, Suit.SPADES), bid(2, Suit.DIAMONDS), Call.Pass, bid(3, Suit.SPADES),
            Call.Pass, Call.Pass, Call.Pass
        )
        assertEquals(Seat.WEST, b.contract!!.declarer)
    }

    @Test
    fun contractRecordsDoubleAndRedouble() {
        val x = auction(Seat.SOUTH, bid(4, Suit.SPADES), Call.Dbl, Call.Pass, Call.Pass, Call.Pass)
        assertEquals(Multiplier.DOUBLED, x.contract!!.multiplier)
        val xx = auction(Seat.SOUTH, bid(4, Suit.SPADES), Call.Dbl, Call.Redbl, Call.Pass, Call.Pass, Call.Pass)
        assertEquals(Multiplier.REDOUBLED, xx.contract!!.multiplier)
        assertEquals(Seat.SOUTH, xx.contract!!.declarer)
    }

    @Test
    fun legalCallsStartAtPassAndHaveThirtyFiveBidsAtMost() {
        val calls = Auction(Seat.NORTH).legalCalls()
        assertEquals(Call.Pass, calls.first())
        assertEquals(36, calls.size) // pass + 35 bids
        val after7NT = Auction(Seat.NORTH).add(bid(7, Suit.NO_TRUMP)).legalCalls()
        assertEquals(listOf(Call.Pass, Call.Dbl), after7NT)
    }

    // ------------------------------------------------------------------- play

    @Test
    fun followSuitAndTrumpWinsTheTrick() {
        // South declares 4S, so West leads and North is dummy.
        val contract = Contract(4, Suit.SPADES, Multiplier.NONE, Seat.SOUTH)
        val hands = listOf(
            Hand(listOf(Card(13, Suit.CLUBS), Card(5, Suit.HEARTS))), // N (dummy)
            Hand(listOf(Card(14, Suit.CLUBS), Card(6, Suit.HEARTS))), // E
            Hand(listOf(Card(2, Suit.SPADES), Card(7, Suit.HEARTS))), // S (declarer)
            Hand(listOf(Card(2, Suit.CLUBS), Card(8, Suit.HEARTS)))  // W
        )
        val play = Play(contract, hands)
        assertEquals(Seat.WEST, play.currentSeat)
        assertFalse(play.dummyVisible)
        play.playCard(Card(2, Suit.CLUBS))
        assertTrue(play.dummyVisible)
        // North is the dummy: South chooses the card, and North must follow clubs
        assertEquals(Seat.NORTH, play.currentSeat)
        assertEquals(Seat.SOUTH, play.controller)
        assertEquals(listOf(Card(13, Suit.CLUBS)), play.legalCards())
        play.playCard(Card(13, Suit.CLUBS))
        play.playCard(Card(14, Suit.CLUBS)) // East
        // South has no clubs, so any card is legal; the spade 2 trumps
        assertEquals(2, play.legalCards().size)
        val trick = play.playCard(Card(2, Suit.SPADES))!!
        assertEquals(Seat.SOUTH, trick.winner)
        assertEquals(Seat.SOUTH, play.currentSeat) // the winner leads next
        assertEquals(1, play.tricksWon(Seat.SOUTH))
        assertEquals(1, play.tricksWon(Seat.NORTH)) // partners share tricks
        assertEquals(0, play.tricksWon(Seat.EAST))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotPlayACardThatIsNotInTheHandOrBreaksFollowSuit() {
        val contract = Contract(1, Suit.NO_TRUMP, Multiplier.NONE, Seat.NORTH)
        val hands = List(4) { Hand(listOf(Card(2 + it, Suit.CLUBS), Card(2 + it, Suit.HEARTS))) }
        val play = Play(contract, hands) // East leads
        play.playCard(Card(3, Suit.CLUBS))
        play.playCard(Card(2, Suit.HEARTS)) // South holds a club and must follow
    }

    @Test
    fun aFullDealCanBePlayedToTheEndWithLegalCards() {
        for (index in 0 until 20) {
            val hands = Deck.deal(SeasonRandom.forPurpose("abcd", "deal", index))
            val contract = Contract(3, Suit.NO_TRUMP, Multiplier.NONE, index % 4)
            val play = Play(contract, hands)
            var played = 0
            while (!play.isFinished) {
                play.playCard(play.legalCards().first())
                played++
            }
            assertEquals(52, played)
            assertEquals(13, play.tricks.size)
            assertEquals(13, play.tricksWon(Seat.NORTH) + play.tricksWon(Seat.EAST))
        }
    }

    // ---------------------------------------------------------------- scoring

    private fun score(level: Int, denom: Int, m: Multiplier, vul: Boolean, tricks: Int) =
        Scoring.declarerScore(Contract(level, denom, m, Seat.SOUTH), vul, tricks)

    @Test
    fun madeContractsScoreTrickPointsAndBonuses() {
        val n = Multiplier.NONE
        assertEquals(90, score(1, Suit.NO_TRUMP, n, false, 7))   // 40 + 50 part-score
        assertEquals(110, score(2, Suit.HEARTS, n, false, 8))    // 60 + 50
        assertEquals(420, score(4, Suit.SPADES, n, false, 10))   // 120 + 300 game
        assertEquals(620, score(4, Suit.SPADES, n, true, 10))    // 120 + 500 vulnerable game
        assertEquals(400, score(3, Suit.NO_TRUMP, n, false, 9))  // 100 + 300
        assertEquals(600, score(3, Suit.NO_TRUMP, n, true, 9))
        assertEquals(450, score(4, Suit.HEARTS, n, false, 11))   // + overtrick 30
        assertEquals(170, score(2, Suit.SPADES, n, false, 10))   // 60 + 50 + 2 overtricks of 30
    }

    @Test
    fun slamBonuses() {
        val n = Multiplier.NONE
        assertEquals(1430, score(6, Suit.SPADES, n, true, 12))      // 180 + 500 + 750
        assertEquals(990, score(6, Suit.NO_TRUMP, n, false, 12))    // 190 + 300 + 500
        assertEquals(1520, score(7, Suit.NO_TRUMP, n, false, 13))   // 220 + 300 + 1000
        assertEquals(2210, score(7, Suit.HEARTS, n, true, 13))      // 210 + 500 + 1500
    }

    @Test
    fun doubledAndRedoubledContractsMade() {
        // 2H doubled: 60*2 = 120 >= 100 so it is a game: 120 + 300 + 50 insult
        assertEquals(470, score(2, Suit.HEARTS, Multiplier.DOUBLED, false, 8))
        // 4S doubled non-vul: 240 + 300 + 50
        assertEquals(590, score(4, Suit.SPADES, Multiplier.DOUBLED, false, 10))
        // 3NT doubled vul +1: 200 + 500 + 50 + 200
        assertEquals(950, score(3, Suit.NO_TRUMP, Multiplier.DOUBLED, true, 10))
        // 1C redoubled: 80 + 50 part-score + 100 insult
        assertEquals(230, score(1, Suit.CLUBS, Multiplier.REDOUBLED, false, 7))
    }

    @Test
    fun undertricks() {
        val n = Multiplier.NONE
        assertEquals(-50, score(3, Suit.NO_TRUMP, n, false, 8))
        assertEquals(-100, score(3, Suit.NO_TRUMP, n, true, 8))
        assertEquals(-100, score(3, Suit.NO_TRUMP, Multiplier.DOUBLED, false, 8))
        assertEquals(-500, score(3, Suit.NO_TRUMP, Multiplier.DOUBLED, false, 6)) // 100+200+200
        assertEquals(-800, score(3, Suit.NO_TRUMP, Multiplier.DOUBLED, true, 6))  // 200+300+300
        assertEquals(-800, score(4, Suit.SPADES, Multiplier.DOUBLED, false, 6))   // 100+200+200+300
        assertEquals(-200, score(3, Suit.NO_TRUMP, Multiplier.REDOUBLED, false, 8))
    }

    @Test
    fun northSouthScoreFlipsSignForEastWestDeclarer() {
        val ew = Contract(3, Suit.NO_TRUMP, Multiplier.NONE, Seat.EAST)
        assertEquals(-400, Scoring.northSouthScore(ew, Vulnerability.NONE, 9))
        assertEquals(-600, Scoring.northSouthScore(ew, Vulnerability.EW, 9))
        assertEquals(-400, Scoring.northSouthScore(ew, Vulnerability.NS, 9)) // NS vulnerable only
        assertEquals(50, Scoring.northSouthScore(ew, Vulnerability.NONE, 8))
        assertEquals(0, Scoring.northSouthScore(null, Vulnerability.NONE, 0))
    }

    @Test
    fun boardCycle() {
        assertEquals(Seat.NORTH, Boards.dealer(1))
        assertEquals(Seat.WEST, Boards.dealer(4))
        assertEquals(Seat.NORTH, Boards.dealer(5))
        assertEquals(Vulnerability.NONE, Boards.vulnerability(1))
        assertEquals(Vulnerability.NS, Boards.vulnerability(2))
        assertEquals(Vulnerability.BOTH, Boards.vulnerability(4))
        assertEquals(Vulnerability.EW, Boards.vulnerability(16))
        assertEquals(Vulnerability.NONE, Boards.vulnerability(17)) // the cycle repeats
        // every vulnerability appears four times in a cycle
        val counts = (1..16).groupingBy { Boards.vulnerability(it) }.eachCount()
        assertTrue(counts.values.all { it == 4 })
    }

    @Test
    fun matchpoints() {
        assertEquals(100.0, Scoring.matchpointPercent(420, listOf(400, 170, -50)), 0.001)
        assertEquals(0.0, Scoring.matchpointPercent(-50, listOf(400, 170)), 0.001)
        assertEquals(50.0, Scoring.matchpointPercent(420, listOf(420, 420)), 0.001)
        assertEquals(75.0, Scoring.matchpointPercent(100, listOf(50, 100)), 0.001) // 2 + 1 of 4
        assertEquals(50.0, Scoring.matchpointPercent(420, emptyList()), 0.001)
    }
}
