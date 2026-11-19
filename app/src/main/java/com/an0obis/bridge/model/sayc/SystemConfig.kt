package com.an0obis.bridge.model.sayc

import kotlinx.serialization.Serializable

/** What an opening of 1♣ means. */
@Serializable
enum class ClubStyle {
    /** Natural: the longer minor (Standard American, 2/1, Acol). Strong hands open 2♣. */
    NATURAL,

    /** Strong and artificial: 16 or more points, any shape (Precision). 2♣ shows long clubs. */
    STRONG,

    /** Polish club: a weak balanced hand, or natural clubs, or a strong hand. 2♣ shows long clubs. */
    POLISH
}

/** What an opening of 2♦ means. */
@Serializable
enum class TwoDiamonds {
    /** A weak two in diamonds. */
    WEAK,

    /** Multi: a weak two in hearts or in spades. */
    MULTI,

    /** Short diamonds: a minimum hand with three suits (4-4-1-4 and similar), Precision style. */
    SHORT
}

/** What an opening of 2♥ or 2♠ means. */
@Serializable
enum class TwoMajors {
    /** A weak two: a six-card suit. */
    WEAK,

    /** Polish two: five cards in the major and four or more in a minor, weak. */
    POLISH
}

/** The meaning of 1NT, 2NT and 3NT in answer to one of a suit. */
@Serializable
enum class NtResponses {
    /** 1NT is a wide range, 2NT and 3NT show strong balanced hands (Standard American). */
    STANDARD,

    /** Acol limit bids: 1NT is weak, 2NT invites game, 3NT is to play. */
    LIMIT
}

/**
 * A bidding system as a short convention card. Every rule of the system (the bots' calls, the
 * "what does this call mean" texts, the advice and the guide) is built from these options by
 * [RuleBuilder], so presets and the player's own systems work the same way.
 */
@Serializable
data class SystemConfig(
    /** A preset id ([PRESET_IDS]) or "custom-…" for a system the player made. */
    val id: String,
    /** The player's name for a custom system; presets take theirs from the string resources. */
    val name: String = "",
    val club: ClubStyle = ClubStyle.NATURAL,
    /** Cards needed to open one of a major: 5, or 4 (Acol). */
    val majorLength: Int = 5,
    /** The fewest points for an opening of one in a suit. */
    val openMin: Int = 12,
    /** The range of the 1NT opening. */
    val ntMin: Int = 15,
    val ntMax: Int = 17,
    /** The range of the 2NT opening. */
    val twoNtMin: Int = 20,
    val twoNtMax: Int = 21,
    val twoDiamonds: TwoDiamonds = TwoDiamonds.WEAK,
    val twoMajors: TwoMajors = TwoMajors.WEAK,
    val stayman: Boolean = true,
    /** Jacoby transfers over 1NT. */
    val transfers: Boolean = true,
    /** 2/1 game forcing: a new suit at the two level forces to game; 1NT over 1♥/1♠ is forcing. */
    val twoOverOne: Boolean = false,
    /** Jacoby 2NT: a game-forcing raise of partner's major with four trumps. */
    val jacoby2nt: Boolean = false,
    val ntResponses: NtResponses = NtResponses.STANDARD,
) {
    /** True for the presets, false for a system the player made. */
    val isPreset: Boolean get() = id in PRESET_IDS

    /** The fewest points for the strong 1♣ (Precision 16, Polish club 18). */
    val strongClubMin: Int get() = if (club == ClubStyle.POLISH) 18 else 16

    /** The fewest points for the strong 2♣ (natural club only): above the 2NT opening. */
    val strongTwoClubsMin: Int get() = maxOf(22, twoNtMax + 1)

    /** The most points for an opening of one in a suit (stronger hands open 1♣ or 2♣). */
    val oneSuitMax: Int
        get() = when (club) {
            ClubStyle.NATURAL -> strongTwoClubsMin - 1
            ClubStyle.STRONG -> strongClubMin - 1
            ClubStyle.POLISH -> strongClubMin - 1
        }

    /**
     * True if the 1NT opening is weak (Acol style): no balanced opening hand is below its range, so
     * the balanced hands that do not open 1NT are the stronger ones.
     */
    val weakNoTrump: Boolean get() = ntMin <= openMin

    /**
     * The same options made consistent, so any combination a player picks still gives a working
     * system: ranges in order, and options that need a strong or Polish club only used with one.
     */
    fun normalized(): SystemConfig {
        val open = openMin.coerceIn(10, 13)
        var lo = ntMin.coerceIn(open, 18)
        var hi = ntMax.coerceIn(lo + 1, lo + 3)
        if (club == ClubStyle.STRONG && hi >= strongClubMin) {
            hi = strongClubMin - 1
            lo = minOf(lo, hi - 2)
        }
        if (club == ClubStyle.POLISH && lo <= open) lo = open + 1 // the Polish 1♣ holds the weaker balanced hands
        hi = maxOf(hi, lo + 1)
        val twoLo = twoNtMin.coerceIn(hi + 2, 24)
        val twoHi = twoNtMax.coerceIn(twoLo + 1, twoLo + 3)
        return copy(
            majorLength = if (majorLength == 4) 4 else 5,
            openMin = open,
            ntMin = lo,
            ntMax = hi,
            twoNtMin = twoLo,
            twoNtMax = twoHi,
            twoDiamonds = if (twoDiamonds == TwoDiamonds.SHORT && club == ClubStyle.NATURAL) TwoDiamonds.WEAK else twoDiamonds,
            // with the Multi the weak twos in the majors go through 2♦, so 2♥/2♠ must mean something else
            twoMajors = if (twoDiamonds == TwoDiamonds.MULTI) TwoMajors.POLISH else twoMajors,
        )
    }

    companion object {
        const val SAYC_ID = "sayc"
        const val TWO_OVER_ONE_ID = "2over1"
        const val ACOL_ID = "acol"
        const val PRECISION_ID = "precision"
        const val POLISH_ID = "polish"

        val SAYC = SystemConfig(SAYC_ID)

        val TWO_OVER_ONE = SystemConfig(TWO_OVER_ONE_ID, twoOverOne = true, jacoby2nt = true)

        val ACOL = SystemConfig(
            ACOL_ID,
            majorLength = 4,
            ntMin = 12, ntMax = 14,
            twoNtMin = 20, twoNtMax = 22,
            ntResponses = NtResponses.LIMIT,
        )

        val PRECISION = SystemConfig(
            PRECISION_ID,
            club = ClubStyle.STRONG,
            openMin = 11,
            ntMin = 13, ntMax = 15,
            twoNtMin = 22, twoNtMax = 23,
            twoDiamonds = TwoDiamonds.SHORT,
        )

        val POLISH = SystemConfig(
            POLISH_ID,
            club = ClubStyle.POLISH,
            ntMin = 15, ntMax = 17,
            twoNtMin = 20, twoNtMax = 21,
            twoDiamonds = TwoDiamonds.MULTI,
            twoMajors = TwoMajors.POLISH,
        )

        val PRESETS: List<SystemConfig> = listOf(SAYC, TWO_OVER_ONE, ACOL, PRECISION, POLISH)
        val PRESET_IDS: Set<String> = PRESETS.map { it.id }.toSet()

        fun preset(id: String): SystemConfig? = PRESETS.firstOrNull { it.id == id }
    }
}
