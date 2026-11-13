package com.an0obis.bridge

import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.Bot
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.play.Bits
import com.an0obis.bridge.model.play.CardPlayer
import com.an0obis.bridge.model.play.DoubleDummy
import com.an0obis.bridge.model.play.Sim
import com.an0obis.bridge.model.sayc.BidInference
import com.an0obis.bridge.model.sayc.SaycBot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPlayTest {

    private fun mask(vararg cards: Card): Long = cards.fold(0L) { m, c -> m or Bits.bit(Bits.idx(c)) }

    private fun spade(rank: Int) = Card(rank, Suit.SPADES)
    private fun heart(rank: Int) = Card(rank, Suit.HEARTS)

    // ------------------------------------------------------------------ solver

    @Test
    fun solverCountsSureTricks() {
        // no trump, North leads: the ace and king of spades win both tricks
        val s = Sim(Suit.NO_TRUMP, 0)
        s.hands[0] = mask(spade(14), spade(13))
        s.hands[1] = mask(spade(12), spade(11))
        s.hands[2] = mask(spade(3), spade(2))
        s.hands[3] = mask(spade(5), spade(4))
        s.leader = 0
        assertEquals(2, DoubleDummy.solve(s))
    }

    @Test
    fun solverGivesTheOpponentsTheirWinners() {
        // West leads two hearts that nobody can beat
        val s = Sim(Suit.NO_TRUMP, 0)
        s.hands[3] = mask(heart(14), heart(13))
        s.hands[0] = mask(heart(12), heart(11))
        s.hands[1] = mask(heart(3), heart(2))
        s.hands[2] = mask(heart(5), heart(4))
        s.leader = 3
        assertEquals(0, DoubleDummy.solve(s))
    }

    @Test
    fun solverFindsTheRuff() {
        // spades trump; East leads the ace of hearts; South ruffs it and then wins the high spade
        val s = Sim(Suit.SPADES, 0)
        s.hands[1] = mask(heart(14), Card(2, Suit.CLUBS))
        s.hands[2] = mask(spade(2), spade(14))
        s.hands[3] = mask(heart(3), Card(3, Suit.CLUBS))
        s.hands[0] = mask(heart(4), Card(4, Suit.CLUBS))
        s.leader = 1
        // East's ace is ruffed by South (trick 1); South's ace of spades wins trick 2
        assertEquals(2, DoubleDummy.solve(s))
    }

    // ------------------------------------------------------------ the bidding as information

    /** "AK5 KQ4 QJ52 Q103" = spades, hearts, diamonds, clubs. */
    private fun hand(text: String): Hand {
        val suits = listOf(Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS)
        val cards = mutableListOf<Card>()
        text.trim().split(Regex("\\s+")).forEachIndexed { i, part ->
            for (ch in part.replace("10", "T")) {
                val rank = when (ch) { 'A' -> 14; 'K' -> 13; 'Q' -> 12; 'J' -> 11; 'T' -> 10; else -> ch.digitToInt() }
                cards.add(Card(rank, suits[i]))
            }
        }
        require(cards.size == 13) { "hand has ${cards.size} cards: $text" }
        return Hand(cards)
    }

    @Test
    fun anOpeningNoTrumpShowsAStrongBalancedHand() {
        val inference = BidInference(Seat.NORTH, listOf(Call.Bid(1, Suit.NO_TRUMP)))
        assertTrue(inference.plausible(Seat.NORTH, hand("AK5 KQ4 QJ52 Q103")))      // 17 balanced
        assertFalse(inference.plausible(Seat.NORTH, hand("Q852 J63 K74 J85")))      // 7 points
        assertFalse(inference.plausible(Seat.NORTH, hand("AKQJ75 K4 Q643 8")))      // unbalanced
        assertTrue(inference.plausible(Seat.EAST, hand("Q852 J63 K74 J85")))        // no call by East: no information
    }

    @Test
    fun aPassShowsAWeakHand() {
        val inference = BidInference(Seat.NORTH, listOf(Call.Pass))
        assertTrue(inference.plausible(Seat.NORTH, hand("Q852 J63 K74 J85")))
        assertFalse(inference.plausible(Seat.NORTH, hand("AK5 KQ4 QJ52 Q103")))
    }

    @Test
    fun theTrueHandsAlwaysFitTheirOwnBidding() {
        // the check replays the system, so every hand must fit the calls it really made
        for (board in 1..400) {
            val hands = Deck.deal(SeasonRandom.forPurpose("inference", "deal", board))
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose("inference", "bots", board * 4 + it), 0) }
            val game = BoardGame(board, hands)
            while (game.phase == Phase.AUCTION) game.call(bots[game.controller].chooseCall(game.view()))
            val inference = BidInference(game.dealer, game.auction.calls)
            for (seat in 0..3) {
                assertTrue("board $board seat $seat ${game.auction.calls}", inference.plausible(seat, hands[seat]))
            }
        }
    }

    // ------------------------------------------------------------ full boards

    private fun playOut(hands: List<Hand>, board: Int, calls: List<Call>, bots: (Int) -> Bot): BoardGame {
        val game = BoardGame(board, hands)
        for (c in calls) game.call(c)
        while (game.phase == Phase.PLAY) {
            val view = game.view()
            val card = bots(game.controller).chooseCard(view)
            assertTrue("illegal $card", card in view.legalCards)
            game.playCard(card)
        }
        return game
    }

    @Test
    fun samplingBotsPlayWholeBoardsLegally() {
        var played = 0
        for (board in 1..40) {
            val hands = Deck.deal(SeasonRandom.forPurpose("play-legal", "deal", board))
            val bidder = List(4) { SaycBot(SeasonRandom.forPurpose("play-legal", "bots", board * 4 + it), 0) }
            val auction = BoardGame(board, hands)
            while (auction.phase == Phase.AUCTION) auction.call(bidder[auction.controller].chooseCall(auction.view()))
            if (auction.auction.contract == null) continue
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose("play-legal", "play", board * 4 + it), 6) }
            val game = playOut(hands, board, auction.auction.calls) { bots[it] }
            assertEquals(Phase.DONE, game.phase)
            played++
        }
        assertTrue(played > 30)
    }

    @Test
    fun aDecisionTakesWellUnderASecond() {
        var worst = 0L
        var sum = 0L
        var n = 0
        for (board in 1..25) {
            val hands = Deck.deal(SeasonRandom.forPurpose("play-time", "deal", board))
            val bidder = List(4) { SaycBot(SeasonRandom.forPurpose("play-time", "bots", board * 4 + it), 0) }
            val auction = BoardGame(board, hands)
            while (auction.phase == Phase.AUCTION) auction.call(bidder[auction.controller].chooseCall(auction.view()))
            if (auction.auction.contract == null) continue
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose("play-time", "play", board * 4 + it)) } // default strength
            val game = BoardGame(board, hands)
            for (c in auction.auction.calls) game.call(c)
            while (game.phase == Phase.PLAY) {
                val view = game.view()
                val t = System.nanoTime()
                val card = bots[game.controller].chooseCard(view)
                val ms = (System.nanoTime() - t) / 1_000_000
                if (view.legalCards.size > 1) { worst = maxOf(worst, ms); sum += ms; n++ }
                game.playCard(card)
            }
        }
        println("PLAY TIME decisions=$n avg=${sum / maxOf(n, 1)}ms worst=${worst}ms")
        assertTrue("too slow: $worst ms", worst < 1500)
    }

    @Test
    fun readingTheBiddingHelps() {
        var readTricks = 0
        var blindTricks = 0
        var edge = 0L
        var boards = 0
        val started = System.nanoTime()
        for (board in 1..120) {
            val hands = Deck.deal(SeasonRandom.forPurpose("play-bidding", "deal", board))
            val bidder = List(4) { SaycBot(SeasonRandom.forPurpose("play-bidding", "bots", board * 4 + it), 0) }
            val auction = BoardGame(board, hands)
            while (auction.phase == Phase.AUCTION) auction.call(bidder[auction.controller].chooseCall(auction.view()))
            val contract = auction.auction.contract ?: continue
            val calls = auction.auction.calls

            fun bots(readSide: Int) = List(4) { seat ->
                SaycBot(SeasonRandom.forPurpose("play-bidding", "play$readSide", board * 4 + seat), 40, (seat and 1) == readSide)
            }
            val a = bots(0).let { b -> playOut(hands, board, calls) { b[it] } }   // north-south read the bidding
            val b = bots(1).let { b -> playOut(hands, board, calls) { b[it] } }   // east-west read the bidding
            fun ns(g: BoardGame) = g.result!!.declarerTricks.let { if ((contract.declarer and 1) == 0) it else 13 - it }
            readTricks += ns(a) + (13 - ns(b))
            blindTricks += ns(b) + (13 - ns(a))
            edge += a.result!!.northSouthScore - b.result!!.northSouthScore
            boards++
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        println("BIDDING READ boards=$boards readTricks=$readTricks blindTricks=$blindTricks avgScoreEdge=${edge / boards} time=${ms}ms")
        println("BIDDING WORLDS matched=${CardPlayer.Stats.matchedWorlds} unmatched=${CardPlayer.Stats.unmatchedWorlds}")
        assertTrue("reading the bidding must not hurt: $readTricks vs $blindTricks", readTricks >= blindTricks - boards / 4)
    }

    @Test
    fun samplingPlayBeatsRandomPlay() {
        var total = 0L
        var boards = 0
        var smartTricks = 0
        var randomTricks = 0
        val started = System.nanoTime()
        for (board in 1..60) {
            val hands = Deck.deal(SeasonRandom.forPurpose("play-strength", "deal", board))
            val bidder = List(4) { SaycBot(SeasonRandom.forPurpose("play-strength", "bots", board * 4 + it), 0) }
            val auction = BoardGame(board, hands)
            while (auction.phase == Phase.AUCTION) auction.call(bidder[auction.controller].chooseCall(auction.view()))
            val contract = auction.auction.contract ?: continue
            val calls = auction.auction.calls

            fun bots(smartSide: Int) = List(4) { seat ->
                val worlds = if ((seat and 1) == smartSide) 12 else 0
                SaycBot(SeasonRandom.forPurpose("play-strength", "play$smartSide", board * 4 + seat), worlds)
            }
            // the same board twice: north-south smart, then east-west smart
            val a = bots(0).let { b -> playOut(hands, board, calls) { b[it] } }
            val b = bots(1).let { b -> playOut(hands, board, calls) { b[it] } }
            total += a.result!!.northSouthScore - b.result!!.northSouthScore
            // tricks taken by the smart side
            val nsA = a.result!!.declarerTricks.let { if ((contract.declarer and 1) == 0) it else 13 - it }
            val nsB = b.result!!.declarerTricks.let { if ((contract.declarer and 1) == 0) it else 13 - it }
            smartTricks += nsA + (13 - nsB)
            randomTricks += nsB + (13 - nsA)
            boards++
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        println("PLAY STRENGTH boards=$boards smartTricks=$smartTricks randomTricks=$randomTricks avgScoreEdge=${total / boards} time=${ms}ms")
        assertTrue("smart side must take more tricks: $smartTricks vs $randomTricks", smartTricks > randomTricks + boards)
        assertTrue("smart side must score better", total > 0)
    }
}
