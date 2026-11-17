package com.an0obis.bridge.model.advice

import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.sayc.Meaning

/**
 * One sentence of advice as data: [key] selects the text (the string resource `adv_<key>`) and
 * [args] fill its placeholders. An argument written as `@seat:N` stands for the letter of seat N,
 * which the screen writes in the player's language.
 */
data class Reason(val key: String, val args: List<String> = emptyList())

/** What to do and why. Exactly one of [call] and [card] is set. */
data class Advice(
    val call: Call?,
    val card: Card?,
    val reasons: List<Reason>,
    /** For a bid taken from the bidding system's rule table: what the call tells the partner. */
    val meaning: Meaning? = null
)

/** Every reason key the advisers can produce; each one needs an `adv_<key>` text in every language. */
object AdviceKeys {
    val bidding = listOf(
        "hand", "partner_range", "partner_min",
        "fb_pass_open", "fb_pass_weak", "fb_pass_enough", "fb_pass_blocked",
        "fb_game_fit", "fb_game_nt", "fb_invite_fit", "fb_invite_nt", "fb_compete_fit", "fb_compete_own"
    )
    val play = listOf(
        "from_dummy", "plan_declare", "plan_made", "plan_defend", "plan_defeated",
        "lead_opening", "lead_draw_trumps", "lead_winner", "lead_sequence", "lead_fourth",
        "lead_doubleton", "lead_single", "lead_low",
        "follow_partner_wins", "follow_support", "follow_win_cheap", "follow_third_high",
        "follow_cover", "follow_win", "follow_second_low", "follow_cannot_win",
        "ruff", "ruff_over", "discard_partner", "discard", "play_plain"
    )
    val all: List<String> get() = bidding + play
}
