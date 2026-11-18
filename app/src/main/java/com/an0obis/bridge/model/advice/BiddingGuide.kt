package com.an0obis.bridge.model.advice

/**
 * The sections of the bidding guide ("More about bidding..."). Each id has a title and a body in
 * the string resources: `guide_<id>_t` and `guide_<id>_b`.
 */
object BiddingGuide {

    val sectionIds = listOf(
        "auction", "points", "goals", "opening", "respond", "notrump",
        "rebid", "strong2c", "compete", "takeout", "fit", "tips"
    )

    /**
     * The section that explains the situation of an advice: the rule of the bidding system that made
     * the call (its id), or, when the points-and-fit logic decided, that logic's section.
     */
    fun sectionFor(ruleId: String?, reasons: List<Reason>): String = when {
        ruleId != null -> when {
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
}
