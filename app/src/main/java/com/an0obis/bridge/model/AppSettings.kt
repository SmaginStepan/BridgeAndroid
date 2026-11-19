package com.an0obis.bridge.model

import kotlinx.serialization.Serializable

/**
 * A small JSON file in app storage. A shared lock protects each read and each read-modify-write,
 * so the table, the leaderboard and background sync preserve each other's changes.
 */
class AppSettings {

    @Serializable
    private class Data {
        var playerName: String = "Player"
        var playerId: String = ""

        /** 0 = never asked, 1 = results are posted to the leaderboard, 2 = declined */
        var leaderboard: Int = 0

        /** Id of the bidding system of the player and partner (a preset or a custom system, see SystemCatalog). */
        var biddingSystem: String = "sayc"

        /** Id of the opponents' bidding system. */
        var opponentSystem: String = "sayc"

        /** True once the player has seen the tutorial (it opens by itself before the first game). */
        var tutorialSeen: Boolean = false

        /** Bit mask of the parts of the on-table tour the player has seen (see the Tour object). */
        var boardTourSeen: Int = 0
    }

    private fun load(): Data = synchronized(storageLock) {
        try {
            val text = BridgeStorage.readText(FILE_NAME) ?: return Data()
            BridgeStorage.json.decodeFromString(Data.serializer(), text)
        } catch (e: Exception) {
            Data()
        }
    }

    private fun edit(change: (Data) -> Unit) = synchronized(storageLock) {
        val d = load()
        change(d)
        BridgeStorage.writeText(FILE_NAME, BridgeStorage.json.encodeToString(Data.serializer(), d))
    }

    var playerName: String
        get() = load().playerName
        set(value) = edit { it.playerName = value }

    /** Stable device identity for online play; doubles as the reconnect token. */
    val playerId: String
        get() = synchronized(storageLock) {
            val current = load().playerId
            if (current.isNotEmpty()) return@synchronized current
            val fresh = java.util.UUID.randomUUID().toString()
            edit { if (it.playerId.isEmpty()) it.playerId = fresh }
            load().playerId
        }

    /** True while the user never typed their own name. */
    val isDefaultPlayerName: Boolean
        get() = isDefaultName(load().playerName)

    /** Whether the player takes part. Update through [GlobalScores.setLeaderboardChoice] to cancel pending work. */
    var leaderboardChoice: Int
        get() = load().leaderboard
        set(value) = edit { it.leaderboard = value }

    var biddingSystem: String
        get() = load().biddingSystem
        set(value) = edit { it.biddingSystem = value }

    var opponentSystem: String
        get() = load().opponentSystem
        set(value) = edit { it.opponentSystem = value }

    var tutorialSeen: Boolean
        get() = load().tutorialSeen
        set(value) = edit { it.tutorialSeen = value }

    var boardTourSeen: Int
        get() = load().boardTourSeen
        set(value) = edit { it.boardTourSeen = value }

    companion object {
        private val storageLock = Any()
        private const val FILE_NAME = "settings.json"

        const val ASK = 0
        const val POST = 1
        const val DECLINED = 2

        // Per-locale placeholder names; treated as "not customized" so the
        // display can follow the app language.
        private val DEFAULT_NAMES = setOf("", "Игрок", "Player", "Jugador")

        fun isDefaultName(name: String) = name.trim() in DEFAULT_NAMES
    }
}
