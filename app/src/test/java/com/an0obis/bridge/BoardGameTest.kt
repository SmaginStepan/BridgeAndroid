package com.an0obis.bridge

import com.an0obis.bridge.model.BasicBot
import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.BoardResult
import com.an0obis.bridge.model.Bot
import com.an0obis.bridge.model.BotView
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.Scoring
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Seat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardGameTest {

    private fun newGame(seed: String, board: Int) =
        BoardGame(board, Deck.deal(SeasonRandom.forPurpose(seed, "deal", board)))

    private fun botsFor(seed: String, board: Int): (Int) -> Bot? {
        val bots = List(4) { seat -> BasicBot(SeasonRandom.forPurpose(seed, "bots", board * 4 + seat)) }
        return { seat -> bots[seat] }
    }

    private fun playBoard(seed: String, board: Int): BoardResult {
        val game = newGame(seed, board)
        assertTrue(game.runBots(botsFor(seed, board)))
        return game.result!!
    }

    @Test
    fun botsPlayHundredsOfBoardsToTheEnd() {
        var contracts = 0
        var made = 0
        for (board in 1..300) {
            val game = newGame("season-1", board)
            assertEquals(Phase.AUCTION, game.phase)
            assertTrue(game.runBots(botsFor("season-1", board)))
            assertEquals(Phase.DONE, game.phase)
            val r = game.result!!
            if (r.contract == null) {
                assertEquals(0, r.northSouthScore)
                assertTrue(game.auction.isPassedOut)
            } else {
                contracts++
                assertEquals(13, game.play!!.tricks.size)
                assertTrue(r.declarerTricks in 0..13)
                if (r.declarerTricks >= r.contract.tricksNeeded) made++
                // the stored score agrees with the scoring function
                val expected = Scoring.northSouthScore(r.contract, game.vulnerability, r.declarerTricks)
                assertEquals(expected, r.northSouthScore)
            }
        }
        // a sanity check that the placeholder bot really bids sometimes, and not always successfully
        assertTrue("contracts=$contracts", contracts in 100..299)
        assertTrue("made=$made", made in 1 until contracts)
    }

    @Test
    fun sameSeedGivesTheSameBoardResult() {
        for (board in 1..30) {
            assertEquals(playBoard("s", board), playBoard("s", board))
        }
        // different seeds usually differ
        assertTrue((1..30).any { playBoard("a", it) != playBoard("b", it) })
    }

    @Test
    fun theViewHidesHandsTheViewerCannotSee() {
        // find a board that has a contract and walk through it, checking every view
        var checked = false
        for (board in 1..50) {
            val game = newGame("views", board)
            val bots = botsFor("views", board)
            while (game.phase != Phase.DONE) {
                val v = game.view()
                assertEquals(game.controller, v.viewer)
                assertEquals(game.actingSeat, v.actingSeat)
                if (game.phase == Phase.AUCTION) {
                    assertEquals(setOf(v.viewer), v.hands.keys)
                    assertEquals(13, v.actingHand.cards.size)
                    assertTrue(v.legalCards.isEmpty())
                    assertTrue(v.legalCalls.contains(Call.Pass))
                } else {
                    val contract = v.contract!!
                    val p = game.play!!
                    val seen = v.hands.keys
                    if (p.dummyVisible) {
                        assertEquals(setOf(v.viewer, contract.dummy), seen)
                        checked = true
                    } else {
                        assertEquals(setOf(v.viewer), seen)
                    }
                    // the declarer chooses for the dummy, nobody else ever does
                    if (v.actingSeat == contract.dummy) assertEquals(contract.declarer, v.viewer)
                    else assertEquals(v.actingSeat, v.viewer)
                    assertTrue(v.legalCards.isNotEmpty())
                    // the acting hand is visible to the viewer and the legal cards come from it
                    assertTrue(v.actingHand.cards.containsAll(v.legalCards))
                }
                val bot = bots(game.controller)!!
                if (game.phase == Phase.AUCTION) game.call(bot.chooseCall(v)) else game.playCard(bot.chooseCard(v))
            }
        }
        assertTrue(checked)
    }

    @Test
    fun runBotsStopsWhenAHumanHasToAct() {
        val game = newGame("human", 1)
        val bots = botsFor("human", 1)
        val humanSeat = Seat.SOUTH
        val finished = game.runBots { seat -> if (seat == humanSeat) null else bots(seat) }
        assertFalse(finished)
        assertEquals(humanSeat, game.controller)
        assertEquals(Phase.AUCTION, game.phase)
        assertNull(game.result)
        // the human passes; bots continue, and stop again at the human's next turn or the end
        game.call(Call.Pass)
        game.runBots { seat -> if (seat == humanSeat) null else bots(seat) }
        assertTrue(game.phase == Phase.DONE || game.controller == humanSeat)
    }

    @Test(expected = IllegalArgumentException::class)
    fun illegalCallIsRejected() {
        val game = newGame("x", 1)
        game.call(Call.Dbl) // nothing to double
    }

    @Test(expected = IllegalStateException::class)
    fun cannotPlayACardDuringTheAuction() {
        val game = newGame("x", 1)
        game.playCard(Card(2, 0))
    }

    @Test
    fun humanCanBeDeclarerAndPlayTheDummy() {
        // Force a contract: West is declarer in 1NT; East therefore leads... check controller for the dummy.
        val game = newGame("force", 2) // dealer East
        val calls = listOf(Call.Pass, Call.Pass, Call.Bid(1, 4), Call.Pass, Call.Pass, Call.Pass)
        // East pass, South pass, West 1NT, North, East, South pass
        calls.forEach { game.call(it) }
        assertEquals(Phase.PLAY, game.phase)
        val contract = game.play!!.contract
        assertEquals(Seat.WEST, contract.declarer)
        assertEquals(Seat.NORTH, game.actingSeat) // left of West leads
        assertEquals(Seat.NORTH, game.controller)
        val lead = game.view().legalCards.first()
        game.playCard(lead)
        assertEquals(Seat.EAST, game.actingSeat)
        assertEquals(Seat.EAST, contract.dummy)
        assertEquals(Seat.WEST, game.controller) // declarer plays the dummy's card
        val v = game.view()
        assertNotNull(v.hands[Seat.EAST])
        assertEquals(setOf(Seat.WEST, Seat.EAST), v.hands.keys)
    }
}
