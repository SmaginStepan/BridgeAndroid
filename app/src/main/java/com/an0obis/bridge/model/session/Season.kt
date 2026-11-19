package com.an0obis.bridge.model.session

import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.BridgeStorage
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.sayc.Seating
import com.an0obis.bridge.model.sayc.SystemCatalog
import com.an0obis.bridge.model.sayc.SystemConfig
import com.an0obis.bridge.net.ScoreClient
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/** One row of the season board as last fetched. */
@Serializable
data class SeasonRow(val playerId: String, val name: String, val score10: Long)

/** A finished board of the current 16-board session (for the session summary). */
@Serializable
data class SeasonBoard(
    /** The deal number in the season's sequence. */
    val deal: Int,
    /** The contract (level 0: passed out). [denom], [multiplier] and [declarer] as in the engine. */
    val level: Int = 0,
    val denom: Int = 0,
    val multiplier: Int = 0,
    val declarer: Int = 0,
    val declarerTricks: Int = 0,
    val northSouthScore: Int = 0,
    val percent: Double = 50.0
)

/** The player's run through the current season, kept on the device. */
@Serializable
class SeasonState {
    var seasonId: String = ""
    var seed: String = ""
    var startsAt: Long = 0
    var endsAt: Long = 0

    /** Finished boards; the next board to play is deal number [boardsPlayed] + 1. */
    var boardsPlayed: Int = 0

    /** Sum over the finished boards of (matchpoint percentage − 50), in tenths. May be negative. */
    var total10: Long = 0

    /** [boardsPlayed] when the server last accepted the total. */
    var submittedBoards: Int = 0

    /** The finished boards of the session in progress. */
    var session: List<SeasonBoard> = emptyList()

    /** The board being played ([currentDeal]) and the moves made in it, so it continues where it was left. */
    var currentDeal: Int = 0
    var currentMoves: List<String> = emptyList()

    /** The season board as last fetched, for offline viewing. */
    var board: List<SeasonRow> = emptyList()
}

/**
 * A season of Bridge: one continuous walk through a deal sequence shared by every player, played
 * in sessions of 16 boards, each board once. Everything is derived from the season seed the server
 * hands out: the deals, the bots, the opponents' bidding system and the bot field each board is
 * scored against. The player keeps their own system. A board scores its matchpoint percentage
 * minus 50; the season total is the sum (a running-total board on the server).
 */
object Seasons {

    const val SESSION_SIZE = 16
    private const val FILE = "season.json"
    private val lock = Any()

    fun load(): SeasonState? = synchronized(lock) {
        try {
            BridgeStorage.readText(FILE)?.let { BridgeStorage.json.decodeFromString(SeasonState.serializer(), it) }
        } catch (e: Exception) {
            null
        }
    }

    fun save(state: SeasonState) = synchronized(lock) {
        BridgeStorage.writeText(FILE, BridgeStorage.json.encodeToString(SeasonState.serializer(), state))
    }

    /**
     * Changes the saved run of season [seasonId] as it is on disk now. The season screen and the game
     * both write the file; the progress (boards, total, moves) is written only by the game, so the
     * screen's own updates (the board, what was posted, the dates) go through here and never put an
     * older copy of the progress back.
     */
    private fun update(seasonId: String, change: (SeasonState) -> Unit) = synchronized(lock) {
        val current = load() ?: return
        if (current.seasonId != seasonId) return
        change(current)
        save(current)
    }

    /** A running-total board: the total may fall and go below zero (server rule for "total:" ids). */
    fun boardName(state: SeasonState) = "total:${state.seasonId}"

    /** Whole days left (rounded up); 0 once the season is over. */
    fun daysLeft(state: SeasonState, nowS: Long = System.currentTimeMillis() / 1000): Int =
        if (nowS >= state.endsAt) 0 else (((state.endsAt - nowS) + 86399) / 86400).toInt()

    fun isOver(state: SeasonState, nowS: Long = System.currentTimeMillis() / 1000) = nowS >= state.endsAt

    /** The player's own system against this season's opponents. */
    fun seating(state: SeasonState): Seating = SystemCatalog.seasonSeating(state.seed)

    fun opponents(state: SeasonState): SystemConfig = SystemCatalog.seasonOpponents(state.seed)

    /** The 1-based number of the session the next board belongs to. */
    fun sessionNumber(state: SeasonState): Int = state.boardsPlayed / SESSION_SIZE + 1

    /** The deal of the season sequence with number [deal]. */
    fun deal(state: SeasonState, deal: Int): List<Hand> = Deck.deal(SeasonRandom.forPurpose(state.seed, "deal", deal))

    /** Points over average of one board, in tenths. */
    fun points10(percent: Double): Long = ((percent - 50.0) * 10).roundToLong()

    /**
     * Asks the server which season is running. A new season id starts a fresh run; the same id
     * keeps the player's progress. Returns the saved state, or null when there is none and the
     * server could not be reached. Blocking.
     */
    fun refresh(): SeasonState? {
        val info = ScoreClient.fetchSeason() ?: return load()
        return refreshWith(info.seasonId, info.seed, info.startsAt, info.endsAt)
    }

    /** [refresh] without the network (also used by the tests). */
    fun refreshWith(seasonId: String, seed: String, startsAt: Long, endsAt: Long): SeasonState =
        synchronized(lock) {
            val old = load()
            if (old != null && old.seasonId == seasonId) {
                old.startsAt = startsAt
                old.endsAt = endsAt
                save(old)
                return old
            }
            val fresh = SeasonState().also {
                it.seasonId = seasonId
                it.seed = seed
                it.startsAt = startsAt
                it.endsAt = endsAt
            }
            save(fresh)
            fresh
        }

    /** Keeps the moves of the board in progress, after every call and card. */
    fun saveMoves(state: SeasonState, deal: Int, moves: List<String>) = synchronized(lock) {
        if (deal != state.boardsPlayed + 1) return
        state.currentDeal = deal
        state.currentMoves = moves
        save(state)
    }

    /** The saved moves of [deal], if that board was left unfinished. */
    fun movesOf(state: SeasonState, deal: Int): List<String> =
        if (state.currentDeal == deal) state.currentMoves else emptyList()

    /** A board is finished. Counted once, even if reported twice. */
    fun boardFinished(state: SeasonState, board: SeasonBoard) = synchronized(lock) {
        if (board.deal != state.boardsPlayed + 1) return
        // the first board of a session starts its summary afresh
        val firstOfSession = (board.deal - 1) % SESSION_SIZE == 0
        state.session = (if (firstOfSession) emptyList() else state.session) + board
        state.total10 += points10(board.percent)
        state.boardsPlayed = board.deal
        state.currentDeal = 0
        state.currentMoves = emptyList()
        save(state)
    }

    /** A board has finished since the server last heard from us. */
    fun needsSubmit(state: SeasonState) = state.boardsPlayed > state.submittedBoards

    /**
     * Posts the current total, if the player agreed to appear on the leaderboards. Blocking, safe to
     * call any time; a rejected or failed attempt is simply tried again later.
     */
    fun submitPending(state: SeasonState) {
        val settings = AppSettings()
        if (!needsSubmit(state) || settings.leaderboardChoice != AppSettings.POST) return
        val played = state.boardsPlayed
        val outcome = ScoreClient.submit(
            settings.playerId, settings.playerName, state.total10,
            System.currentTimeMillis() / 1000, board = boardName(state)
        )
        if (outcome != ScoreClient.SubmitOutcome.RETRY) {
            state.submittedBoards = maxOf(state.submittedBoards, played)
            update(state.seasonId) { it.submittedBoards = maxOf(it.submittedBoards, played) }
        }
    }

    /** Refreshes the cached season board. Blocking. */
    fun refreshBoard(state: SeasonState) {
        val top = ScoreClient.fetchTop(10, boardName(state)) ?: return
        val rows = top.map { SeasonRow(it.player_id, it.name, it.score) }
        state.board = rows
        update(state.seasonId) { it.board = rows }
    }

    // ------------------------------------------------------------------ moves as text

    /** A call or a card as text: "P", "X", "XX", "B14" (1♠), "C14.3" (♠A), and "Q" for a concession. */
    fun encode(move: Any): String = when (move) {
        is Call.Pass -> "P"
        is Call.Dbl -> "X"
        is Call.Redbl -> "XX"
        is Call.Bid -> "B${move.level}${move.denom}"
        is Card -> "C${move.rank}.${move.suit}"
        else -> CONCEDE
    }

    /** The call, card or [CONCEDE] a text stands for. */
    fun decode(text: String): Any = when {
        text == "P" -> Call.Pass
        text == "X" -> Call.Dbl
        text == "XX" -> Call.Redbl
        text.startsWith("B") -> Call.Bid(text[1].digitToInt(), text[2].digitToInt())
        text.startsWith("C") -> text.substring(1).split('.').let { Card(it[0].toInt(), it[1].toInt()) }
        else -> CONCEDE
    }

    const val CONCEDE = "Q"

    /** Replays saved [moves] on a fresh [game]; [human] is the seat whose concession "Q" means. */
    fun replay(game: BoardGame, moves: List<String>, human: Int) {
        for (m in moves) {
            when (val move = decode(m)) {
                is Call -> game.call(move)
                is Card -> game.playCard(move)
                else -> game.giveRest(1 - (human and 1))
            }
        }
    }
}
