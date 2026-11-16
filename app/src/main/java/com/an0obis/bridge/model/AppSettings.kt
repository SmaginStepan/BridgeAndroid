package com.an0obis.bridge.model

import kotlinx.serialization.Serializable

/**
 * A small JSON file in app storage. Every read and write goes to the file, so several
 * instances (the table screen, the leaderboard screen) never overwrite each other's changes.
 */
class AppSettings {

    @Serializable
    private class Data {
        var playerName: String = "Player"
        var playerId: String = ""

        /** 0 = never asked, 1 = results are posted to the leaderboard, 2 = declined */
        var leaderboard: Int = 0
    }

    private fun load(): Data {
        return try {
            val text = BridgeStorage.readText(FILE_NAME) ?: return Data()
            BridgeStorage.json.decodeFromString(Data.serializer(), text)
        } catch (e: Exception) {
            Data()
        }
    }

    private fun edit(change: (Data) -> Unit) {
        val d = load()
        change(d)
        BridgeStorage.writeText(FILE_NAME, BridgeStorage.json.encodeToString(Data.serializer(), d))
    }

    var playerName: String
        get() = load().playerName
        set(value) = edit { it.playerName = value }

    /** Stable device identity for online play; doubles as the reconnect token. */
    val playerId: String
        get() {
            val current = load().playerId
            if (current.isNotEmpty()) return current
            val fresh = java.util.UUID.randomUUID().toString()
            edit { if (it.playerId.isEmpty()) it.playerId = fresh }
            return load().playerId
        }

    /** True while the user never typed their own name. */
    val isDefaultPlayerName: Boolean
        get() = isDefaultName(load().playerName)

    /** Whether the player takes part in the global leaderboard. */
    var leaderboardChoice: Int
        get() = load().leaderboard
        set(value) = edit { it.leaderboard = value }

    companion object {
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
