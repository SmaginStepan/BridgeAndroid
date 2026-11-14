package com.an0obis.bridge

import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.Scoring
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.play.Bits
import com.an0obis.bridge.model.play.Claims
import com.an0obis.bridge.model.play.DoubleDummy
import com.an0obis.bridge.model.sayc.SaycBot
import com.an0obis.bridge.model.session.Baseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {

    private fun auctioned(seed: String, board: Int): Pair<BoardGame, List<Hand>> {
        val hands = Deck.deal(SeasonRandom.forPurpose(seed, "deal", board))
        val bots = List(4) { SaycBot(SeasonRandom.forPurpose(seed, "bots", board * 4 + it), 0) }
        val game = BoardGame(board, hands)
        while (game.phase == Phase.AUCTION) game.call(bots[game.controller].chooseCall(game.view()))
        return game to hands
    }

    /** The legal card that is best for [side] in the real layout (all hands known). */
    private fun bestCard(game: BoardGame, side: Int): Card {
        val sim = Claims.simOf(game.play!!)
        var m = sim.legal()
        var bestCard = -1
        var bestValue = if (side == 0) -1 else 99
        while (m != 0L) {
            val c = Bits.low(m)
            m = m and (m - 1)
            val n = sim.copy()
            n.play(c)
            val v = DoubleDummy.solve(n)
            if ((side == 0 && v > bestValue) || (side == 1 && v < bestValue)) { bestValue = v; bestCard = c }
        }
        return Bits.card(bestCard)
    }

    // ---------------------------------------------------------------- the field

    @Test
    fun theFieldIsTheSameEveryTimeAndNotAllTablesAgree() {
        var different = 0
        val started = System.nanoTime()
        for (board in 1..8) {
            val hands = Deck.deal(SeasonRandom.forPurpose("field-test", "deal", board))
            val a = Baseline.field("field-test", board, hands)
            val b = Baseline.field("field-test", board, hands)
            assertEquals(Baseline.TABLES, a.size)
            assertEquals("the same deal must give the same field", a, b)
            if (a.toSet().size > 1) different++
        }
        val ms = (System.nanoTime() - started) / 16 / 1_000_000
        println("FIELD per board ${ms}ms, boards with different results: $different of 8")
        assertTrue("the bot tables must not all score the same: $different", different >= 6)
    }

    @Test
    fun aGoodResultRanksHighAndABadOneRanksLow() {
        val field = listOf(-100, 90, 140, 420, 420, 620, -50, 90, 170, 420)
        assertEquals(100.0, Scoring.matchpointPercent(1000, field), 0.001)
        assertEquals(0.0, Scoring.matchpointPercent(-1000, field), 0.001)
        val mid = Scoring.matchpointPercent(140, field)
        assertTrue(mid in 30.0..60.0)
    }

    // ------------------------------------------------------------------ claims

    @Test
    fun giveRestEndsTheBoardWithTheRightTricks() {
        var checked = 0
        for (board in 1..30) {
            val (game, _) = auctioned("rest-test", board)
            if (game.phase != Phase.PLAY) continue
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose("rest-test", "play", board * 4 + it), 0) }
            while (game.phase == Phase.PLAY && game.play!!.tricks.size < 5) game.playCard(bots[game.controller].chooseCard(game.view()))
            if (game.phase != Phase.PLAY) continue
            val contract = game.play!!.contract
            val won = game.play!!.declarerTricks
            game.giveRest(contract.declarer % 2)
            assertEquals(won + (13 - game.play!!.tricks.size), game.result!!.declarerTricks)
            assertEquals(Phase.DONE, game.phase)
            checked++
        }
        assertTrue(checked > 15)
    }

    @Test
    fun aSureClaimIsReallySureWhateverIsPlayed() {
        var claims = 0
        var slowest = 0L
        var totalMs = 0L
        var checks = 0
        for (board in 1..120) {
            val (auction, hands) = auctioned("claim-test", board)
            val calls = auction.auction.calls
            if (auction.phase != Phase.PLAY) continue
            val game = BoardGame(board, hands)
            for (c in calls) game.call(c)
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose("claim-test", "play", board * 4 + it), 0) }
            val cards = mutableListOf<Card>()
            var sure: Int? = null
            var tricksAtClaim = 0
            while (game.phase == Phase.PLAY) {
                val t = System.nanoTime()
                val s = Claims.sureWinner(game.play!!)
                val took = (System.nanoTime() - t) / 1_000_000
                slowest = maxOf(slowest, took); totalMs += took; checks++
                if (s != null) { sure = s; tricksAtClaim = game.play!!.tricks.size; break }
                // play one more trick
                val before = game.play!!.tricks.size
                while (game.phase == Phase.PLAY && game.play!!.tricks.size == before) {
                    val card = bots[game.controller].chooseCard(game.view())
                    cards.add(card)
                    game.playCard(card)
                }
            }
            if (sure == null) continue
            claims++
            // replay up to the claim, then finish with the claiming side playing its best and the other side at random
            for (run in 0 until 6) {
                val g = BoardGame(board, hands)
                for (c in calls) g.call(c)
                for (card in cards) g.playCard(card)
                val rnd = SeasonRandom.forPurpose("claim-test", "random$run", board)
                while (g.phase == Phase.PLAY) {
                    val legal = g.view().legalCards
                    val mover = g.play!!.currentSeat and 1
                    g.playCard(if (mover == sure) bestCard(g, sure) else legal[rnd.nextInt(legal.size)])
                }
                val sideTricks = g.play!!.tricks.drop(tricksAtClaim).count { it.winner % 2 == sure }
                assertEquals("board $board: side $sure must win all ${13 - tricksAtClaim} remaining tricks", 13 - tricksAtClaim, sideTricks)
            }
        }
        println("CLAIMS found=$claims of 120 boards, slowest check ${slowest}ms, average ${totalMs.toDouble() / checks}ms over $checks checks")
        assertTrue("claims should happen", claims > 10)
        assertTrue("claim check too slow: $slowest ms", slowest < 1000)
    }
}
