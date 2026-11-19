package com.an0obis.bridge.model.session

import com.an0obis.bridge.model.BoardGame
import com.an0obis.bridge.model.Hand
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.sayc.SaycBot
import com.an0obis.bridge.model.sayc.Seating

/**
 * The "field" for matchpoints in solo play: the same deal played by a number of bot tables, so a
 * human result can be ranked against them like in a duplicate session.
 *
 * The bots bid by the same systems as the player's table ([seating]), so to get different contracts every bot gets a random boldness
 * (a shift of its point count, -2..+2, most often 0), and the card play is sampled, so it varies too.
 * Everything is derived from [seed] and the board number: the same deal gives the same field.
 */
object Baseline {

    const val TABLES = 20

    /** North-South scores of [tables] bot tables playing [hands] as board [board]. */
    fun field(
        seed: String,
        board: Int,
        hands: List<Hand>,
        seating: Seating = Seating.SAYC,
        tables: Int = TABLES,
        worlds: Int = 8
    ): List<Int> {
        val scores = ArrayList<Int>(tables)
        for (t in 0 until tables) {
            val style = SeasonRandom.forPurpose(seed, "field-style", board * 1000 + t)
            val bots = List(4) { seat ->
                val shift = style.nextInt(3) + style.nextInt(3) - 2
                SaycBot(SeasonRandom.forPurpose(seed, "field-bot", (board * 1000 + t) * 4 + seat), worlds, true, shift, seating)
            }
            val game = BoardGame(board, hands)
            game.runBots { bots[it] }
            scores.add(game.result!!.northSouthScore)
        }
        return scores
    }
}
