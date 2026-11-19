package com.an0obis.bridge.model.sayc

import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.BridgeStorage
import com.an0obis.bridge.model.SeasonRandom
import kotlinx.serialization.Serializable

/**
 * Every bidding system the player can choose: the presets and the systems they made with the
 * builder (kept in `systems.json`).
 */
object SystemCatalog {

    private const val FILE_NAME = "systems.json"
    private val lock = Any()

    @Serializable
    private class Stored(val systems: List<SystemConfig> = emptyList())

    /** The player's own systems, oldest first. */
    fun custom(): List<SystemConfig> = synchronized(lock) {
        try {
            val text = BridgeStorage.readText(FILE_NAME) ?: return emptyList()
            BridgeStorage.json.decodeFromString(Stored.serializer(), text).systems
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(list: List<SystemConfig>) {
        BridgeStorage.writeText(FILE_NAME, BridgeStorage.json.encodeToString(Stored.serializer(), Stored(list)))
    }

    /** The presets, then the player's own systems. */
    fun all(): List<SystemConfig> = SystemConfig.PRESETS + custom()

    /** The system with [id]; a removed or unknown id gives Standard American. */
    fun find(id: String): SystemConfig =
        SystemConfig.preset(id) ?: custom().firstOrNull { it.id == id } ?: SystemConfig.SAYC

    /** Saves a system of the player's (made consistent first). A new one gets an id. Returns what was saved. */
    fun save(config: SystemConfig): SystemConfig = synchronized(lock) {
        val id = if (config.id.startsWith(CUSTOM_PREFIX)) config.id else CUSTOM_PREFIX + java.util.UUID.randomUUID()
        val saved = config.copy(id = id).normalized()
        val list = custom().toMutableList()
        val at = list.indexOfFirst { it.id == id }
        if (at >= 0) list[at] = saved else list.add(saved)
        write(list)
        saved
    }

    /** Removes one of the player's systems; a side that used it goes back to Standard American. */
    fun delete(id: String) = synchronized(lock) {
        write(custom().filter { it.id != id })
        val settings = AppSettings()
        if (settings.biddingSystem == id) settings.biddingSystem = SystemConfig.SAYC_ID
        if (settings.opponentSystem == id) settings.opponentSystem = SystemConfig.SAYC_ID
    }

    /**
     * Normal play: the systems the player chose in the settings, North-South (the player and
     * partner) and East-West. They stay the same until the player changes them.
     */
    fun seating(settings: AppSettings = AppSettings()): Seating =
        Seating.of(find(settings.biddingSystem).normalized(), find(settings.opponentSystem).normalized())

    /**
     * The presets the opponent bots may play in a season, in the order the season generator picks
     * from. Part of the season contract (see [SeasonRandom]): change it only together with the
     * other platforms, and never during a running season.
     */
    val SEASON_OPPONENT_SYSTEMS = listOf(
        SystemConfig.SAYC_ID, SystemConfig.TWO_OVER_ONE_ID, SystemConfig.ACOL_ID,
        SystemConfig.PRECISION_ID, SystemConfig.POLISH_ID
    )

    /** The opponents' system of the season with [seedHex]: chosen at random, the same for every player. */
    fun seasonOpponents(seedHex: String): SystemConfig {
        val pick = SeasonRandom.forPurpose(seedHex, "opponent-system", 0).nextInt(SEASON_OPPONENT_SYSTEMS.size)
        return SystemConfig.preset(SEASON_OPPONENT_SYSTEMS[pick])!!
    }

    /**
     * A season table: nobody is made to learn a system, so the player and partner play the
     * player's own system from the settings, and the opponent bots play the season's system.
     */
    fun seasonSeating(seedHex: String, settings: AppSettings = AppSettings()): Seating =
        Seating.of(find(settings.biddingSystem).normalized(), seasonOpponents(seedHex))

    const val CUSTOM_PREFIX = "custom-"
}
