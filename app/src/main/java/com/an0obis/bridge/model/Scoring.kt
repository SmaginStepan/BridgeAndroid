package com.an0obis.bridge.model

enum class Vulnerability {
    NONE, NS, EW, BOTH;

    fun isVulnerable(seat: Int): Boolean = when (this) {
        NONE -> false
        BOTH -> true
        NS -> seat % 2 == 0
        EW -> seat % 2 == 1
    }
}

/** The standard 16-board cycle of dealers and vulnerability used in duplicate bridge. */
object Boards {
    private val VULNERABILITY = listOf(
        Vulnerability.NONE, Vulnerability.NS, Vulnerability.EW, Vulnerability.BOTH,
        Vulnerability.NS, Vulnerability.EW, Vulnerability.BOTH, Vulnerability.NONE,
        Vulnerability.EW, Vulnerability.BOTH, Vulnerability.NONE, Vulnerability.NS,
        Vulnerability.BOTH, Vulnerability.NONE, Vulnerability.NS, Vulnerability.EW
    )

    /** Board numbers start at 1. Board 1 is dealt by North, then East, South, West. */
    fun dealer(boardNumber: Int): Int = (boardNumber - 1) % 4

    fun vulnerability(boardNumber: Int): Vulnerability = VULNERABILITY[(boardNumber - 1) % 16]
}

/** Duplicate (and rubber-less) contract bridge scoring. */
object Scoring {

    /**
     * Score for the declaring side: positive if the contract made, negative if it failed.
     * [tricks] is the number of tricks the declaring side won.
     */
    fun declarerScore(contract: Contract, vulnerable: Boolean, tricks: Int): Int {
        val needed = contract.tricksNeeded
        return if (tricks >= needed) madeScore(contract, vulnerable, tricks - needed)
        else -penalty(contract.multiplier, vulnerable, needed - tricks)
    }

    /** Score from North-South's point of view; 0 for a passed-out board. */
    fun northSouthScore(contract: Contract?, vulnerability: Vulnerability, declarerTricks: Int): Int {
        if (contract == null) return 0
        val score = declarerScore(contract, vulnerability.isVulnerable(contract.declarer), declarerTricks)
        return if (contract.declarer % 2 == 0) score else -score
    }

    private fun trickValue(denom: Int) = if (denom == Suit.CLUBS || denom == Suit.DIAMONDS) 20 else 30

    private fun madeScore(c: Contract, vul: Boolean, overtricks: Int): Int {
        val factor = when (c.multiplier) {
            Multiplier.NONE -> 1
            Multiplier.DOUBLED -> 2
            Multiplier.REDOUBLED -> 4
        }
        // first no-trump trick is worth 40, all others 30 (minors 20, majors 30)
        val contractTricks = c.level * trickValue(c.denom) + if (c.denom == Suit.NO_TRUMP) 10 else 0
        val trickScore = contractTricks * factor

        var total = trickScore
        total += if (trickScore >= 100) (if (vul) 500 else 300) else 50 // game or part-score bonus
        if (c.level == 6) total += if (vul) 750 else 500
        if (c.level == 7) total += if (vul) 1500 else 1000
        total += when (c.multiplier) { // "insult" for making a doubled contract
            Multiplier.NONE -> 0
            Multiplier.DOUBLED -> 50
            Multiplier.REDOUBLED -> 100
        }
        total += overtricks * when (c.multiplier) {
            Multiplier.NONE -> trickValue(c.denom)
            Multiplier.DOUBLED -> if (vul) 200 else 100
            Multiplier.REDOUBLED -> if (vul) 400 else 200
        }
        return total
    }

    private fun penalty(m: Multiplier, vul: Boolean, undertricks: Int): Int {
        if (m == Multiplier.NONE) return undertricks * if (vul) 100 else 50
        var total = 0
        for (i in 1..undertricks) {
            total += if (vul) {
                if (i == 1) 200 else 300
            } else {
                when (i) { 1 -> 100; 2, 3 -> 200; else -> 300 }
            }
        }
        return if (m == Multiplier.REDOUBLED) total * 2 else total
    }

    /**
     * Matchpoint result of one pair: 2 points for every other result they beat, 1 for every tie.
     * Returns a percentage 0.0..100.0 of the available points; 50.0 when there is nothing to compare with.
     */
    fun matchpointPercent(score: Int, others: List<Int>): Double {
        if (others.isEmpty()) return 50.0
        val points = others.sumOf { when { score > it -> 2; score == it -> 1; else -> 0 } }
        return points * 100.0 / (2 * others.size)
    }
}
