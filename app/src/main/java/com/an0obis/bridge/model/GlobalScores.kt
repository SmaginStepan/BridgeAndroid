package com.an0obis.bridge.model

import com.an0obis.bridge.net.ScoreClient
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/**
 * The global "Top players" list, offline-friendly. A finished 16-board session is
 * ranked by its average matchpoint percentage (best session per player). Results
 * wait in a local file until the network allows; the last fetched list is cached.
 *
 * Nothing is ever sent unless the player agreed ([AppSettings.POST]).
 */
object GlobalScores {

    @Serializable
    data class Row(
        val playerId: String,
        val name: String,
        /** average percentage in tenths (63.4 % is 634); the server stores integers */
        val score10: Long
    ) {
        val percent: Double get() = score10 / 10.0
    }

    @Serializable
    private data class Cache(var rows: List<Row> = emptyList())

    @Serializable
    private data class PendingScore(var name: String, var score10: Long, var deviceTs: Long)

    @Serializable
    private data class PendingList(var items: MutableList<PendingScore> = mutableListOf())

    private const val CACHE_FILE = "global_scores.json"
    private const val PENDING_FILE = "pending_scores.json"

    /** Percentage to the integer scale of the server. */
    fun toScore10(percent: Double): Long = (percent * 10).roundToLong().coerceIn(0L, 1000L)

    fun cached(): List<Row> = try {
        BridgeStorage.readText(CACHE_FILE)
            ?.let { BridgeStorage.json.decodeFromString(Cache.serializer(), it).rows }
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveCache(rows: List<Row>) = try {
        BridgeStorage.writeText(CACHE_FILE, BridgeStorage.json.encodeToString(Cache.serializer(), Cache(rows)))
    } catch (e: Exception) {
        android.util.Log.w("Bridge", "score cache save failed", e)
    }

    private fun loadPending(): PendingList = try {
        BridgeStorage.readText(PENDING_FILE)
            ?.let { BridgeStorage.json.decodeFromString(PendingList.serializer(), it) }
            ?: PendingList()
    } catch (e: Exception) {
        PendingList()
    }

    private fun savePending(p: PendingList) = try {
        BridgeStorage.writeText(PENDING_FILE, BridgeStorage.json.encodeToString(PendingList.serializer(), p))
    } catch (e: Exception) {
        android.util.Log.w("Bridge", "pending score save failed", e)
    }

    /** Queues the result of a finished session, but only for a player who agreed. */
    fun enqueue(name: String, percent: Double) {
        if (AppSettings().leaderboardChoice != AppSettings.POST) return
        val p = loadPending()
        p.items.add(PendingScore(name, toScore10(percent), System.currentTimeMillis() / 1000))
        savePending(p)
    }

    /** Drops everything waiting to be sent (the player turned the leaderboard off). */
    fun clearPending() = savePending(PendingList())

    /** Sends every queued result; keeps the ones worth retrying. Blocking. */
    fun flushPending() {
        val p = loadPending()
        if (p.items.isEmpty()) return
        if (AppSettings().leaderboardChoice != AppSettings.POST) {
            clearPending()
            return
        }
        val playerId = AppSettings().playerId
        val remaining = mutableListOf<PendingScore>()
        for (item in p.items) {
            when (ScoreClient.submit(playerId, item.name, item.score10, item.deviceTs)) {
                ScoreClient.SubmitOutcome.ACCEPTED, ScoreClient.SubmitOutcome.DROP -> {}
                ScoreClient.SubmitOutcome.RETRY -> remaining.add(item)
            }
        }
        p.items = remaining
        savePending(p)
    }

    /**
     * Flushes the queue, then fetches the fresh top list and caches it.
     * Returns null when the server is unreachable (show [cached]). Blocking.
     */
    fun sync(limit: Int = 20): List<Row>? {
        flushPending()
        val top = ScoreClient.fetchTop(limit) ?: return null
        val rows = top.map { Row(it.player_id, it.name, it.score) }
        saveCache(rows)
        return rows
    }
}
