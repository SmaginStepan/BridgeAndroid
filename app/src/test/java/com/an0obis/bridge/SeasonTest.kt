package com.an0obis.bridge

import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.BridgeStorage
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.sayc.SaycBot
import com.an0obis.bridge.model.session.SeasonBoard
import com.an0obis.bridge.model.session.Seasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class SeasonTest {

    @Before
    fun setUp() {
        BridgeStorage.init(Files.createTempDirectory("bridge-season-test").toFile())
    }

    private fun board(deal: Int, percent: Double) = SeasonBoard(deal, 4, Suit.SPADES, 0, Seat.SOUTH, 10, 420, percent)

    @Test
    fun aNewSeasonStartsAFreshRunAndTheSameSeasonKeepsIt() {
        val first = Seasons.refreshWith("2026-11", "ab12", 100, 200)
        Seasons.boardFinished(first, board(1, 62.5))
        val again = Seasons.refreshWith("2026-11", "ab12", 100, 300)
        assertEquals(1, again.boardsPlayed)
        assertEquals(300, again.endsAt)
        val next = Seasons.refreshWith("2026-12", "cd34", 300, 400)
        assertEquals(0, next.boardsPlayed)
        assertEquals("cd34", next.seed)
    }

    @Test
    fun eachBoardCountsOnceAsPointsOverAverage() {
        val run = Seasons.refreshWith("s", "ab12", 0, Long.MAX_VALUE)
        Seasons.boardFinished(run, board(1, 62.5))
        Seasons.boardFinished(run, board(1, 62.5)) // reported twice: counted once
        Seasons.boardFinished(run, board(3, 90.0)) // not the next board: ignored
        Seasons.boardFinished(run, board(2, 40.0))
        assertEquals(2, run.boardsPlayed)
        assertEquals(125L - 100L, run.total10)
        assertEquals(25L, Seasons.load()!!.total10)
        assertEquals("+2.5", com.an0obis.bridge.ui.season.pointsText(25))
        assertEquals("−10", com.an0obis.bridge.ui.season.pointsText(-100))
        assertTrue(Seasons.needsSubmit(run))
    }

    @Test
    fun aSessionHoldsSixteenBoardsAndTheNextStartsAfresh() {
        val run = Seasons.refreshWith("s", "ab12", 0, Long.MAX_VALUE)
        for (deal in 1..16) Seasons.boardFinished(run, board(deal, 50.0))
        assertEquals(16, run.session.size)
        assertEquals(2, Seasons.sessionNumber(run))
        Seasons.boardFinished(run, board(17, 50.0))
        assertEquals(listOf(17), run.session.map { it.deal })
    }

    @Test
    fun aBoardLeftUnfinishedContinuesWithItsMoves() {
        val run = Seasons.refreshWith("s", "ab12", 0, Long.MAX_VALUE)
        Seasons.saveMoves(run, 1, listOf("B14", "P"))
        Seasons.saveMoves(run, 5, listOf("P")) // not the board in play: ignored
        val loaded = Seasons.load()!!
        assertEquals(listOf("B14", "P"), Seasons.movesOf(loaded, 1))
        Seasons.boardFinished(loaded, board(1, 50.0))
        assertTrue(Seasons.movesOf(loaded, 1).isEmpty())
    }

    @Test
    fun movesAsTextGoBothWays() {
        val moves = listOf<Any>(Call.Pass, Call.Dbl, Call.Redbl) +
            (1..7).flatMap { level -> (Suit.CLUBS..Suit.NO_TRUMP).map { Call.Bid(level, it) } } +
            (Suit.CLUBS..Suit.SPADES).flatMap { suit -> (2..14).map { Card(it, suit) } }
        for (m in moves) assertEquals(m, Seasons.decode(Seasons.encode(m)))
        assertEquals(Seasons.CONCEDE, Seasons.decode(Seasons.encode(Seasons.CONCEDE)))
    }

    @Test
    fun replayingTheSavedMovesRebuildsTheBoard() {
        for (deal in 1..20) {
            val hands = Deck.deal(SeasonRandom.forPurpose("replay", "deal", deal))
            val game = BoardGame(deal, hands)
            val bots = List(4) { SaycBot(SeasonRandom.forPurpose("replay", "bots", deal * 4 + it), 0) }
            val moves = mutableListOf<String>()
            var steps = 0
            while (game.phase != Phase.DONE && steps < 30) {
                val view = game.view()
                if (game.phase == Phase.AUCTION) bots[game.controller].chooseCall(view).also { game.call(it); moves += Seasons.encode(it) }
                else bots[game.controller].chooseCard(view).also { game.playCard(it); moves += Seasons.encode(it) }
                steps++
            }
            val again = BoardGame(deal, hands)
            Seasons.replay(again, moves, Seat.SOUTH)
            assertEquals(game.phase, again.phase)
            assertEquals(game.auction.calls, again.auction.calls)
            assertEquals(game.play?.tricks?.size, again.play?.tricks?.size)
        }
    }

    @Test
    fun theSeasonDealsAreTheSameForEveryone() {
        val run = Seasons.refreshWith("s", "5eed", 0, Long.MAX_VALUE)
        for (deal in 1..5) {
            assertEquals(
                Deck.deal(SeasonRandom.forPurpose("5eed", "deal", deal)).map { it.cards },
                Seasons.deal(run, deal).map { it.cards }
            )
        }
    }
}
