package com.an0obis.bridge.model.advice

import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.sayc.BiddingSystem
import com.an0obis.bridge.model.sayc.ClubStyle
import com.an0obis.bridge.model.sayc.NtResponses
import com.an0obis.bridge.model.sayc.SystemConfig
import com.an0obis.bridge.model.sayc.TwoDiamonds
import com.an0obis.bridge.model.sayc.TwoMajors

/**
 * The sections of the bidding guide ("More about bidding..."). Each id has a title and a body in
 * the string resources: `guide_<id>_t` and `guide_<id>_b`. Under the body of a section the guide
 * shows the calls of the player's own system that belong to it ([table]), so the numbers are
 * always those of the chosen system.
 */
object BiddingGuide {

    val sectionIds = listOf(
        "auction", "points", "goals", "opening", "strongclub", "polishclub", "conventions", "strong2c",
        "respond", "fourcard", "twooverone", "notrump", "rebid", "compete", "takeout", "fit", "tips"
    )

    /** The sections that apply to [config] (a Precision player never sees the strong 2♣, and so on). */
    fun sectionsFor(config: SystemConfig): List<String> = sectionIds.filter { id ->
        when (id) {
            "strongclub" -> config.club == ClubStyle.STRONG
            "polishclub" -> config.club == ClubStyle.POLISH
            "strong2c" -> config.club == ClubStyle.NATURAL
            "conventions" -> config.club != ClubStyle.NATURAL || config.twoDiamonds != TwoDiamonds.WEAK ||
                config.twoMajors != TwoMajors.WEAK
            "twooverone" -> config.twoOverOne || config.jacoby2nt
            "fourcard" -> config.majorLength == 4 || config.weakNoTrump || config.ntResponses == NtResponses.LIMIT
            else -> true
        }
    }

    /**
     * The section that explains the situation of an advice: the rule of the bidding system that made
     * the call (its id), or, when the points-and-fit logic decided, that logic's section.
     */
    fun sectionFor(ruleId: String?, reasons: List<Reason>): String = when {
        ruleId != null -> when {
            ruleId == "open_1c_strong" || ruleId == "open_1d_prec" || ruleId.startsWith("sc_") -> "strongclub"
            ruleId.startsWith("open_1c_polish") || ruleId == "open_1d_polish" ||
                ruleId.startsWith("pc_") || ruleId.startsWith("pcr_") -> "polishclub"
            ruleId in setOf("open_2c_nat", "open_2d_short", "open_2d_multi", "open_2m_polish") ||
                ruleId.startsWith("multi_") -> "conventions"
            ruleId in setOf("resp_jacoby", "resp_2over1", "resp_1nt_forcing", "reb_nf_minor") -> "twooverone"
            ruleId in setOf("open_1s4", "open_1h4", "open_1d4", "open_1c4", "resp_2nt_limit") -> "fourcard"
            ruleId == "open_2c" || ruleId.startsWith("s2c_") -> "strong2c"
            ruleId.startsWith("open_") -> "opening"
            ruleId.startsWith("resp_") -> "respond"
            ruleId.startsWith("n_") -> "notrump"
            ruleId.startsWith("reb_") -> "rebid"
            ruleId.startsWith("ov_takeout") || ruleId.startsWith("td_") -> "takeout"
            ruleId.startsWith("ov_") || ruleId.startsWith("adv_") -> "compete"
            else -> "auction"
        }
        reasons.any { it.key.startsWith("fb_") } -> "fit"
        else -> "auction"
    }

    /** One line of a section's table: a meaning ([ruleId]) and the calls that carry it, with its range. */
    data class Line(val ruleId: String, val calls: List<Call>, val hcp: IntRange, val balanced: Boolean?)

    /** The calls of [system] that belong to [section], one line per meaning, in the system's own order. */
    fun table(system: BiddingSystem, section: String): List<Line> =
        lines(system) { sectionFor(it, emptyList()) == section }

    /** Every opening bid of [system] (for the builder's preview). */
    fun openings(system: BiddingSystem): List<Line> = lines(system) { it.startsWith("open_") }

    private fun lines(system: BiddingSystem, keep: (String) -> Boolean): List<Line> =
        system.rules.filter { keep(it.id) }
            .groupBy { it.id }
            .map { (id, rules) ->
                Line(
                    id,
                    rules.map { it.call }.distinct(),
                    rules.minOf { it.hcp.first }..rules.maxOf { it.hcp.last },
                    rules.map { it.balanced }.distinct().singleOrNull()
                )
            }
}
