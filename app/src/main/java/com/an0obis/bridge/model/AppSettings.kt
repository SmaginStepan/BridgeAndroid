package com.an0obis.bridge.model

import kotlinx.serialization.Serializable

/** A small JSON file in app storage. */
class AppSettings {

    @Serializable
    private class Data {
        var playerName: String = "Player"
        var playerId: String = ""
    }

    private var data: Data = load()

    private fun load(): Data {
        return try {
            val text = BridgeStorage.readText(FILE_NAME) ?: return Data()
            BridgeStorage.json.decodeFromString(Data.serializer(), text)
        } catch (e: Exception) {
            Data()
        }
    }

    private fun save() {
        BridgeStorage.writeText(FILE_NAME, BridgeStorage.json.encodeToString(Data.serializer(), data))
    }

    var playerName: String
        get() = data.playerName
        set(value) {
            data.playerName = value
            save()
        }

    /** Stable device identity for online play; doubles as the reconnect token. */
    val playerId: String
        get() {
            if (data.playerId.isEmpty()) {
                data.playerId = java.util.UUID.randomUUID().toString()
                save()
            }
            return data.playerId
        }

    /** True while the user never typed their own name. */
    val isDefaultPlayerName: Boolean
        get() = isDefaultName(data.playerName)

    companion object {
        private const val FILE_NAME = "settings.json"

        // Per-locale placeholder names; treated as "not customized" so the
        // display can follow the app language.
        private val DEFAULT_NAMES = setOf("", "Игрок", "Player", "Jugador")

        fun isDefaultName(name: String) = name.trim() in DEFAULT_NAMES
    }
}
