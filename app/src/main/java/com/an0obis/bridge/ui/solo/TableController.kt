package com.an0obis.bridge.ui.solo

import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.sayc.Meaning
import com.an0obis.bridge.model.sayc.SystemConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * What the table screen ([SoloScreen]) talks to: solo and season play ([SoloViewModel]), and the
 * online game, where the host runs the board ([com.an0obis.bridge.ui.online.OnlineHostViewModel])
 * and every guest shows the state the host sends ([com.an0obis.bridge.ui.online.OnlineGuestViewModel]).
 * A controller that does not offer something (advice, undo, a new session) simply ignores the call
 * and never sets the matching flag in [state].
 */
interface TableController {
    val state: StateFlow<TableState>
    val advice: StateFlow<AdviceUi>

    /** The system of the player and partner (the bidding guide explains it). */
    val ourSystem: SystemConfig

    /** Interstitial ads may run between boards (solo and season play, never online). */
    val showsAds: Boolean get() = true

    /** The meanings of the call at [index] of this board's auction, in the system of the side that made it. */
    fun explain(index: Int): List<Meaning>

    fun onCall(call: Call)
    fun onCard(card: Card)

    /** The player tapped the table: the finished trick goes to its winner now. */
    fun takeTrick()
    fun undo()
    fun concede()
    fun requestAdvice()
    fun closeAdvice()

    /** Next board (online: this player is ready for it). */
    fun nextBoard()
    fun newSession()
    fun dismissSummary()
    fun joinLeaderboard(name: String)
    fun declineLeaderboard()
}

/** A player at an online table, as the table shows it. */
data class PlayerTag(val name: String, val bot: Boolean)
